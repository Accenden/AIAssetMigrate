package io.github.aiassetmigrate.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OAuthSessionTest {
    private static final String HOST = "urn:uuid:" + UUID.randomUUID();
    private static final String CLIENT = "oaiapp_test-client";
    private static final String SUBJECT = "verified-account";
    private static final String CODE = "secret-code+with/special?&characters";
    private static final Instant NOW = Instant.parse("2026-11-02T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static RSAKey key;

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    }

    private static OAuthCredentials saved() {
        return new OAuthCredentials(CLIENT, SUBJECT, Optional.of("person@example.test"), "old-access", "old-refresh",
                "old-id", Set.of("openid", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"), NOW);
    }

    @Test
    void refreshUsesTheIssuedClientAndKeepsTheRotatedRefreshToken() throws Exception {
        try (var provider = new Provider()) {
            provider.rawBody = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\","
                    + "\"token_type\":\"Bearer\",\"expires_in\":1800}";
            var refreshed = provider.session(Optional.of(new OAuthSession.Registration(CLIENT, SUBJECT))).refresh(saved());
            assertEquals(Map.of("grant_type", "refresh_token", "client_id", CLIENT, "refresh_token", "old-refresh",
                    "resource", OAuthSession.RESOURCE), provider.exchange);
            assertEquals("new-access", refreshed.accessToken());
            assertEquals("new-refresh", refreshed.refreshToken());
            assertEquals("old-id", refreshed.idToken());
            assertEquals(SUBJECT, refreshed.subject());
            assertEquals(NOW.plusSeconds(1800), refreshed.expiresAt());
            provider.rawBody = "{\"access_token\":\"a2\",\"token_type\":\"Bearer\",\"expires_in\":60}";
            assertEquals("old-refresh", provider.session(Optional.empty()).refresh(saved()).refreshToken(),
                    "a refresh response without a replacement keeps the saved refresh token");
            provider.rawBody = "{\"access_token\":\"a3\",\"token_type\":\"Bearer\",\"expires_in\":60,\"scope\":\"openid\"}";
            assertEquals(OAuthException.Reason.INSUFFICIENT_SCOPE, assertThrows(OAuthException.class,
                    () -> provider.session(Optional.empty()).refresh(saved())).reason());
            provider.status = 400;
            var expired = assertThrows(OAuthException.class, () -> provider.session(Optional.empty()).refresh(saved()));
            assertEquals(OAuthException.Reason.TOKEN_EXCHANGE_FAILED, expired.reason());
            assertFalse(expired.getMessage().contains("old-refresh"));
        }
    }

    @Test
    void dynamicRegistrationValidatesTokensAndUsesTheIssuedClientForExchange() throws Exception {
        try (var provider = new Provider()) {
            var session = provider.session(Optional.empty());
            OAuthCredentials result = session.authorize(uri -> {
                Map<String, String> auth = provider.capture(uri);
                assertEquals("dynamic_agent_client", auth.get("client_id"));
                assertEquals("AIAssetMigrate", auth.get("agent_name_hint"));
                assertEquals(HOST, auth.get("ext_agent_host_id"));
                assertEquals("code", auth.get("response_type"));
                assertEquals("S256", auth.get("code_challenge_method"));
                assertEquals(OAuthSession.RESOURCE, auth.get("resource"));
                assertEquals("127.0.0.1", URI.create(auth.get("redirect_uri")).getHost());
                assertEquals("/auth/callback", URI.create(auth.get("redirect_uri")).getPath());
                assertTrue(auth.get("state").matches("[A-Za-z0-9_-]{43}"));
                assertTrue(auth.get("nonce").matches("[A-Za-z0-9_-]{43}"));
                assertFalse(auth.containsKey("code_verifier"));
                provider.callback(auth, ignored -> {});
            }, Duration.ofSeconds(5));
            assertEquals(1, provider.exchanges.get());
            assertEquals(CLIENT, result.clientId());
            assertEquals(SUBJECT, result.subject());
            assertEquals(Optional.of("person@example.test"), result.email());
            assertEquals(NOW.plusSeconds(3600), result.expiresAt());
            assertEquals("secret-access-token", result.accessToken());
            assertEquals("secret-refresh-token", result.refreshToken());
            assertEquals(CODE, provider.exchange.get("code"));
            assertEquals("authorization_code", provider.exchange.get("grant_type"));
            assertEquals(CLIENT, provider.exchange.get("client_id"));
            assertEquals(OAuthSession.RESOURCE, provider.exchange.get("resource"));
            assertEquals(provider.authorization.get("redirect_uri"), provider.exchange.get("redirect_uri"));
            assertEquals(provider.authorization.get("code_challenge"), OAuthSession.challenge(provider.exchange.get("code_verifier")));
            assertFalse(provider.exchange.containsKey("client_secret"));
            assertEquals("application/x-www-form-urlencoded", provider.contentType);
            assertNull(provider.authorizationHeader);
            assertEquals("OAuthCredentials[REDACTED]", result.toString());
            assertThrows(UnsupportedOperationException.class, () -> result.scopes().clear());
        }
    }

    @Test
    void returningRegistrationCanOmitClientIdButCannotChangeIdentity() throws Exception {
        try (var provider = new Provider()) {
            var session = provider.session(Optional.of(new OAuthSession.Registration(CLIENT, SUBJECT)));
            assertEquals(SUBJECT, session.authorize(uri -> {
                var auth = provider.capture(uri);
                assertEquals(CLIENT, auth.get("client_id"));
                assertFalse(auth.containsKey("agent_name_hint"));
                provider.callback(auth, values -> values.remove("client_id"));
            }, Duration.ofSeconds(5)).subject());

            provider.subject = "different-account";
            assertSanitized(assertThrows(OAuthException.class,
                    () -> session.authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.INVALID_IDENTITY);
        }
    }

    @Test
    void rejectsInvalidCallbacksBeforeAnyTokenExchange() throws Exception {
        List<Consumer<Map<String, String>>> changes = List.of(
                values -> values.remove("code"),
                values -> values.put("code", ""),
                values -> values.remove("client_id"),
                values -> values.put("client_id", "dynamic_agent_client"),
                values -> values.put("client_id", "not-an-issued-id"),
                values -> values.put("iss", "https://attacker.invalid"),
                values -> values.put("error", "secret-error-with-code"));
        try (var provider = new Provider()) {
            for (var change : changes) {
                assertSanitized(assertThrows(OAuthException.class, () -> provider.session(Optional.empty()).authorize(
                        uri -> provider.callback(provider.capture(uri), change), Duration.ofSeconds(5))),
                        OAuthException.Reason.INVALID_CALLBACK);
            }
            var returning = provider.session(Optional.of(new OAuthSession.Registration(CLIENT, SUBJECT)));
            assertSanitized(assertThrows(OAuthException.class, () -> returning.authorize(
                    uri -> provider.callback(provider.capture(uri), values -> values.put("client_id", "oaiapp_other")),
                    Duration.ofSeconds(5))), OAuthException.Reason.INVALID_CALLBACK);
            assertEquals(0, provider.exchanges.get());
        }
    }

    @Test
    void handlesDenialAndProviderErrorsWithoutEchoingTheCallback() throws Exception {
        try (var provider = new Provider()) {
            for (var error : Map.of("access_denied", OAuthException.Reason.CANCELED,
                    "secret-provider-error", OAuthException.Reason.INVALID_CALLBACK).entrySet()) {
                var failure = assertThrows(OAuthException.class, () -> provider.session(Optional.empty()).authorize(
                        uri -> provider.callback(provider.capture(uri), values -> {
                            values.remove("code");
                            values.put("error", error.getKey());
                            values.put("error_description", "secret-provider-body");
                        }), Duration.ofSeconds(5)));
                assertSanitized(failure, error.getValue());
            }
            assertEquals(0, provider.exchanges.get());
        }
    }

    @Test
    void callbackScopeCannotGrantPermissionsMissingFromTheTokenResponse() throws Exception {
        try (var provider = new Provider()) {
            provider.mutateTokens = body -> body.put("scope", "openid profile email");
            assertSanitized(assertThrows(OAuthException.class, () -> provider.session(Optional.empty()).authorize(
                    uri -> provider.callback(provider.capture(uri), values -> values.put("scope",
                            "openid offline_access resource.invoke chatgpt.tokens.use.direct")), Duration.ofSeconds(5))),
                    OAuthException.Reason.INSUFFICIENT_SCOPE);
        }
    }

    @Test
    void rejectsMalformedOrMissingTokenFieldsWithoutLeakingProviderData() throws Exception {
        List<Consumer<ObjectNode>> changes = List.of(
                body -> body.remove("access_token"), body -> body.put("access_token", ""),
                body -> body.put("access_token", "secret-token\nheader"), body -> body.put("access_token", 42),
                body -> body.put("access_token", "x".repeat(65537)),
                body -> body.remove("refresh_token"), body -> body.remove("id_token"), body -> body.remove("scope"),
                body -> body.put("token_type", "Basic"), body -> body.put("expires_in", "3600"),
                body -> body.put("expires_in", 1.5), body -> body.put("expires_in", 0),
                body -> body.put("expires_in", -1), body -> body.put("expires_in", 86401),
                body -> body.remove("expires_in"));
        try (var provider = new Provider()) {
            var session = provider.session(Optional.empty());
            for (var change : changes) {
                provider.mutateTokens = change;
                assertSanitized(assertThrows(OAuthException.class,
                        () -> session.authorize(provider.browser(), Duration.ofSeconds(5))),
                        OAuthException.Reason.INVALID_TOKEN_RESPONSE);
            }
            for (String body : new String[]{"secret-invalid-json", "null", "[]", "{} {}",
                    "{\"access_token\":\"secret\",\"access_token\":\"duplicate\"}"}) {
                provider.rawBody = body;
                assertSanitized(assertThrows(OAuthException.class,
                        () -> session.authorize(provider.browser(), Duration.ofSeconds(5))),
                        OAuthException.Reason.INVALID_TOKEN_RESPONSE);
            }
        }
    }

    @Test
    void rejectsBadIdentityBeforeReturningAnyCredentials() throws Exception {
        try (var provider = new Provider()) {
            provider.mutateTokens = body -> body.put("id_token", "secret-unverified-id-token");
            assertSanitized(assertThrows(OAuthException.class, () -> provider.session(Optional.empty())
                    .authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.INVALID_IDENTITY);
        }
    }

    @Test
    void exchangeDoesNotFollowRedirectsOrExposeErrorBodies() throws Exception {
        try (var provider = new Provider()) {
            for (int status : new int[]{302, 400, 429, 500}) {
                provider.status = status;
                provider.rawBody = "secret-provider-body";
                assertSanitized(assertThrows(OAuthException.class, () -> provider.session(Optional.empty())
                        .authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.TOKEN_EXCHANGE_FAILED);
            }
            assertEquals(4, provider.exchanges.get());
        }
    }

    @Test
    void boundsTokenResponseSizeAndHandlesConnectionFailure() throws Exception {
        try (var provider = new Provider()) {
            provider.rawBody = "secret-provider-body".repeat(20000);
            assertSanitized(assertThrows(OAuthException.class, () -> provider.session(Optional.empty())
                    .authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.NETWORK);
        }
        var provider = new Provider();
        var session = provider.session(Optional.empty());
        provider.close();
        assertSanitized(assertThrows(OAuthException.class,
                () -> session.authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.NETWORK);
    }

    @Test
    void createsFreshStateNonceAndPkceAndCanRetryAfterBrowserFailure() throws Exception {
        try (var provider = new Provider()) {
            var session = provider.session(Optional.empty());
            var requests = new ArrayList<Map<String, String>>();
            for (int i = 0; i < 2; i++) {
                assertSanitized(assertThrows(OAuthException.class, () -> session.authorize(uri -> {
                    requests.add(LoopbackCallback.parseQuery(uri.getRawQuery()));
                    throw new IOException("secret-browser-error");
                }, Duration.ofSeconds(5))), OAuthException.Reason.BROWSER_UNAVAILABLE);
            }
            for (String field : List.of("state", "nonce", "code_challenge")) {
                assertNotEquals(requests.getFirst().get(field), requests.getLast().get(field));
            }
            assertEquals(SUBJECT, session.authorize(provider.browser(), Duration.ofSeconds(5)).subject());
        }
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthSession.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void timesOutAndCancellationDoesNotPoisonTheSession() throws Exception {
        try (var provider = new Provider()) {
            var session = provider.session(Optional.empty());
            AtomicReference<URI> callback = new AtomicReference<>();
            assertSanitized(assertThrows(OAuthException.class, () -> session.authorize(uri -> {
                callback.set(URI.create(LoopbackCallback.parseQuery(uri.getRawQuery()).get("redirect_uri")));
            }, Duration.ofMillis(50))), OAuthException.Reason.TIMED_OUT);
            assertThrows(IOException.class, () -> HTTP.send(HttpRequest.newBuilder(callback.get())
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString()));
            assertSanitized(assertThrows(OAuthException.class,
                    () -> session.authorize(uri -> session.cancelAuthorization(), Duration.ofSeconds(5))),
                    OAuthException.Reason.CANCELED);
            assertEquals(SUBJECT, session.authorize(provider.browser(), Duration.ofSeconds(5)).subject());
        }
    }

    @Test
    void preventsConcurrentAttemptsAndCancelsAwaitedCallback() throws Exception {
        try (var provider = new Provider(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = provider.session(Optional.empty());
            var launched = new CountDownLatch(1);
            var future = executor.submit(() -> session.authorize(uri -> launched.countDown(), Duration.ofSeconds(5)));
            try {
                assertTrue(launched.await(2, TimeUnit.SECONDS));
                assertSanitized(assertThrows(OAuthException.class,
                        () -> session.authorize(provider.browser(), Duration.ofSeconds(5))), OAuthException.Reason.ALREADY_IN_PROGRESS);
            } finally {
                session.cancelAuthorization();
            }
            var failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertSanitized((OAuthException) failure.getCause(), OAuthException.Reason.CANCELED);
            assertEquals(SUBJECT, session.authorize(provider.browser(), Duration.ofSeconds(5)).subject());
        }
    }

    @Test
    void cancelsInFlightTokenExchangeAndNeverReturnsItsCredentials() throws Exception {
        try (var provider = new Provider(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = provider.session(Optional.empty());
            provider.releaseExchange = new CountDownLatch(1);
            var future = executor.submit(() -> session.authorize(provider.browser(), Duration.ofSeconds(5)));
            try {
                assertTrue(provider.exchangeStarted.await(2, TimeUnit.SECONDS));
                session.cancelAuthorization();
                var failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
                assertSanitized((OAuthException) failure.getCause(), OAuthException.Reason.CANCELED);
            } finally {
                provider.releaseExchange.countDown();
            }
        }
    }

    @Test
    void interruptionClosesTheListenerAndAllowsAnotherAttempt() throws Exception {
        try (var provider = new Provider(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = provider.session(Optional.empty());
            var launched = new CountDownLatch(1);
            var worker = new AtomicReference<Thread>();
            var future = executor.submit(() -> {
                worker.set(Thread.currentThread());
                return session.authorize(uri -> launched.countDown(), Duration.ofSeconds(5));
            });
            assertTrue(launched.await(2, TimeUnit.SECONDS));
            worker.get().interrupt();
            var failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(SUBJECT, session.authorize(provider.browser(), Duration.ofSeconds(5)).subject());
        }
    }

    @Test
    void rejectsInvalidHostRegistrationTimeoutAndUnsafeHttpPolicy() throws Exception {
        for (String host : new String[]{"", "short", UUID.randomUUID().toString(),
                "urn:uuid:1-1-4-8-1", "urn:uuid:00000000-0000-1000-8000-000000000000"}) {
            assertThrows(IllegalArgumentException.class, () -> new OAuthSession(host));
        }
        assertThrows(NullPointerException.class, () -> new OAuthSession(null));
        assertThrows(IllegalArgumentException.class, () -> new OAuthSession.Registration("dynamic_agent_client", SUBJECT));
        assertThrows(IllegalArgumentException.class, () -> new OAuthSession.Registration(CLIENT, ""));
        assertThrows(IllegalArgumentException.class, () -> new OAuthSession.Registration(CLIENT, "secret\nsubject"));
        try (var provider = new Provider()) {
            var session = provider.session(Optional.empty());
            for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(11))) {
                assertThrows(IllegalArgumentException.class, () -> session.authorize(provider.browser(), duration));
            }
            assertThrows(IllegalArgumentException.class, () -> new OAuthSession(HOST, Optional.empty(),
                    provider.endpoint("authorize"), provider.endpoint("token"),
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
                    validator(), CLOCK));
        }
    }

    @Test
    void credentialsSnapshotScopesAndNeverIncludeSecretsInToString() {
        var scopes = new java.util.HashSet<>(Set.of("openid"));
        var credentials = new OAuthCredentials(CLIENT, SUBJECT, Optional.empty(), "secret-access",
                "secret-refresh", "secret-id", scopes, NOW);
        scopes.clear();
        assertEquals(Set.of("openid"), credentials.scopes());
        assertEquals("OAuthCredentials[REDACTED]", credentials.toString());
    }

    private static OpenAiIdTokenValidator validator() {
        return new OpenAiIdTokenValidator(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), CLOCK);
    }

    private static void assertSanitized(OAuthException failure, OAuthException.Reason reason) {
        assertEquals(reason, failure.reason());
        assertFalse(failure.toString().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static final class Provider implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger exchanges = new AtomicInteger();
        final CountDownLatch exchangeStarted = new CountDownLatch(1);
        volatile CountDownLatch releaseExchange;
        volatile Map<String, String> authorization;
        volatile Map<String, String> exchange;
        volatile String contentType;
        volatile String authorizationHeader;
        volatile int status = 200;
        volatile String subject = SUBJECT;
        volatile String rawBody;
        volatile Consumer<ObjectNode> mutateTokens = ignored -> {};

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
            server.createContext("/token", request -> {
                try (request) {
                    exchanges.incrementAndGet();
                    exchange = LoopbackCallback.parseQuery(new String(request.getRequestBody().readNBytes(16384), StandardCharsets.UTF_8));
                    contentType = request.getRequestHeaders().getFirst("Content-Type");
                    authorizationHeader = request.getRequestHeaders().getFirst("Authorization");
                    exchangeStarted.countDown();
                    CountDownLatch release = releaseExchange;
                    if (release != null && !release.await(3, TimeUnit.SECONDS)) throw new IOException("Test exchange release timed out");
                    byte[] bytes = (rawBody == null ? tokens() : rawBody).getBytes(StandardCharsets.UTF_8);
                    request.getResponseHeaders().set("Content-Type", "application/json");
                    request.getResponseHeaders().set("Location", endpoint("token").toString());
                    request.sendResponseHeaders(status, bytes.length);
                    request.getResponseBody().write(bytes);
                } catch (Exception e) {
                    request.close();
                }
            });
            server.start();
        }

        OAuthSession session(Optional<OAuthSession.Registration> registration) {
            return new OAuthSession(HOST, registration, endpoint("authorize"), endpoint("token"), HTTP, validator(), CLOCK);
        }

        URI endpoint(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path);
        }

        Map<String, String> capture(URI uri) {
            assertEquals(endpoint("authorize").getAuthority(), uri.getAuthority());
            authorization = LoopbackCallback.parseQuery(uri.getRawQuery());
            return authorization;
        }

        OAuthSession.BrowserLauncher browser() {
            return uri -> callback(capture(uri), ignored -> {});
        }

        void callback(Map<String, String> auth, Consumer<Map<String, String>> modify) throws IOException {
            Map<String, String> response = new LinkedHashMap<>(Map.of("state", auth.get("state"), "code", CODE, "client_id", CLIENT));
            modify.accept(response);
            try {
                var result = HTTP.send(HttpRequest.newBuilder(URI.create(auth.get("redirect_uri") + "?" + OAuthSession.form(response)))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
                assertFalse(result.body().contains(CODE));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Test callback interrupted");
            }
        }

        String tokens() throws Exception {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder().issuer(OAuthSession.ISSUER).subject(subject)
                            .audience(exchange.get("client_id")).issueTime(Date.from(NOW.minusSeconds(30)))
                            .expirationTime(Date.from(NOW.plusSeconds(3600))).claim("nonce", authorization.get("nonce"))
                            .claim("email", "person@example.test").build());
            jwt.sign(new RSASSASigner(key));
            ObjectNode body = JSON.createObjectNode().put("access_token", "secret-access-token")
                    .put("refresh_token", "secret-refresh-token").put("id_token", jwt.serialize())
                    .put("token_type", "Bearer").put("expires_in", 3600)
                    .put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
            mutateTokens.accept(body);
            return JSON.writeValueAsString(body);
        }

        @Override public void close() {
            if (releaseExchange != null) releaseExchange.countDown();
            server.stop(0);
        }
    }
}
