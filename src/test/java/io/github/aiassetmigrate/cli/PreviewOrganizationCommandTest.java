package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.application.AssetManager;
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

class PreviewOrganizationCommandTest {
    @Test
    void displaysAllEntriesAndFindingsSafelyIncludingBlockedStatus() {
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID(), excluded = UUID.randomUUID();
        AtomicBoolean closed = new AtomicBoolean();
        var result = new OrganizationPreview.Result(project, 17, List.of(
                new OrganizationPreview.Entry(asset, Path.of("src\nasset.obj"), Optional.of("dest\u001b[31m.obj"), false),
                new OrganizationPreview.Entry(excluded, Path.of("excluded.obj"), Optional.empty(), true)), List.of(
                new OrganizationPreview.Finding("BAD\nCODE", Optional.of(asset), "bad\u001b[31m"),
                new OrganizationPreview.Finding("PROJECT", Optional.empty(), "incomplete")));
        AssetManager manager = manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            assertEquals("previewOrganization", method);
            assertEquals(project, args[0]);
            assertEquals(17L, args[1]);
            return result;
        });
        StringWriter out = new StringWriter();
        var cli = new CommandLine(new PreviewOrganizationCommand((state, logs) -> {
            assertEquals(Path.of("state"), state);
            assertEquals(Path.of("logs"), logs);
            return manager;
        })).setOut(new PrintWriter(out));
        assertEquals(0, cli.execute("--project", project.toString(), "--inventory-revision", "17",
                "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        String text = out.toString();
        assertTrue(text.contains("Inventory revision: 17"));
        assertTrue(text.contains("Blocked: true"));
        assertTrue(text.contains("no asset writes or migration approval"));
        assertTrue(text.contains("Entries (2):"));
        assertTrue(text.contains("Asset ID: " + asset));
        assertTrue(text.contains("src\\u000aasset.obj"));
        assertTrue(text.contains("dest\\u001b[31m.obj"));
        assertTrue(text.contains("Asset ID: " + excluded + " Source: excluded.obj Destination: <none> Excluded: true"));
        assertTrue(text.contains("Findings (2):"));
        assertTrue(text.contains("BAD\\u000aCODE Asset ID: " + asset + ": bad\\u001b[31m"));
        assertTrue(text.contains("PROJECT Asset ID: <project>: incomplete"));
        assertFalse(text.contains("\u001b"));
    }

    @Test
    void reportsUnblockedAndOperationalFailures() {
        UUID project = UUID.randomUUID();
        String[] options = {"--project", project.toString(), "--inventory-revision", "1",
                "--state-directory", "state", "--log-directory", "logs"};
        StringWriter out = new StringWriter();
        var cli = new CommandLine(new PreviewOrganizationCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) return null;
            return new OrganizationPreview.Result(project, 1, List.of(), List.of());
        }))).setOut(new PrintWriter(out));
        assertEquals(0, cli.execute(options));
        assertTrue(out.toString().contains("Blocked: false"));

        AtomicBoolean closed = new AtomicBoolean();
        StringWriter err = new StringWriter();
        var failing = new CommandLine(new PreviewOrganizationCommand((state, logs) -> manager((method, args) -> {
            if (method.equals("close")) { closed.set(true); return null; }
            throw new IllegalStateException("stale\nrevision");
        }))).setErr(new PrintWriter(err));
        assertEquals(1, failing.execute(options));
        assertTrue(closed.get());
        assertTrue(err.toString().contains("stale\\u000arevision"));
    }

    @Test
    void validatesArgumentsAndRegistersHelp() {
        for (String revision : List.of("0", "-1", "bad", "9223372036854775808")) {
            var cli = new CommandLine(new PreviewOrganizationCommand((state, logs) -> { throw new AssertionError(); }))
                    .setErr(new PrintWriter(new StringWriter()));
            assertEquals(2, cli.execute("--project", UUID.randomUUID().toString(), "--inventory-revision", revision,
                    "--state-directory", "state", "--log-directory", "logs"));
        }
        var cli = new CommandLine(new PreviewOrganizationCommand((state, logs) -> { throw new AssertionError(); }))
                .setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, cli.execute("--project", "not-a-uuid", "--inventory-revision", "1",
                "--state-directory", "state", "--log-directory", "logs"));
        assertEquals(2, cli.execute("--project", UUID.randomUUID().toString(), "--state-directory", "state", "--log-directory", "logs"));
        StringWriter help = new StringWriter();
        var root = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(help));
        assertEquals(0, root.execute("preview-organization", "--help"));
        assertTrue(help.toString().contains("--inventory-revision"));
        assertTrue(help.toString().contains("without writing assets or approving migration"));
    }

    private interface Handler { Object invoke(String method, Object[] args) throws Exception; }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[] { AssetManager.class, AutoCloseable.class },
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
