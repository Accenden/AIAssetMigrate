package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.inventory.AssetScanner;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import io.github.aiassetmigrate.inventory.ScanLimits;
import io.github.aiassetmigrate.logging.LogPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultAssetManagerTest {
    @TempDir Path directory;
    Path source;
    Path state;
    Path logs;

    @BeforeEach void setup() throws IOException {
        source = Files.createDirectory(directory.resolve("source"));
        state = directory.resolve("state");
        logs = directory.resolve("logs");
    }

    @Test void importReloadRescanAndPreserveSources() throws Exception {
        Path asset = Files.writeString(source.resolve("a.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        Files.writeString(source.resolve("unknown.xyz"), "unrecognized");
        InventorySnapshot first;
        try (var manager = new DefaultAssetManager(state, logs)) {
            first = manager.importAssets(source).snapshot();
            assertEquals(1, first.project().inventoryRevision());
            assertEquals(1, first.project().assets().size());
            assertFalse(first.findings().isEmpty());
            assertEquals(first, manager.loadInventory(first.project().id()));
        }
        byte[] changed = "v 0 0 0\nv 2 0 0\nv 0 2 0\nf 1 2 3\n".getBytes();
        Files.write(asset, changed);
        try (var manager = new DefaultAssetManager(state, logs)) {
            var second = manager.rescan(first.project().id(), source).snapshot();
            assertEquals(2, second.project().inventoryRevision());
            assertEquals(first.project().id(), second.project().id());
            assertEquals(first.project().createdAt(), second.project().createdAt());
            assertEquals(first.project().assets().getFirst().id(), second.project().assets().getFirst().id());
            assertNotEquals(first.project().assets().getFirst().fingerprint(), second.project().assets().getFirst().fingerprint());
            assertEquals(first, manager.loadInventory(first.project().id(), 1));
            assertEquals(second, manager.loadInventory(first.project().id()));
            assertArrayEquals(changed, Files.readAllBytes(asset));
            Files.delete(asset);
            assertTrue(manager.rescan(first.project().id(), source).snapshot().project().assets().isEmpty());
        }
        assertTrue(LogPublisher.readLogs(logs, first.project().id(), 0).size() >= 12);
    }

    @Test void incompleteScanPreservesCoverageAndDoesNotCarryOldAssets() throws Exception {
        Files.writeString(source.resolve("a.obj"), "v 0 0 0\n");
        UUID id;
        try (var manager = new DefaultAssetManager(state, logs, new AssetScanner(),
                (path, project) -> new NoopPublisher())) {
            id = manager.importAssets(source).snapshot().project().id();
        }
        try (var manager = new DefaultAssetManager(state, logs, new AssetScanner(new ScanLimits(0, 10, 100, 1000, 10)),
                (path, project) -> new NoopPublisher())) {
            var result = manager.rescan(id, source).snapshot();
            assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
            assertTrue(result.project().assets().isEmpty());
            assertTrue(result.pathOutcomes().stream().anyMatch(outcome -> outcome.status().name().equals("UNVISITED")));
            assertFalse(result.findings().isEmpty());
        }
    }

    @Test void rejectsOverlapAndWrongRootBeforeWriting() throws Exception {
        assertThrows(IOException.class, () -> new DefaultAssetManager(state, state.resolve("other-logs")));
        Path inside = source.resolve("inside");
        try (var manager = new DefaultAssetManager(state, inside)) {
            assertThrows(ImportException.class, () -> manager.importAssets(source));
        }
        assertFalse(Files.exists(state));
        assertFalse(Files.exists(inside));
        try (var manager = new DefaultAssetManager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            Path other = Files.createDirectory(directory.resolve("other"));
            assertThrows(ImportException.class, () -> manager.rescan(first.project().id(), other));
            assertEquals(first, manager.loadInventory(first.project().id()));
        }
    }

    @Test void rejectsSymlinkedLogAndSidecarBeforeWritingRevision() throws Exception {
        try (var manager = new DefaultAssetManager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            UUID id = first.project().id();
            Path target = Files.writeString(directory.resolve("target"), "unchanged");
            for (Path file : new Path[] { logs.resolve(id + ".log"), logs.resolve(id + ".lock") }) {
                Files.delete(file);
                try { Files.createSymbolicLink(file, target); }
                catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Symlinks unavailable"); }
                assertThrows(ImportException.class, () -> manager.rescan(id, source));
                assertEquals("unchanged", Files.readString(target));
                assertEquals(first, manager.loadInventory(id));
                Files.delete(file);
                Files.createFile(file);
            }
        }
    }

    @Test void lockContentionPreventsRescan() throws Exception {
        try (var manager = new DefaultAssetManager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            try (var ignored = new FileAssetRepository(state).beginWrite(first.project().id(), source)) {
                assertThrows(ImportException.class, () -> manager.rescan(first.project().id(), source));
            }
            assertEquals(first, manager.loadInventory(first.project().id()));
        }
    }

    @Test void hardLinkedLogCannotModifyAnAsset() throws Exception {
        Path asset = Files.writeString(source.resolve("asset.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            assumeTrue(Files.getFileStore(asset).supportsFileAttributeView("unix"), "unix link count unavailable");
            Path log = logs.resolve(first.project().id() + ".log");
            Files.delete(log);
            try { Files.createLink(log, asset); }
            catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Hard links unavailable"); }
            assertThrows(ImportException.class, () -> manager.rescan(first.project().id(), source));
            assertEquals("v 0 0 0\n", Files.readString(asset));
            assertEquals(first, manager.loadInventory(first.project().id()));
        }
    }

    @Test void hardLinkedCommitSidecarsCannotModifyAnAsset() throws Exception {
        Path asset = Files.writeString(source.resolve("asset.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            assumeTrue(Files.getFileStore(asset).supportsFileAttributeView("unix"), "unix link count unavailable");
            for (String suffix : new String[] {".gate", ".commit"}) {
                Path sidecar = logs.resolve(first.project().id() + suffix);
                byte[] original = Files.readAllBytes(sidecar);
                Files.delete(sidecar);
                try { Files.createLink(sidecar, asset); }
                catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Hard links unavailable"); }
                assertThrows(ImportException.class, () -> manager.rescan(first.project().id(), source));
                assertEquals("v 0 0 0\n", Files.readString(asset));
                assertEquals(first, manager.loadInventory(first.project().id()));
                Files.delete(sidecar);
                Files.write(sidecar, original);
            }
        }
    }

    @Test void unavailableLinkCountRejectsExistingFilesEvenWithoutKnownHardLinks() throws Exception {
        Path file = Files.createFile(directory.resolve("existing-log"));
        IOException failure = assertThrows(IOException.class,
                () -> DefaultAssetManager.validateLogLinkCount(file, false, false));
        assertTrue(failure.getMessage().contains("Cannot safely reuse"));
        // After a strict publisher has atomically created its files, pre-commit checks can continue.
        assertDoesNotThrow(() -> DefaultAssetManager.validateLogLinkCount(file, false, true));
    }

    @Test void unavailableLinkCountRejectsHardLinkedLogsAndSidecars() throws Exception {
        Path asset = Files.createFile(source.resolve("asset.obj"));
        for (String name : new String[] {"project.log", "project.lock"}) {
            Path link = directory.resolve(name);
            try { Files.createLink(link, asset); }
            catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Hard links unavailable"); }
            assertThrows(IOException.class, () -> DefaultAssetManager.validateLogLinkCount(link, false, false));
            assertEquals(0, Files.size(asset));
        }
    }

    @Test void projectLockHeldDuringCompletionPublication() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var manager = new DefaultAssetManager(state, logs, new AssetScanner(), (path, id) -> new NoopPublisher() {
            @Override public void publish(io.github.aiassetmigrate.domain.LogEvent.Level level, String message) throws IOException {
                if (calls.incrementAndGet() == 4) {
                    assertThrows(IOException.class, () -> new FileAssetRepository(state).beginWrite(id, source));
                }
            }
        })) {
            manager.importAssets(source);
            assertEquals(4, calls.get());
        }
    }

    @Test void loggingFailureBeforeSaveNeverCommits() throws Exception {
        for (int failAt = 1; failAt <= 3; failAt++) {
            try (var manager = managerWithFailure(failAt, false)) {
                ImportException failure = assertThrows(ImportException.class, () -> manager.importAssets(source));
                assertTrue(failure.projectId().isPresent());
                assertTrue(failure.committedRevision().isEmpty());
                assertThrows(ImportException.class, () -> manager.loadInventory(failure.projectId().orElseThrow()));
            }
        }
    }

    @Test void completionOrCloseFailureReturnsRecoverableCommittedIdentity() throws Exception {
        for (boolean failClose : new boolean[] {false, true}) {
            try (var manager = managerWithFailure(failClose ? 0 : 4, failClose)) {
                ImportException failure = assertThrows(ImportException.class, () -> manager.importAssets(source));
                assertEquals(1L, failure.committedRevision().orElseThrow());
                assertEquals(1L, manager.loadInventory(failure.projectId().orElseThrow()).project().inventoryRevision());
            }
        }
    }

    @Test void readsDoNotCreateStateAndUnsupportedOperationsThrow() throws Exception {
        var manager = new DefaultAssetManager(state, logs);
        assertThrows(ImportException.class, () -> manager.loadInventory(UUID.randomUUID()));
        assertFalse(Files.exists(state));
        assertFalse(Files.exists(logs));
        assertThrows(java.io.UncheckedIOException.class, () -> manager.watch(UUID.randomUUID(), 0, event -> {}));
        assertThrows(ImportException.class, () -> manager.analyze(UUID.randomUUID(), AssetManager.AnalysisKind.CATEGORIES));
        assertThrows(ImportException.class, () -> manager.checkCompatibility(UUID.randomUUID(), new AssetManager.TargetProfile("target", "1")));
        manager.close();
        assertThrows(IllegalStateException.class, () -> manager.importAssets(source));
    }

    private DefaultAssetManager managerWithFailure(int failAt, boolean failClose) throws IOException {
        return new DefaultAssetManager(state, logs, new AssetScanner(), (path, id) -> new NoopPublisher() {
            private final AtomicInteger count = new AtomicInteger();
            @Override public void publish(io.github.aiassetmigrate.domain.LogEvent.Level level, String message) throws IOException {
                if (count.incrementAndGet() == failAt) throw new IOException("injected publication failure");
            }
            @Override public void close() throws IOException {
                if (failClose) throw new IOException("injected close failure");
            }
        });
    }

    private static class NoopPublisher implements DefaultAssetManager.Publisher {
        public void publish(io.github.aiassetmigrate.domain.LogEvent.Level level, String message) throws IOException { }
        public void close() throws IOException { }
    }
}
