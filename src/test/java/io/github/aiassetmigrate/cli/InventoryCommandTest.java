package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCommandTest {
    @Test
    void loadsRequestedSnapshotWithoutScanningAndReportsPartialStatus() {
        UUID project = UUID.randomUUID();
        AtomicBoolean closed = new AtomicBoolean();
        InventorySnapshot snapshot = snapshot(project, InventorySnapshot.ScanStatus.PARTIAL);
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[] { AssetManager.class, AutoCloseable.class }, (proxy, method, args) -> {
                    if (method.getName().equals("loadInventory")) {
                        assertEquals(project, args[0]);
                        assertEquals(4L, args[1]);
                        return snapshot;
                    }
                    if (method.getName().equals("close")) {
                        closed.set(true);
                        return null;
                    }
                    if (method.getName().equals("importAssets") || method.getName().equals("rescan")) {
                        throw new AssertionError("inventory must not scan");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new InventoryCommand((state, logs) -> {
            assertEquals(Path.of("state"), state);
            assertEquals(Path.of("state", "logs"), logs);
            return manager;
        })).setOut(new PrintWriter(output)).setErr(new PrintWriter(new StringWriter()));

        assertEquals(3, cli.execute("--state-dir", "state", "--project", project.toString(), "--revision", "4"));
        assertTrue(closed.get());
        assertTrue(output.toString().contains("Source root: " + snapshot.project().sourceRoot()));
        assertTrue(output.toString().contains("Scan status: PARTIAL"));
        assertTrue(output.toString().contains("Assets (1):"));
        assertTrue(output.toString().contains("Findings (0):"));
        assertTrue(output.toString().contains("Logs: aiassetmigrate logs --directory " + Path.of("state", "logs")
                + " --project " + project));
    }

    @Test
    void missingInventoryDoesNotCreateStateOrScanSource(@TempDir Path directory) {
        Path state = directory.resolve("missing-state");
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter()));

        assertEquals(1, cli.execute("inventory", "--state-directory", state.toString(), "--project",
                UUID.randomUUID().toString()));
        assertFalse(Files.exists(state));
    }

    @Test
    void loadsSavedRevisionAfterSourceDisappears(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path asset = Files.writeString(source.resolve("mesh.obj"), "v 0 0 0\n");
        Path state = directory.resolve("state");
        StringWriter imported = new StringWriter();
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(imported))
                .setErr(new PrintWriter(new StringWriter()));

        assertEquals(0, cli.execute("import", source.toString(), "--state-dir", state.toString()));
        UUID project = imported.toString().lines().filter(line -> line.startsWith("Project ID: "))
                .map(line -> UUID.fromString(line.substring("Project ID: ".length()))).findFirst().orElseThrow();

        StringWriter revisionOne = new StringWriter();
        cli.setOut(new PrintWriter(revisionOne));
        assertEquals(0, cli.execute("inventory", "--state-dir", state.toString(), "--project", project.toString(),
                "--revision", "1"));
        assertTrue(revisionOne.toString().contains("Revision: 1"));

        Files.delete(asset);
        Files.delete(source);
        StringWriter latest = new StringWriter();
        cli.setOut(new PrintWriter(latest));
        assertEquals(0, cli.execute("inventory", "--state-dir", state.toString(), "--project", project.toString()));
        assertTrue(latest.toString().contains("Revision: 1"));
        assertTrue(latest.toString().contains("Source root: " + source.toAbsolutePath()));
    }

    @Test
    void rejectsNonpositiveRevisionBeforeCreatingManager() {
        var cli = new CommandLine(new InventoryCommand((state, logs) -> {
            throw new AssertionError("manager created");
        })).setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, cli.execute("--state-dir", "state", "--project", UUID.randomUUID().toString(),
                "--revision", "0"));
    }

    private static InventorySnapshot snapshot(UUID project, InventorySnapshot.ScanStatus status) {
        Instant time = Instant.parse("2025-01-01T00:00:00Z");
        Asset asset = new Asset(UUID.randomUUID(), Path.of("mesh.obj"), Asset.Type.MODEL, "a".repeat(64), Map.of());
        Project saved = new Project(project, Path.of("source").toAbsolutePath(), 4, List.of(asset), time);
        return new InventorySnapshot(1, 1, saved, List.of(), status, time, List.of(), List.of());
    }
}
