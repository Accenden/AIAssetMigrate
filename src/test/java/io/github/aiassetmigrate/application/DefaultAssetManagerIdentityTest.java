package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.LogEvent;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.inventory.AssetScanner;
import io.github.aiassetmigrate.inventory.ScanLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultAssetManagerIdentityTest {
    @TempDir Path directory;

    private DefaultAssetManager manager(int depth) throws IOException {
        return new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"),
                new AssetScanner(new ScanLimits(depth, 100, 1000, 10000, 100)),
                (path, id) -> new DefaultAssetManager.Publisher() {
                    public void publish(LogEvent.Level level, String message) { }
                    public void close() { }
                });
    }

    private UUID assetId(InventorySnapshot snapshot, String path) {
        return snapshot.project().assets().stream()
                .filter(asset -> asset.relativePath().equals(Path.of(path)))
                .findFirst().orElseThrow().id();
    }

    @Test void recoversAfterMultipleUnvisitedRevisionsAndRestart() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.createDirectory(source.resolve("nested"));
        Files.writeString(source.resolve("nested/a.obj"), "v 0 0 0\n");
        UUID project;
        UUID original;
        try (var manager = manager(64)) {
            var first = manager.importAssets(source).snapshot();
            project = first.project().id();
            original = assetId(first, "nested/a.obj");
        }
        try (var manager = manager(1)) {
            for (int i = 0; i < 2; i++) {
                var partial = manager.rescan(project, source).snapshot();
                assertTrue(partial.project().assets().isEmpty());
                assertTrue(partial.pathOutcomes().stream().anyMatch(outcome ->
                        outcome.relativePath().equals(Path.of("nested"))
                                && outcome.status() == PathOutcome.Status.UNVISITED));
            }
        }
        try (var manager = manager(64)) {
            assertEquals(original, assetId(manager.rescan(project, source).snapshot(), "nested/a.obj"));
        }
    }

    @Test void failedPathCanRecoverButSkippedCorruptPathCannot() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path asset = Files.writeString(source.resolve("a.fbx"), "FBX content");
        try (var manager = manager(64)) {
            var first = manager.importAssets(source).snapshot();
            UUID project = first.project().id();
            UUID original = assetId(first, "a.fbx");
            Files.delete(asset);
            try { Files.createSymbolicLink(asset, directory.resolve("missing")); }
            catch (UnsupportedOperationException | IOException | SecurityException failure) {
                assumeTrue(false, "Symbolic links unavailable");
            }
            var failed = manager.rescan(project, source).snapshot();
            assertTrue(failed.pathOutcomes().stream().anyMatch(outcome ->
                    outcome.relativePath().equals(Path.of("a.fbx")) && outcome.status() == PathOutcome.Status.FAILED));
            Files.delete(asset);
            Files.writeString(asset, "FBX content");
            assertEquals(original, assetId(manager.rescan(project, source).snapshot(), "a.fbx"));
            Files.writeString(asset, "");
            var skipped = manager.rescan(project, source).snapshot();
            assertTrue(skipped.pathOutcomes().stream().anyMatch(outcome ->
                    outcome.relativePath().equals(Path.of("a.fbx")) && outcome.status() == PathOutcome.Status.SKIPPED));
            Files.writeString(asset, "FBX content");
            assertNotEquals(original, assetId(manager.rescan(project, source).snapshot(), "a.fbx"));
        }
    }

    @Test void deletionIsNotHiddenByAnUnvisitedSibling() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path asset = Files.writeString(source.resolve("a.obj"), "v 0 0 0\n");
        Files.createDirectory(source.resolve("nested"));
        UUID project;
        UUID original;
        try (var manager = manager(64)) {
            var first = manager.importAssets(source).snapshot();
            project = first.project().id();
            original = assetId(first, "a.obj");
        }
        Files.delete(asset);
        try (var manager = manager(1)) {
            var deleted = manager.rescan(project, source).snapshot();
            assertTrue(deleted.project().assets().isEmpty());
            assertTrue(deleted.pathOutcomes().stream().anyMatch(outcome ->
                    outcome.relativePath().toString().isEmpty() && outcome.status() == PathOutcome.Status.UNVISITED));
        }
        try (var manager = manager(0)) {
            assertTrue(manager.rescan(project, source).snapshot().project().assets().isEmpty());
        }
        Files.writeString(asset, "v 0 0 0\n");
        try (var manager = manager(64)) {
            assertNotEquals(original, assetId(manager.rescan(project, source).snapshot(), "a.obj"));
        }
    }

    @Test void renameAndRecreationDoNotReuseId() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path originalPath = Files.writeString(source.resolve("a.obj"), "v 0 0 0\n");
        try (var manager = manager(64)) {
            var first = manager.importAssets(source).snapshot();
            UUID original = assetId(first, "a.obj");
            Files.move(originalPath, source.resolve("b.obj"));
            var renamed = manager.rescan(first.project().id(), source).snapshot();
            assertNotEquals(original, assetId(renamed, "b.obj"));
            Files.writeString(originalPath, "v 0 0 0\n");
            var recreated = manager.rescan(first.project().id(), source).snapshot();
            assertNotEquals(original, assetId(recreated, "a.obj"));
            assertEquals(assetId(renamed, "b.obj"), assetId(recreated, "b.obj"));
        }
    }
}
