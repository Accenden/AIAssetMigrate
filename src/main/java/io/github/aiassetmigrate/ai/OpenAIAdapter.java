package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aiassetmigrate.auth.AccountSession;
import io.github.aiassetmigrate.auth.OAuthException;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts {@link LLMClient} to OpenAI's Responses API using the signed-in ChatGPT account
 * (Sign in with ChatGPT). There is no API-key or paid fallback: a missing account, expired
 * session or usage limit is reported, and callers keep their deterministic results.
 */
public final class OpenAIAdapter implements LLMClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private final AccountSession session;
    private final SiwcInference inference;
    private final String model;

    public OpenAIAdapter(AccountSession session, SiwcInference.Transport transport, String model) {
        this.session = Objects.requireNonNull(session, "session");
        this.model = Objects.requireNonNull(model, "model");
        this.inference = new SiwcInference(Objects.requireNonNull(transport, "transport"), Set.of(model), TIMEOUT);
    }

    /** Uses the account's saved model selection. */
    public static OpenAIAdapter forSession(AccountSession session) throws AiException {
        String model;
        try {
            model = session.account().model().orElseThrow(() -> new AiException(AiException.Reason.NOT_CONNECTED,
                    "No model is selected; run 'auth models' then 'auth use-model SLUG'"));
        } catch (AccountSession.NotConnectedException e) {
            throw new AiException(AiException.Reason.NOT_CONNECTED, e.getMessage());
        } catch (IOException e) {
            throw new AiException(AiException.Reason.AUTHENTICATION, e.getMessage());
        }
        return new OpenAIAdapter(session, new SiwcHttpTransport(), model);
    }

    /**
     * Returns a client for the current user's saved account, or a client whose every request
     * reports why AI is unavailable, so hybrid analysis still returns rule-based results.
     */
    public static LLMClient connectedOrUnavailable() {
        try {
            return forSession(AccountSession.forStore(io.github.aiassetmigrate.auth.AccountStore.forCurrentUser()));
        } catch (AiException e) {
            return unavailable(e);
        } catch (IOException e) {
            return unavailable(new AiException(AiException.Reason.NOT_CONNECTED, e.getMessage()));
        }
    }

    /** A client that always fails with the given reason; it never contacts a provider. */
    public static LLMClient unavailable(AiException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LLMClient() {
            @Override public JsonNode generate(String prompt) throws AiException { throw failure; }
            @Override public String description() { return "unavailable"; }
        };
    }

    @Override public String description() { return "OpenAI " + model + " via your ChatGPT account"; }

    @Override public JsonNode generate(String prompt) throws AiException {
        try {
            try {
                return inference.infer(session.accessToken(false), model, prompt);
            } catch (SiwcInference.InferenceException e) {
                if (e.failure() != SiwcInference.Failure.UNAUTHORIZED) throw e;
                return inference.infer(session.accessToken(true), model, prompt);
            }
        } catch (SiwcInference.InferenceException e) {
            throw switch (e.failure()) {
                case UNAUTHORIZED -> new AiException(AiException.Reason.AUTHENTICATION,
                        "The AI account session was rejected; sign in again");
                case USAGE_LIMIT -> new AiException(AiException.Reason.USAGE_LIMIT,
                        "ChatGPT plan usage limit reached; AI suggestions are unavailable until it resets");
                case INVALID_OUTPUT, INVALID_STREAM, RESPONSE_TOO_LARGE -> new AiException(AiException.Reason.INVALID_OUTPUT,
                        "The model returned an unusable response (" + e.failure() + ")");
                default -> new AiException(AiException.Reason.UNAVAILABLE, "AI request failed (" + e.failure() + ")");
            };
        } catch (AccountSession.NotConnectedException e) {
            throw new AiException(AiException.Reason.NOT_CONNECTED, e.getMessage());
        } catch (OAuthException e) {
            throw new AiException(AiException.Reason.AUTHENTICATION, e.getMessage());
        } catch (IOException e) {
            throw new AiException(AiException.Reason.AUTHENTICATION, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Reason.INTERRUPTED, "AI request interrupted");
        }
    }
}
