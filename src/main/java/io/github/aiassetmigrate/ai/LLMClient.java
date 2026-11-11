package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Application-side model interface (Adapter target). Implementations translate a prompt into
 * a provider request and return the model's JSON object. Output is untrusted advisory data:
 * callers validate every field and never treat it as an approved change.
 */
public interface LLMClient {
    JsonNode generate(String prompt) throws AiException;

    /** Short, non-secret description such as the provider and model, for display only. */
    String description();

    /** Sanitized failure; never includes credentials or provider response content. */
    final class AiException extends Exception {
        public enum Reason { NOT_CONNECTED, AUTHENTICATION, USAGE_LIMIT, UNAVAILABLE, INVALID_OUTPUT, INTERRUPTED }

        private final Reason reason;

        public AiException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }
}
