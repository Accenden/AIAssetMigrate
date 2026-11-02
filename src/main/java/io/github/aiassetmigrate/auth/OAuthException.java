package io.github.aiassetmigrate.auth;

/** Sanitized authorization failure. Provider bodies, callback URLs and tokens are never attached. */
public final class OAuthException extends Exception {
    public enum Reason {
        CANCELED, TIMED_OUT, INVALID_CALLBACK, TOKEN_EXCHANGE_FAILED,
        INVALID_TOKEN_RESPONSE, INVALID_IDENTITY, INSUFFICIENT_SCOPE,
        NETWORK, ALREADY_IN_PROGRESS, BROWSER_UNAVAILABLE
    }

    private final Reason reason;

    public OAuthException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
