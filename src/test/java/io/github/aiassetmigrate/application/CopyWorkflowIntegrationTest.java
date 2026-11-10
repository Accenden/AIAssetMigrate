package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CopyWorkflowIntegrationTest {
    @TempDir Path directory;

    @Test void approvedExactPlanCopiesReviewedNameWithoutChangingSource() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Path original = source.resolve("hero.obj");
        Files.writeString(original, "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            assertEquals(InventorySnapshot.ScanStatus.PARTIAL, snapshot.scanStatus());
            assertTrue(snapshot.hasCompleteCoverage());
            UUID project = snapshot.project().id();
            var name = new ReviewedChange(UUID.randomUUID(), snapshot.project().assets().getFirst().id(),
                    ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", "renamed.obj"), Instant.now());
            manager.review(project, snapshot.project().inventoryRevision(), List.of(name));
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertEquals(PlanStatus.DRAFT, plan.status());
            assertFalse(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR), plan.findings().toString());
            assertFalse(Files.exists(output.resolve("renamed.obj")));
            assertThrows(Exception.class, () -> manager.migrate(plan.id()));
            assertThrows(Exception.class, () -> manager.approve(plan.id(), plan.version() + 1));
            assertEquals(PlanStatus.APPROVED, manager.approve(plan.id(), plan.version()).status());
            var run = manager.migrate(plan.id());
            assertEquals(RunStatus.SUCCESS, run.status());
            assertEquals(run, manager.loadRun(plan.id()));
            assertEquals("v 0 0 0\n", Files.readString(original));
            assertEquals(Files.readString(original), Files.readString(output.resolve("renamed.obj")));
            assertThrows(Exception.class, () -> manager.migrate(plan.id()));
        }
    }

    @Test void editedReviewsInvalidateApprovalAndExistingOutputIsNeverOverwritten() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Files.writeString(output.resolve("hero.obj"), "existing\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id(), asset = snapshot.project().assets().getFirst().id();
            var target = new AssetManager.TargetProfile("unity", "6000.0");
            var stale = manager.createPlan(project, target, output);
            manager.approve(stale.id(), stale.version());
            manager.review(project, snapshot.project().inventoryRevision(), List.of(new ReviewedChange(UUID.randomUUID(), asset,
                    ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED, Map.of("category", "Characters"), Instant.now())));
            assertThrows(Exception.class, () -> manager.migrate(stale.id()));
            var fresh = manager.createPlan(project, target, output);
            manager.approve(fresh.id(), fresh.version());
            assertThrows(Exception.class, () -> manager.migrate(fresh.id()));
            assertEquals("existing\n", Files.readString(output.resolve("hero.obj")));
            assertEquals("v 0 0 0\n", Files.readString(source.resolve("hero.obj")));
            Files.delete(output.resolve("hero.obj"));
            assertEquals(RunStatus.SUCCESS, manager.migrate(fresh.id()).status());
        }
    }

    @Test void unresolvedReferencesPreventApproval() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib missing.mtl\nv 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var plan = manager.createPlan(snapshot.project().id(), new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertTrue(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR));
            assertThrows(Exception.class, () -> manager.approve(plan.id(), plan.version()));
            assertFalse(Files.exists(output.resolve("hero.obj")));
        }
    }

    @Test void skippedSourcePathsPreventApproval() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        Files.writeString(source.resolve("unknown.xyz"), "not supported\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            assertFalse(snapshot.hasCompleteCoverage());
            var plan = manager.createPlan(snapshot.project().id(), new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertThrows(Exception.class, () -> manager.approve(plan.id(), plan.version()));
            assertFalse(Files.exists(output.resolve("hero.obj")));
        }
    }

    @Test void changedSourceFailsBeforeReservationAndCanBeRetriedAfterRestoration() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Path model = source.resolve("hero.obj");
        Files.writeString(model, "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            var snapshot = manager.importAssets(source).snapshot();
            var plan = manager.createPlan(snapshot.project().id(), new AssetManager.TargetProfile("unity", "6000.0"), output);
            manager.approve(plan.id(), plan.version());
            Files.writeString(model, "v 1 1 1\n");
            assertThrows(Exception.class, () -> manager.migrate(plan.id()));
            assertThrows(Exception.class, () -> manager.loadRun(plan.id()));
            assertFalse(Files.exists(output.resolve("hero.obj")));
            Files.writeString(model, "v 0 0 0\n");
            assertEquals(RunStatus.SUCCESS, manager.migrate(plan.id()).status());
        }
    }
}
