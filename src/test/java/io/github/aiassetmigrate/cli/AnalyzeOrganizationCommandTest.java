package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Finding;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeOrganizationCommandTest {
    @Test void passesAllRulesAndDisplaysEscapedResults() {
        Path hostilePath = io.github.aiassetmigrate.TestPaths.hostile("file\n.obj");
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID(), suggestion = UUID.randomUUID();
        for (String name : List.of("preserve", "snake-case", "kebab-case")) {
            for (String folder : List.of("preserve", "by-type", "by-category")) {
                AtomicBoolean closed = new AtomicBoolean();
                AssetManager manager = manager((method, args) -> {
                    if (method.equals("close")) { closed.set(true); return null; }
                    assertEquals("analyzeOrganization", method);
                    assertEquals(project, args[0]);
                    assertEquals(new AssetManager.OrganizationRules(
                            AssetManager.NameStyle.valueOf(name.replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                            AssetManager.FolderStyle.valueOf(folder.replace('-', '_').toUpperCase(java.util.Locale.ROOT))), args[1]);
                    return new AssetManager.AnalysisResult(42, List.of(new AssetManager.Suggestion(suggestion, asset,
                            AssetManager.AnalysisKind.NAMES, "value\nunsafe", Optional.of("ref\u001b"))),
                            List.of(new Finding(UUID.randomUUID(), Optional.of(asset), Optional.of(io.github.aiassetmigrate.TestPaths.hostile("file\n.obj")),
                                    Finding.Severity.WARNING, "CODE\n", "message\u001b")));
                });
                StringWriter output = new StringWriter();
                var cli = new CommandLine(new AnalyzeOrganizationCommand((state, logs) -> {
                    assertEquals(Path.of("state"), state);
                    assertEquals(Path.of("logs"), logs);
                    return manager;
                })).setOut(new PrintWriter(output));
                assertEquals(0, cli.execute("--project", project.toString(), "--name-style", name,
                        "--folder-style", folder, "--state-directory", "state", "--log-directory", "logs"));
                assertTrue(closed.get());
                String text = output.toString();
                assertTrue(text.contains("name-style=" + name + " folder-style=" + folder));
                assertTrue(text.contains("Inventory revision: 42"));
                assertTrue(text.contains(suggestion.toString()));
                assertTrue(text.contains("value\\u000aunsafe"));
                assertTrue(text.contains("ref\\u001b"));
                assertTrue(text.contains("CODE\\u000a"));
                assertTrue(text.contains("file\\u000a.obj"));
                assertTrue(text.contains("message\\u001b"));
            }
        }
    }

    @Test void defaultsToPreserveAndValidatesBeforeCreatingManager() {
        var expected = new AssetManager.OrganizationRules(AssetManager.NameStyle.PRESERVE, AssetManager.FolderStyle.PRESERVE);
        var cli = new CommandLine(new AnalyzeOrganizationCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) return null;
            assertEquals("analyzeOrganization", method);
            assertEquals(expected, args[1]);
            return new AssetManager.AnalysisResult(1, List.of(), List.of());
        }))).setOut(new PrintWriter(new StringWriter()));
        assertEquals(0, cli.execute("--project", UUID.randomUUID().toString(), "--state-directory", "state", "--log-directory", "logs"));

        for (String[] invalid : List.of(
                new String[]{"--name-style", "snake_case"}, new String[]{"--name-style", "SNAKE-CASE"},
                new String[]{"--folder-style", "other"})) {
            var rejected = new CommandLine(new AnalyzeOrganizationCommand((state, logs) -> { throw new AssertionError(); }))
                    .setErr(new PrintWriter(new StringWriter()));
            assertEquals(2, rejected.execute("--project", UUID.randomUUID().toString(), "--state-directory", "state",
                    "--log-directory", "logs", invalid[0], invalid[1]));
        }
        var rejected = new CommandLine(new AnalyzeOrganizationCommand((state, logs) -> { throw new AssertionError(); }))
                .setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, rejected.execute("--project", "invalid", "--state-directory", "state", "--log-directory", "logs"));
        assertEquals(2, rejected.execute("--project", UUID.randomUUID().toString()));
    }

    @Test void reportsFailureSafelyAndRegistersHelp() {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter error = new StringWriter();
        var cli = new CommandLine(new AnalyzeOrganizationCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            throw new IllegalStateException("bad\ninput");
        }))).setErr(new PrintWriter(error));
        assertEquals(1, cli.execute("--project", UUID.randomUUID().toString(), "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        assertTrue(error.toString().contains("bad\\u000ainput"));
        StringWriter help = new StringWriter();
        var root = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(help));
        assertEquals(0, root.execute("analyze-organization", "--help"));
        assertTrue(help.toString().contains("--name-style"));
        assertTrue(help.toString().contains("--folder-style"));
    }

    private interface Handler { Object invoke(String method, Object[] args) throws Exception; }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
