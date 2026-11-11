package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.application.AssetManager.TargetProfile;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F05 explanations, F06 plan proposals and F09 interpretation, all with untrusted model output. */
class AIEngineAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Path ROOT = io.github.aiassetmigrate.TestPaths.absolute("secret-root");
    private static final TargetProfile UNITY = new TargetProfile("unity", "6000.0");
    private final List<String> prompts = new ArrayList<>();

    private LLMClient replying(Function<String, String> reply) {
        return new LLMClient() {
            @Override public JsonNode generate(String prompt) throws AiException {
                prompts.add(prompt);
                try { return JSON.readTree(reply.apply(prompt)); }
                catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "bad"); }
            }
            @Override public String description() { return "fake model"; }
        };
    }

    private static LLMClient failing() {
        return OpenAIAdapter.unavailable(new LLMClient.AiException(LLMClient.AiException.Reason.USAGE_LIMIT, "Usage limit reached"));
    }

    private static Asset asset(String path, Asset.Type type) {
        return new Asset(UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)), Path.of(path), type,
                "a".repeat(64), Map.of(Asset.FORMAT, path.substring(path.lastIndexOf('.') + 1)));
    }

    private static Finding finding(Asset asset, Finding.Severity severity, String code, String message) {
        return new Finding(UUID.randomUUID(), Optional.ofNullable(asset).map(Asset::id),
                Optional.ofNullable(asset).map(Asset::relativePath), severity, code, message);
    }

    private static Map<String, List<Finding>> byCode(List<Finding> findings) {
        return findings.stream().collect(Collectors.groupingBy(Finding::code));
    }

    // ---------------------------------------------------------------- F05

    @Test void explanationsAreValidatedPerCodeAndNeverReplaceFindings() {
        Asset fbx = asset("props/crate.fbx", Asset.Type.MODEL);
        List<Finding> assessment = List.of(
                finding(fbx, Finding.Severity.WARNING, "INSPECTION_UNSUPPORTED", "secret message " + ROOT),
                finding(fbx, Finding.Severity.UNKNOWN, "CONTENT_UNVERIFIED", "x"),
                finding(null, Finding.Severity.INFO, "INSTALLED_CONVERTERS", "info only"));
        var added = new AIEngine(replying(p -> """
                {"explanations":[
                  {"code":"INSPECTION_UNSUPPORTED","explanation":"FBX references cannot be read.\\u0007 Check them in your DCC tool."},
                  {"code":"INSPECTION_UNSUPPORTED","explanation":"duplicate"},
                  {"code":"MADE_UP","explanation":"invented"},
                  {"code":"CONTENT_UNVERIFIED","explanation":"   "}]}
                """)).explainCompatibility(UNITY, assessment);
        var codes = byCode(added);
        assertEquals(1, codes.get("AI_EXPLANATION").size(), added.toString());
        assertTrue(codes.get("AI_EXPLANATION").getFirst().message()
                .startsWith("INSPECTION_UNSUPPORTED: FBX references cannot be read. Check them"), added.toString());
        assertTrue(codes.get("AI_OUTPUT_REJECTED").getFirst().message().startsWith("3 "));
        assertTrue(codes.containsKey("AI_SOURCE"));
        String prompt = prompts.getFirst();
        assertTrue(prompt.contains("props/crate.fbx") && prompt.contains("\"count\":1"));
        assertFalse(prompt.contains("secret message") || prompt.contains(ROOT.toString()), "messages and absolute paths stay local");
        assertFalse(prompt.contains("INSTALLED_CONVERTERS"), "INFO findings need no explanation");
    }

    @Test void explanationFailureAndNothingToExplainAreReported() {
        Asset fbx = asset("crate.fbx", Asset.Type.MODEL);
        var failed = new AIEngine(failing()).explainCompatibility(UNITY,
                List.of(finding(fbx, Finding.Severity.WARNING, "INSPECTION_UNSUPPORTED", "x")));
        assertEquals(List.of("AI_UNAVAILABLE"), failed.stream().map(Finding::code).toList());
        var none = new AIEngine(replying(p -> { throw new AssertionError("no request expected"); }))
                .explainCompatibility(UNITY, List.of(finding(null, Finding.Severity.INFO, "X", "y")));
        assertEquals(List.of("AI_NOT_NEEDED"), none.stream().map(Finding::code).toList());
    }

    // ---------------------------------------------------------------- F06

    private record Planned(InventorySnapshot inventory, OrganizationPreview.Result preview, List<MigrationCommand> commands,
                           Asset obj, Asset png, Asset fbx, Asset excluded) {}

    private Planned planned() {
        Asset obj = asset("hero.obj", Asset.Type.MODEL), png = asset("hero.png", Asset.Type.TEXTURE),
                fbx = asset("crate.fbx", Asset.Type.MODEL), excluded = asset("old.png", Asset.Type.TEXTURE),
                jpg = asset("hero_alt.jpg", Asset.Type.TEXTURE);
        var inventory = new InventorySnapshot(1, 1, new Project(UUID.randomUUID(), ROOT, 1, List.of(obj, png, fbx, excluded, jpg), NOW),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, NOW, List.of(), List.of());
        var preview = new OrganizationPreview.Result(inventory.project().id(), 1, List.of(
                new OrganizationPreview.Entry(obj.id(), obj.relativePath(), Optional.of("hero.obj"), false),
                new OrganizationPreview.Entry(png.id(), png.relativePath(), Optional.of("hero.png"), false),
                new OrganizationPreview.Entry(fbx.id(), fbx.relativePath(), Optional.of("crate.fbx"), false),
                new OrganizationPreview.Entry(excluded.id(), excluded.relativePath(), Optional.empty(), true),
                new OrganizationPreview.Entry(jpg.id(), jpg.relativePath(), Optional.of("hero_alt.jpg"), false)), List.of());
        List<MigrationCommand> commands = new ArrayList<>();
        for (Asset a : List.of(obj, png, fbx, jpg)) {
            commands.add(new AssetOperationCommand(UUID.randomUUID(), a.id(), MigrationCommand.Operation.COPY,
                    a.relativePath(), Map.of(), List.of(), Optional.empty()));
        }
        return new Planned(inventory, preview, commands, obj, png, fbx, excluded);
    }

    @Test void planProposalsAreCheckedAgainstToolsReviewsAndConflicts() {
        var p = planned();
        var added = new AIEngine(replying(prompt -> """
                {"steps":[
                  {"assetId":"%s","operation":"COPY","reason":"already engine ready"},
                  {"assetId":"%s","operation":"CONVERT_TEXTURE","format":"jpg","reason":"smaller"},
                  {"assetId":"%s","operation":"CONVERT_MODEL","format":"gltf","reason":"modern"},
                  {"assetId":"%s","operation":"COPY"},
                  {"assetId":"%s","operation":"COPY"},
                  {"assetId":"%s","operation":"DELETE_SOURCE"}]}
                """.formatted(p.obj().id(), p.png().id(), p.fbx().id(), p.excluded().id(), p.obj().id(), p.png().id())))
                .plan(p.inventory(), p.preview(), p.commands(), UNITY);
        var codes = byCode(added);
        assertEquals(1, codes.get("AI_PLAN_STEP").size(), added.toString());
        assertTrue(codes.get("AI_PLAN_STEP").getFirst().message().contains("AI agrees: COPY hero.obj"));
        var proposal = codes.get("AI_PLAN_PROPOSAL");
        assertEquals(1, proposal.size(), added.toString());
        assertTrue(proposal.getFirst().message().contains("hero.png to jpg (output hero.jpg)")
                && proposal.getFirst().message().contains("Not applied"), proposal.toString());
        var rejected = codes.get("AI_PLAN_STEP_REJECTED").stream().map(Finding::message).toList();
        assertEquals(2, rejected.size(), rejected.toString());
        assertTrue(rejected.stream().anyMatch(m -> m.contains("crate.fbx") && m.contains("no installed converter")));
        assertTrue(rejected.stream().anyMatch(m -> m.contains("old.png") && m.contains("excludes")));
        assertTrue(codes.get("AI_OUTPUT_REJECTED").getFirst().message().startsWith("2 "), "duplicate and unknown operation");
        assertTrue(codes.get("AI_PLAN_RETAINED").getFirst().message().startsWith("1 "), "hero_alt.jpg had no proposal");
        assertTrue(added.stream().noneMatch(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN),
                "AI findings never block approval");
        assertFalse(prompts.getFirst().contains(ROOT.toString()));
    }

    @Test void conversionThatWouldCollideIsRejected() {
        var p = planned();
        Asset alt = p.inventory().project().assets().stream().filter(a -> a.relativePath().toString().equals("hero_alt.jpg")).findFirst().orElseThrow();
        // Another output already uses hero.jpg, so converting hero.png to JPEG would collide.
        var preview = new OrganizationPreview.Result(p.preview().projectId(), 1, List.of(
                new OrganizationPreview.Entry(p.png().id(), p.png().relativePath(), Optional.of("hero.png"), false),
                new OrganizationPreview.Entry(alt.id(), alt.relativePath(), Optional.of("hero.jpg"), false)), List.of());
        var added = new AIEngine(replying(prompt -> """
                {"steps":[{"assetId":"%s","operation":"CONVERT_TEXTURE","format":"jpeg"}]}
                """.formatted(p.png().id()))).plan(p.inventory(), preview, p.commands(), UNITY);
        var rejected = byCode(added).get("AI_PLAN_STEP_REJECTED");
        assertNotNull(rejected, added.toString());
        assertTrue(rejected.getFirst().message().contains("would collide"), rejected.toString());
    }

    @Test void planFailureLeavesThePlanUnchanged() {
        var p = planned();
        var added = new AIEngine(failing()).plan(p.inventory(), p.preview(), p.commands(), UNITY);
        assertEquals(List.of("AI_UNAVAILABLE"), added.stream().map(Finding::code).toList());
        assertTrue(added.getFirst().message().contains("retry"));
    }

    // ---------------------------------------------------------------- F09

    private AIEngine.Interpretation interpret(String reply) {
        var context = new AIEngine.CommandContext(UUID.randomUUID(), 2, Map.of("MODEL", 1), List.of(UNITY));
        return new AIEngine(replying(p -> reply)).interpret("check unity please", context);
    }

    @Test void interpretationAcceptsOnlyAllowlistedActionsAndParameters() {
        var ok = interpret("""
                {"action":"check_compatibility","parameters":{"target":"unity","version":"6000.0"},"message":"Checking"}""");
        assertEquals(AIEngine.Action.CHECK_COMPATIBILITY, ok.intent().orElseThrow().action());
        assertEquals(Map.of("target", "unity", "version", "6000.0"), ok.intent().get().parameters());
        String prompt = prompts.getFirst();
        assertTrue(prompt.contains("\"request\":\"check unity please\"") && prompt.contains("\"MODEL\":1"));

        for (String bad : List.of(
                "{\"action\":\"APPROVE\",\"parameters\":{\"planId\":\"x\"}}",
                "{\"action\":\"MIGRATE\"}",
                "{\"action\":\"ANALYZE\",\"parameters\":{\"kind\":\"names\",\"approve\":\"true\"}}",
                "{\"action\":\"ANALYZE\",\"parameters\":{\"kind\":3}}",
                "{\"action\":\"CLARIFY\",\"message\":\"  \"}",
                "[\"SHOW_INVENTORY\"]")) {
            var rejected = interpret(bad);
            assertTrue(rejected.intent().isEmpty(), bad);
            assertEquals("AI_OUTPUT_REJECTED", rejected.findings().getFirst().code(), bad);
        }
        var clarify = interpret("{\"action\":\"CLARIFY\",\"message\":\"Which target?\\u0000\"}");
        assertEquals("Which target?", clarify.intent().orElseThrow().message());
    }

    @Test void interpretationFailureAndOversizedRequests() {
        var context = new AIEngine.CommandContext(UUID.randomUUID(), 1, Map.of(), List.of(UNITY));
        var failed = new AIEngine(failing()).interpret("show inventory", context);
        assertTrue(failed.intent().isEmpty());
        assertEquals("AI_UNAVAILABLE", failed.findings().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> new AIEngine(failing()).interpret(" ", context));
        assertThrows(IllegalArgumentException.class, () -> new AIEngine(failing()).interpret("x".repeat(2001), context));
    }
}
