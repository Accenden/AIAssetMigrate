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

class RescanCommandTest {
    @TempDir Path directory;

    @Test
    void rescansExistingProjectWithManagedStateLogs() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path state = directory.resolve("state");
        UUID project = UUID.randomUUID();
        InventorySnapshot snapshot = snapshot(project, source);
        AtomicBoolean rescanCalled = new AtomicBoolean();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[] { AssetManager.class, AutoCloseable.class }, (proxy, method, args) -> {
                    if (method.getName().equals("rescan")) {
                        rescanCalled.set(true);
                        assertEquals(project, args[0]);
                        assertEquals(source, args[1]);
                        return new AssetManager.ImportResult(snapshot);
                    }
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        StringWriter rescanned = new StringWriter();
        StringWriter errors = new StringWriter();
        var cli = new CommandLine(new RescanCommand((configuredState, logs) -> {
            assertEquals(state, configuredState);
            assertEquals(state.resolve("logs"), logs);
            return manager;
        })).setOut(new PrintWriter(rescanned)).setErr(new PrintWriter(errors));
        assertEquals(0, cli.execute(source.toString(), "--project", project.toString(),
                "--state-dir", state.toString()), errors.toString());

        assertTrue(rescanCalled.get());
        assertTrue(rescanned.toString().contains("Revision: 2"));
        assertTrue(rescanned.toString().contains("Source root: " + source.toAbsolutePath()));
        assertTrue(rescanned.toString().contains("Logs: aiassetmigrate logs --directory \"" + state.resolve("logs") + "\""));
    }

    @Test
    void excludeAddsToTheFilesAlreadyLeftOutAndIncludeAllClearsThem() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        UUID project = UUID.randomUUID();
        InventorySnapshot current = new InventorySnapshot(1, 1, snapshot(project, source).project(), List.of(),
                InventorySnapshot.ScanStatus.COMPLETE, Instant.parse("2025-01-01T00:00:00Z"),
                List.of(new io.github.aiassetmigrate.domain.PathOutcome(Path.of("old.txt"),
                        io.github.aiassetmigrate.domain.PathOutcome.Kind.FILE, io.github.aiassetmigrate.domain.PathOutcome.Status.EXCLUDED)),
                List.of());
        for (boolean includeAll : List.of(false, true)) {
            var received = new java.util.concurrent.atomic.AtomicReference<Object>();
            AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                    new Class<?>[] { AssetManager.class, AutoCloseable.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "loadInventory" -> current;
                        case "rescan" -> {
                            assertEquals(3, args.length, "the exclusion overload is used");
                            received.set(args[2]);
                            yield new AssetManager.ImportResult(snapshot(project, source));
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            var cli = new CommandLine(new RescanCommand((state, logs) -> manager))
                    .setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(new StringWriter()));
            String[] args = includeAll
                    ? new String[]{source.toString(), "--project", project.toString(), "--state-dir", directory.resolve("s").toString(), "--include-all"}
                    : new String[]{source.toString(), "--project", project.toString(), "--state-dir", directory.resolve("s").toString(),
                            "--exclude", "misc/notes.txt", "--exclude", "big.psd"};
            assertEquals(0, cli.execute(args));
            assertEquals(includeAll ? java.util.Set.of() : java.util.Set.of(Path.of("old.txt"), Path.of("misc", "notes.txt"), Path.of("big.psd")),
                    received.get());
        }
    }

    @Test
    void rejectsMissingProjectWithoutCreatingInventory() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        UUID project = UUID.randomUUID();
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter()));
        assertNotEquals(0, cli.execute("rescan", source.toString(), "--project", project.toString(),
                "--state-directory", directory.resolve("state").toString()));
        assertFalse(Files.exists(directory.resolve("state/projects").resolve(project.toString())
                .resolve("inventory-000001.json")));
        assertFalse(Files.exists(directory.resolve("logs")));
    }

    private static InventorySnapshot snapshot(UUID project, Path source) {
        Instant time = Instant.parse("2025-01-01T00:00:00Z");
        Asset asset = new Asset(UUID.randomUUID(), Path.of("mesh.obj"), Asset.Type.MODEL, "a".repeat(64), Map.of());
        Project saved = new Project(project, source.toAbsolutePath(), 2, List.of(asset), time);
        return new InventorySnapshot(1, 1, saved, List.of(), InventorySnapshot.ScanStatus.COMPLETE,
                time, List.of(), List.of());
    }
}
