package io.github.aiassetmigrate.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One-use listener, bound only to IPv4 loopback, with no token or callback logging. */
final class LoopbackCallback implements AutoCloseable {
    private final HttpServer server;
    private final URI redirectUri;
    private final String state;
    private final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();

    LoopbackCallback(String state) throws IOException {
        this.state = state;
        server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 8);
        redirectUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback");
        server.createContext("/", this::handle);
        server.start();
    }

    URI redirectUri() {
        return redirectUri;
    }

    Map<String, String> await(Duration timeout) throws OAuthException, InterruptedException {
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new OAuthException(OAuthException.Reason.TIMED_OUT, "Authorization timed out; start a new sign-in.");
        } catch (ExecutionException e) {
            throw new OAuthException(OAuthException.Reason.CANCELED, "Authorization was canceled.");
        }
    }

    void cancel() {
        result.completeExceptionally(new IOException("Authorization canceled"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "Only GET callbacks are accepted.");
                return;
            }
            if (uri.isAbsolute() || !"/auth/callback".equals(uri.getRawPath()) || uri.getRawFragment() != null) {
                respond(exchange, 404, "Not found.");
                return;
            }
            var hosts = exchange.getRequestHeaders().get("Host");
            if (hosts == null || hosts.size() != 1 || !redirectUri.getRawAuthority().equals(hosts.getFirst())) {
                respond(exchange, 400, "Authorization callback rejected.");
                return;
            }
            Map<String, String> parameters;
            try {
                parameters = parseQuery(uri.getRawQuery());
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, "Authorization callback rejected.");
                return;
            }
            String returnedState = parameters.get("state");
            if (returnedState == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                    returnedState.getBytes(StandardCharsets.UTF_8))) {
                // Unsolicited local requests must not consume the real browser transaction.
                respond(exchange, 400, "Authorization callback rejected.");
                return;
            }
            if (result.isDone()) {
                respond(exchange, 409, "This authorization attempt is already complete.");
                return;
            }
            try {
                respond(exchange, 200, "Authorization callback received. Return to the application for the sign-in result.");
                exchange.getResponseBody().close();
            } finally {
                // Finish the browser response before the waiting caller can stop this server.
                result.complete(parameters);
            }
        }
    }

    static Map<String, String> parseQuery(String query) {
        if (query == null || query.length() > 8192) {
            throw new IllegalArgumentException("Invalid callback query");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Invalid callback parameter");
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (key.isBlank() || key.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)
                    || parameters.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Invalid or duplicate callback parameter");
            }
        }
        return Map.copyOf(parameters);
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        cancel();
        server.stop(0);
    }
}
