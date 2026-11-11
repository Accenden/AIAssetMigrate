package io.github.aiassetmigrate.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * OpenAI's public-client OAuth authorization-code flow for locally hosted OSS apps.
 * The caller supplies an already-persisted host ID and must securely save the returned
 * credentials before announcing a saved connection. Persistence, refresh, model calls
 * and account UI are deliberately separate from this first OAuth increment.
 * Authorization blocks; never call it on the JavaFX application thread.
 *
 * @see <a href="https://developers.openai.com/siwc/token-sharing-open-source/sign-in">OpenAI registration and sign-in</a>
 */
public final class OAuthSession {
    static final String ISSUER = "https://auth.openai.com";
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final Set<String> REQUIRED_SCOPES = Set.of(
            "openid", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String hostId;
    private final Optional<Registration> registration;
    private final URI authorizationEndpoint;
    private final URI tokenEndpoint;
    private final HttpClient http;
    private final OpenAiIdTokenValidator identityValidator;
    private final Clock clock;
    private final AtomicReference<Pending> active = new AtomicReference<>();

    /** Reuse this host ID across launches; do not generate a new ID per sign-in. */
    public OAuthSession(String hostId) {
        this(hostId, Optional.empty());
    }

    /** A returning registration must come from previously validated, securely stored credentials. */
    public OAuthSession(String hostId, Optional<Registration> registration) {
        this(hostId, registration, URI.create(ISSUER + "/api/accounts/authorize"),
                URI.create(ISSUER + "/api/accounts/oauth/token"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                new OpenAiIdTokenValidator(), Clock.systemUTC());
    }

    OAuthSession(String hostId, Optional<Registration> registration, URI authorizationEndpoint,
                 URI tokenEndpoint, HttpClient http, OpenAiIdTokenValidator identityValidator, Clock clock) {
        Objects.requireNonNull(hostId, "hostId");
        try {
            UUID id = UUID.fromString(hostId.substring("urn:uuid:".length()));
            if (!hostId.equals("urn:uuid:" + id) || id.version() != 4 || id.variant() != 2) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Supply a persisted UUIDv4 host ID in urn:uuid: form");
        }
        this.hostId = hostId;
        this.registration = Objects.requireNonNull(registration, "registration");
        this.authorizationEndpoint = Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.http = Objects.requireNonNull(http, "http");
        this.identityValidator = Objects.requireNonNull(identityValidator, "identityValidator");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("OAuth HTTP redirects must be disabled");
        }
    }

    /** Opens the system browser. Headless clients may supply their own launcher instead. */
    public OAuthCredentials authorize() throws OAuthException, InterruptedException {
        return authorize(uri -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("System browser unavailable");
            }
            Desktop.getDesktop().browse(uri);
        }, Duration.ofMinutes(5));
    }

    public OAuthCredentials authorize(BrowserLauncher browser, Duration timeout)
            throws OAuthException, InterruptedException {
        Objects.requireNonNull(browser, "browser");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Authorization timeout must be positive and at most ten minutes");
        }
        Pending pending = new Pending();
        if (!active.compareAndSet(null, pending)) {
            throw new OAuthException(OAuthException.Reason.ALREADY_IN_PROGRESS, "Authorization is already in progress.");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String state = randomValue();
        String nonce = randomValue();
        String verifier = randomValue();
        try (LoopbackCallback callback = new LoopbackCallback(state)) {
            pending.callback = callback;
            checkCanceled(pending);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("client_id", registration.map(Registration::clientId).orElse(DYNAMIC_CLIENT));
            if (registration.isEmpty()) parameters.put("agent_name_hint", "AIAssetMigrate");
            parameters.put("ext_agent_host_id", hostId);
            parameters.put("response_type", "code");
            parameters.put("redirect_uri", callback.redirectUri().toString());
            parameters.put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
            parameters.put("resource", RESOURCE);
            parameters.put("state", state);
            parameters.put("nonce", nonce);
            parameters.put("code_challenge_method", "S256");
            parameters.put("code_challenge", challenge(verifier));
            try {
                browser.open(URI.create(authorizationEndpoint + "?" + form(parameters)));
            } catch (IOException | RuntimeException e) {
                throw new OAuthException(OAuthException.Reason.BROWSER_UNAVAILABLE,
                        "Could not open the authorization browser; start a new sign-in.");
            }
            checkCanceled(pending);
            Map<String, String> response = callback.await(remaining(deadline));
            checkCanceled(pending);
            String code = response.get("code");
            String error = response.get("error");
            if (response.containsKey("iss") && !ISSUER.equals(response.get("iss"))) {
                throw invalidCallback();
            }
            if (error != null) {
                if (code != null) throw invalidCallback();
                throw new OAuthException("access_denied".equals(error)
                        ? OAuthException.Reason.CANCELED : OAuthException.Reason.INVALID_CALLBACK,
                        "Authorization was not granted; start a new sign-in.");
            }
            if (code == null || code.isBlank()) throw invalidCallback();
            String clientId = response.get("client_id");
            if (registration.isPresent()) {
                if (clientId != null && !clientId.equals(registration.get().clientId())) throw invalidCallback();
                clientId = registration.get().clientId();
            }
            if (!validClientId(clientId)) throw invalidCallback();
            OAuthCredentials credentials = exchange(pending, clientId, code, verifier,
                    callback.redirectUri(), nonce, deadline);
            checkCanceled(pending);
            remaining(deadline);
            return credentials;
        } catch (IOException e) {
            throw new OAuthException(OAuthException.Reason.NETWORK, "Could not start the local authorization callback.");
        } finally {
            CompletableFuture<?> request = pending.request;
            if (request != null && !request.isDone()) request.cancel(true);
            active.compareAndSet(pending, null);
        }
    }

    /** Cancels only this authorization attempt; it cannot affect migration approval or a subscription. */
    public void cancelAuthorization() {
        Pending pending = active.get();
        if (pending != null) {
            pending.canceled = true;
            LoopbackCallback callback = pending.callback;
            if (callback != null) callback.cancel();
            CompletableFuture<?> request = pending.request;
            if (request != null) request.cancel(true);
        }
    }

    private OAuthCredentials exchange(Pending pending, String clientId, String code, String verifier,
                                      URI redirectUri, String nonce, long deadline)
            throws OAuthException, InterruptedException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("grant_type", "authorization_code");
        parameters.put("client_id", clientId);
        parameters.put("code", code);
        parameters.put("code_verifier", verifier);
        parameters.put("redirect_uri", redirectUri.toString());
        parameters.put("resource", RESOURCE);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint).timeout(remaining(deadline))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build();
        HttpResponse<byte[]> response;
        try {
            pending.request = http.sendAsync(request, ignored -> limitedBody(256 * 1024));
            checkCanceled(pending);
            response = pending.request.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new OAuthException(OAuthException.Reason.TIMED_OUT, "Authorization timed out; start a new sign-in.");
        } catch (ExecutionException | CancellationException e) {
            checkCanceled(pending);
            throw new OAuthException(OAuthException.Reason.NETWORK, "Authorization token exchange could not be completed.");
        }
        checkCanceled(pending);
        if (response.statusCode() != 200) {
            throw new OAuthException(OAuthException.Reason.TOKEN_EXCHANGE_FAILED,
                    "Authorization token exchange failed (HTTP " + response.statusCode() + "); start a new sign-in.");
        }
        try {
            JsonNode body = JSON.readTree(response.body());
            if (body == null || !body.isObject() || body.has("error")
                    || !"Bearer".equalsIgnoreCase(text(body, "token_type"))) throw new IOException();
            String accessToken = text(body, "access_token");
            String refreshToken = text(body, "refresh_token");
            String idToken = text(body, "id_token");
            String scope = text(body, "scope");
            Set<String> scopes = Set.of(scope.split(" +"));
            if (!scopes.containsAll(REQUIRED_SCOPES)) {
                throw new OAuthException(OAuthException.Reason.INSUFFICIENT_SCOPE,
                        "ChatGPT plan usage and offline access were not granted; authorize again with the required permissions.");
            }
            JsonNode expiresIn = body.get("expires_in");
            if (expiresIn == null || !expiresIn.isIntegralNumber() || !expiresIn.canConvertToLong()
                    || expiresIn.longValue() <= 0 || expiresIn.longValue() > 86400) throw new IOException();
            Instant expiresAt = clock.instant().plusSeconds(expiresIn.longValue());
            var identity = identityValidator.validate(idToken, clientId, nonce, registration.map(Registration::subject));
            return new OAuthCredentials(clientId, identity.subject(), identity.email(), accessToken,
                    refreshToken, idToken, scopes, expiresAt);
        } catch (IOException | IllegalArgumentException e) {
            throw new OAuthException(OAuthException.Reason.INVALID_TOKEN_RESPONSE,
                    "The authorization service returned an invalid token response.");
        }
    }

    /**
     * Exchanges the saved refresh token for new tokens (grant_type=refresh_token, existing grant
     * kept by omitting scope). The rotating refresh token is replaced when one is returned; the
     * caller must persist the result before using it and serialize refreshes per account.
     */
    public OAuthCredentials refresh(OAuthCredentials current) throws OAuthException, InterruptedException {
        Objects.requireNonNull(current, "current");
        if (!validClientId(current.clientId())) {
            throw new OAuthException(OAuthException.Reason.INVALID_TOKEN_RESPONSE, "Saved credentials cannot be refreshed; sign in again.");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("grant_type", "refresh_token");
        parameters.put("client_id", current.clientId());
        parameters.put("refresh_token", current.refreshToken());
        parameters.put("resource", RESOURCE);
        HttpResponse<byte[]> response = postForm(tokenEndpoint, parameters, Duration.ofSeconds(30));
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw new OAuthException(OAuthException.Reason.TOKEN_EXCHANGE_FAILED,
                    "The saved AI account session expired or was revoked; sign in again.");
        }
        if (response.statusCode() != 200) {
            throw new OAuthException(OAuthException.Reason.NETWORK,
                    "Token refresh failed (HTTP " + response.statusCode() + "); try again later.");
        }
        try {
            JsonNode body = JSON.readTree(response.body());
            if (body == null || !body.isObject() || body.has("error")
                    || !"Bearer".equalsIgnoreCase(text(body, "token_type"))) throw new IOException();
            String accessToken = text(body, "access_token");
            String refreshToken = body.has("refresh_token") ? text(body, "refresh_token") : current.refreshToken();
            String idToken = body.has("id_token") ? text(body, "id_token") : current.idToken();
            Set<String> scopes = body.has("scope") ? Set.of(text(body, "scope").split(" +")) : current.scopes();
            if (!scopes.containsAll(REQUIRED_SCOPES)) {
                throw new OAuthException(OAuthException.Reason.INSUFFICIENT_SCOPE,
                        "ChatGPT plan usage is no longer granted; sign in again.");
            }
            JsonNode expiresIn = body.get("expires_in");
            if (expiresIn == null || !expiresIn.isIntegralNumber() || !expiresIn.canConvertToLong()
                    || expiresIn.longValue() <= 0 || expiresIn.longValue() > 86400) throw new IOException();
            return new OAuthCredentials(current.clientId(), current.subject(), current.email(), accessToken,
                    refreshToken, idToken, scopes, clock.instant().plusSeconds(expiresIn.longValue()));
        } catch (IOException | IllegalArgumentException e) {
            throw new OAuthException(OAuthException.Reason.INVALID_TOKEN_RESPONSE,
                    "The authorization service returned an invalid refresh response.");
        }
    }

    /**
     * Revokes the refresh token at the issuer's discovered revocation endpoint. Returns true
     * only when revocation was confirmed; callers clear local credentials either way and must
     * tell the user when remote revocation was not confirmed.
     */
    public boolean revoke(OAuthCredentials credentials) throws InterruptedException {
        Objects.requireNonNull(credentials, "credentials");
        try {
            HttpRequest discovery = HttpRequest.newBuilder(URI.create(ISSUER + "/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> metadata = http.sendAsync(discovery, ignored -> limitedBody(256 * 1024))
                    .get(20, TimeUnit.SECONDS);
            if (metadata.statusCode() != 200) return false;
            JsonNode endpoint = JSON.readTree(metadata.body()).get("revocation_endpoint");
            if (endpoint == null || !endpoint.isTextual()) return false;
            URI revocation = URI.create(endpoint.textValue());
            if (!"https".equals(revocation.getScheme()) || !"auth.openai.com".equals(revocation.getHost())) return false;
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("token", credentials.refreshToken());
            parameters.put("token_type_hint", "refresh_token");
            parameters.put("client_id", credentials.clientId());
            return postForm(revocation, parameters, Duration.ofSeconds(15)).statusCode() == 200;
        } catch (OAuthException | IOException | ExecutionException | TimeoutException | RuntimeException e) {
            return false;
        }
    }

    private HttpResponse<byte[]> postForm(URI endpoint, Map<String, String> parameters, Duration timeout)
            throws OAuthException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build();
        try {
            return http.sendAsync(request, ignored -> limitedBody(256 * 1024))
                    .get(timeout.toNanos() + TimeUnit.SECONDS.toNanos(5), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            throw new OAuthException(OAuthException.Reason.NETWORK, "The authorization service could not be reached.");
        }
    }

    private static String text(JsonNode body, String name) throws IOException {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > 65536 || value.textValue().chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid token field");
        }
        return value.textValue();
    }

    private static HttpResponse.BodySubscriber<byte[]> limitedBody(int limit) {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private int count;

            @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            @Override public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > limit - count) {
                        subscription.cancel();
                        delegate.onError(new IOException("Authorization response exceeded its size limit"));
                        return;
                    }
                    count += buffer.remaining();
                }
                delegate.onNext(buffers);
            }
            @Override public void onError(Throwable error) { delegate.onError(error); }
            @Override public void onComplete() { delegate.onComplete(); }
        };
    }

    private static Duration remaining(long deadline) throws OAuthException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new OAuthException(OAuthException.Reason.TIMED_OUT, "Authorization timed out; start a new sign-in.");
        }
        return Duration.ofNanos(nanos);
    }

    private static void checkCanceled(Pending pending) throws OAuthException {
        if (pending.canceled) throw new OAuthException(OAuthException.Reason.CANCELED, "Authorization was canceled.");
    }

    private static OAuthException invalidCallback() {
        return new OAuthException(OAuthException.Reason.INVALID_CALLBACK, "Authorization callback was invalid; start a new sign-in.");
    }

    static boolean validClientId(String clientId) {
        return clientId != null && clientId.matches("oaiapp_[A-Za-z0-9_-]{1,256}");
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime");
        }
    }

    static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Registration(String clientId, String subject) {
        public Registration {
            if (!validClientId(clientId) || subject == null || subject.isBlank()
                    || subject.length() > 1024 || subject.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A returning registration requires an issued client ID and verified subject");
            }
        }
    }

    @FunctionalInterface
    public interface BrowserLauncher {
        /** Open in the system browser. Do not log the authorization URL or display its query as a diagnostic. */
        void open(URI authorizationUri) throws IOException;
    }

    private static final class Pending {
        volatile boolean canceled;
        volatile LoopbackCallback callback;
        volatile CompletableFuture<HttpResponse<byte[]>> request;
    }
}
