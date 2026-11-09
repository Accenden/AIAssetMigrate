package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SiwcInferenceTest {
    private static final String TOKEN = "secret-token";
    private static final String INPUT = "private-prompt";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String event(String type, String payload) {
        return "event: " + type + "\ndata: " + payload + "\n\n";
    }
    private static String completed(String text) throws Exception {
        String payload = JSON.writeValueAsString(java.util.Map.of("type", "response.completed", "response",
                java.util.Map.of("status", "completed", "output", java.util.List.of(
                        java.util.Map.of("type", "message", "role", "assistant", "content", java.util.List.of(
                                java.util.Map.of("type", "output_text", "text", text)))))));
        return event("response.completed", payload);
    }
    private static SiwcInference adapter(SiwcInference.Transport transport) {
        return new SiwcInference(transport, Set.of("approved"), Duration.ofSeconds(5));
    }
    private static SiwcInference withStream(String stream) {
        return adapter((uri, token, body, timeout, max) -> new SiwcInference.Response(200, bytes(stream)));
    }
    private static void fails(SiwcInference.Failure expected, SiwcInference adapter) {
        var error = assertThrows(SiwcInference.InferenceException.class,
                () -> adapter.infer(TOKEN, "approved", INPUT));
        assertEquals(expected, error.failure());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(TOKEN));
        assertFalse(error.toString().contains(INPUT));
    }

    @Test void sendsOnlySupportedFieldsAndReadsCompletedOutput() throws Exception {
        var adapter = adapter((uri, token, body, timeout, max) -> {
            assertEquals(SiwcInference.ENDPOINT, uri);
            assertEquals(TOKEN, token);
            assertEquals(Duration.ofSeconds(5), timeout);
            assertEquals(SiwcInference.MAX_RESPONSE_BYTES, max);
            var request = JSON.readTree(body);
            assertEquals(Set.of("model", "input", "store", "stream"),
                    java.util.stream.StreamSupport.stream(
                            java.util.Spliterators.spliteratorUnknownSize(request.fieldNames(), 0), false)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals("approved", request.path("model").asText());
            assertEquals(INPUT, request.path("input").get(0).path("content").asText());
            assertFalse(request.path("store").asBoolean(true));
            assertTrue(request.path("stream").asBoolean(false));
            return new SiwcInference.Response(200, bytes(event("response.created", "{\"type\":\"response.created\"}")
                    + completed("{\"advice\":\"review\"}")));
        });
        assertEquals("review", adapter.infer(TOKEN, "approved", INPUT).path("advice").asText());
    }

    @Test void requiresApprovedModelAndSafeInputs() throws Exception {
        var adapter = adapter((uri, token, body, timeout, max) -> fail("must not call transport"));
        assertEquals(SiwcInference.Failure.INVALID_REQUEST,
                assertThrows(SiwcInference.InferenceException.class, () -> adapter.infer(TOKEN, "other", INPUT)).failure());
        assertEquals(SiwcInference.Failure.INVALID_REQUEST,
                assertThrows(SiwcInference.InferenceException.class, () -> adapter.infer("x\r\ny", "approved", INPUT)).failure());
        assertEquals(SiwcInference.Failure.INVALID_REQUEST,
                assertThrows(SiwcInference.InferenceException.class, () -> adapter.infer(TOKEN, "approved", "x".repeat(100001))).failure());
        assertThrows(IllegalArgumentException.class, () -> new SiwcInference((a,b,c,d,e) -> null, Set.of(), Duration.ofSeconds(61)));
    }

    @Test void sanitizesTransportAndHttpFailures() {
        fails(SiwcInference.Failure.TRANSPORT, adapter((a,b,c,d,e) -> { throw new Exception(TOKEN + INPUT); }));
        fails(SiwcInference.Failure.HTTP, adapter((a,b,c,d,e) -> new SiwcInference.Response(429, bytes(TOKEN + INPUT))));
        fails(SiwcInference.Failure.RESPONSE_TOO_LARGE, adapter((a,b,c,d,e) -> new SiwcInference.Response(200, new byte[e + 1])));
        assertFalse(new SiwcInference.Response(400, bytes(TOKEN)).toString().contains(TOKEN));
    }

    @Test void rejectsFailedIncompleteAndTruncatedStreams() throws Exception {
        fails(SiwcInference.Failure.FAILED, withStream(event("response.failed", "{\"type\":\"response.failed\"}")));
        fails(SiwcInference.Failure.INCOMPLETE, withStream(event("response.incomplete", "{\"type\":\"response.incomplete\"}")));
        fails(SiwcInference.Failure.INVALID_STREAM, withStream(event("response.created", "{\"type\":\"response.created\"}")));
        String complete = completed("{}");
        fails(SiwcInference.Failure.INVALID_STREAM, withStream(complete.substring(0, complete.length() - 1)));
        fails(SiwcInference.Failure.INVALID_STREAM, withStream(complete + event("response.failed", "{\"type\":\"response.failed\"}")));
        fails(SiwcInference.Failure.INVALID_STREAM, withStream(event("response.completed", "{\"type\":\"response.failed\"}")));
    }

    @Test void rejectsNonStrictOrOversizedOutput() throws Exception {
        for (String output : new String[]{"[]", "{} {}", "{\"a\":1,\"a\":2}", "not json", "{\"x\":\"" + "x".repeat(100001) + "\"}"}) {
            fails(SiwcInference.Failure.INVALID_OUTPUT, withStream(completed(output)));
        }
    }
}
