package io.github.aiassetmigrate.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.SiwcInference;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.auth.AccountSession;
import io.github.aiassetmigrate.auth.AccountStore;
import io.github.aiassetmigrate.auth.OAuthCredentials;
import io.github.aiassetmigrate.auth.OAuthSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthCommandTest {
    @TempDir Path directory;

    private static final AccountStore.Protector PLAIN = new AccountStore.Protector() {
        @Override public byte[] protect(byte[] plain) { return plain.clone(); }
        @Override public byte[] unprotect(byte[] stored) { return stored.clone(); }
        @Override public String description() { return "test storage"; }
    };

    private static OAuthCredentials credentials() {
        return new OAuthCredentials("oaiapp_client", "subject", Optional.of("dev@example.com"), "secret-access",
                "secret-refresh", "id", Set.of("openid", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"),
                Instant.now().plusSeconds(3600));
    }

    private final List<String> calls = new ArrayList<>();

    private AuthCommand.Environment environment(AccountStore store, boolean revoked) {
        return new AuthCommand.Environment() {
            @Override public AccountStore store() { return store; }
            @Override public OAuthCredentials authorize(String hostId, Optional<OAuthSession.Registration> registration) {
                calls.add("authorize:" + registration.isPresent());
                return credentials();
            }
            @Override public List<SiwcInference.Model> models(String accessToken) {
                assertEquals("secret-access", accessToken);
                return List.of(new SiwcInference.Model("model-a", "Model A"), new SiwcInference.Model("model-b", "Model B"));
            }
            @Override public boolean revoke(String hostId, OAuthCredentials credentials) {
                calls.add("revoke");
                return revoked;
            }
            @Override public AccountSession session(AccountStore s) {
                return new AccountSession(s, current -> current, Clock.systemUTC());
            }
        };
    }

    private record Run(int exit, String out, String err) {}

    private static Run run(AuthCommand.Environment environment, String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        int exit = new CommandLine(new AuthCommand(environment)).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    @Test void connectStatusModelsUseModelAndDisconnectNeverPrintTokens() throws Exception {
        var store = new AccountStore(directory.resolve("account"), PLAIN);
        var env = environment(store, false);
        Run status = run(env, "status");
        assertEquals(0, status.exit());
        assertTrue(status.out().contains("Not connected"));

        Run connect = run(env, "connect");
        assertEquals(0, connect.exit(), connect.err());
        assertTrue(connect.out().contains("Connected as dev@example.com"));
        assertTrue(connect.out().contains("Model: model-a"));
        assertEquals(Optional.of("model-a"), store.load().orElseThrow().model());

        assertEquals(0, run(env, "connect", "--model", "model-b").exit());
        assertEquals(List.of("authorize:false", "authorize:true"), calls, "a returning sign-in reuses the registration");
        assertEquals(Optional.of("model-b"), store.load().orElseThrow().model());
        assertEquals(1, run(env, "connect", "--model", "missing").exit());

        Run models = run(env, "models");
        assertTrue(models.out().contains("model-b Model B (selected)"));
        assertEquals(0, run(env, "use-model", "model-a").exit());
        assertEquals(Optional.of("model-a"), store.load().orElseThrow().model());
        assertEquals(1, run(env, "use-model", "nope").exit());
        assertEquals(2, run(env, "use-model").exit());

        Run connected = run(env, "status");
        assertTrue(connected.out().contains("Storage: test storage"));
        for (Run r : List.of(connect, models, connected)) {
            assertFalse(r.out().contains("secret-access") || r.out().contains("secret-refresh"));
        }

        Run disconnect = run(env, "disconnect");
        assertTrue(disconnect.out().contains("Local credentials removed"));
        assertTrue(disconnect.out().contains("not confirmed"));
        assertEquals(Optional.empty(), store.load());
        assertTrue(run(env, "disconnect").out().contains("Not connected"));
        assertEquals(2, run(env, "bogus").exit());
    }

    @Test void analyzeWithAiAddsLabelledSuggestionsAndFallsBackWhenUnavailable() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("blob.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        Path state = directory.resolve("state");
        String project;
        String asset;
        try (var manager = new DefaultAssetManager(state)) {
            var snapshot = manager.importAssets(source).snapshot();
            project = snapshot.project().id().toString();
            asset = snapshot.project().assets().getFirst().id().toString();
        }
        LLMClient model = new LLMClient() {
            @Override public JsonNode generate(String prompt) throws AiException {
                try { return new ObjectMapper().readTree("{\"categories\":[{\"assetId\":\"" + asset + "\",\"category\":\"Props\"}]}"); }
                catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "bad"); }
            }
            @Override public String description() { return "fake"; }
        };
        String[] args = {"categories", "--project", project, "--state-directory", state.toString(),
                "--log-directory", state.resolve("logs").toString(), "--ai"};
        StringWriter out = new StringWriter();
        int exit = new CommandLine(new AnalyzeCommand(DefaultAssetManager::new, () -> model)).setOut(new PrintWriter(out)).execute(args);
        assertEquals(0, exit);
        assertTrue(out.toString().contains("Value: Props Source: AI"), out.toString());
        assertTrue(out.toString().contains("Value: Uncategorized"), "rule suggestions are still shown");

        StringWriter fallback = new StringWriter();
        exit = new CommandLine(new AnalyzeCommand(DefaultAssetManager::new, () -> {
            throw new LLMClient.AiException(LLMClient.AiException.Reason.NOT_CONNECTED, "No AI account is connected");
        })).setOut(new PrintWriter(fallback)).execute(args);
        assertEquals(0, exit);
        assertTrue(fallback.toString().contains("AI_UNAVAILABLE"));
        assertTrue(fallback.toString().contains("Value: Uncategorized"));
    }
}
