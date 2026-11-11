package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCommandsTest {
    private final UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), runId = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");

    private interface Handler { Object call(String name, Object[] args); }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class},
                (proxy, method, args) -> handler.call(method.getName(), args));
    }

    private Report report(Report.ValidationStatus status, Optional<String> ai) {
        return new Report(UUID.randomUUID(), project, planId, runId, RunStatus.SUCCESS, status,
                List.of(new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(Path.of("hero.obj")),
                        Finding.Severity.ERROR, "OUTPUT_CHANGED", "bad\u001bmessage")),
                "Validation " + status, ai, time);
    }

    private int run(Object command, StringWriter out, StringWriter err, String... args) {
        return new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
    }

    @Test void validateByRunPrintsTheReportAndExitsZeroOnlyWhenPassed() {
        for (var status : Report.ValidationStatus.values()) {
            List<String> calls = new ArrayList<>();
            StringWriter out = new StringWriter(), err = new StringWriter();
            int exit = run(new ValidationCommands.Validate((s, l) -> manager((name, args) -> {
                calls.add(name);
                if (name.equals("close")) return null;
                assertEquals("validate", name);
                assertEquals(1, args.length, "no AI unless requested");
                assertEquals(runId, args[0]);
                return report(status, Optional.empty());
            }), () -> { throw new AssertionError("AI not requested"); }), out, err,
                    "--run", runId.toString(), "--state-directory", "s", "--log-directory", "l");
            assertEquals(status == Report.ValidationStatus.PASSED ? 0 : 1, exit, err.toString());
            assertEquals(List.of("validate", "close"), calls);
            String text = out.toString();
            assertTrue(text.contains("Validation status: " + status), text);
            assertTrue(text.contains("ERROR OUTPUT_CHANGED Source: hero.obj: bad\\u001bmessage"), "findings are shown and escaped");
            assertFalse(text.contains("AI summary"));
        }
    }

    @Test void validateByPlanResolvesItsRunAndAiIsLabelledAdvisory() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        LLMClient model = new LLMClient() {
            @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) { throw new AssertionError(); }
            @Override public String description() { return "fake"; }
        };
        int exit = run(new ValidationCommands.Validate((s, l) -> manager((name, args) -> switch (name) {
            case "close" -> null;
            case "loadRun" -> {
                assertEquals(planId, args[0]);
                yield new MigrationRun(runId, project, planId, 1, Path.of("out").toAbsolutePath(), RunStatus.SUCCESS,
                        Map.of(), time, Optional.of(time), Optional.of(time));
            }
            case "validate" -> {
                assertEquals(runId, args[0]);
                assertSame(model, args[1]);
                yield report(Report.ValidationStatus.PASSED, Optional.of("Looks fine."));
            }
            default -> throw new AssertionError(name);
        }), () -> model), out, err, "--plan", planId.toString(), "--ai", "--state-directory", "s", "--log-directory", "l");
        assertEquals(0, exit, err.toString());
        assertTrue(out.toString().contains("AI summary (advisory; the status above is authoritative): Looks fine."), out.toString());
    }

    @Test void validateRequiresExactlyOneTarget() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        var validate = new ValidationCommands.Validate((s, l) -> { throw new AssertionError("manager not created"); });
        assertNotEquals(0, run(validate, out, err, "--state-directory", "s", "--log-directory", "l"));
        assertNotEquals(0, run(new ValidationCommands.Validate((s, l) -> { throw new AssertionError(); }), out, err,
                "--run", runId.toString(), "--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"));
    }

    @Test void historyReportsMissingHistoryAndListsReports() {
        StringWriter empty = new StringWriter(), err = new StringWriter();
        assertEquals(0, run(new ValidationCommands.History((s, l) -> manager((name, args) ->
                name.equals("history") ? List.of() : null)), empty, err,
                "--project", project.toString(), "--state-directory", "s", "--log-directory", "l"));
        assertTrue(empty.toString().contains("No validation history for project " + project), empty.toString());

        StringWriter out = new StringWriter();
        var first = report(Report.ValidationStatus.FAILED, Optional.empty());
        var second = report(Report.ValidationStatus.PASSED, Optional.empty());
        assertEquals(0, run(new ValidationCommands.History((s, l) -> manager((name, args) ->
                name.equals("history") ? List.of(first, second) : null)), out, err,
                "--project", project.toString(), "--state-directory", "s", "--log-directory", "l"));
        String text = out.toString();
        assertTrue(text.contains("Validation reports (2):"), text);
        assertTrue(text.indexOf(first.id().toString()) < text.indexOf(second.id().toString()), "oldest first");
        assertFalse(text.contains("OUTPUT_CHANGED"), "findings only with --details");
    }

    @Test void failuresAreReported() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(1, run(new ValidationCommands.Validate((s, l) -> manager((name, args) -> {
            if (name.equals("validate")) throw new java.io.UncheckedIOException(new java.io.IOException("Run not found"));
            return null;
        })), out, err, "--run", runId.toString(), "--state-directory", "s", "--log-directory", "l"));
        assertTrue(err.toString().contains("Command failed:"), err.toString());
    }
}
