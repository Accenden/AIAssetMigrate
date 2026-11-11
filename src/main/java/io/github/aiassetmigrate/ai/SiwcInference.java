package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Isolated SIWC inference. Returned JSON is untrusted advisory data, not an approved change. */
public final class SiwcInference {
    public static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    /** Account-specific model catalog; entries with visibility "list" are selectable. */
    public static final URI MODELS_ENDPOINT = URI.create("https://api.openai.com/v1/models");
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_INPUT_CHARS = 100_000;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50)
                    .maxStringLength(MAX_RESPONSE_BYTES).maxNumberLength(100).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /**
     * Implementations must enforce the total deadline, including connection and body reads,
     * and abort before buffering more than maxResponseBytes. They must not log arguments or
     * redirect the bearer credential. {@link SiwcHttpTransport} provides an HTTP implementation.
     */
    @FunctionalInterface
    public interface Transport {
        Response post(URI endpoint, String bearerToken, byte[] jsonBody,
                      Duration timeout, int maxResponseBytes) throws Exception;

        /** Bounded GET for the model catalog; transports that cannot list models may refuse. */
        default Response get(URI endpoint, String bearerToken, Duration timeout, int maxResponseBytes) throws Exception {
            throw new UnsupportedOperationException("Model listing is not supported by this transport");
        }
    }

    /** A selectable model from the signed-in account's catalog. */
    public record Model(String slug, String displayName) {}

    /** Transport response; never log its body. */
    public record Response(int status, byte[] body) {
        @Override public String toString() { return "Response[REDACTED]"; }
    }

    public enum Failure { INVALID_REQUEST, TRANSPORT, HTTP, UNAUTHORIZED, RESPONSE_TOO_LARGE, INVALID_STREAM,
        FAILED, USAGE_LIMIT, INCOMPLETE, INVALID_OUTPUT }

    /** Deliberately excludes transport exceptions and provider content. */
    public static final class InferenceException extends Exception {
        private final Failure failure;
        private InferenceException(Failure failure) {
            super("SIWC inference failed: " + failure);
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    private final Transport transport;
    private final Set<String> approvedModels;
    private final Duration timeout;

    /** Approved slugs must come from the selected account's current model catalog. */
    public SiwcInference(Transport transport, Set<String> approvedModels, Duration timeout) {
        this.transport = Objects.requireNonNull(transport);
        this.approvedModels = Set.copyOf(approvedModels);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Timeout must be positive and at most 60 seconds");
        }
    }

    /**
     * Lists models the account may use, preserving server order. Only entries whose
     * visibility is "list" are returned. Entries without a usable slug are skipped.
     */
    public static java.util.List<Model> listModels(Transport transport, String accessToken, Duration timeout)
            throws InferenceException {
        if (!validToken(accessToken)) throw new InferenceException(Failure.INVALID_REQUEST);
        Response response;
        try {
            response = transport.get(MODELS_ENDPOINT, accessToken, timeout, MAX_RESPONSE_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException(Failure.TRANSPORT);
        } catch (Exception e) {
            throw new InferenceException(Failure.TRANSPORT);
        }
        if (response == null || response.body() == null) throw new InferenceException(Failure.TRANSPORT);
        if (response.status() == 401) throw new InferenceException(Failure.UNAUTHORIZED);
        if (response.status() != 200) throw new InferenceException(Failure.HTTP);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response.body())).toString();
        } catch (CharacterCodingException e) {
            throw new InferenceException(Failure.INVALID_OUTPUT);
        }
        JsonNode body = parseJson(text, Failure.INVALID_OUTPUT);
        if (!body.path("models").isArray()) throw new InferenceException(Failure.INVALID_OUTPUT);
        java.util.List<Model> models = new java.util.ArrayList<>();
        for (JsonNode entry : body.path("models")) {
            String slug = entry.path("slug").asText("");
            if (!entry.path("visibility").asText("").equals("list") || !slug.matches("[A-Za-z0-9._:-]{1,128}")) continue;
            String display = entry.path("display_name").asText(slug);
            if (display.isBlank() || display.length() > 200 || display.chars().anyMatch(Character::isISOControl)) display = slug;
            models.add(new Model(slug, display));
        }
        return java.util.List.copyOf(models);
    }

    private static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.indexOf('\r') < 0 && token.indexOf('\n') < 0;
    }

    /** Caller controls opt-in, token freshness, account/model selection and revision validation. */
    public JsonNode infer(String accessToken, String model, String input) throws InferenceException {
        if (accessToken == null || accessToken.isBlank() || accessToken.indexOf('\r') >= 0
                || accessToken.indexOf('\n') >= 0 || model == null || model.isBlank()
                || !approvedModels.contains(model) || input == null || input.isBlank()
                || input.length() > MAX_INPUT_CHARS) {
            throw new InferenceException(Failure.INVALID_REQUEST);
        }
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        ArrayNode messages = request.putArray("input");
        messages.addObject().put("role", "user").put("content", input);
        request.put("store", false);
        request.put("stream", true);
        Response response;
        try {
            response = transport.post(ENDPOINT, accessToken, JSON.writeValueAsBytes(request), timeout,
                    MAX_RESPONSE_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException(Failure.TRANSPORT);
        } catch (Exception e) {
            throw new InferenceException(Failure.TRANSPORT);
        }
        if (response == null) throw new InferenceException(Failure.TRANSPORT);
        if (response.status() == 401) throw new InferenceException(Failure.UNAUTHORIZED);
        if (response.status() != 200) throw new InferenceException(Failure.HTTP);
        if (response.body() == null) throw new InferenceException(Failure.INVALID_STREAM);
        if (response.body().length > MAX_RESPONSE_BYTES) throw new InferenceException(Failure.RESPONSE_TOO_LARGE);
        return parseStream(response.body());
    }

    private static JsonNode parseStream(byte[] bytes) throws InferenceException {
        String stream;
        try {
            stream = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new InferenceException(Failure.INVALID_STREAM);
        }
        String event = "";
        StringBuilder data = new StringBuilder();
        JsonNode completed = null;
        // A blank line is required to dispatch an SSE event; a truncated final event is rejected.
        String[] lines = stream.split("\\r\\n|\\r|\\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    if (completed != null) throw new InferenceException(Failure.INVALID_STREAM);
                    JsonNode payload = parseJson(data.substring(0, data.length() - 1), Failure.INVALID_STREAM);
                    String type = payload.path("type").asText("");
                    if (!event.isEmpty() && !event.equals(type)) throw new InferenceException(Failure.INVALID_STREAM);
                    if (type.equals("response.failed") || type.equals("error")) {
                        String code = payload.path("response").path("error").path("code").asText(
                                payload.path("error").path("code").asText(""));
                        throw new InferenceException(code.startsWith("subscription_sharing_usage")
                                ? Failure.USAGE_LIMIT : Failure.FAILED);
                    }
                    if (type.equals("response.incomplete")) throw new InferenceException(Failure.INCOMPLETE);
                    if (type.equals("response.completed")) completed = payload;
                    else if (type.isEmpty()) throw new InferenceException(Failure.INVALID_STREAM);
                }
                event = "";
                data.setLength(0);
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if (field.equals("event")) event = value;
                if (field.equals("data")) data.append(value).append('\n');
            }
        }
        if (completed == null || data.length() != 0 || !event.isEmpty() || !lines[lines.length - 1].isEmpty()) {
            throw new InferenceException(Failure.INVALID_STREAM);
        }
        JsonNode response = completed.path("response");
        if (!response.path("status").asText().equals("completed") || !response.path("output").isArray()) {
            throw new InferenceException(Failure.INVALID_STREAM);
        }
        String text = null;
        for (JsonNode item : response.path("output")) {
            if (item.path("type").asText().equals("reasoning")) continue;
            if (!item.path("type").asText().equals("message") || !item.path("role").asText().equals("assistant")
                    || !item.path("content").isArray()) throw new InferenceException(Failure.INVALID_OUTPUT);
            for (JsonNode part : item.path("content")) {
                if (!part.path("type").asText().equals("output_text") || !part.path("text").isTextual()
                        || text != null) throw new InferenceException(Failure.INVALID_OUTPUT);
                text = part.path("text").textValue();
            }
        }
        if (text == null || text.length() > MAX_OUTPUT_CHARS) throw new InferenceException(Failure.INVALID_OUTPUT);
        JsonNode output = parseJson(text, Failure.INVALID_OUTPUT);
        if (!output.isObject()) throw new InferenceException(Failure.INVALID_OUTPUT);
        return output;
    }

    private static JsonNode parseJson(String value, Failure failure) throws InferenceException {
        try (var parser = JSON.createParser(value)) {
            JsonNode result = JSON.readTree(parser);
            if (result == null || parser.nextToken() != null) throw new InferenceException(failure);
            return result;
        } catch (InferenceException e) {
            throw e;
        } catch (Exception e) {
            throw new InferenceException(failure);
        }
    }
}
