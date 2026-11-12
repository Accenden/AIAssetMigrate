package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** UC07/UC08: the facade's migrate-then-validate coordination, independent of storage. */
class MigrateAndValidateTest {
    private final UUID project = UUID.randomUUID(), planId = UUID.randomUUID();
    private final Path output = io.github.aiassetmigrate.TestPaths.absolute("output");
    private final MigrationRun finished = run(RunStatus.SUCCESS);
    private final List<String> calls = new ArrayList<>();
    /** Scripted results per facade method, consumed in call order; a Throwable is thrown. */
    private final Map<String, Deque<Object>> script = Map.of("loadRun", new ArrayDeque<>(), "migrate", new ArrayDeque<>(),
            "validate", new ArrayDeque<>());

    private MigrationRun run(RunStatus status) {
        return new MigrationRun(UUID.randomUUID(), project, planId, 1, output, status, Map.of(), Instant.EPOCH,
                Optional.of(Instant.EPOCH), status == RunStatus.INCOMPLETE ? Optional.empty() : Optional.of(Instant.EPOCH));
    }

    private Report report(MigrationRun run, Report.ValidationStatus status) {
        return new Report(UUID.randomUUID(), project, planId, run.id(), run.status(), status, List.of(), "summary",
                Optional.empty(), Instant.EPOCH);
    }

    private void then(String method, Object... results) {
        script.get(method).addAll(List.of(results));
    }

    private AssetManager manager() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("migrateAndValidate")) return InvocationHandler.invokeDefault(proxy, method, args);
            calls.add(method.getName() + "/" + args.length);
            if (!method.getName().equals("validate")) assertEquals(planId, args[0], "plan ID is passed through");
            Deque<Object> results = script.get(method.getName());
            if (results == null || results.isEmpty()) throw new AssertionError("Unexpected call: " + method.getName());
            Object result = results.removeFirst();
            if (result instanceof RuntimeException failure) throw failure;
            return result;
        };
        return (AssetManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AssetManager.class}, handler);
    }

    private static UncheckedIOException notStarted() {
        return new UncheckedIOException("Cannot load run", new java.nio.file.NoSuchFileException("started.json"));
    }

    @Test void finishedRunIsValidatedAndItsReportReturned() {
        Report passed = report(finished, Report.ValidationStatus.PASSED);
        then("loadRun", notStarted());
        then("migrate", finished);
        then("validate", passed);
        var outcome = manager().migrateAndValidate(planId, Optional.empty());
        assertEquals(new AssetManager.MigrationOutcome(finished, Optional.of(passed), Optional.empty(), Optional.empty()), outcome);
        assertEquals(List.of("loadRun/1", "migrate/1", "validate/1"), calls);
    }

    @Test void blockedMigrationThrowsAndValidatesNothing() {
        var blocked = new UncheckedIOException("Cannot execute plan: Plan inventory is stale", new IOException("stale"));
        then("loadRun", notStarted(), notStarted());
        then("migrate", blocked);
        assertSame(blocked, assertThrows(UncheckedIOException.class, () -> manager().migrateAndValidate(planId, Optional.empty())));
        assertEquals(List.of("loadRun/1", "migrate/1", "loadRun/1"), calls);
    }

    @Test void consumedPlanDoesNotRevalidateItsEarlierRun() {
        var consumed = new UncheckedIOException("Cannot execute plan: Plan already consumed", new IOException("consumed"));
        then("loadRun", finished);
        then("migrate", consumed);
        assertSame(consumed, assertThrows(UncheckedIOException.class, () -> manager().migrateAndValidate(planId, Optional.empty())));
        assertEquals(List.of("loadRun/1", "migrate/1"), calls, "the earlier run is neither reloaded nor validated");
    }

    @Test void interruptedRunIsStillValidatedSoAnIncompleteReportExists() {
        MigrationRun interrupted = run(RunStatus.INCOMPLETE);
        Report incomplete = report(interrupted, Report.ValidationStatus.INCOMPLETE);
        then("loadRun", notStarted(), interrupted);
        then("migrate", new UncheckedIOException("Cannot execute plan " + planId + ": Source files changed",
                new IOException("Source files changed")));
        then("validate", incomplete);
        var outcome = manager().migrateAndValidate(planId, Optional.empty());
        assertEquals(interrupted, outcome.run());
        assertEquals(Optional.of(incomplete), outcome.report());
        assertEquals(Optional.of("Cannot execute plan " + planId + ": Source files changed"), outcome.migrationFailure());
        assertEquals(List.of("loadRun/1", "migrate/1", "loadRun/1", "validate/1"), calls);
    }

    @Test void validationFailureKeepsTheRunOutcome() {
        then("loadRun", notStarted());
        then("migrate", finished);
        then("validate", new UncheckedIOException(new IOException("Another writer holds the project lock")));
        var outcome = manager().migrateAndValidate(planId, Optional.empty());
        assertEquals(finished, outcome.run());
        assertTrue(outcome.report().isEmpty());
        assertTrue(outcome.migrationFailure().isEmpty());
        assertTrue(outcome.validationFailure().orElseThrow().contains("Another writer"), outcome.toString());
    }

    @Test void assistantSelectsTheAiValidationOverload() {
        LLMClient model = new LLMClient() {
            @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) { throw new AssertionError(); }
            @Override public String description() { return "fake"; }
        };
        then("loadRun", notStarted());
        then("migrate", finished);
        then("validate", report(finished, Report.ValidationStatus.PASSED));
        assertTrue(manager().migrateAndValidate(planId, Optional.of(model)).report().isPresent());
        assertEquals(List.of("loadRun/1", "migrate/1", "validate/2"), calls);
    }

    @Test void outcomeRequiresExactlyOneOfReportOrFailureForTheSameRun() {
        Report report = report(finished, Report.ValidationStatus.PASSED);
        assertThrows(IllegalArgumentException.class,
                () -> new AssetManager.MigrationOutcome(finished, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetManager.MigrationOutcome(finished, Optional.of(report), Optional.empty(), Optional.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new AssetManager.MigrationOutcome(run(RunStatus.SUCCESS),
                Optional.of(report), Optional.empty(), Optional.empty()));
    }
}
