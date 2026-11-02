package io.github.aiassetmigrate.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class OpenAiIdTokenValidator {
    private static final String ISSUER = "https://auth.openai.com";
    private static final int MAX_TOKEN_LENGTH = 64 * 1024;
    private static final long CLOCK_SKEW_SECONDS = 5;

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final Clock clock;

    OpenAiIdTokenValidator() {
        this(trustedKeys(), Clock.systemUTC());
    }

    OpenAiIdTokenValidator(JWKSource<SecurityContext> keys, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, Objects.requireNonNull(keys)));
        // Claims are checked below, after signature verification, using the injected clock.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
    }

    VerifiedIdentity validate(String idToken, String clientId, String nonce,
                              Optional<String> expectedSubject) throws OAuthException {
        if (idToken == null || idToken.isBlank() || idToken.length() > MAX_TOKEN_LENGTH
                || clientId == null || clientId.isBlank() || nonce == null || nonce.isBlank()
                || expectedSubject == null) {
            throw invalidIdentity();
        }
        try {
            JWTClaimsSet claims = processor.process(idToken, null);
            List<String> audience = claims.getAudience();
            String authorizedParty = claims.getStringClaim("azp");
            if (!ISSUER.equals(claims.getIssuer()) || !audience.contains(clientId)
                    || (authorizedParty != null && !clientId.equals(authorizedParty))
                    || (audience.size() > 1 && authorizedParty == null)) {
                throw invalidIdentity();
            }

            Instant now = clock.instant();
            Date expiration = claims.getExpirationTime();
            Date issuedAt = claims.getIssueTime();
            Date notBefore = claims.getNotBeforeTime();
            if (expiration == null || issuedAt == null
                    || !expiration.toInstant().isAfter(now.minusSeconds(CLOCK_SKEW_SECONDS))
                    || issuedAt.toInstant().isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))
                    || issuedAt.after(expiration)
                    || (notBefore != null && (notBefore.toInstant().isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))
                    || notBefore.after(expiration)))) {
                throw invalidIdentity();
            }

            String subject = claims.getSubject();
            String tokenNonce = claims.getStringClaim("nonce");
            if (subject == null || subject.isBlank() || tokenNonce == null || tokenNonce.isBlank()
                    || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                    tokenNonce.getBytes(StandardCharsets.UTF_8))
                    || (expectedSubject.isPresent() && !expectedSubject.get().equals(subject))) {
                throw invalidIdentity();
            }
            Optional<String> email = Optional.ofNullable(claims.getStringClaim("email"))
                    .filter(value -> !value.isBlank());
            return new VerifiedIdentity(subject, email);
        } catch (ParseException | BadJOSEException | JOSEException | RuntimeException e) {
            throw invalidIdentity();
        }
    }

    private static JWKSource<SecurityContext> trustedKeys() {
        try {
            var retriever = new DefaultResourceRetriever(2_000, 2_000, 1024 * 1024) {
                @Override
                protected HttpURLConnection openHTTPConnection(URL url) throws IOException {
                    HttpURLConnection connection = super.openHTTPConnection(url);
                    connection.setInstanceFollowRedirects(false);
                    return connection;
                }
            };
            // Only this fixed endpoint is used; JWT jku, x5u and embedded keys are not trusted.
            return JWKSourceBuilder.<SecurityContext>create(
                            URI.create(ISSUER + "/.well-known/jwks.json").toURL(), retriever)
                    .cache(300_000, 5_000)
                    .refreshAheadCache(false)
                    .rateLimited(1_000)
                    .retrying(false)
                    .outageTolerant(false)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to configure OpenAI identity verification.");
        }
    }

    private static OAuthException invalidIdentity() {
        return new OAuthException(OAuthException.Reason.INVALID_IDENTITY,
                "OpenAI identity token could not be verified.");
    }

    record VerifiedIdentity(String subject, Optional<String> email) {}
}
