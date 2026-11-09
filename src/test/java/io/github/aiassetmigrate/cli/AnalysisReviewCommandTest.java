package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisReviewCommandTest {
    @Test
    void analysisCallsRequestedKindAndDisplaysSuggestionsAndFindings() {
        UUID project = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID suggestion = UUID.randomUUID();
        for (String kind : List.of("categories", "relationships", "materials")) {
            AtomicBoolean closed = new AtomicBoolean();
            AssetManager manager = manager((method, args) -> {
                if (method.equals("close")) { closed.set(true); return null; }
                assertEquals("analyze", method);
                assertEquals(project, args[0]);
                assertEquals(AssetManager.AnalysisKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)), args[1]);
                return new AssetManager.AnalysisResult(List.of(new AssetManager.Suggestion(suggestion, asset,
                        (AssetManager.AnalysisKind) args[1], "value\nunsafe")),
                        List.of(new Finding(UUID.randomUUID(), Optional.of(asset), Optional.of(Path.of("model.obj")),
                                Finding.Severity.WARNING, "WARN", "inspect")));
            });
            StringWriter out = new StringWriter();
            CommandLine cli = new CommandLine(new AnalyzeCommand((state, logs) -> {
                assertEquals(Path.of("state"), state);
                assertEquals(Path.of("logs"), logs);
                return manager;
            })).setOut(new PrintWriter(out));
            assertEquals(0, cli.execute(kind, "--project", project.toString(), "--state-directory", "state",
                    "--log-directory", "logs"));
            assertTrue(closed.get());
            assertTrue(out.toString().contains(suggestion.toString()));
            assertTrue(out.toString().contains("value\\u000aunsafe"));
            assertTrue(out.toString().contains("WARNING WARN"));
            assertTrue(out.toString().contains("model.obj"));
        }
    }

    @Test
    void reviewPassesExplicitDecisionAndStructuredValues() {
        UUID project = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AtomicBoolean closed = new AtomicBoolean();
        AssetManager manager = manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            assertEquals("review", method);
            assertEquals(project, args[0]);
            @SuppressWarnings("unchecked") List<ReviewedChange> changes = (List<ReviewedChange>) args[1];
            assertEquals(1, changes.size());
            ReviewedChange change = changes.getFirst();
            assertEquals(asset, change.assetId());
            assertEquals(ReviewedChange.Kind.MATERIAL_MAPPING, change.kind());
            assertEquals(ReviewedChange.Decision.ACCEPTED, change.decision());
            assertEquals(Map.of("targetAssetId", target.toString(), "reference", "textures/a=b.png"), change.values());
            assertNotNull(change.id());
            assertNotNull(change.reviewedAt());
            return changes;
        });
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(new ReviewCommand((state, logs) -> manager)).setOut(new PrintWriter(out));
        assertEquals(0, cli.execute("--project", project.toString(), "--asset", asset.toString(),
                "--kind", "MATERIAL_MAPPING", "--decision", "ACCEPTED", "--value", "targetAssetId=" + target,
                "--value", "reference=textures/a=b.png", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        assertTrue(out.toString().contains("Decision: ACCEPTED"));
        assertTrue(out.toString().contains("reference=textures/a=b.png"));
    }

    @Test
    void rejectsInvalidArgumentsBeforeManagerCreationAndReportsFailures() {
        var analyze = new CommandLine(new AnalyzeCommand((state, logs) -> { throw new AssertionError(); }))
                .setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, analyze.execute("names", "--project", UUID.randomUUID().toString(),
                "--state-directory", "state", "--log-directory", "logs"));
        var review = new CommandLine(new ReviewCommand((state, logs) -> { throw new AssertionError(); }))
                .setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, review.execute("--project", UUID.randomUUID().toString(), "--asset", UUID.randomUUID().toString(),
                "--kind", "CATEGORY", "--state-directory", "state", "--log-directory", "logs"));
        StringWriter errors = new StringWriter();
        var failing = new CommandLine(new AnalyzeCommand((state, logs) -> {
            throw new java.io.IOException("unavailable");
        })).setErr(new PrintWriter(errors));
        assertEquals(1, failing.execute("categories", "--project", UUID.randomUUID().toString(),
                "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(errors.toString().contains("unavailable"));
        var root = new CommandLine(new AssetMigrateCli());
        assertTrue(root.getSubcommands().containsKey("analyze"));
        assertTrue(root.getSubcommands().containsKey("review"));
    }

    private interface Handler { Object invoke(String method, Object[] args) throws Exception; }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[] { AssetManager.class, AutoCloseable.class },
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
