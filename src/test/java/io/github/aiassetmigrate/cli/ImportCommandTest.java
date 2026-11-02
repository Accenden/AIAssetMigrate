package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ImportCommandTest {
    @Test
    void displaysInventoryFindingsAndCoverageAndClosesManager() {
        UUID id = UUID.randomUUID();
        InventorySnapshot snapshot = snapshot(id);
        AtomicBoolean closed = new AtomicBoolean();
        AssetManager manager = manager(snapshot, null, closed, false);
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        CommandLine cli = command((state, logs) -> {
            assertEquals(Path.of("state"), state);
            assertEquals(Path.of("logs"), logs);
            return manager;
        }, output, errors);

        assertEquals(0, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        assertTrue(errors.toString().isEmpty());
        String text = output.toString();
        assertTrue(text.contains("Project ID: " + id));
        assertTrue(text.contains("Revision: 3"));
        assertTrue(text.contains("Scan status: PARTIAL"));
        assertTrue(text.contains("mesh.obj [MODEL]"));
        assertTrue(text.contains("format: obj"));
        assertTrue(text.contains("WARNING UNVERIFIED mesh.obj: needs inspection"));
        assertTrue(text.contains("UNVISITED DIRECTORY blocked"));
    }

    @Test
    void reportsFatalFailureWithRecoveryDetails() {
        UUID id = UUID.randomUUID();
        ImportException failure = new ImportException("completion failed", new IOException(),
                Optional.of(id), Optional.of(4L));
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter errors = new StringWriter();
        CommandLine cli = command((state, logs) -> manager(null, failure, closed, false), new StringWriter(), errors);

        assertEquals(1, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(closed.get());
        assertTrue(errors.toString().contains("completion failed"));
        assertTrue(errors.toString().contains("Project ID: " + id));
        assertTrue(errors.toString().contains("Committed revision: 4"));
    }

    @Test
    void reportsCommittedProjectWhenCloseFails() {
        UUID id = UUID.randomUUID();
        StringWriter errors = new StringWriter();
        CommandLine cli = command((state, logs) -> manager(snapshot(id), null, new AtomicBoolean(), true),
                new StringWriter(), errors);

        assertEquals(1, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(errors.toString().contains("close failed"));
        assertTrue(errors.toString().contains("Project ID: " + id));
        assertTrue(errors.toString().contains("Committed revision: 3"));
    }

    @Test
    void reportsCommittedProjectWhenOutputFails() {
        UUID id = UUID.randomUUID();
        StringWriter errors = new StringWriter();
        CommandLine cli = new CommandLine(new ImportCommand((state, logs) ->
                manager(snapshot(id), null, new AtomicBoolean(), false)))
                .setOut(new PrintWriter(new Writer() {
                    @Override public void write(char[] buffer, int offset, int length) throws IOException {
                        throw new IOException("output unavailable");
                    }
                    @Override public void flush() { }
                    @Override public void close() { }
                }))
                .setErr(new PrintWriter(errors));

        assertEquals(1, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(errors.toString().contains("Could not write inventory output"));
        assertTrue(errors.toString().contains("Project ID: " + id));
        assertTrue(errors.toString().contains("Committed revision: 3"));
    }

    @Test
    void requiresSourceAndBothDirectoriesBeforeCreatingManager() {
        for (String[] args : new String[][] {
                {}, {"source"}, {"source", "--state-directory", "state"},
                {"source", "--log-directory", "logs"}
        }) {
            CommandLine cli = command((state, logs) -> { throw new AssertionError("manager created"); },
                    new StringWriter(), new StringWriter());
            assertEquals(2, cli.execute(args));
        }
    }

    @Test
    void initializationFailureIsFatal() {
        StringWriter errors = new StringWriter();
        CommandLine cli = command((state, logs) -> { throw new IOException("unavailable"); },
                new StringWriter(), errors);
        assertEquals(1, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(errors.toString().contains("unavailable"));
    }

    @Test
    void importFailureWithoutCommitDoesNotClaimARevision() {
        StringWriter errors = new StringWriter();
        ImportException failure = new ImportException("scan failed", new IOException("source unavailable"));
        CommandLine cli = command((state, logs) -> manager(null, failure, new AtomicBoolean(), false),
                new StringWriter(), errors);
        assertEquals(1, cli.execute("source", "--state-directory", "state", "--log-directory", "logs"));
        assertTrue(errors.toString().contains("source unavailable"));
        assertFalse(errors.toString().contains("Committed revision:"));
    }

    @Test
    void realCommandImportsAndRejectsMissingSource(@TempDir Path temp) throws IOException {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path unsupported = Files.writeString(source.resolve("notes.txt"), "preserve me");
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        CommandLine cli = new CommandLine(new AssetMigrateCli())
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
        String state = temp.resolve("state").toString();
        String logs = temp.resolve("logs").toString();

        assertEquals(0, cli.execute("import", source.toString(), "--state-directory", state, "--log-directory", logs),
                errors.toString());
        assertTrue(output.toString().contains("Project ID:"));
        assertTrue(output.toString().contains("Revision: 1"));
        assertTrue(output.toString().contains("notes.txt"));
        assertEquals("preserve me", Files.readString(unsupported));
        assertNotEquals(0, cli.execute("import", temp.resolve("missing").toString(),
                "--state-directory", state, "--log-directory", logs));
        Path unsafeState = source.resolve("state");
        assertNotEquals(0, cli.execute("import", source.toString(),
                "--state-directory", unsafeState.toString(), "--log-directory", logs));
        assertFalse(Files.exists(unsafeState));
    }

    private static CommandLine command(ImportCommand.ManagerFactory factory, StringWriter out, StringWriter err) {
        return new CommandLine(new ImportCommand(factory)).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
    }

    private static InventorySnapshot snapshot(UUID id) {
        Instant time = Instant.parse("2025-01-01T00:00:00Z");
        Asset asset = new Asset(UUID.randomUUID(), Path.of("mesh.obj"), Asset.Type.MODEL, "a".repeat(64),
                Map.of("format", "obj"));
        Finding finding = new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                Finding.Severity.WARNING, "UNVERIFIED", "needs inspection");
        Project project = new Project(id, Path.of("source").toAbsolutePath(), 3, List.of(asset), time);
        return new InventorySnapshot(1, 1, project, List.of(finding), InventorySnapshot.ScanStatus.PARTIAL,
                time, List.of(new PathOutcome(Path.of("blocked"), PathOutcome.Kind.DIRECTORY,
                PathOutcome.Status.UNVISITED)), List.of());
    }

    private static AssetManager manager(InventorySnapshot snapshot, ImportException failure,
                                        AtomicBoolean closed, boolean failClose) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[] { AssetManager.class, AutoCloseable.class }, (proxy, method, args) -> {
                    if (method.getName().equals("importAssets")) {
                        assertEquals(Path.of("source"), args[0]);
                        if (failure != null) throw failure;
                        return new AssetManager.ImportResult(snapshot);
                    }
                    if (method.getName().equals("close")) {
                        closed.set(true);
                        if (failClose) throw new IOException("close failed");
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
