package io.github.aiassetmigrate.auth;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackCallbackTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void bindsIpv4LoopbackAndConsumesOneCallbackWithoutEchoingSecrets() throws Exception {
        try (var listener = new LoopbackCallback("state-secret")) {
            assertEquals("127.0.0.1", listener.redirectUri().getHost());
            assertEquals("/auth/callback", listener.redirectUri().getPath());
            var response = get(listener.redirectUri() + "?state=state-secret&code=secret-code");
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
            assertFalse(response.body().contains("state-secret"));
            assertFalse(response.body().contains("secret-code"));
            assertEquals(Map.of("state", "state-secret", "code", "secret-code"), listener.await(Duration.ofSeconds(1)));
            assertEquals(409, get(listener.redirectUri() + "?state=state-secret&code=reused-code").statusCode());
        }
    }

    @Test
    void invalidRequestsDoNotConsumeThePendingBrowserTransaction() throws Exception {
        try (var listener = new LoopbackCallback("correct")) {
            String callback = listener.redirectUri().toString();
            for (String query : new String[]{"", "?code=no-state", "?state=wrong&code=x",
                    "?state=correct&state=wrong", "?state=correct&st%61te=correct", "?state=correct&code=%00"}) {
                assertEquals(400, get(callback + query).statusCode(), query);
            }
            assertEquals(404, get(callback + "/extra?state=correct&code=x").statusCode());
            assertEquals(404, get(callback.replace("/auth/callback", "/auth/%63allback") + "?state=correct&code=x").statusCode());
            var post = HttpRequest.newBuilder(listener.redirectUri()).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, HTTP.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, get(callback + "?state=correct&code=ok").statusCode());
            assertEquals("ok", listener.await(Duration.ofSeconds(1)).get("code"));
        }
    }

    @Test
    void rejectsUnexpectedHostHeader() throws Exception {
        try (var listener = new LoopbackCallback("correct")) {
            try (var socket = new Socket("127.0.0.1", listener.redirectUri().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("GET /auth/callback?state=correct&code=x HTTP/1.1\r\n"
                        + "Host: attacker.invalid\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertTrue(reader.readLine().contains("400"));
            }
            assertEquals(200, get(listener.redirectUri() + "?state=correct&code=ok").statusCode());
        }
    }

    @Test
    void parsesEncodedValuesAndRejectsMalformedDuplicateOrOversizeQueries() {
        assertEquals(Map.of("code", "a+b&c=d", "scope", "openid email"),
                LoopbackCallback.parseQuery("code=a%2Bb%26c%3Dd&scope=openid+email"));
        for (String query : new String[]{null, "", "state", "=value", "state=%zz", "state=%",
                "state=x&state=x", "state=x&", "st%00ate=x", "state=" + "a".repeat(8192)}) {
            assertThrows(IllegalArgumentException.class, () -> LoopbackCallback.parseQuery(query));
        }
    }

    @Test
    void timesOutCancelsAndReleasesTheListener() throws Exception {
        URI callback;
        try (var listener = new LoopbackCallback("correct")) {
            callback = listener.redirectUri();
            assertEquals(OAuthException.Reason.TIMED_OUT, assertThrows(OAuthException.class,
                    () -> listener.await(Duration.ofMillis(10))).reason());
            listener.cancel();
            assertEquals(OAuthException.Reason.CANCELED, assertThrows(OAuthException.class,
                    () -> listener.await(Duration.ofSeconds(1))).reason());
            assertEquals(409, get(callback + "?state=correct&code=x").statusCode());
        }
        assertThrows(java.io.IOException.class, () -> get(callback + "?state=correct&code=x"));
    }

    private static HttpResponse<String> get(String uri) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
