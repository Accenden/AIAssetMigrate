package io.github.aiassetmigrate.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.logging.LogPublisher;
import io.github.aiassetmigrate.inventory.AssetScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisIntegrationTest {
    @TempDir Path directory;

    @Test void categoryAnalysisUsesSavedInventoryWithoutChangingSource() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path file = source.resolve("hero.obj");
        String content = "v 0 0 0\n";
        Files.writeString(file, content);
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var result = manager.analyze(snapshot.project().id(), AssetManager.AnalysisKind.CATEGORIES);
            assertEquals(snapshot.project().inventoryRevision(), result.inventoryRevision());
            assertEquals(1, result.suggestions().size());
            assertEquals("Characters", result.suggestions().getFirst().proposedValue());
            assertEquals(snapshot.project().assets().getFirst().id(), result.suggestions().getFirst().assetId());
            assertEquals(content, Files.readString(file));
            assertEquals(snapshot, manager.loadInventory(snapshot.project().id()));
        }
    }

    @Test void explicitCategoryReviewsSurviveRestartButNotRescan() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Path state = directory.resolve("state");
        Path logs = directory.resolve("logs");
        UUID project;
        ReviewedChange change;
        try (var manager = new DefaultAssetManager(state, logs)) {
            var snapshot = manager.importAssets(source).snapshot();
            project = snapshot.project().id();
            change = new ReviewedChange(UUID.randomUUID(), snapshot.project().assets().getFirst().id(),
                    ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED,
                    Map.of("category", "Heroes"), Instant.now());
            assertEquals(List.of(change), manager.review(project, snapshot.project().inventoryRevision(), List.of(change)));
        }
        try (var manager = new DefaultAssetManager(state, logs)) {
            assertEquals(List.of(change), manager.loadReviews(project));
            manager.rescan(project, source);
            assertTrue(manager.loadReviews(project).isEmpty());
        }
    }

    @Test void referenceAnalysisUsesInventoryFingerprintsAndReportsMissingReferences() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path model = source.resolve("hero.obj");
        Files.writeString(model, "mtllib hero.mtl\nusemtl body\nv 0 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl body\nmap_Kd missing.png\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            var relationships = manager.analyze(project, AssetManager.AnalysisKind.RELATIONSHIPS);
            assertEquals(1, relationships.suggestions().size());
            assertTrue(relationships.findings().stream().anyMatch(f -> f.code().startsWith("REFERENCE_")));
            var materials = manager.analyze(project, AssetManager.AnalysisKind.MATERIALS);
            assertEquals(1, materials.suggestions().size());
            Files.writeString(model, "mtllib changed.mtl\n");
            var stale = manager.analyze(project, AssetManager.AnalysisKind.MATERIALS);
            assertTrue(stale.findings().stream().anyMatch(f -> f.code().equals("INSPECTION_FINGERPRINT_MISMATCH")));
            assertTrue(stale.suggestions().isEmpty());
        }
    }

    @Test void rescanRejectsDecisionBasedOnEarlierAnalysisEvenWhenAssetIdentityIsStable() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Path logs = directory.resolve("logs");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), logs)) {
            var first = manager.importAssets(source).snapshot();
            UUID project = first.project().id();
            var analysis = manager.analyze(project, AssetManager.AnalysisKind.CATEGORIES);
            var suggestion = analysis.suggestions().getFirst();
            var change = new ReviewedChange(UUID.randomUUID(), suggestion.assetId(), ReviewedChange.Kind.CATEGORY,
                    ReviewedChange.Decision.ACCEPTED, Map.of("category", suggestion.proposedValue()), Instant.now());
            var next = manager.rescan(project, source).snapshot();
            assertEquals(suggestion.assetId(), next.project().assets().getFirst().id());
            assertEquals(1, analysis.inventoryRevision());
            assertEquals(2, next.project().inventoryRevision());
            int logCount = LogPublisher.readLogs(logs, project, 0).size();
            var failure = assertThrows(IllegalStateException.class,
                    () -> manager.review(project, analysis.inventoryRevision(), List.of(change)));
            assertTrue(failure.getMessage().contains("analyze again"));
            assertTrue(manager.loadReviews(project).isEmpty());
            assertEquals(logCount, LogPublisher.readLogs(logs, project, 0).size());
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 0, List.of(change)));
            assertEquals(List.of(change), manager.review(project, next.project().inventoryRevision(), List.of(change)));
        }
    }

    @Test void rescanAfterReviewValidationIsRejectedByLockedSave() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Path state = directory.resolve("state"), logs = directory.resolve("logs");
        try (var rescanner = new DefaultAssetManager(state, logs)) {
            var snapshot = rescanner.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            var change = new ReviewedChange(UUID.randomUUID(), snapshot.project().assets().getFirst().id(),
                    ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED,
                    Map.of("category", "Characters"), Instant.now());
            try (var reviewer = new DefaultAssetManager(state, logs, new AssetScanner(), (path, id) ->
                    new DefaultAssetManager.Publisher() {
                        public void publish(io.github.aiassetmigrate.domain.LogEvent.Level level, String message) {
                            if (message.equals("Saving reviewed changes")) rescanner.rescan(project, source);
                        }
                        public void close() { }
                    })) {
                var failure = assertThrows(UncheckedIOException.class,
                        () -> reviewer.review(project, snapshot.project().inventoryRevision(), List.of(change)));
                assertTrue(failure.getCause().getMessage().contains("stale"));
                assertTrue(reviewer.loadReviews(project).isEmpty());
                assertEquals(2, reviewer.loadInventory(project).project().inventoryRevision());
            }
        }
    }
}
