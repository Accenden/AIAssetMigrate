package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import io.github.aiassetmigrate.inventory.FileReviewedChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FileMigrationPlanRepositoryTest {
    @TempDir Path temp;
    private Path source, state;
    private final UUID project = UUID.randomUUID(), asset = UUID.randomUUID(), command = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    private FileAssetRepository inventories;
    private FileReviewedChangeRepository reviews;
    private FileMigrationPlanRepository plans;
    private static final FileMigrationPlanRepository.PlanValidation VALID = (plan, inventory) -> { };

    @BeforeEach void setup() throws IOException {
        source = Files.createDirectory(temp.resolve("source"));
        state = temp.resolve("state");
        inventories = new FileAssetRepository(state);
        reviews = new FileReviewedChangeRepository(state);
        plans = new FileMigrationPlanRepository(state);
        saveInventory(1);
    }

    private InventorySnapshot snapshot(long revision) {
        Asset item = new Asset(asset, Path.of("model.obj"), Asset.Type.MODEL, "a".repeat(64), Map.of());
        return new InventorySnapshot(1, 1, new Project(project, source, revision, List.of(item), time), List.of(),
                InventorySnapshot.ScanStatus.COMPLETE, time.plusSeconds(revision), List.of(), List.of());
    }

    private void saveInventory(long revision) throws IOException {
        try (var writer = inventories.beginWrite(project, source)) { writer.saveNextRevision(snapshot(revision)); }
    }

    private MigrationPlan draft(List<ReviewedChange> changes) {
        var operation = new AssetOperationCommand(command, asset, MigrationCommand.Operation.COPY,
                Path.of("models/model.obj"), Map.of(), List.of(), Optional.empty());
        return new MigrationPlan(UUID.randomUUID(), project, 1, 1, "target", "1", temp.resolve("output"),
                changes, List.of(operation), List.of(), PlanStatus.DRAFT, time);
    }

    @Test void roundTripApprovalReservationAndCompletion() throws IOException {
        MigrationPlan draft = draft(List.of());
        plans.createDraft(draft, 0);
        var reopened = new FileMigrationPlanRepository(state);
        assertEquals(draft, reopened.load(draft.id()).plan());
        assertThrows(IOException.class, () -> reopened.approve(draft.id(), 2, VALID));
        assertEquals(PlanStatus.APPROVED, reopened.approve(draft.id(), 1, VALID).status());
        assertThrows(IOException.class, () -> reopened.approve(draft.id(), 1, VALID));
        var done = reopened.execute(draft.id(), 1, VALID, (plan, inventory, run) -> {
            assertEquals(RunStatus.INCOMPLETE, reopened.loadRun(draft.id()).status());
            return new FileMigrationPlanRepository.ExecutionResult(RunStatus.SUCCESS, Map.of(command, StepStatus.SUCCESS));
        });
        assertEquals(done, reopened.loadRun(draft.id()));
        assertThrows(IOException.class, () -> reopened.execute(draft.id(), 1, VALID, (plan, inventory, run) ->
                new FileMigrationPlanRepository.ExecutionResult(RunStatus.SUCCESS, Map.of(command, StepStatus.SUCCESS))));
        assertThrows(IOException.class, () -> reopened.createDraft(draft, 0));
    }

    @Test void diagnosticsPersistAndLegacyRunsRemainReadable() throws IOException {
        MigrationPlan draft = draft(List.of());
        plans.createDraft(draft, 0);
        plans.approve(draft.id(), 1, VALID);
        MigrationRun result = plans.execute(draft.id(), 1, VALID, (p, i, r) ->
                new FileMigrationPlanRepository.ExecutionResult(RunStatus.PARTIAL,
                        Map.of(command, StepStatus.FAILED), Optional.of("preflight\nfailed"),
                        Map.of(command, "cleanup warning: " + "x".repeat(3000))));
        MigrationRun loaded = new FileMigrationPlanRepository(state).loadRun(draft.id());
        assertEquals(result, loaded);
        assertEquals(Optional.of("preflight failed"), loaded.message());
        assertEquals(2048, loaded.stepDetails().get(command).length());
        assertTrue(loaded.stepDetails().get(command).startsWith("cleanup warning"));

        Path directory = state.resolve("plans").resolve(draft.id().toString());
        for (String name : List.of("started.json", "completed.json")) {
            Path file = directory.resolve(name);
            String json = Files.readString(file);
            json = json.replaceAll(",\\\"message\\\":(?:null|\\\"[^\\\"]*\\\")", "")
                    .replaceAll(",\\\"stepDetails\\\":\\{[^}]*}", "");
            Files.writeString(file, json);
        }
        MigrationRun legacy = plans.loadRun(draft.id());
        assertTrue(legacy.message().isEmpty());
        assertTrue(legacy.stepDetails().isEmpty());
    }

    @Test void rejectsDetailsForUnknownSteps() {
        assertThrows(IllegalArgumentException.class, () -> new FileMigrationPlanRepository.ExecutionResult(
                RunStatus.FAILED, Map.of(command, StepStatus.FAILED), Optional.empty(), Map.of(UUID.randomUUID(), "bad")));
    }

    @Test void revisionChangesAndProjectLockRejectOperations() throws IOException {
        MigrationPlan first = draft(List.of());
        plans.createDraft(first, 0);
        reviews.save(snapshot(1), 0, List.of());
        assertThrows(IOException.class, () -> plans.approve(first.id(), 1, VALID));
        MigrationPlan second = draft(List.of());
        plans.createDraft(second, 1);
        plans.approve(second.id(), 1, VALID);
        try (var writer = inventories.beginWrite(project, source)) {
            assertThrows(IOException.class, () -> plans.execute(second.id(), 1, VALID, (p, i, r) -> null));
            writer.saveNextRevision(snapshot(2));
        }
        assertThrows(IOException.class, () -> plans.execute(second.id(), 1, VALID, (p, i, r) -> null));
    }

    @Test void executeKeepsLockAndCrashCannotBeRerun() throws IOException {
        MigrationPlan draft = draft(List.of());
        plans.createDraft(draft, 0);
        plans.approve(draft.id(), 1, VALID);
        assertThrows(IOException.class, () -> plans.execute(draft.id(), 1, VALID, (plan, inventory, run) -> {
            assertThrows(IOException.class, () -> inventories.beginWrite(project, source));
            throw new IOException("simulated crash");
        }));
        assertEquals(RunStatus.INCOMPLETE, plans.loadRun(draft.id()).status());
        assertThrows(IOException.class, () -> plans.execute(draft.id(), 1, VALID, (plan, inventory, run) ->
                new FileMigrationPlanRepository.ExecutionResult(RunStatus.SUCCESS, Map.of(command, StepStatus.SUCCESS))));
    }

    @Test void validationRunsUnderLockBeforeEitherTransition() throws IOException {
        MigrationPlan draft = draft(List.of());
        plans.createDraft(draft, 0);
        var reject = (FileMigrationPlanRepository.PlanValidation) (plan, inventory) -> {
            assertThrows(IOException.class, () -> inventories.beginWrite(project, source));
            throw new IOException("Unsafe plan");
        };
        assertThrows(IOException.class, () -> plans.approve(draft.id(), 1, reject));
        assertEquals(PlanStatus.DRAFT, plans.load(draft.id()).plan().status());
        plans.approve(draft.id(), 1, VALID);
        assertThrows(IOException.class, () -> plans.execute(draft.id(), 1, reject, (p, i, r) -> null));
        assertFalse(Files.exists(state.resolve("plans").resolve(draft.id().toString()).resolve("started.json")));
        assertEquals(RunStatus.PARTIAL, plans.execute(draft.id(), 1, VALID, (p, i, r) ->
                new FileMigrationPlanRepository.ExecutionResult(RunStatus.PARTIAL, Map.of(command, StepStatus.FAILED))).status());
    }

    @Test void rejectsUnsafePlanPathAndCorruptApproval() throws IOException {
        MigrationPlan draft = draft(List.of());
        plans.createDraft(draft, 0);
        Path planDir = state.resolve("plans").resolve(draft.id().toString());
        Files.writeString(planDir.resolve("approved.json"), "{}");
        assertThrows(IOException.class, () -> plans.load(draft.id()));
        UUID other = UUID.randomUUID();
        io.github.aiassetmigrate.TestLinks.symlinkOrSkip(state.resolve("plans").resolve(other.toString()), planDir);
        assertThrows(IOException.class, () -> plans.load(other));
    }

    @Test void exactReviewContentAndRevisionAreBound() throws IOException {
        var change = new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.CATEGORY,
                ReviewedChange.Decision.ACCEPTED, Map.of("category", "model"), time);
        reviews.save(snapshot(1), 0, List.of(change));
        assertThrows(IOException.class, () -> plans.createDraft(draft(List.of()), 1));
        assertThrows(IOException.class, () -> plans.createDraft(draft(List.of(change)), 0));
        MigrationPlan plan = draft(List.of(change));
        plans.createDraft(plan, 1);
        plans.approve(plan.id(), 1, VALID);
        reviews.save(snapshot(1), 1, List.of(change));
        assertThrows(IOException.class, () -> plans.execute(plan.id(), 1, VALID, (p, i, r) -> null));
    }

    @Test void concurrentExecutionCannotReserveTwice() throws Exception {
        MigrationPlan plan = draft(List.of());
        plans.createDraft(plan, 0);
        plans.approve(plan.id(), 1, VALID);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> plans.execute(plan.id(), 1, VALID, (p, i, r) -> {
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return new FileMigrationPlanRepository.ExecutionResult(RunStatus.SUCCESS, Map.of(command, StepStatus.SUCCESS));
            }));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> plans.execute(plan.id(), 1, VALID, (p, i, r) -> null));
            release.countDown();
            assertEquals(RunStatus.SUCCESS, first.get(10, java.util.concurrent.TimeUnit.SECONDS).status());
            assertThrows(IOException.class, () -> plans.execute(plan.id(), 1, VALID, (p, i, r) -> null));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
