package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImportRunnerTest {
    private static final Path STATE = Path.of("state");
    private static final Path LOGS = Path.of("logs");

    @Test
    void runsAndClosesOffCallerThreadAndDispatchesCompletion() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Thread> closedBy = new AtomicReference<>();
        AtomicReference<ImportRunner.Outcome> result = new AtomicReference<>();
        InventorySnapshot snapshot = snapshot();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> {
            assertEquals(STATE, state);
            assertEquals(LOGS, logs);
            worker.set(Thread.currentThread());
            return new ImportRunner.Managed(null, () -> closedBy.set(Thread.currentThread()));
        })) {
            assertTrue(runner.submit(STATE, LOGS, manager -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return snapshot;
            }, result::set));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(runner.isBusy());
            assertFalse(runner.submit(STATE, LOGS, manager -> snapshot, result::set));
            release.countDown();
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            assertNull(result.get());
            assertTrue(runner.isBusy());
            completion.run();
            assertFalse(runner.isBusy());
            assertSame(snapshot, result.get().snapshot());
            assertNull(result.get().failure());
            assertNotSame(Thread.currentThread(), worker.get());
            assertSame(worker.get(), closedBy.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void preservesSnapshotWhenClosingFails() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Outcome> result = new AtomicReference<>();
        InventorySnapshot snapshot = snapshot();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) ->
                new ImportRunner.Managed(null, () -> { throw new IllegalStateException("close failed"); }))) {
            assertTrue(runner.submit(STATE, LOGS, manager -> snapshot, result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertSame(snapshot, result.get().snapshot());
            assertEquals("close failed", result.get().failure().getMessage());
            assertTrue(AssetMigrateApplication.failureText(result.get().failure(), snapshot).contains("cleanup failed"));
        }
    }

    @Test
    void preservesImportFailureAndClosesManager() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Outcome> result = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        UUID id = UUID.randomUUID();
        ImportException failure = new ImportException("completion failed", new IllegalStateException("log unavailable"),
                Optional.of(id), Optional.of(2L));
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) ->
                new ImportRunner.Managed(null, () -> closed.set(true)))) {
            assertTrue(runner.submit(STATE, LOGS, manager -> { throw failure; }, result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertTrue(closed.get());
            assertSame(failure, result.get().failure());
            assertTrue(AssetMigrateApplication.failureText(failure, null).contains("Cause: log unavailable"));
        }
    }

    @Test
    void shutdownLetsRunningOperationCloseButSuppressesUiCallback() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean();
        ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> new ImportRunner.Managed(null, closed::countDown));
        try {
            assertTrue(runner.submit(STATE, LOGS, manager -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return snapshot();
            }, outcome -> called.set(true)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runner.close();
            assertFalse(runner.submit(STATE, LOGS, manager -> snapshot(), outcome -> { }));
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertFalse(called.get());
        } finally {
            release.countDown();
            runner.close();
        }
    }

    @Test
    void rendersRecoveryAndCoverageWithoutStartingJavaFx() {
        UUID project = UUID.randomUUID();
        ImportException failure = new ImportException("completion log failed", null, Optional.of(project), Optional.of(3L));
        String message = AssetMigrateApplication.failureText(failure, null);
        assertTrue(message.contains(project.toString()));
        assertTrue(message.contains("Revision: 3"));
        assertTrue(message.contains("not rolled back"));
        String details = AssetMigrateApplication.formatFindings(snapshot());
        assertTrue(details.contains("WARNING [UNSUPPORTED] file.xyz: Not supported"));
        assertTrue(details.contains("Coverage UNVISITED DIRECTORY: missing"));
    }

    @Test
    void analysisAndReviewTasksRunOffCallerThreadAndRetainResultsOnCloseFailure() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<ImportRunner.Result<List<String>>> result = new AtomicReference<>();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) ->
                new ImportRunner.Managed(null, () -> { throw new IllegalStateException("close failed"); }))) {
            assertTrue(runner.submitValue(STATE, LOGS, manager -> {
                worker.set(Thread.currentThread());
                return List.of("saved");
            }, result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertNotSame(Thread.currentThread(), worker.get());
            assertEquals(List.of("saved"), result.get().value());
            assertEquals("close failed", result.get().failure().getMessage());
        }
    }

    @Test
    void reviewRequiresExplicitDecisionAndEncodesEditedValues() {
        UUID asset = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        var category = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.CATEGORIES, "old");
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(category, null, "new", ""));
        ReviewedChange accepted = AssetMigrateApplication.makeReview(category, ReviewedChange.Decision.ACCEPTED, " new ", "");
        assertEquals(asset, accepted.assetId());
        assertEquals(ReviewedChange.Kind.CATEGORY, accepted.kind());
        assertEquals(Map.of("category", "new"), accepted.values());
        var relationship = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.RELATIONSHIPS, "unused");
        ReviewedChange rejected = AssetMigrateApplication.makeReview(relationship, ReviewedChange.Decision.REJECTED, target.toString(), "");
        assertEquals(ReviewedChange.Decision.REJECTED, rejected.decision());
        assertEquals(Map.of("targetAssetId", target.toString()), rejected.values());
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(relationship, ReviewedChange.Decision.ACCEPTED, "bad UUID", ""));
        var material = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.MATERIALS, "unused");
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(material, ReviewedChange.Decision.ACCEPTED, target.toString(), " "));
        assertEquals(Map.of("targetAssetId", target.toString(), "reference", "textures/a.png"),
                AssetMigrateApplication.makeReview(material, ReviewedChange.Decision.ACCEPTED, target.toString(), "textures/a.png").values());
    }

    @Test
    void formatsAnalysisFindingsWithAssetContext() {
        UUID asset = UUID.randomUUID();
        Finding finding = new Finding(UUID.randomUUID(), Optional.of(asset), Optional.of(Path.of("a.obj")),
                Finding.Severity.WARNING, "MISSING", "Missing reference");
        String text = AssetMigrateApplication.formatAnalysisFindings(List.of(finding));
        assertTrue(text.contains("WARNING [MISSING] a.obj"));
        assertTrue(text.contains(asset.toString()));
        assertTrue(text.contains("Missing reference"));
        assertEquals("No analysis findings.", AssetMigrateApplication.formatAnalysisFindings(List.of()));
    }

    private static InventorySnapshot snapshot() {
        Instant now = Instant.parse("2025-01-01T00:00:00Z");
        Project project = new Project(UUID.randomUUID(), Path.of("/source"), 1, List.of(), now);
        Finding finding = new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(Path.of("file.xyz")),
                Finding.Severity.WARNING, "UNSUPPORTED", "Not supported");
        PathOutcome outcome = new PathOutcome(Path.of("missing"), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.UNVISITED);
        return new InventorySnapshot(1, 1, project, List.of(finding), InventorySnapshot.ScanStatus.PARTIAL,
                now, List.of(outcome), List.of());
    }
}
