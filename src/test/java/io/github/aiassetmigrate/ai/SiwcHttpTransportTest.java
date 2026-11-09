package io.github.aiassetmigrate.ai;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SiwcHttpTransportTest {
    private static final byte[] BODY = "private prompt".getBytes(StandardCharsets.UTF_8);
    private static final String TOKEN = "fake-secret";

    @Test void constructsRequestAndReturnsBoundedStream() throws Exception {
        FakeClient client = new FakeClient(List.of("ab", "cd"), true);
        var result = new SiwcHttpTransport(client).post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofSeconds(1), 4);
        assertEquals(200, result.status());
        assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), result.body());
        assertEquals(SiwcInference.ENDPOINT, client.request.uri());
        assertEquals("POST", client.request.method());
        assertEquals("Bearer " + TOKEN, client.request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", client.request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("text/event-stream", client.request.headers().firstValue("Accept").orElseThrow());
        assertTrue(client.request.timeout().isPresent());
    }

    @Test void rejectsOtherEndpointsAndUnsafeClients() {
        FakeClient client = new FakeClient(List.of(), true);
        var transport = new SiwcHttpTransport(client);
        for (String endpoint : List.of("http://api.openai.com/v1/responses", "https://evil.example/v1/responses",
                "https://api.openai.com/v1/responses?x=1")) {
            assertThrows(IOException.class, () -> transport.post(URI.create(endpoint), TOKEN, BODY, Duration.ofSeconds(1), 10));
        }
        assertNull(client.request);
        client.redirect = HttpClient.Redirect.ALWAYS;
        assertThrows(IllegalArgumentException.class, () -> new SiwcHttpTransport(client));
        client.redirect = HttpClient.Redirect.NEVER;
        client.connect = Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> new SiwcHttpTransport(client));
    }

    @Test void oversizedBodyCancelsAndSanitizes() {
        FakeClient client = new FakeClient(List.of("1234", "secret response"), true);
        IOException error = assertThrows(IOException.class, () -> new SiwcHttpTransport(client)
                .post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofSeconds(1), 4));
        assertTrue(client.cancelled.get());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("secret"));
        assertFalse(error.toString().contains("prompt"));
    }

    @Test void timeoutCancelsStalledBodyAndFuture() {
        FakeClient client = new FakeClient(List.of("partial"), false);
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> new SiwcHttpTransport(client)
                .post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofMillis(80), 30));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
        assertTrue(client.cancelled.get());
        assertTrue(client.future.isCancelled());
    }

    @Test void timeoutBeforeHeadersCancelsFuture() {
        FakeClient client = new FakeClient(List.of(), false) {
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler) {
                CompletableFuture<HttpResponse<T>> pending = new CompletableFuture<>();
                future = pending;
                return pending;
            }
        };
        assertThrows(IOException.class, () -> new SiwcHttpTransport(client)
                .post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofMillis(50), 30));
        assertTrue(client.future.isCancelled());
    }

    @Test void sanitizesClientExceptions() {
        FakeClient client = new FakeClient(List.of(), false) {
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler) {
                throw new IllegalArgumentException(TOKEN + " private prompt secret response");
            }
        };
        IOException error = assertThrows(IOException.class, () -> new SiwcHttpTransport(client)
                .post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofSeconds(1), 30));
        assertEquals("SIWC transport failed", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void interruptionCancelsAndPreservesInterrupt() throws Exception {
        FakeClient client = new FakeClient(List.of(), false);
        AtomicBoolean preserved = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                new SiwcHttpTransport(client).post(SiwcInference.ENDPOINT, TOKEN, BODY, Duration.ofSeconds(5), 30);
                unexpected.set(new AssertionError("Expected interruption"));
            } catch (InterruptedException e) {
                preserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) { unexpected.set(e); }
        });
        thread.start();
        assertTrue(client.subscribed.await(2, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertNull(unexpected.get());
        assertTrue(preserved.get());
        assertTrue(client.cancelled.get());
        assertTrue(client.future.isCancelled());
    }

    private static class FakeClient extends HttpClient {
        final List<String> chunks;
        final boolean complete;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch subscribed = new CountDownLatch(1);
        Redirect redirect = Redirect.NEVER;
        Optional<Duration> connect = Optional.of(Duration.ofSeconds(1));
        volatile HttpRequest request;
        volatile CompletableFuture<?> future;
        FakeClient(List<String> chunks, boolean complete) { this.chunks = chunks; this.complete = complete; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return connect; }
        public Redirect followRedirects() { return redirect; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { return null; }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                 HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r, h); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> handler) {
            request = r;
            HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                public int statusCode() { return 200; }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public Version version() { return Version.HTTP_1_1; }
            };
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
            CompletableFuture<HttpResponse<T>> result = subscriber.getBody().<HttpResponse<T>>thenApply(body -> new HttpResponse<T>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return r; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return info.headers(); }
                public T body() { return body; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return r.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            }).toCompletableFuture();
            future = result;
            subscriber.onSubscribe(new Flow.Subscription() {
                boolean sent;
                public void request(long n) {
                    if (sent) return;
                    sent = true;
                    subscribed.countDown();
                    for (String chunk : chunks) {
                        if (cancelled.get()) return;
                        subscriber.onNext(List.of(ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8))));
                    }
                    if (complete && !cancelled.get()) subscriber.onComplete();
                }
                public void cancel() { cancelled.set(true); }
            });
            return result;
        }
    }
}
