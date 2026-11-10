package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Finding;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityCommandTest {
    @Test void assessmentDisplaysAllFieldsAndEscapesUntrustedText() {
        Path hostilePath = io.github.aiassetmigrate.TestPaths.hostile("bad\nname.obj");
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID(), findingId = UUID.randomUUID();
        AtomicBoolean closed = new AtomicBoolean();
        AssetManager manager = manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            assertEquals("assessCompatibility", method);
            assertEquals(project, args[0]);
            assertEquals(new AssetManager.TargetProfile("engine", "1"), args[1]);
            return new AssetManager.CompatibilityResult(42, new AssetManager.TargetProfile("engine\nname", "1\u001b"),
                    List.of(new Finding(findingId, Optional.of(asset), Optional.of(io.github.aiassetmigrate.TestPaths.hostile("bad\nname.obj")),
                                    Finding.Severity.WARNING, "CODE\nX", "message\u001b[31m"),
                            new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(),
                                    Finding.Severity.UNKNOWN, "OTHER", "second")));
        });
        StringWriter output = new StringWriter();
        var cli = new CommandLine(new CompatibilityCommand((state, logs) -> {
            assertEquals(Path.of("state"), state);
            assertEquals(Path.of("logs"), logs);
            return manager;
        })).setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("--project", project.toString(), "--target", "engine", "--target-version", "1",
                "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        String text = output.toString();
        assertTrue(text.contains("Target: engine\\u000aname Version: 1\\u001b"));
        assertTrue(text.contains("Inventory revision: 42"));
        assertTrue(text.contains("Findings (2):"));
        assertTrue(text.contains(findingId.toString()));
        assertTrue(text.contains(asset.toString()));
        assertTrue(text.contains("WARNING Code: CODE\\u000aX"));
        assertTrue(text.contains("bad\\u000aname.obj"));
        assertTrue(text.contains("message\\u001b[31m"));
        assertTrue(text.contains("UNKNOWN Code: OTHER Asset ID: <project> Path: <project> Message: second"));
        assertTrue(text.contains("not validation"));
    }

    @Test void discoveryNeedsNoProjectAndEscapesProfiles() {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter output = new StringWriter();
        var cli = new CommandLine(new CompatibilityCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            assertEquals("supportedTargets", method);
            return List.of(new AssetManager.TargetProfile("id\nunsafe", "1\u001b"));
        }))).setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("--list-targets", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        assertTrue(output.toString().contains("Supported targets (1):"));
        assertTrue(output.toString().contains("ID: id\\u000aunsafe Version: 1\\u001b"));
    }

    @Test void invalidArgumentsNeverCreateManagerAndHelpIsRegistered() {
        String project = UUID.randomUUID().toString();
        for (String[] args : List.of(
                new String[]{"--project", "bad", "--target", "a", "--target-version", "1", "--state-directory", "s", "--log-directory", "l"},
                new String[]{"--project", project, "--target", "a", "--state-directory", "s", "--log-directory", "l"},
                new String[]{"--project", project, "--target", " ", "--target-version", "1", "--state-directory", "s", "--log-directory", "l"},
                new String[]{"--list-targets", "--project", project, "--state-directory", "s", "--log-directory", "l"},
                new String[]{"--list-targets"})) {
            var cli = new CommandLine(new CompatibilityCommand((state, logs) -> { throw new AssertionError(); }))
                    .setErr(new PrintWriter(new StringWriter()));
            assertEquals(2, cli.execute(args));
        }
        StringWriter help = new StringWriter();
        var root = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(help));
        assertEquals(0, root.execute("compatibility", "--help"));
        assertTrue(help.toString().contains("--list-targets"));
        assertTrue(help.toString().contains("--target-version"));
    }

    @Test void reportsFailuresAndOutputErrorsAndClosesManager() {
        String[] options = {"--list-targets", "--state-directory", "s", "--log-directory", "l"};
        StringWriter error = new StringWriter();
        var failing = new CommandLine(new CompatibilityCommand((state, logs) -> { throw new IOException("bad\npath"); }))
                .setErr(new PrintWriter(error));
        assertEquals(1, failing.execute(options));
        assertTrue(error.toString().contains("bad\\u000apath"));

        AtomicBoolean closed = new AtomicBoolean();
        var brokenOutput = new PrintWriter(new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException { throw new IOException(); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        var cli = new CommandLine(new CompatibilityCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            assertEquals("supportedTargets", method);
            return List.of();
        }))).setOut(brokenOutput).setErr(new PrintWriter(new StringWriter()));
        assertEquals(1, cli.execute(options));
        assertTrue(closed.get());
    }

    private interface Handler { Object invoke(String method, Object[] args) throws Exception; }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
