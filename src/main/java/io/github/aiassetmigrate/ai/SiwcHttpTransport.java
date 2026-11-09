package io.github.aiassetmigrate.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP transport for SIWC only. No retries, redirects, logging, or credential storage. */
public final class SiwcHttpTransport implements SiwcInference.Transport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private final HttpClient client;

    public SiwcHttpTransport() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT).build());
    }

    // Injection is restricted to this package; production callers use the configured client above.
    SiwcHttpTransport(HttpClient client) {
        if (client == null || client.followRedirects() != HttpClient.Redirect.NEVER
                || client.connectTimeout().isEmpty() || client.connectTimeout().get().isZero()
                || client.connectTimeout().get().isNegative()
                || client.connectTimeout().get().compareTo(CONNECT_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Invalid HTTP client configuration");
        }
        this.client = client;
    }

    @Override
    public SiwcInference.Response post(URI endpoint, String bearerToken, byte[] jsonBody,
                                       Duration timeout, int maxResponseBytes)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        if (!SiwcInference.ENDPOINT.equals(endpoint) || bearerToken == null || bearerToken.isBlank()
                || jsonBody == null || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(MAX_TIMEOUT) > 0 || maxResponseBytes <= 0
                || maxResponseBytes > SiwcInference.MAX_RESPONSE_BYTES) {
            throw new IOException("Invalid SIWC transport request");
        }
        AtomicReference<BoundedSubscriber> subscriber = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(SiwcInference.ENDPOINT).timeout(timeout)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody)).build();
            future = client.sendAsync(request, info -> {
                BoundedSubscriber body = new BoundedSubscriber(maxResponseBytes);
                subscriber.set(body);
                if (stopped.get()) body.cancel();
                return body;
            });
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = future.get(remaining, TimeUnit.NANOSECONDS);
            if (System.nanoTime() - started >= timeout.toNanos()) throw new TimeoutException();
            return new SiwcInference.Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("SIWC transport interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new IOException("SIWC transport failed");
        } finally {
            stopped.set(true);
            BoundedSubscriber body = subscriber.get();
            if (body != null) body.cancel();
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private static final Flow.Subscription CANCELLED = new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { }
        };
        private final byte[] buffer;
        private int size;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private BoundedSubscriber(int limit) { buffer = new byte[limit]; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value)) value.cancel();
            else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (subscription.get() == CANCELLED) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > buffer.length - size) {
                    cancel();
                    body.completeExceptionally(new IOException("SIWC response limit exceeded"));
                    return;
                }
                int length = chunk.remaining();
                chunk.get(buffer, size, length);
                size += length;
            }
            Flow.Subscription current = subscription.get();
            if (current != CANCELLED && current != null) current.request(1);
        }
        @Override public void onError(Throwable ignored) {
            body.completeExceptionally(new IOException("SIWC response failed"));
        }
        @Override public void onComplete() { body.complete(Arrays.copyOf(buffer, size)); }
        private void cancel() {
            Flow.Subscription current = subscription.getAndSet(CANCELLED);
            if (current != null && current != CANCELLED) current.cancel();
        }
    }
}
