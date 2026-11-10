package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    void organizationRulesAreCapturedAndSubmittedOnWorker() throws Exception {
        var defaults = AssetMigrateApplication.DEFAULT_ORGANIZATION_RULES;
        assertEquals(AssetManager.NameStyle.PRESERVE, defaults.nameStyle());
        assertEquals(AssetManager.FolderStyle.PRESERVE, defaults.folderStyle());
        UUID project = UUID.randomUUID();
        for (var rules : List.of(defaults, new AssetManager.OrganizationRules(
                AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.BY_TYPE))) {
            var operation = AssetMigrateApplication.organizationAnalysis(rules);
            var analysis = new AssetManager.AnalysisResult(17L, List.of(), List.of());
            AtomicReference<Object[]> arguments = new AtomicReference<>();
            AtomicReference<Thread> thread = new AtomicReference<>();
            AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                    new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                        assertEquals("analyzeOrganization", method.getName());
                        arguments.set(args);
                        thread.set(Thread.currentThread());
                        return analysis;
                    });
            BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
            AtomicReference<ImportRunner.Result<AssetManager.AnalysisResult>> result = new AtomicReference<>();
            try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> new ImportRunner.Managed(manager, () -> { }))) {
                assertTrue(runner.submitValue(STATE, LOGS, instance -> operation.apply(instance, project), result::set));
                Runnable completion = ui.poll(5, TimeUnit.SECONDS);
                assertNotNull(completion);
                completion.run();
                assertNull(result.get().failure());
                assertSame(analysis, result.get().value());
                assertArrayEquals(new Object[]{project, rules}, arguments.get());
                assertNotSame(Thread.currentThread(), thread.get());
                var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS, result.get().value());
                assertEquals("Analysis inventory revision: 17", context.revisionText());
            }
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
        assertEquals(Map.of("targetAssetId", target.toString(), "reference", "models/original.obj"),
                AssetMigrateApplication.makeReview(relationship, ReviewedChange.Decision.ACCEPTED,
                        target.toString(), "models/original.obj").values());
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(relationship, ReviewedChange.Decision.ACCEPTED, "bad UUID", ""));
        var material = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.MATERIALS, "unused");
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(material, ReviewedChange.Decision.ACCEPTED, target.toString(), " "));
        assertEquals(Map.of("targetAssetId", target.toString(), "reference", "textures/a.png"),
                AssetMigrateApplication.makeReview(material, ReviewedChange.Decision.ACCEPTED, target.toString(), "textures/a.png").values());
    }

    @Test
    void manualReviewsRequireExplicitInputsAndEncodeAllKinds() {
        UUID asset = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String id = asset.toString();
        for (ReviewedChange.Decision decision : ReviewedChange.Decision.values()) {
            var category = AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.CATEGORY, decision, " Props ", "");
            assertEquals(asset, category.assetId());
            assertEquals(decision, category.decision());
            assertEquals(Map.of("category", "Props"), category.values());
            assertEquals(Map.of("targetAssetId", target.toString()), AssetMigrateApplication.makeReview(id,
                    ReviewedChange.Kind.RELATIONSHIP, decision, target.toString(), "").values());
            assertEquals(Map.of("targetAssetId", target.toString(), "reference", "missing/material"),
                    AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.MATERIAL_MAPPING, decision,
                            target.toString(), "missing/material").values());
            assertEquals(Map.of("reason", "Not needed"), AssetMigrateApplication.makeReview(id,
                    ReviewedChange.Kind.EXCLUSION, decision, " Not needed ", "").values());
        }
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview("bad", ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED, "x", ""));
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(id, null, ReviewedChange.Decision.ACCEPTED, "x", ""));
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.EXCLUSION, null, "x", ""));
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED, " ", ""));
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Decision.REJECTED, target.toString(), " "));
        assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(id, ReviewedChange.Kind.RELATIONSHIP, ReviewedChange.Decision.ACCEPTED, "bad", ""));
    }

    @Test
    void manualReviewUsesAnalysisRevisionOnBackgroundWorker() throws Exception {
        UUID project = UUID.randomUUID();
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS,
                new AssetManager.AnalysisResult(13L, List.of(), List.of()));
        var change = AssetMigrateApplication.makeReview(UUID.randomUUID().toString(), ReviewedChange.Kind.EXCLUSION,
                ReviewedChange.Decision.REJECTED, "Keep asset", "");
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        AtomicReference<Thread> thread = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                    thread.set(Thread.currentThread());
                    arguments.set(args);
                    return List.of(change);
                });
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Result<List<ReviewedChange>>> result = new AtomicReference<>();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> new ImportRunner.Managed(manager, () -> { }))) {
            assertTrue(runner.submitValue(STATE, LOGS, instance -> context.review(instance, change), result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertNull(result.get().failure());
            assertEquals(List.of(change), result.get().value());
            assertArrayEquals(new Object[]{project, 13L, List.of(change)}, arguments.get());
            assertNotSame(Thread.currentThread(), thread.get());
        }
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

    @Test
    void analysisContextDisplaysAndSubmitsTheReturnedRevision() {
        UUID project = UUID.randomUUID();
        var suggestion = new AssetManager.Suggestion(UUID.randomUUID(), UUID.randomUUID(),
                AssetManager.AnalysisKind.CATEGORIES, "Characters");
        var analysis = new AssetManager.AnalysisResult(7L, List.of(suggestion), List.of());
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS, analysis);
        var change = AssetMigrateApplication.makeReview(suggestion, ReviewedChange.Decision.ACCEPTED, "Characters", "");
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                    assertEquals("review", method.getName());
                    arguments.set(args);
                    return List.of(change);
                });

        assertEquals("Analysis inventory revision: 7", context.revisionText());
        assertEquals(List.of(change), context.review(manager, change));
        assertArrayEquals(new Object[]{project, 7L, List.of(change)}, arguments.get());
    }

    @Test
    void loadsAndFormatsRevisionCheckedReviewsOnWorker() throws Exception {
        UUID project = UUID.randomUUID();
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS,
                new AssetManager.AnalysisResult(1L, List.of(), List.of()));
        var change = AssetMigrateApplication.makeReview(UUID.randomUUID().toString(), ReviewedChange.Kind.EXCLUSION,
                ReviewedChange.Decision.REJECTED, "Keep asset", "");
        List<String> calls = new ArrayList<>();
        AtomicReference<Thread> thread = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                    assertArrayEquals(new Object[]{project}, args);
                    calls.add(method.getName());
                    thread.set(Thread.currentThread());
                    return method.getName().equals("loadInventory") ? snapshot() : List.of(change);
                });
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Result<List<ReviewedChange>>> result = new AtomicReference<>();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> new ImportRunner.Managed(manager, () -> { }))) {
            assertTrue(runner.submitValue(STATE, LOGS, context::loadReviews, result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertNull(result.get().failure());
            assertEquals(List.of(change), result.get().value());
            assertEquals(List.of("loadInventory", "loadReviews", "loadInventory"), calls);
            assertNotSame(Thread.currentThread(), thread.get());
            String display = AssetMigrateApplication.formatReviews(result.get().value());
            assertTrue(display.contains(change.assetId().toString()));
            assertTrue(display.contains("EXCLUSION"));
            assertTrue(display.contains("REJECTED"));
            assertTrue(display.contains("reason=Keep asset"));
            assertTrue(AssetMigrateApplication.formatReviews(List.of()).contains("No saved reviews"));
        }
    }

    @Test
    void rejectsReviewLoadingWhenEitherInventoryReadDiffers() {
        UUID project = UUID.randomUUID();
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS,
                new AssetManager.AnalysisResult(1L, List.of(), List.of()));
        for (boolean staleBefore : List.of(true, false)) {
            List<String> calls = new ArrayList<>();
            AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                    new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        if (method.getName().equals("loadReviews")) return List.of();
                        return snapshot(staleBefore || calls.size() == 3 ? 2 : 1);
                    });
            assertThrows(IllegalStateException.class, () -> context.loadReviews(manager));
            assertEquals(staleBefore ? List.of("loadInventory") : List.of("loadInventory", "loadReviews", "loadInventory"), calls);
        }
    }

    @Test
    void nameAndFolderReviewsSupportSuggestionsAndRootFolder() {
        UUID asset = UUID.randomUUID();
        for (ReviewedChange.Decision decision : ReviewedChange.Decision.values()) {
            var name = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.NAMES, "old.obj");
            var folder = new AssetManager.Suggestion(UUID.randomUUID(), asset, AssetManager.AnalysisKind.FOLDERS, "");
            var named = AssetMigrateApplication.makeReview(name, decision, "new.obj", "");
            var root = AssetMigrateApplication.makeReview(folder, decision, "", "");
            assertEquals(ReviewedChange.Kind.NAME, named.kind());
            assertEquals(Map.of("name", "new.obj"), named.values());
            assertEquals(ReviewedChange.Kind.FOLDER, root.kind());
            assertEquals(Map.of("folder", ""), root.values());
            assertEquals(decision, root.decision());
            assertEquals(Map.of("folder", "models/props"), AssetMigrateApplication.makeReview(asset.toString(),
                    ReviewedChange.Kind.FOLDER, decision, "models/props", "").values());
            assertThrows(IllegalArgumentException.class, () -> AssetMigrateApplication.makeReview(name, decision, "", ""));
        }
    }

    @Test
    void previewRunsOnWorkerWithAnalyzedRevisionAndFormatsAllEntriesAndFindings() throws Exception {
        UUID project = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var preview = new OrganizationPreview.Result(project, 9L, List.of(
                new OrganizationPreview.Entry(first, Path.of("a.obj"), Optional.of("models/a.obj"), false),
                new OrganizationPreview.Entry(second, Path.of("b.obj"), Optional.empty(), true)), List.of(
                new OrganizationPreview.Finding("COLLISION", Optional.of(first), "Destination collision"),
                new OrganizationPreview.Finding("PARTIAL", Optional.empty(), "Incomplete inventory")));
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS,
                new AssetManager.AnalysisResult(9L, List.of(), List.of()));
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                    assertEquals("previewOrganization", method.getName());
                    thread.set(Thread.currentThread());
                    arguments.set(args);
                    return preview;
                });
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Result<OrganizationPreview.Result>> result = new AtomicReference<>();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) -> new ImportRunner.Managed(manager, () -> { }))) {
            assertTrue(runner.submitValue(STATE, LOGS, context::preview, result::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertNull(result.get().failure());
            assertSame(preview, result.get().value());
            assertArrayEquals(new Object[]{project, 9L}, arguments.get());
            assertNotSame(Thread.currentThread(), thread.get());
            String text = AssetMigrateApplication.formatPreview(preview);
            for (String expected : List.of("not approval", "BLOCKED", first.toString(), second.toString(),
                    "a.obj", "b.obj", "models/a.obj", "(none)", "Excluded: true", "COLLISION", "PARTIAL", "Incomplete inventory")) {
                assertTrue(text.contains(expected), expected);
            }
        }
    }

    @Test
    void previewRejectsWrongProjectOrRevisionAndRendersUnblocked() {
        UUID project = UUID.randomUUID();
        var context = new AssetMigrateApplication.AnalysisContext(project, STATE, LOGS,
                new AssetManager.AnalysisResult(9L, List.of(), List.of()));
        for (var preview : List.of(new OrganizationPreview.Result(UUID.randomUUID(), 9L, List.of(), List.of()),
                new OrganizationPreview.Result(project, 10L, List.of(), List.of()))) {
            AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                    new Class<?>[]{AssetManager.class}, (proxy, method, args) -> preview);
            assertThrows(IllegalStateException.class, () -> context.preview(manager));
        }
        String text = AssetMigrateApplication.formatPreview(new OrganizationPreview.Result(project, 9L, List.of(), List.of()));
        assertTrue(text.contains("No blocking findings"));
        assertTrue(text.contains("None"));
    }

    @Test
    void compatibilityLoadsTargetsAndAssessesOnWorker() throws Exception {
        UUID project = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        var target = new AssetManager.TargetProfile("engine", "1.2");
        var finding = new Finding(UUID.randomUUID(), Optional.of(asset), Optional.of(Path.of("models/a.obj")),
                Finding.Severity.WARNING, "FORMAT", "Unsupported format");
        var assessment = new AssetManager.CompatibilityResult(7L, target, List.of(finding));
        var request = new AssetMigrateApplication.CompatibilityRequest(project, target);
        List<String> calls = new ArrayList<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) -> {
                    worker.set(Thread.currentThread());
                    calls.add(method.getName());
                    if (method.getName().equals("supportedTargets")) return List.of(target);
                    assertArrayEquals(new Object[]{project, target}, args);
                    return assessment;
                });
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicReference<ImportRunner.Result<List<AssetManager.TargetProfile>>> targets = new AtomicReference<>();
        AtomicReference<ImportRunner.Result<AssetManager.CompatibilityResult>> result = new AtomicReference<>();
        try (ImportRunner runner = new ImportRunner(ui::add, (state, logs) ->
                new ImportRunner.Managed(manager, () -> { throw new IllegalStateException("close failed"); }))) {
            assertTrue(runner.submitValue(STATE, LOGS, AssetManager::supportedTargets, targets::set));
            Runnable completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertEquals(List.of(target), targets.get().value());
            assertEquals("close failed", targets.get().failure().getMessage());
            assertTrue(runner.submitValue(STATE, LOGS, request::assess, result::set));
            completion = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertSame(assessment, result.get().value());
            assertEquals("close failed", result.get().failure().getMessage());
            assertEquals(List.of("supportedTargets", "assessCompatibility"), calls);
            assertNotSame(Thread.currentThread(), worker.get());
        }
        String text = AssetMigrateApplication.formatCompatibility(assessment);
        for (String expected : List.of("not full validation or approval", "revision: 7", "engine / 1.2",
                "WARNING [FORMAT]", "models/a.obj", asset.toString(), "Unsupported format")) {
            assertTrue(text.contains(expected), expected);
        }
        String empty = AssetMigrateApplication.formatCompatibility(new AssetManager.CompatibilityResult(8L, target, List.of()));
        assertTrue(empty.contains("No compatibility findings"));
        assertTrue(empty.contains("not full validation or approval"));
    }

    @Test
    void compatibilityRejectsMismatchedTargetAndInvalidatesStaleCallbacks() {
        var target = new AssetManager.TargetProfile("engine", "1");
        var request = new AssetMigrateApplication.CompatibilityRequest(UUID.randomUUID(), target);
        AssetManager manager = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class}, (proxy, method, args) ->
                        new AssetManager.CompatibilityResult(1L, new AssetManager.TargetProfile("engine", "2"), List.of()));
        assertThrows(IllegalStateException.class, () -> request.assess(manager));
        var generation = new AssetMigrateApplication.CompatibilityGeneration();
        long captured = generation.current();
        assertTrue(generation.isCurrent(captured));
        generation.invalidate();
        assertFalse(generation.isCurrent(captured));
        assertTrue(generation.isCurrent(generation.current()));
    }

    private static InventorySnapshot snapshot() {
        return snapshot(1);
    }

    private static InventorySnapshot snapshot(long revision) {
        Instant now = Instant.parse("2025-01-01T00:00:00Z");
        Project project = new Project(UUID.randomUUID(), io.github.aiassetmigrate.TestPaths.absolute("source"), revision, List.of(), now);
        Finding finding = new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(Path.of("file.xyz")),
                Finding.Severity.WARNING, "UNSUPPORTED", "Not supported");
        PathOutcome outcome = new PathOutcome(Path.of("missing"), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.UNVISITED);
        return new InventorySnapshot(1, 1, project, List.of(finding), InventorySnapshot.ScanStatus.PARTIAL,
                now, List.of(outcome), List.of());
    }
}
