package io.github.aiassetmigrate.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // ---------------------------------------------------------------- F05/F06: plan suggestions

    private record Planned(AIEngine.StepContext context, Asset obj, Asset mtl, Asset png, Asset fbx, Asset broken) {}

    private Planned planned() {
        Asset obj = asset("hero.obj", Asset.Type.MODEL), mtl = asset("hero.mtl", Asset.Type.MATERIAL),
                png = asset("tex/hero.png", Asset.Type.TEXTURE), fbx = asset("crate.fbx", Asset.Type.MODEL),
                broken = asset("misc/broken.png", Asset.Type.TEXTURE);
        var references = List.of(
                new AssetReference(obj.id(), AssetReference.Kind.MATERIAL_LIBRARY, "hero.mtl", Optional.of(Path.of("hero.mtl")),
                        AssetReference.ResolutionStatus.RESOLVED),
                new AssetReference(mtl.id(), AssetReference.Kind.TEXTURE, "hero.png", Optional.of(Path.of("hero.png")),
                        AssetReference.ResolutionStatus.MISSING));
        var context = new AIEngine.StepContext(UNITY, List.of(obj, mtl, png, fbx, broken),
                Map.of(obj.id(), "hero.obj", mtl.id(), "hero.mtl", png.id(), "tex/hero.png", fbx.id(), "crate.fbx", broken.id(), "misc/broken.png"),
                Map.of(), Map.of(broken.id(), Set.of("SOURCE_IMAGE_INVALID")), Map.of(png.id(), Set.of("transparent")),
                references, List.of("Unity imports .glb only with the glTFast package."));
        return new Planned(context, obj, mtl, png, fbx, broken);
    }

    @Test void stepProposalsAreParsedStrictlyAndSendOnlyMetadata() {
        var p = planned();
        var result = new AIEngine(replying(prompt -> """
                {"proposals":[
                  {"kind":"CONVERT","assetId":"%s","format":"JPG","background":"#ffffff","reason":"smaller"},
                  {"kind":"exclude","assetId":"%s","reason":"damaged"},
                  {"kind":"FIX_REFERENCE","assetId":"%s","reference":"hero.png","targetAssetId":"%s","reason":"moved"},
                  {"kind":"FIX_REFERENCE","assetId":"%s","reference":"hero.png","targetAssetId":"%s"},
                  {"kind":"FIX_REFERENCE","assetId":"%s","reference":"not-broken.png","targetAssetId":"%s"},
                  {"kind":"CONVERT","assetId":"%s","format":"glb","background":"white"},
                  {"kind":"DELETE","assetId":"%s"},
                  {"kind":"EXCLUDE","assetId":"%s"},
                  {"kind":"EXCLUDE","assetId":"%s"}]}
                """.formatted(p.png().id(), p.broken().id(), p.mtl().id(), p.png().id(), p.mtl().id(), p.obj().id(),
                p.mtl().id(), p.png().id(), p.fbx().id(), p.obj().id(), UUID.randomUUID(), p.broken().id())))
                .proposeSteps(p.context());
        assertEquals(List.of(AIEngine.StepKind.CONVERT, AIEngine.StepKind.EXCLUDE, AIEngine.StepKind.FIX_REFERENCE),
                result.proposals().stream().map(AIEngine.StepProposal::kind).toList(), result.toString());
        assertEquals(Map.of("format", "jpg", "background", "#FFFFFF"), result.proposals().get(0).values());
        assertEquals(Map.of("reference", "hero.png", "targetAssetId", p.png().id().toString()), result.proposals().get(2).values());
        assertEquals(Optional.of("moved"), result.proposals().get(2).reason());
        // wrong target type, reference that is not broken, bad colour, unknown kind, unknown asset, duplicate
        assertTrue(byCode(result.findings()).get("AI_OUTPUT_REJECTED").getFirst().message().startsWith("6 "), result.findings().toString());
        String prompt = prompts.getFirst();
        assertFalse(prompt.contains(ROOT.toString()), "no absolute paths");
        assertTrue(prompt.contains("\"traits\":[\"transparent\"]") && prompt.contains("\"issues\":[\"SOURCE_IMAGE_INVALID\"]")
                && prompt.contains("glTFast") && prompt.contains("\"reference\":\"hero.png\""), prompt);
    }

    @Test void stepProposalFailureKeepsThePlan() {
        var result = new AIEngine(failing()).proposeSteps(planned().context());
        assertTrue(result.proposals().isEmpty());
        assertEquals(List.of("AI_UNAVAILABLE"), result.findings().stream().map(Finding::code).toList());
        assertTrue(result.findings().getFirst().message().contains("retry"));
    }

    // ---------------------------------------------------------------- F09: operator

    @Test void operatorRunsChosenToolsInSequenceAndStopsOnFinish() {
        Deque<String> replies = new ArrayDeque<>(List.of(
                "{\"tool\":\"INVENTORY\"}",
                "{\"tool\":\"ANALYZE\",\"parameters\":{\"kind\":\"relationships\"}}",
                "{\"tool\":\"ANALYZE\",\"parameters\":{\"kind\":\"RELATIONSHIPS\"}}",
                "{\"tool\":\"SUGGEST_PLAN_STEPS\"}",
                "{\"tool\":\"FINISH\",\"parameters\":{\"message\":\"Two fixes proposed.\"}}"));
        List<String> ran = new ArrayList<>();
        var operation = new AIEngine(replying(prompt -> replies.poll())).operate("prepare for unity", UNITY, (tool, parameters) -> {
            ran.add(tool + parameters.getOrDefault("kind", ""));
            if (tool == AIEngine.Tool.SUGGEST_PLAN_STEPS) throw new IllegalStateException("secret C:\\path failure");
            return JSON.createObjectNode().put("tool", tool.name());
        });
        assertEquals(List.of("INVENTORY", "ANALYZERELATIONSHIPS", "SUGGEST_PLAN_STEPS"), ran, "a repeated call is not run again");
        assertEquals(4, operation.steps().size());
        assertFalse(operation.steps().get(2).ran());
        assertFalse(operation.steps().get(3).ran(), "a failing tool is recorded as not run");
        assertEquals(Optional.of("Two fixes proposed."), operation.message());
        assertTrue(prompts.get(1).contains("\"tool\":\"INVENTORY\""), "each turn sees earlier results");
        assertFalse(prompts.getLast().contains("secret"), "a tool's failure text is never sent to the model");
        assertTrue(byCode(operation.findings()).containsKey("AI_SOURCE"));
    }

    @Test void operatorRejectsUnknownToolsAndIsBounded() {
        var rejected = new AIEngine(replying(prompt -> "{\"tool\":\"APPROVE\",\"parameters\":{\"planId\":\"x\"}}"))
                .operate("approve it", UNITY, (tool, parameters) -> { throw new AssertionError("no tool may run"); });
        assertTrue(rejected.steps().isEmpty());
        assertTrue(byCode(rejected.findings()).containsKey("AI_OUTPUT_REJECTED"));
        var badKind = new AIEngine(replying(prompt -> "{\"tool\":\"ANALYZE\",\"parameters\":{\"kind\":\"delete\"}}"))
                .operate("x", UNITY, (tool, parameters) -> { throw new AssertionError("no tool may run"); });
        assertTrue(byCode(badKind.findings()).containsKey("AI_OUTPUT_REJECTED"));

        int[] calls = {0};
        List<String> kinds = List.of("categories", "relationships", "materials", "names", "folders", "categories", "names", "folders");
        var looping = new AIEngine(replying(prompt -> calls[0] < 5
                ? "{\"tool\":\"ANALYZE\",\"parameters\":{\"kind\":\"" + kinds.get(calls[0]++) + "\"}}"
                : "{\"tool\":\"" + List.of("INVENTORY", "CHECK_COMPATIBILITY", "PREVIEW_ORGANIZATION", "SUGGEST_PLAN_STEPS").get(calls[0]++ % 4) + "\"}"))
                .operate("x", UNITY, (tool, parameters) -> JSON.createObjectNode());
        assertEquals(AIEngine.MAX_OPERATOR_STEPS, looping.steps().size());
        assertTrue(byCode(looping.findings()).containsKey("AI_LIMIT"));
        var failed = new AIEngine(failing()).operate("x", UNITY, (tool, parameters) -> JSON.createObjectNode());
        assertTrue(byCode(failed.findings()).containsKey("AI_UNAVAILABLE"));
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
