package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.LogEvent;
import io.github.aiassetmigrate.inventory.AssetScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultAssetManagerReferenceTest {
    @TempDir Path directory;

    @Test
    void persistsScanTimeReferencesAcrossRestartAndRemapsTheirOwnersOnRescan() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path state = directory.resolve("state");
        Path logs = directory.resolve("logs");
        Files.writeString(source.resolve("model.obj"), "mtllib material.mtl\n");
        Files.writeString(source.resolve("material.mtl"), "newmtl painted\nmap_Kd painted.png\n");
        Files.write(source.resolve("painted.png"), new byte[] {1, 2, 3});

        UUID project;
        AssetReference firstReference;
        try (var manager = manager(state, logs)) {
            var first = manager.importAssets(source).snapshot();
            project = first.project().id();
            assertEquals(2, first.references().size());
            firstReference = first.references().stream().filter(reference -> reference.kind()
                    == AssetReference.Kind.MATERIAL_LIBRARY).findFirst().orElseThrow();
            assertEquals(first.project().assets().stream().filter(asset -> asset.relativePath().equals(Path.of("model.obj")))
                    .findFirst().orElseThrow().id(), firstReference.ownerAssetId());
            var rescanned = manager.rescan(project, source).snapshot();
            assertEquals(first.references(), rescanned.references());
        }

        Files.delete(source.resolve("painted.png"));
        Files.delete(source.resolve("material.mtl"));
        Files.delete(source.resolve("model.obj"));
        try (var manager = manager(state, logs)) {
            var loaded = manager.loadInventory(project);
            assertEquals(2, loaded.references().size());
            assertEquals(firstReference, loaded.references().stream().filter(reference -> reference.kind()
                    == AssetReference.Kind.MATERIAL_LIBRARY).findFirst().orElseThrow());
            assertFalse(loaded.references().isEmpty());
        }
    }

    private static DefaultAssetManager manager(Path state, Path logs) throws java.io.IOException {
        return new DefaultAssetManager(state, logs, new AssetScanner(), (path, id) -> new DefaultAssetManager.Publisher() {
            public void publish(LogEvent.Level level, String message) { }
            public void close() { }
        });
    }
}
