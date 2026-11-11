package io.github.aiassetmigrate.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F05/F06 AI assistance and F09 natural-language commands through the real facade. */
class AgentWorkflowIntegrationTest {
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    @TempDir Path directory;
    private final Deque<String> replies = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();

    private final LLMClient model = new LLMClient() {
        @Override public JsonNode generate(String prompt) throws AiException {
            prompts.add(prompt);
            String reply = replies.poll();
            if (reply == null) throw new AiException(AiException.Reason.UNAVAILABLE, "no scripted reply");
            try { return new ObjectMapper().readTree(reply); }
            catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "bad"); }
        }
        @Override public String description() { return "scripted model"; }
    };

    private Path source, output;

    private UUID importChain(DefaultAssetManager manager) throws Exception {
        source = Files.createDirectory(directory.resolve("source"));
        output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd hero.png\n");
        Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
        return manager.importAssets(source).snapshot().project().id();
    }

    private AssetManager.CommandResult ask(DefaultAssetManager manager, UUID project, String request, String reply) {
        replies.add(reply);
        return manager.command(project, request, model);
    }

    private static Set<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).collect(Collectors.toSet());
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test void naturalLanguageRequestsDispatchAllowlistedActionsAndNeverApprove() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);

            var noModel = manager.command(project, "show my inventory");
            assertTrue(codes(noModel.findings()).contains("AI_UNAVAILABLE"));
            assertTrue(noModel.message().contains("no action was taken"));

            var inventory = ask(manager, project, "what did I import?", "{\"action\":\"SHOW_INVENTORY\",\"parameters\":{}}");
            assertTrue(inventory.message().contains("3 asset(s)"), inventory.message());
            assertTrue(prompts.getLast().contains("what did I import?"));
            assertFalse(prompts.getLast().contains(source.toString()), "no absolute paths in the prompt");

            var compatibility = ask(manager, project, "is this ok for unity?",
                    "{\"action\":\"CHECK_COMPATIBILITY\",\"parameters\":{\"target\":\"Unity\",\"version\":\"6000.0\"}}");
            assertTrue(compatibility.message().startsWith("Compatibility with unity 6000.0"), compatibility.message());

            var unknownTarget = ask(manager, project, "check unreal",
                    "{\"action\":\"CHECK_COMPATIBILITY\",\"parameters\":{\"target\":\"unreal\",\"version\":\"5\"}}");
            assertTrue(codes(unknownTarget.findings()).contains("COMMAND_CLARIFICATION"));
            assertTrue(unknownTarget.message().contains("unity 6000.0"), unknownTarget.message());

            var noOutput = ask(manager, project, "make a plan for unity",
                    "{\"action\":\"CREATE_PLAN\",\"parameters\":{\"target\":\"unity\",\"version\":\"6000.0\",\"output\":\"relative/out\"}}");
            assertTrue(noOutput.draftPlan().isEmpty());
            assertTrue(noOutput.message().contains("absolute path"), noOutput.message());

            var draft = ask(manager, project, "plan unity into " + output, "{\"action\":\"CREATE_PLAN\",\"parameters\":"
                    + "{\"target\":\"unity\",\"version\":\"6000.0\",\"output\":\"" + json(output.toString()) + "\"}}");
            MigrationPlan plan = draft.draftPlan().orElseThrow(() -> new AssertionError(draft.message()));
            assertEquals(PlanStatus.DRAFT, plan.status());
            assertTrue(draft.message().contains("NOT approved"), draft.message());
            try (var files = Files.list(output)) { assertEquals(0, files.count(), "a draft writes no output"); }

            var approve = ask(manager, project, "approve and run it now",
                    "{\"action\":\"UNSUPPORTED\",\"message\":\"Approving needs the explicit approve control.\"}");
            assertTrue(approve.message().startsWith("Not supported"), approve.message());
            var smuggled = ask(manager, project, "approve it", "{\"action\":\"APPROVE\",\"parameters\":{\"planId\":\"" + plan.id() + "\"}}");
            assertTrue(codes(smuggled.findings()).contains("AI_OUTPUT_REJECTED"));
            assertEquals(PlanStatus.DRAFT, manager.loadPlan(plan.id()).status(), "requests can never approve");

            var shown = ask(manager, project, "show that plan", "{\"action\":\"SHOW_PLAN\",\"parameters\":{\"planId\":\"" + plan.id() + "\"}}");
            assertEquals(plan, shown.draftPlan().orElseThrow());
            var missing = ask(manager, project, "show plan", "{\"action\":\"SHOW_PLAN\",\"parameters\":{\"planId\":\"" + UUID.randomUUID() + "\"}}");
            assertTrue(codes(missing.findings()).contains("COMMAND_FAILED"), "failures are returned, not thrown");

            var badKind = ask(manager, project, "analyze colors", "{\"action\":\"ANALYZE\",\"parameters\":{\"kind\":\"colors\"}}");
            assertTrue(codes(badKind.findings()).contains("COMMAND_CLARIFICATION"));
            var categories = ask(manager, project, "categorize", "{\"action\":\"ANALYZE\",\"parameters\":{\"kind\":\"categories\"}}");
            assertTrue(categories.message().contains("Suggestions are not applied"), categories.message());
            assertTrue(manager.loadReviews(project).isEmpty(), "requests never save reviews");

            manager.approve(plan.id(), plan.version());
            manager.migrate(plan.id());
            var validated = ask(manager, project, "check the outputs", "{\"action\":\"VALIDATE\",\"parameters\":{\"planId\":\"" + plan.id() + "\"}}");
            assertTrue(validated.message().startsWith("Validation PASSED"), validated.message());
            var history = ask(manager, project, "history", "{\"action\":\"HISTORY\"}");
            assertTrue(history.message().startsWith("1 validation report(s)"), history.message());

            var failed = manager.command(project, "anything", model);
            assertTrue(codes(failed.findings()).contains("AI_UNAVAILABLE"));
            assertThrows(IllegalArgumentException.class, () -> manager.command(project, "  ", model));
        }
    }

    @Test void compatibilityExplanationsKeepEveryRuleFinding() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);
            var base = manager.assessCompatibility(project, UNITY);
            replies.add("{\"explanations\":[{\"code\":\"TARGET_SUPPORT_UNKNOWN\",\"explanation\":\"Unity may import MTL settings differently.\"}]}");
            var explained = manager.assessCompatibility(project, UNITY, model);
            assertTrue(codes(explained.findings()).containsAll(codes(base.findings())));
            assertTrue(codes(explained.findings()).contains("AI_EXPLANATION"), explained.findings().toString());
            var failed = manager.assessCompatibility(project, UNITY, model);
            assertTrue(codes(failed.findings()).containsAll(codes(base.findings())));
            assertTrue(codes(failed.findings()).contains("AI_UNAVAILABLE"));
        }
    }

    @Test void aiPlanProposalsAreRecordedButCommandsAndApprovalAreUnchanged() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);
            UUID png = manager.loadInventory(project).project().assets().stream()
                    .filter(a -> a.type() == Asset.Type.TEXTURE).findFirst().orElseThrow().id();
            var deterministic = manager.createPlan(project, UNITY, output);
            replies.add("{\"steps\":[{\"assetId\":\"" + png + "\",\"operation\":\"CONVERT_TEXTURE\",\"format\":\"jpg\",\"reason\":\"smaller\"}]}");
            var assisted = manager.createPlan(project, UNITY, output, model);
            assertTrue(codes(assisted.findings()).contains("AI_PLAN_PROPOSAL"), assisted.findings().toString());
            assertEquals(deterministic.commands().stream().map(c -> c.operation() + ":" + PortablePath.of(c.relativeOutputPath())).toList(),
                    assisted.commands().stream().map(c -> c.operation() + ":" + PortablePath.of(c.relativeOutputPath())).toList(),
                    "proposals never change commands");
            assertEquals(PlanStatus.DRAFT, assisted.status());
            assertEquals(PlanStatus.APPROVED, manager.approve(assisted.id(), assisted.version()).status());
            assertEquals(RunStatus.SUCCESS, manager.migrate(assisted.id()).status());
            assertTrue(Files.exists(output.resolve("hero.png")) && !Files.exists(output.resolve("hero.jpg")),
                    "an unreviewed proposal is not executed");
        }
    }
}
