package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.auth.AccountSession;
import io.github.aiassetmigrate.auth.AccountStore;
import io.github.aiassetmigrate.auth.OAuthCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    @TempDir Path directory;

    private static final AccountStore.Protector PLAIN = new AccountStore.Protector() {
        @Override public byte[] protect(byte[] plain) { return plain.clone(); }
        @Override public byte[] unprotect(byte[] stored) { return stored.clone(); }
        @Override public String description() { return "test"; }
    };

    private static OAuthCredentials credentials(String access) {
        return new OAuthCredentials("oaiapp_client", "subject", Optional.empty(), access, "refresh", "id",
                Set.of("openid", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"), NOW.plusSeconds(3600));
    }

    private static byte[] completed(String text) throws Exception {
        String payload = JSON.writeValueAsString(Map.of("type", "response.completed", "response", Map.of("status", "completed",
                "output", List.of(Map.of("type", "message", "role", "assistant", "content",
                        List.of(Map.of("type", "output_text", "text", text)))))));
        return ("event: response.completed\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private AccountSession session(List<String> refreshed) throws Exception {
        var store = new AccountStore(directory, PLAIN);
        store.save(new AccountStore.Account(credentials("stale"), Optional.of("model-a")));
        return new AccountSession(store, current -> {
            refreshed.add(current.accessToken());
            return credentials("fresh");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void rejectedTokenIsRefreshedOnceAndRequestRetried() throws Exception {
        List<String> refreshed = new ArrayList<>(), tokens = new ArrayList<>();
        var adapter = new OpenAIAdapter(session(refreshed), (uri, token, body, timeout, max) -> {
            tokens.add(token);
            String json = new String(body, StandardCharsets.UTF_8);
            assertTrue(json.contains("\"store\":false") && json.contains("\"stream\":true") && json.contains("\"model\":\"model-a\""));
            return token.equals("stale") ? new SiwcInference.Response(401, new byte[0])
                    : new SiwcInference.Response(200, completed("{\"ok\":true}"));
        }, "model-a");
        assertTrue(adapter.generate("prompt").path("ok").asBoolean());
        assertEquals(List.of("stale", "fresh"), tokens);
        assertEquals(List.of("stale"), refreshed);
        assertTrue(adapter.description().contains("model-a"));
    }

    @Test void usageLimitsAndFailuresAreSanitized() throws Exception {
        String failed = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":"
                + "{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"secret detail\"}}}\n\n";
        var limited = new OpenAIAdapter(session(new ArrayList<>()), (u, t, b, d, m) ->
                new SiwcInference.Response(200, failed.getBytes(StandardCharsets.UTF_8)), "model-a");
        var error = assertThrows(LLMClient.AiException.class, () -> limited.generate("p"));
        assertEquals(LLMClient.AiException.Reason.USAGE_LIMIT, error.reason());
        assertFalse(error.getMessage().contains("secret detail"));
        var down = new OpenAIAdapter(session(new ArrayList<>()), (u, t, b, d, m) -> { throw new java.io.IOException("token=abc"); }, "model-a");
        var transport = assertThrows(LLMClient.AiException.class, () -> down.generate("p"));
        assertEquals(LLMClient.AiException.Reason.UNAVAILABLE, transport.reason());
        assertFalse(transport.getMessage().contains("abc"));
    }

    @Test void missingAccountOrModelIsNotConnected() throws Exception {
        var store = new AccountStore(directory.resolve("empty"), PLAIN);
        var session = new AccountSession(store, current -> current, Clock.systemUTC());
        assertEquals(LLMClient.AiException.Reason.NOT_CONNECTED,
                assertThrows(LLMClient.AiException.class, () -> OpenAIAdapter.forSession(session)).reason());
        store.save(new AccountStore.Account(credentials("t"), Optional.empty()));
        assertEquals(LLMClient.AiException.Reason.NOT_CONNECTED,
                assertThrows(LLMClient.AiException.class, () -> OpenAIAdapter.forSession(session)).reason());
    }

    @Test void modelCatalogKeepsListedEntriesInServerOrder() throws Exception {
        String body = """
                {"models":[{"slug":"b-model","display_name":"B","visibility":"list"},
                           {"slug":"hidden","display_name":"H","visibility":"hide"},
                           {"slug":"bad slug","visibility":"list"},
                           {"slug":"a-model","visibility":"list"}]}""";
        SiwcInference.Transport transport = new SiwcInference.Transport() {
            @Override public SiwcInference.Response post(java.net.URI e, String t, byte[] b, Duration d, int m) { throw new AssertionError(); }
            @Override public SiwcInference.Response get(java.net.URI endpoint, String token, Duration timeout, int max) {
                assertEquals(SiwcInference.MODELS_ENDPOINT, endpoint);
                return new SiwcInference.Response(token.equals("ok") ? 200 : 401, body.getBytes(StandardCharsets.UTF_8));
            }
        };
        assertEquals(List.of(new SiwcInference.Model("b-model", "B"), new SiwcInference.Model("a-model", "a-model")),
                SiwcInference.listModels(transport, "ok", Duration.ofSeconds(5)));
        assertEquals(SiwcInference.Failure.UNAUTHORIZED, assertThrows(SiwcInference.InferenceException.class,
                () -> SiwcInference.listModels(transport, "expired", Duration.ofSeconds(5))).failure());
    }
}
