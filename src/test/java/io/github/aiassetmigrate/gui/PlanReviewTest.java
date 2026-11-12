package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.AssetOperationCommand;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationCommand;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.PlanStatus;
import io.github.aiassetmigrate.domain.Project;
import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlanReviewTest {
    private final UUID project = UUID.randomUUID();
    private final Path output = io.github.aiassetmigrate.TestPaths.absolute("output");
    private final AssetManager.TargetProfile target = new AssetManager.TargetProfile("engine", "1");
    private final AssetMigrateApplication.PlanRequest request = new AssetMigrateApplication.PlanRequest(
            project, io.github.aiassetmigrate.TestPaths.absolute("state"), io.github.aiassetmigrate.TestPaths.absolute("logs"), output, target);

    private MigrationPlan plan(PlanStatus status) {
        var command = new AssetOperationCommand(UUID.randomUUID(), UUID.randomUUID(),
                MigrationCommand.Operation.COPY, Path.of("models/a.obj"), Map.of("key", "value"),
                List.of(UUID.randomUUID()), Optional.of("tool"));
        var finding = new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.ERROR, "BLOCKED", "Conflict");
        return new MigrationPlan(UUID.randomUUID(), project, 3, 7, "engine", "1", output,
                List.of(), List.of(command), List.of(finding), status, Instant.EPOCH);
    }

    private MigrationPlan status(MigrationPlan plan, PlanStatus status) {
        return new MigrationPlan(plan.id(), plan.projectId(), plan.version(), plan.inventoryRevision(), plan.targetProfile(),
                plan.targetProfileVersion(), plan.outputRoot(), plan.reviewedChanges(), plan.commands(), plan.findings(), status, plan.createdAt());
    }

    private InventorySnapshot inventory(MigrationPlan plan, UUID id, long revision, boolean includeAsset) {
        var assets = includeAsset ? List.of(new Asset(plan.commands().get(0).assetId(), Path.of("original/source.obj"),
                Asset.Type.MODEL, "a".repeat(64), Map.of())) : List.<Asset>of();
        var project = new Project(id, io.github.aiassetmigrate.TestPaths.absolute("source"), revision, assets, Instant.EPOCH);
        return new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE,
                Instant.EPOCH, List.of(), List.of());
    }

    private AssetMigrateApplication.PlanContext context(MigrationPlan plan) {
        return AssetMigrateApplication.PlanContext.fromInventory(request, plan, inventory(plan, project, 7, true));
    }

    @Test
    void displaysCompletePlanAndBindsApprovalToDisplayedVersion() throws Exception {
        MigrationPlan draft = plan(PlanStatus.DRAFT);
        MigrationPlan approved = status(draft, PlanStatus.APPROVED);
        List<String> calls = new ArrayList<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    worker.set(Thread.currentThread());
                    if (method.getName().equals("createPlan")) {
                        assertArrayEquals(new Object[]{project, target, output}, args);
                        return draft;
                    }
                    if (method.getName().equals("loadInventory")) {
                        assertArrayEquals(new Object[]{project, 7L}, args);
                        return inventory(draft, project, 7, true);
                    }
                    assertEquals("approve", method.getName());
                    assertArrayEquals(new Object[]{draft.id(), 3}, args);
                    return approved;
                });
        var queue = new LinkedBlockingQueue<Runnable>();
        var result = new AtomicReference<ImportRunner.Result<AssetMigrateApplication.PlanContext>>();
        try (var runner = new ImportRunner(queue::add, (s, l) -> new ImportRunner.Managed(manager, () -> { }))) {
            assertTrue(runner.submitValue(request.state(), request.logs(), request::create, result::set));
            Runnable callback = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            assertNull(result.get().failure());
            assertEquals(List.of("createPlan", "loadInventory"), calls);
            assertNotSame(Thread.currentThread(), worker.get());
            String text = AssetMigrateApplication.formatPlan(result.get().value());
            for (String expected : List.of(draft.id().toString(), project.toString(), "Plan version: 3", "revision: 7",
                    "DRAFT", "engine / 1", output.toString(), "original/source.obj", draft.commands().get(0).toString(), "BLOCKED", "Conflict")) {
                assertTrue(text.contains(expected), expected);
            }
            var context = result.get().value();
            var approval = new AtomicReference<ImportRunner.Result<MigrationPlan>>();
            assertTrue(runner.submitValue(request.state(), request.logs(), context::approve, approval::set));
            callback = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            assertEquals(approved, approval.get().value());
            assertEquals(List.of("createPlan", "loadInventory", "approve"), calls);
        }
    }

    @Test
    void refusesChangedPlansAndDraftMigration() {
        MigrationPlan draft = plan(PlanStatus.DRAFT);
        MigrationPlan approved = status(draft, PlanStatus.APPROVED);
        List<String> calls = new ArrayList<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> { calls.add(method.getName()); return plan(PlanStatus.APPROVED); });
        assertThrows(IllegalStateException.class, () -> context(draft).migrate(manager, Optional.empty()));
        assertTrue(calls.isEmpty());
        assertThrows(IllegalStateException.class, () -> context(draft).approve(manager));
        calls.clear();
        assertThrows(IllegalStateException.class, () -> context(approved).migrate(manager, Optional.empty()));
        assertEquals(List.of("loadPlan"), calls);
        var generation = new AssetMigrateApplication.CompatibilityGeneration();
        long captured = generation.current();
        generation.invalidate();
        assertFalse(generation.isCurrent(captured));
    }

    @Test
    void migrationIsSeparateAndRechecksTheExactApprovedPlan() {
        MigrationPlan approved = plan(PlanStatus.APPROVED);
        var run = new MigrationRun(UUID.randomUUID(), project, approved.id(), approved.version(), output,
                RunStatus.values()[0], Map.of(), Instant.EPOCH, Optional.empty(), Optional.empty());
        var outcome = new AssetManager.MigrationOutcome(run, Optional.empty(), Optional.empty(), Optional.of("busy"));
        List<String> calls = new ArrayList<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("loadPlan")) {
                        assertArrayEquals(new Object[]{approved.id()}, args);
                        return approved;
                    }
                    assertArrayEquals(new Object[]{approved.id(), Optional.empty()}, args);
                    return outcome;
                });
        assertEquals(outcome, context(approved).migrate(manager, Optional.empty()));
        assertEquals(List.of("loadPlan", "migrateAndValidate"), calls);

        var otherPlan = new AssetManager.MigrationOutcome(new MigrationRun(UUID.randomUUID(), project, UUID.randomUUID(),
                approved.version(), output, RunStatus.SUCCESS, Map.of(), Instant.EPOCH, Optional.empty(), Optional.empty()),
                Optional.empty(), Optional.empty(), Optional.of("busy"));
        AssetManager wrong = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> method.getName().equals("loadPlan") ? approved : otherPlan);
        assertThrows(IllegalStateException.class, () -> context(approved).migrate(wrong, Optional.empty()));
    }

    @Test
    void resultTextNamesFilesAndShowsInterruptionAndValidationFailure() {
        MigrationPlan approved = plan(PlanStatus.APPROVED);
        var command = approved.commands().getFirst();
        var context = context(approved);
        var run = new MigrationRun(UUID.randomUUID(), project, approved.id(), 1, output, RunStatus.INCOMPLETE,
                Map.of(command.id(), io.github.aiassetmigrate.domain.StepStatus.PENDING), Instant.EPOCH,
                Optional.of(Instant.EPOCH), Optional.empty());
        var report = new io.github.aiassetmigrate.domain.Report(UUID.randomUUID(), project, approved.id(), run.id(), RunStatus.INCOMPLETE,
                io.github.aiassetmigrate.domain.Report.ValidationStatus.INCOMPLETE, List.of(), "summary", Optional.empty(), Instant.EPOCH);
        String validated = AssetMigrateApplication.formatResult(
                new AssetManager.MigrationOutcome(run, Optional.of(report), Optional.of("source changed"), Optional.empty()), context);
        assertTrue(validated.startsWith("Run INCOMPLETE: 0 of 1 step(s) succeeded."), validated);
        assertTrue(validated.contains("PENDING  models/a.obj (copy)"), "steps are shown by output path: " + validated);
        assertFalse(validated.contains(command.id().toString()), "command IDs are not shown");
        assertTrue(validated.contains("Migration interrupted: source changed"), validated);
        assertFalse(validated.contains("Validation failed"), validated);

        String unvalidated = AssetMigrateApplication.formatResult(
                new AssetManager.MigrationOutcome(run, Optional.empty(), Optional.empty(), Optional.of("Another writer")), context);
        assertFalse(unvalidated.contains("interrupted"), unvalidated);
        assertTrue(unvalidated.contains("Validation failed: Another writer"), unvalidated);
        assertTrue(unvalidated.contains("No report was saved"), unvalidated);
    }

    @Test
    void unknownSourcesAndMismatchedInventoryBlockApproval() {
        MigrationPlan draft = plan(PlanStatus.DRAFT);
        var invs = List.of(inventory(draft, project, 7, false), inventory(draft, UUID.randomUUID(), 7, true),
                inventory(draft, project, 8, true));
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> { fail("Approval must not call the manager"); return null; });
        for (var inventory : invs) {
            var context = AssetMigrateApplication.PlanContext.fromInventory(request, draft, inventory);
            assertFalse(context.sourceErrors().isEmpty());
            String text = AssetMigrateApplication.formatPlan(context);
            assertTrue(text.contains("<unknown>"));
            assertTrue(text.contains("ERROR:"));
            assertThrows(IllegalStateException.class, () -> context.approve(manager));
        }
    }

    @Test
    void displaysAllReviewedDecisionsIncludingExcludedAssetsWithoutCommands() {
        MigrationPlan base = plan(PlanStatus.DRAFT);
        UUID excluded = UUID.randomUUID();
        UUID commandAsset = base.commands().get(0).assetId();
        var exclusion = new ReviewedChange(UUID.randomUUID(), excluded, ReviewedChange.Kind.EXCLUSION,
                ReviewedChange.Decision.ACCEPTED, Map.of("reason", "Not needed"), Instant.EPOCH);
        var rejected = new ReviewedChange(UUID.randomUUID(), commandAsset, ReviewedChange.Kind.NAME,
                ReviewedChange.Decision.REJECTED, Map.of("name", "alternate.obj"), Instant.EPOCH);
        var draft = new MigrationPlan(base.id(), project, base.version(), base.inventoryRevision(), base.targetProfile(),
                base.targetProfileVersion(), output, List.of(exclusion, rejected), base.commands(), base.findings(),
                base.status(), base.createdAt());
        var original = inventory(draft, project, 7, true);
        var assets = new ArrayList<>(original.project().assets());
        assets.add(new Asset(excluded, Path.of("excluded/asset.obj"), Asset.Type.MODEL, "b".repeat(64), Map.of()));
        var snapshot = new InventorySnapshot(1, 1, new Project(project, io.github.aiassetmigrate.TestPaths.absolute("source"), 7, assets, Instant.EPOCH),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, Instant.EPOCH, List.of(), List.of());
        var context = AssetMigrateApplication.PlanContext.fromInventory(request, draft, snapshot);
        assertTrue(context.sourcesVerified());
        String text = AssetMigrateApplication.formatPlan(context);
        for (String expected : List.of("Reviewed decisions (complete):", exclusion.id().toString(), excluded.toString(),
                "excluded/asset.obj", "Kind: EXCLUSION", "Decision: ACCEPTED", "reason=Not needed",
                rejected.id().toString(), commandAsset.toString(), "original/source.obj", "Kind: NAME",
                "Decision: REJECTED", "name=alternate.obj")) {
            assertTrue(text.contains(expected), expected);
        }

        var missing = AssetMigrateApplication.PlanContext.fromInventory(request, draft, original);
        assertFalse(missing.sourcesVerified());
        assertTrue(AssetMigrateApplication.formatPlan(missing).contains("Unknown reviewed asset ID: " + excluded));
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class},
                (proxy, method, args) -> { fail("Approval must not call the manager"); return null; });
        assertThrows(IllegalStateException.class, () -> missing.approve(manager));
        assertTrue(AssetMigrateApplication.formatPlan(context(base)).contains("Reviewed decisions (complete):\nNone\n"));
    }
}
