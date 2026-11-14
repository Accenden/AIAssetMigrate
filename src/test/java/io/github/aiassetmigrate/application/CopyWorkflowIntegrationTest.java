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
            assertEquals(InventorySnapshot.ScanStatus.COMPLETE, snapshot.scanStatus());
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

    @Test void missingOutputFolderIsCreatedOnlyWhenTheApprovedPlanRuns() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = directory.resolve("out").resolve("nested");
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertTrue(plan.findings().stream().anyMatch(f -> f.code().equals("OUTPUT_ROOT_CREATED_ON_RUN")
                    && f.severity() == Finding.Severity.INFO), plan.findings().toString());
            manager.approve(plan.id(), plan.version());
            assertFalse(Files.exists(directory.resolve("out")), "planning and approval never create the folder");
            assertEquals(RunStatus.SUCCESS, manager.migrate(plan.id()).status());
            assertEquals("v 0 0 0\n", Files.readString(output.resolve("hero.obj")));
        }
    }

    @Test void createdOutputFolderIsRemovedWhenTheRunCannotStart() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = directory.resolve("out").resolve("nested");
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            manager.approve(plan.id(), plan.version());
            Files.writeString(source.resolve("hero.obj"), "v 1 1 1\n");
            assertThrows(RuntimeException.class, () -> manager.migrate(plan.id()));
            assertFalse(Files.exists(directory.resolve("out")), "no folder is left behind when the run does not start");
            assertThrows(RuntimeException.class, () -> manager.loadRun(plan.id()), "no run was reserved");
        }
    }

    @Test void planVersionAndStatusFailuresSayWhatIsWrong() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), directory.resolve("logs"))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            String unapproved = assertThrows(RuntimeException.class, () -> manager.migrate(plan.id())).getMessage();
            assertTrue(unapproved.contains("not approved yet") && unapproved.contains("approve version 1"), unapproved);
            String wrongVersion = assertThrows(RuntimeException.class, () -> manager.approve(plan.id(), 2)).getMessage();
            assertTrue(wrongVersion.contains("version 1, not 2"), wrongVersion);
            manager.approve(plan.id(), plan.version());
            String twice = assertThrows(RuntimeException.class, () -> manager.approve(plan.id(), plan.version())).getMessage();
            assertTrue(twice.contains("already approved"), twice);
        }
    }

    @Test void importOfAMissingFolderSaysSoAndCreatesNoProject() throws Exception {
        Path state = directory.resolve("state");
        Path missing = directory.resolve("no-such-folder");
        Path file = Files.writeString(directory.resolve("hero.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(state, directory.resolve("logs"))) {
            ImportException notFound = assertThrows(ImportException.class, () -> manager.importAssets(missing));
            assertTrue(notFound.getMessage().startsWith("Asset folder not found: "), notFound.getMessage());
            assertTrue(notFound.getMessage().contains("No project was created"), notFound.getMessage());
            assertTrue(notFound.projectId().isEmpty(), "no project ID is reported for a project that does not exist");
            ImportException notFolder = assertThrows(ImportException.class, () -> manager.importAssets(file));
            assertTrue(notFolder.getMessage().startsWith("Not a folder: "), notFolder.getMessage());
            Path projects = state.resolve("projects");
            if (Files.exists(projects)) {
                try (var entries = Files.list(projects)) { assertEquals(0, entries.count()); }
            }
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
