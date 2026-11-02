package io.github.aiassetmigrate.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validated, in-memory OAuth result. Callers must securely persist it before
 * reporting a saved account connection. Never put this object in logs or project state.
 */
public record OAuthCredentials(String clientId, String subject, Optional<String> email,
                               String accessToken, String refreshToken, String idToken,
                               Set<String> scopes, Instant expiresAt) {
    public OAuthCredentials {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        scopes = Set.copyOf(scopes);
        if (clientId.isBlank() || subject.isBlank() || accessToken.isBlank()
                || refreshToken.isBlank() || idToken.isBlank()) {
            throw new IllegalArgumentException("OAuth credential fields must not be blank");
        }
    }

    @Override
    public String toString() {
        return "OAuthCredentials[REDACTED]";
    }
}
