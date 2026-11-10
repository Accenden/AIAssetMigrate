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

    @Test void compatibilityIncludesReferenceCoverageAndChangesAreReported() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path model = source.resolve("hero.obj");
        Files.writeString(model, "mtllib missing.mtl\nv 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            var target = new AssetManager.TargetProfile("godot", "4.3");
            assertTrue(manager.supportedTargets().contains(target));
            var result = manager.assessCompatibility(project, target);
            assertEquals(snapshot.project().inventoryRevision(), result.inventoryRevision());
            assertEquals(target, result.target());
            assertTrue(result.findings().stream().anyMatch(f -> f.code().startsWith("REFERENCE_")));
            assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("CONVERTER_AVAILABILITY_UNKNOWN")));
            assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("SOURCE_REVALIDATION_INCOMPLETE")));
            assertThrows(IllegalArgumentException.class, () -> manager.assessCompatibility(project,
                    new AssetManager.TargetProfile("godot", "unsupported")));
            Files.writeString(model, "v 1 1 1\n");
            assertTrue(manager.assessCompatibility(project, target).findings().stream()
                    .anyMatch(f -> f.code().equals("INSPECTION_FINGERPRINT_MISMATCH")));
        }
    }

    @Test void invalidAcceptedOrganizationReviewsAreRejectedBeforeSaving() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id(), asset = snapshot.project().assets().getFirst().id();
            long revision = snapshot.project().inventoryRevision();
            var name = new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.NAME,
                    ReviewedChange.Decision.ACCEPTED, Map.of("name", "../hero.obj"), Instant.now());
            var folder = new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.FOLDER,
                    ReviewedChange.Decision.ACCEPTED, Map.of("folder", "../elsewhere"), Instant.now());
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, revision, List.of(name)));
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, revision, List.of(folder)));
            assertTrue(manager.loadReviews(project).isEmpty());
        }
    }

    @Test void ruleSuggestionsRemainUnacceptedUntilExplicitReview() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path model = source.resolve("Hero Knight.obj");
        String content = "v 0 0 0\n";
        Files.writeString(model, content);
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            var rules = new AssetManager.OrganizationRules(AssetManager.NameStyle.SNAKE_CASE,
                    AssetManager.FolderStyle.BY_TYPE);
            var analysis = manager.analyzeOrganization(project, rules);
            assertEquals(snapshot.project().inventoryRevision(), analysis.inventoryRevision());
            var name = analysis.suggestions().stream().filter(s -> s.kind() == AssetManager.AnalysisKind.NAMES)
                    .findFirst().orElseThrow();
            var folder = analysis.suggestions().stream().filter(s -> s.kind() == AssetManager.AnalysisKind.FOLDERS)
                    .findFirst().orElseThrow();
            assertEquals("hero_knight.obj", name.proposedValue());
            assertEquals("Models", folder.proposedValue());
            assertTrue(manager.loadReviews(project).isEmpty());
            assertEquals("Hero Knight.obj", manager.previewOrganization(project, analysis.inventoryRevision())
                    .entries().getFirst().destination().orElseThrow());
            var decisions = List.of(
                    new ReviewedChange(UUID.randomUUID(), name.assetId(), ReviewedChange.Kind.NAME,
                            ReviewedChange.Decision.ACCEPTED, Map.of("name", name.proposedValue()), Instant.now()),
                    new ReviewedChange(UUID.randomUUID(), folder.assetId(), ReviewedChange.Kind.FOLDER,
                            ReviewedChange.Decision.ACCEPTED, Map.of("folder", folder.proposedValue()), Instant.now()));
            manager.review(project, analysis.inventoryRevision(), decisions);
            assertEquals("Models/hero_knight.obj", manager.previewOrganization(project, analysis.inventoryRevision())
                    .entries().getFirst().destination().orElseThrow());
            assertEquals(content, Files.readString(model));
            manager.rescan(project, source);
            assertThrows(IllegalStateException.class, () -> manager.review(project, analysis.inventoryRevision(), decisions));
        }
    }

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

    @Test void materialReviewsRequireCompatibleAssetTypes() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Files.writeString(source.resolve("other.obj"), "v 1 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl body\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var assets = snapshot.project().assets().stream().collect(java.util.stream.Collectors.toMap(
                    asset -> asset.relativePath().toString(), asset -> asset.id()));
            var valid = new ReviewedChange(UUID.randomUUID(), assets.get("hero.obj"),
                    ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Decision.ACCEPTED,
                    Map.of("targetAssetId", assets.get("hero.mtl").toString(), "reference", "body"), Instant.now());
            var invalid = new ReviewedChange(UUID.randomUUID(), assets.get("hero.obj"),
                    ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Decision.ACCEPTED,
                    Map.of("targetAssetId", assets.get("other.obj").toString(), "reference", "body"), Instant.now());
            UUID project = snapshot.project().id();
            long revision = snapshot.project().inventoryRevision();
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, revision, List.of(invalid)));
            assertTrue(manager.loadReviews(project).isEmpty());
            assertEquals(List.of(valid), manager.review(project, revision, List.of(valid)));
            assertTrue(manager.previewOrganization(project, revision).findings().stream()
                    .anyMatch(f -> f.code().equals("MAPPING_LIBRARY_NOT_REFERENCED")));
        }
    }

    @Test void unsupportedModelReferenceInspectionIsReported() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("character.fbx"), "; FBX ASCII\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var result = manager.analyze(snapshot.project().id(), AssetManager.AnalysisKind.RELATIONSHIPS);
            assertTrue(result.suggestions().isEmpty());
            assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("INSPECTION_UNSUPPORTED")
                    && f.sourceRelativePath().orElseThrow().equals(Path.of("character.fbx"))));
        }
    }

    @Test void referenceBasedRelationshipCorrectionReplacesOnlySameReference() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Files.writeString(source.resolve("a.mtl"), "newmtl body\n");
        Files.writeString(source.resolve("b.mtl"), "newmtl body\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var assets = snapshot.project().assets().stream().collect(java.util.stream.Collectors.toMap(
                    asset -> asset.relativePath().toString(), asset -> asset.id()));
            UUID owner = assets.get("hero.obj"), project = snapshot.project().id();
            long revision = snapshot.project().inventoryRevision();
            var first = new ReviewedChange(UUID.randomUUID(), owner, ReviewedChange.Kind.RELATIONSHIP,
                    ReviewedChange.Decision.ACCEPTED, Map.of("targetAssetId", assets.get("a.mtl").toString(),
                            "reference", "missing.mtl"), Instant.now());
            var correction = new ReviewedChange(UUID.randomUUID(), owner, ReviewedChange.Kind.RELATIONSHIP,
                    ReviewedChange.Decision.ACCEPTED, Map.of("targetAssetId", assets.get("b.mtl").toString(),
                            "reference", "missing.mtl"), Instant.now());
            manager.review(project, revision, List.of(first));
            assertEquals(List.of(correction), manager.review(project, revision, List.of(correction)));
            assertEquals(List.of(correction), manager.loadReviews(project));
        }
    }

    @Test void organizationPreviewAppliesReviewsWithoutWritingAndBlocksCollisions() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("a.obj"), "v 0 0 0\n");
        Files.writeString(source.resolve("b.obj"), "v 1 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            long revision = snapshot.project().inventoryRevision();
            var names = manager.analyze(project, AssetManager.AnalysisKind.NAMES);
            assertEquals(revision, names.inventoryRevision());
            assertEquals(2, names.suggestions().size());
            var changes = snapshot.project().assets().stream().map(asset -> new ReviewedChange(
                    UUID.randomUUID(), asset.id(), ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED,
                    Map.of("name", "same.obj"), Instant.now())).toList();
            manager.review(project, revision, changes);
            var preview = manager.previewOrganization(project, revision);
            assertTrue(preview.blocked());
            assertTrue(preview.findings().stream().anyMatch(finding -> finding.code().equals("DESTINATION_CONFLICT")));
            assertEquals("v 0 0 0\n", Files.readString(source.resolve("a.obj")));
            assertEquals("v 1 0 0\n", Files.readString(source.resolve("b.obj")));
            manager.rescan(project, source);
            assertThrows(IllegalStateException.class, () -> manager.previewOrganization(project, revision));
        }
    }

    @Test void organizationPreviewBlocksMissingReferencesAndUninspectedModels() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.obj"), "mtllib missing.mtl\nv 0 0 0\n");
        Files.writeString(source.resolve("character.fbx"), "; FBX ASCII\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var preview = manager.previewOrganization(snapshot.project().id(), snapshot.project().inventoryRevision());
            assertTrue(preview.blocked());
            assertTrue(preview.findings().stream().anyMatch(f -> f.code().equals("UNRESOLVED_REFERENCE")));
            assertTrue(preview.findings().stream().anyMatch(f -> f.code().equals("INSPECTION_UNSUPPORTED")));
        }
    }
}
