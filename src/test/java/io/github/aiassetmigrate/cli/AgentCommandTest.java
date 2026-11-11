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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** CLI wiring for F09 'command' and the --ai options of compatibility and plan. */
class AgentCommandTest {
    private final UUID project = UUID.randomUUID();
    private final LLMClient model = new LLMClient() {
        @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) { throw new AssertionError("not called by the CLI"); }
        @Override public String description() { return "fake"; }
    };

    private interface Handler { Object call(String name, Object[] args); }

    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class},
                (proxy, method, args) -> handler.call(method.getName(), args));
    }

    private static Finding finding(String code) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.INFO, code, "m\u001b");
    }

    @Test void commandJoinsTheRequestAndPrintsTheResult() {
        List<Object[]> calls = new ArrayList<>();
        StringWriter out = new StringWriter(), err = new StringWriter();
        var cli = new CommandLine(new AgentCommand((s, l) -> manager((name, args) -> {
            if (name.equals("close")) return null;
            assertEquals("command", name);
            calls.add(args);
            return new AssetManager.CommandResult("Supported targets: unity 6000.0.", Optional.empty(), List.of(finding("AI_SOURCE")));
        }), () -> model)).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(0, cli.execute("--project", project.toString(), "--state-directory", "s", "--log-directory", "l",
                "which", "targets", "exist?"), err.toString());
        assertEquals(1, calls.size());
        assertEquals(project, calls.getFirst()[0]);
        assertEquals("which targets exist?", calls.getFirst()[1]);
        assertSame(model, calls.getFirst()[2]);
        assertTrue(out.toString().contains("Supported targets: unity 6000.0."));
        assertTrue(out.toString().contains("INFO AI_SOURCE m\\u001b"), "findings are escaped");
    }

    @Test void unavailableAccountStillReachesTheManagerAndExitsNonZero() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        var cli = new CommandLine(new AgentCommand((s, l) -> manager((name, args) -> {
            if (name.equals("close")) return null;
            assertEquals("unavailable", ((LLMClient) args[2]).description());
            return new AssetManager.CommandResult("Could not interpret", Optional.empty(), List.of(finding("AI_UNAVAILABLE")));
        }), () -> { throw new LLMClient.AiException(LLMClient.AiException.Reason.NOT_CONNECTED, "Not connected"); }))
                .setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(1, cli.execute("--project", project.toString(), "--state-directory", "s", "--log-directory", "l", "hi"));
        assertNotEquals(0, new CommandLine(new AgentCommand((s, l) -> { throw new AssertionError(); }, () -> model))
                .setErr(new PrintWriter(new StringWriter())).execute("--project", project.toString(), "--state-directory", "s", "--log-directory", "l"),
                "a request is required");
    }

    @Test void compatibilityAndPlanUseTheModelOnlyWithAi() {
        var target = new AssetManager.TargetProfile("unity", "6000.0");
        for (boolean ai : List.of(false, true)) {
            List<Integer> arity = new ArrayList<>();
            var compatibility = new CompatibilityCommand((s, l) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                assertEquals("assessCompatibility", name);
                arity.add(args.length);
                return new AssetManager.CompatibilityResult(1, target, List.of(finding(ai ? "AI_EXPLANATION" : "RULE")));
            }), () -> model);
            StringWriter out = new StringWriter();
            List<String> args = new ArrayList<>(List.of("--project", project.toString(), "--target", "unity", "--target-version",
                    "6000.0", "--state-directory", "s", "--log-directory", "l"));
            if (ai) args.add("--ai");
            assertEquals(0, new CommandLine(compatibility).setOut(new PrintWriter(out)).execute(args.toArray(String[]::new)));
            assertEquals(List.of(ai ? 3 : 2), arity);
            assertTrue(out.toString().contains(ai ? "AI_EXPLANATION" : "RULE"));
        }
        assertEquals(2, new CommandLine(new CompatibilityCommand((s, l) -> { throw new AssertionError(); }, () -> model))
                .setErr(new PrintWriter(new StringWriter()))
                .execute("--list-targets", "--ai", "--state-directory", "s", "--log-directory", "l"), "--ai needs an assessment");

        UUID planId = UUID.randomUUID(), asset = UUID.randomUUID();
        var plan = new MigrationPlan(planId, project, 1, 1, "unity", "6000.0", Path.of("out").toAbsolutePath(), List.of(),
                List.of(), List.of(finding("AI_PLAN_PROPOSAL")), PlanStatus.DRAFT, Instant.parse("2026-01-01T00:00:00Z"));
        List<Integer> planArity = new ArrayList<>();
        var planCommand = new PlanCommands.Plan((s, l) -> manager((name, args) -> switch (name) {
            case "close" -> null;
            case "createPlan" -> { planArity.add(args.length); assertSame(model, args[3]); yield plan; }
            case "loadInventory" -> new InventorySnapshot(1, 1, new Project(project, Path.of("src").toAbsolutePath(), 1,
                    List.of(new Asset(asset, Path.of("a.obj"), Asset.Type.MODEL, "0".repeat(64), java.util.Map.of())), plan.createdAt()),
                    List.of(), InventorySnapshot.ScanStatus.COMPLETE, plan.createdAt(), List.of(), List.of());
            default -> throw new AssertionError(name);
        }), () -> model);
        StringWriter planOut = new StringWriter();
        assertEquals(0, new CommandLine(planCommand).setOut(new PrintWriter(planOut)).execute("--project", project.toString(),
                "--target", "unity", "--target-version", "6000.0", "--output", "out", "--ai", "--state-directory", "s", "--log-directory", "l"));
        assertEquals(List.of(4), planArity);
        assertTrue(planOut.toString().contains("AI_PLAN_PROPOSAL"));
    }
}
