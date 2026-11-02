package io.github.aiassetmigrate.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiIdTokenValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-02T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CLIENT = "issued-client-id";
    private static final String NONCE = "secret-random-nonce";
    private static final String SUBJECT = "account-subject";
    private static RSAKey key;
    private static RSAKey otherKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("trusted-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
    }

    @Test
    void returnsOnlyVerifiedIdentityAndMatchesReturningAccount() throws Exception {
        var identity = validator().validate(token(claims().build()), CLIENT, NONCE, Optional.of(SUBJECT));
        assertEquals(new OpenAiIdTokenValidator.VerifiedIdentity(SUBJECT,
                Optional.of("person@example.test")), identity);
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsInvalidClaims(String description, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var builder = claims();
        change.accept(builder);
        assertInvalid(token(builder.build()), CLIENT, NONCE, Optional.empty());
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(
                invalid("missing issuer", b -> b.issuer(null)),
                invalid("different issuer", b -> b.issuer("https://attacker.example")),
                invalid("issuer trailing slash", b -> b.issuer("https://auth.openai.com/")),
                invalid("missing audience", b -> b.audience((String) null)),
                invalid("different audience", b -> b.audience("other-client")),
                invalid("empty audience", b -> b.audience(List.of())),
                invalid("expired", b -> b.expirationTime(date(-6))),
                invalid("expired at skew boundary", b -> b.expirationTime(date(-5))),
                invalid("missing expiration", b -> b.expirationTime(null)),
                invalid("missing issued-at", b -> b.issueTime(null)),
                invalid("future issued-at", b -> b.issueTime(date(6))),
                invalid("issued after expiration", b -> b.issueTime(date(2)).expirationTime(date(1))),
                invalid("future not-before", b -> b.notBeforeTime(date(6))),
                invalid("not-before after expiration", b -> b.notBeforeTime(date(2)).expirationTime(date(1))),
                invalid("missing subject", b -> b.subject(null)),
                invalid("blank subject", b -> b.subject(" \t")),
                invalid("missing nonce", b -> b.claim("nonce", null)),
                invalid("blank nonce", b -> b.claim("nonce", " \t")),
                invalid("different nonce", b -> b.claim("nonce", "secret-other-nonce")),
                invalid("non-string nonce", b -> b.claim("nonce", 42)),
                invalid("multi-audience without azp", b -> b.audience(List.of(CLIENT, "other-client"))),
                invalid("multi-audience wrong azp", b -> b.audience(List.of(CLIENT, "other-client"))
                        .claim("azp", "other-client")),
                invalid("single-audience wrong azp", b -> b.claim("azp", "other-client")),
                invalid("blank azp", b -> b.claim("azp", "")),
                invalid("non-string azp", b -> b.claim("azp", List.of(CLIENT))),
                invalid("wrong expiration type", b -> b.claim("exp", "not-a-date")),
                invalid("wrong issued-at type", b -> b.claim("iat", "not-a-date")),
                invalid("wrong not-before type", b -> b.claim("nbf", "not-a-date")),
                invalid("wrong email type", b -> b.claim("email", List.of("person@example.test")))
        );
    }

    @Test
    void acceptsMultiAudienceOnlyWithMatchingAuthorizedParty() throws Exception {
        var claims = claims().audience(List.of("other-client", CLIENT)).claim("azp", CLIENT).build();
        assertEquals(SUBJECT, validator().validate(token(claims), CLIENT, NONCE, Optional.empty()).subject());
        assertEquals(SUBJECT, validator().validate(token(claims().claim("azp", CLIENT).build()),
                CLIENT, NONCE, Optional.empty()).subject());
    }

    @Test
    void allowsAtMostFiveSecondsClockSkew() throws Exception {
        for (var claims : List.of(
                claims().expirationTime(date(-4)).build(),
                claims().issueTime(date(5)).build(),
                claims().notBeforeTime(date(5)).build())) {
            assertEquals(SUBJECT, validator().validate(token(claims), CLIENT, NONCE, Optional.empty()).subject());
        }
    }

    @Test
    void omitsAbsentOrBlankEmail() throws Exception {
        for (String email : new String[]{null, "", " \t"}) {
            var identity = validator().validate(token(claims().claim("email", email).build()),
                    CLIENT, NONCE, Optional.empty());
            assertEquals(Optional.empty(), identity.email());
        }
    }

    @Test
    void rejectsMismatchedReturningAccountAndNonce() throws Exception {
        String token = token(claims().build());
        assertInvalid(token, CLIENT, NONCE, Optional.of("different-account"));
        assertInvalid(token, CLIENT, NONCE, Optional.of(""));
        assertInvalid(token, CLIENT, "different-nonce", Optional.empty());
    }

    @Test
    void rejectsWrongSignatureAndUnknownKey() throws Exception {
        assertInvalid(sign(claims().build(), otherKey,
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build()),
                CLIENT, NONCE, Optional.empty());
        assertInvalid(sign(claims().build(), otherKey,
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(otherKey.getKeyID()).build()),
                CLIENT, NONCE, Optional.empty());
        var emptyKeys = new OpenAiIdTokenValidator(new ImmutableJWKSet<>(new JWKSet()), CLOCK);
        assertSanitized(assertThrows(OAuthException.class,
                () -> emptyKeys.validate(token(claims().build()), CLIENT, NONCE, Optional.empty())));
    }

    @Test
    void rejectsUnsignedHmacAndOtherRsaAlgorithms() throws Exception {
        assertInvalid(new PlainJWT(claims().build()).serialize(), CLIENT, NONCE, Optional.empty());
        var hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        hmac.sign(new MACSigner(new byte[32]));
        assertInvalid(hmac.serialize(), CLIENT, NONCE, Optional.empty());
        for (var algorithm : List.of(JWSAlgorithm.RS384, JWSAlgorithm.RS512, JWSAlgorithm.PS256)) {
            assertInvalid(sign(claims().build(), key,
                    new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build()),
                    CLIENT, NONCE, Optional.empty());
        }
    }

    @Test
    void ignoresUntrustedKeyLocationsAndEmbeddedKeys() throws Exception {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .jwkURL(URI.create("https://attacker.invalid/jwks"))
                .x509CertURL(URI.create("https://attacker.invalid/certificate"))
                .jwk(otherKey.toPublicJWK()).build();
        assertEquals(SUBJECT, validator().validate(sign(claims().build(), key, header),
                CLIENT, NONCE, Optional.empty()).subject());
        assertInvalid(sign(claims().build(), otherKey, header), CLIENT, NONCE, Optional.empty());
    }

    @Test
    void rejectsMalformedOversizeAndMissingInputs() throws Exception {
        for (String input : new String[]{null, "", " ", "secret-malformed-token", "a.b.c",
                "a".repeat(64 * 1024), "a".repeat(64 * 1024 + 1)}) {
            assertInvalid(input, CLIENT, NONCE, Optional.empty());
        }
        String token = token(claims().build());
        for (String input : new String[]{null, "", " \t"}) {
            assertInvalid(token, input, NONCE, Optional.empty());
            assertInvalid(token, CLIENT, input, Optional.empty());
        }
        assertInvalid(token, CLIENT, NONCE, null);
    }

    @Test
    void enforcesLengthLimitEvenForOtherwiseValidSignedTokens() throws Exception {
        String allowed = token(claims().claim("padding", "a".repeat(47_000)).build());
        assertTrue(allowed.length() < 64 * 1024);
        assertEquals(SUBJECT, validator().validate(allowed, CLIENT, NONCE, Optional.empty()).subject());
        String oversized = token(claims().claim("padding", "a".repeat(50_000)).build());
        assertTrue(oversized.length() > 64 * 1024);
        assertInvalid(oversized, CLIENT, NONCE, Optional.empty());
    }

    @Test
    void sanitizesKeySourceErrorsWithoutExposingClaimsOrCauses() throws Exception {
        var validator = new OpenAiIdTokenValidator((selector, context) -> {
            throw new KeySourceException("secret-provider-body-and-url");
        }, CLOCK);
        assertSanitized(assertThrows(OAuthException.class,
                () -> validator.validate(token(claims().build()), CLIENT, NONCE, Optional.empty())));
    }

    @Test
    void constructsProductionValidatorWithoutNetworkAccess() {
        assertNotNull(new OpenAiIdTokenValidator());
    }

    private static Arguments invalid(String description, Consumer<JWTClaimsSet.Builder> change) {
        return Arguments.of(description, change);
    }

    private static OpenAiIdTokenValidator validator() {
        return new OpenAiIdTokenValidator(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(key.toPublicJWK())), CLOCK);
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("https://auth.openai.com").subject(SUBJECT)
                .audience(CLIENT).issueTime(date(-30)).expirationTime(date(300))
                .claim("nonce", NONCE).claim("email", "person@example.test");
    }

    private static Date date(long seconds) {
        return Date.from(NOW.plusSeconds(seconds));
    }

    private static String token(JWTClaimsSet claims) throws Exception {
        return sign(claims, key, new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build());
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey, JWSHeader header) throws Exception {
        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static void assertInvalid(String token, String client, String nonce,
                                      Optional<String> expectedSubject) {
        assertSanitized(assertThrows(OAuthException.class,
                () -> validator().validate(token, client, nonce, expectedSubject)));
    }

    private static void assertSanitized(OAuthException error) {
        assertEquals(OAuthException.Reason.INVALID_IDENTITY, error.reason());
        assertEquals("OpenAI identity token could not be verified.", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }
}
