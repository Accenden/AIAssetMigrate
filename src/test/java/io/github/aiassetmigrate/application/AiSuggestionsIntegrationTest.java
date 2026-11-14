package io.github.aiassetmigrate.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.conversion.TextureConverter;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI plan suggestions (F05/F06) and the operator agent (F09) through the real facade: every AI proposal is checked by
 * the program before the user sees it, nothing is saved until the user applies chosen ones, and a plan still needs
 * explicit approval.
 */
class AiSuggestionsIntegrationTest {
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    @TempDir Path directory;
    private Path source, output;
    private final List<String> prompts = new ArrayList<>();
    private Map<String, UUID> ids;

    private LLMClient model(Function<String, String> reply) {
        return new LLMClient() {
            @Override public JsonNode generate(String prompt) throws AiException {
                prompts.add(prompt);
                String text = reply.apply(prompt);
                if (text == null) throw new AiException(AiException.Reason.UNAVAILABLE, "no scripted reply");
                try { return new ObjectMapper().readTree(text); }
                catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "bad"); }
            }
            @Override public String description() { return "scripted model"; }
        };
    }

    /** hero.mtl names skin.png, which is in textures/; misc/broken.png cannot be decoded; two transparent UI images. */
    private UUID importProject(DefaultAssetManager manager) throws Exception {
        source = Files.createDirectory(directory.resolve("source"));
        output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd skin.png\n");
        Files.createDirectories(source.resolve("textures"));
        byte[] opaque = ConversionStrategyTest.png(false);
        Files.write(source.resolve("textures/skin.png"), opaque);
        Files.write(source.resolve("textures/skin.jpg"), new TextureConverter().convert(opaque, "jpg", Map.of("format", "jpg")));
        Files.createDirectories(source.resolve("ui"));
        Files.write(source.resolve("ui/icon.png"), ConversionStrategyTest.png(true));
        Files.write(source.resolve("ui/grass.png"), ConversionStrategyTest.png(true));
        Files.createDirectories(source.resolve("misc"));
        Files.write(source.resolve("misc/broken.png"), Arrays.copyOf(opaque, 40));
        UUID project = manager.importAssets(source).snapshot().project().id();
        ids = new HashMap<>();
        manager.loadInventory(project).project().assets().forEach(asset -> ids.put(PortablePath.of(asset.relativePath()), asset.id()));
        return project;
    }

    private String proposals() {
        return """
                {"proposals":[
                  {"kind":"CONVERT","assetId":"%s","format":"jpg","background":"#FFFFFF","reason":"UI on white"},
                  {"kind":"CONVERT","assetId":"%s","format":"jpg","reason":"smaller"},
                  {"kind":"CONVERT","assetId":"%s","format":"jpg","reason":"smaller"},
                  {"kind":"EXCLUDE","assetId":"%s","reason":"not needed"},
                  {"kind":"EXCLUDE","assetId":"%s","reason":"damaged"},
                  {"kind":"FIX_REFERENCE","assetId":"%s","reference":"skin.png","targetAssetId":"%s","reason":"moved to textures"}]}
                """.formatted(ids.get("ui/icon.png"), ids.get("ui/grass.png"), ids.get("textures/skin.png"), ids.get("textures/skin.jpg"),
                ids.get("misc/broken.png"), ids.get("hero.mtl"), ids.get("textures/skin.png"));
    }

    private static long blocking(MigrationPlan plan) {
        return plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN).count();
    }

    @Test void suggestionsAreCheckedBeforeTheUserSeesThemAndApplyOnlyWhenChosen() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            MigrationPlan before = manager.createPlan(project, UNITY, output);
            assertTrue(blocking(before) > 0, "a broken reference and a damaged image block the plan");

            var suggestions = manager.suggestPlanSteps(project, UNITY, model(prompt -> proposals()));
            List<String> passed = suggestions.proposal().fixes().stream().map(BlockerFixes.Fix::description).toList();
            assertEquals(3, passed.size(), passed + " / " + suggestions.proposal().unfixable());
            // Conversions are checked (and listed) after the other kinds.
            assertTrue(passed.get(0).startsWith("Leave misc/broken.png out of the migration"), passed.toString());
            assertTrue(passed.get(1).startsWith("Point 'skin.png' in hero.mtl to textures/skin.png"), passed.toString());
            assertTrue(passed.get(2).startsWith("Convert ui/icon.png to jpg on a #FFFFFF background (AI: UI on white)"), passed.toString());
            assertTrue(suggestions.proposal().fixes().stream().allMatch(fix -> fix.source() == BlockerFixes.Source.AI && !fix.recommended()));

            List<String> dropped = suggestions.proposal().unfixable();
            assertEquals(3, dropped.size(), dropped.toString());
            assertTrue(dropped.stream().anyMatch(line -> line.contains("ui/grass.png") && line.contains("background")), "converter check: " + dropped);
            assertTrue(dropped.stream().anyMatch(line -> line.contains("textures/skin.png to jpg") && line.contains("block the plan")),
                    "the converted name would clash with textures/skin.jpg: " + dropped);
            assertTrue(dropped.stream().anyMatch(line -> line.contains("textures/skin.jpg") && line.contains("usable file")),
                    "a healthy file is never left out: " + dropped);

            String prompt = prompts.getFirst();
            assertFalse(prompt.contains(source.toString()) || prompt.contains(directory.toString()), "no absolute paths");
            assertFalse(prompt.contains("newmtl") || prompt.contains("map_Kd"), "no file contents");
            assertTrue(prompt.contains("SOURCE_IMAGE_INVALID") && prompt.contains("\"transparent\"") && prompt.contains("glTFast"), prompt);

            assertTrue(manager.loadReviews(project).isEmpty(), "suggesting saves nothing");
            manager.applyBlockerFixes(project, suggestions.proposal().inventoryRevision(), suggestions.proposal().fixes());
            MigrationPlan after = manager.createPlan(project, UNITY, output);
            assertEquals(0, blocking(after), after.findings().toString());
            assertTrue(after.commands().stream().anyMatch(c -> c.operation() == MigrationCommand.Operation.CONVERT_TEXTURE
                    && PortablePath.of(c.relativeOutputPath()).equals("ui/icon.jpg")));
            assertEquals(PlanStatus.DRAFT, after.status(), "applying suggestions never approves");
            assertEquals(PlanStatus.APPROVED, manager.approve(after.id(), after.version()).status());
        }
    }

    @Test void aiPlanRecordsCheckedSuggestionsAsNonBlockingFindingsWithoutAgreeNoise() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            MigrationPlan deterministic = manager.createPlan(project, UNITY, output);
            MigrationPlan assisted = manager.createPlan(project, UNITY, output, model(prompt -> proposals()));
            Map<String, Long> codes = assisted.findings().stream().collect(Collectors.groupingBy(Finding::code, Collectors.counting()));
            assertEquals(3L, codes.get("AI_PLAN_PROPOSAL"), codes.toString());
            assertEquals(3L, codes.get("AI_PLAN_PROPOSAL_DROPPED"), codes.toString());
            assertFalse(codes.containsKey("AI_PLAN_STEP"), "no 'AI agrees' findings");
            assertTrue(assisted.findings().stream().filter(f -> f.code().startsWith("AI_"))
                    .noneMatch(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN));
            assertEquals(blocking(deterministic), blocking(assisted), "suggestions never add or clear blockers");
            assertEquals(deterministic.commands().size(), assisted.commands().size());
            assertTrue(assisted.findings().stream().filter(f -> f.code().equals("AI_PLAN_PROPOSAL"))
                    .allMatch(f -> f.message().contains("review --asset")), "each says how to apply it");
        }
    }

    @Test void unavailableModelKeepsTheRuleBasedPlan() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            var suggestions = manager.suggestPlanSteps(project, UNITY, model(prompt -> null));
            assertTrue(suggestions.proposal().fixes().isEmpty());
            assertTrue(suggestions.findings().stream().anyMatch(f -> f.code().equals("AI_UNAVAILABLE")));
        }
    }

    @Test void checkDecisionsLeavesOutDecisionsThatChangeNothing() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            long revision = manager.loadInventory(project).project().inventoryRevision();
            var resolved = new BlockerFixes.Fix("X", Optional.of(ids.get("hero.obj")), "resolved already",
                    List.of(new ReviewedChange(UUID.randomUUID(), ids.get("hero.obj"), ReviewedChange.Kind.RELATIONSHIP,
                            ReviewedChange.Decision.ACCEPTED, Map.of("targetAssetId", ids.get("hero.mtl").toString(), "reference", "hero.mtl"),
                            java.time.Instant.now())), Set.of(), BlockerFixes.Source.AI, false);
            var category = new BlockerFixes.Fix("X", Optional.of(ids.get("hero.obj")), "category",
                    List.of(new ReviewedChange(UUID.randomUUID(), ids.get("hero.obj"), ReviewedChange.Kind.CATEGORY,
                            ReviewedChange.Decision.ACCEPTED, Map.of("category", "Characters"), java.time.Instant.now())),
                    Set.of(), BlockerFixes.Source.AI, false);
            var checked = manager.checkDecisions(project, revision, Optional.empty(), List.of(resolved, category, category));
            assertEquals(List.of("category"), checked.fixes().stream().map(BlockerFixes.Fix::description).toList(),
                    "a correction for a resolved reference and an identical duplicate are left out");
            assertTrue(checked.unfixable().isEmpty(), checked.unfixable().toString());
            manager.review(project, revision, category.changes());
            assertTrue(manager.checkDecisions(project, revision, Optional.empty(), List.of(category)).fixes().isEmpty(),
                    "an already saved decision is not proposed again");
        }
    }

    @Test void aConversionThatNeedsAReferenceFixPassesTogetherWithItAndSaysSo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.github.aiassetmigrate.conversion.AssimpModelConverter.available());
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            // The AI lists the conversion first; alone it fails (hero.mtl's skin.png is missing).
            String reply = """
                    {"proposals":[{"kind":"CONVERT","assetId":"%s","format":"glb","reason":"GLB"},
                      {"kind":"FIX_REFERENCE","assetId":"%s","reference":"skin.png","targetAssetId":"%s","reason":"moved"}]}
                    """.formatted(ids.get("hero.obj"), ids.get("hero.mtl"), ids.get("textures/skin.png"));
            var suggestions = manager.suggestPlanSteps(project, UNITY, model(prompt -> reply));
            List<String> passed = suggestions.proposal().fixes().stream().map(BlockerFixes.Fix::description).toList();
            assertEquals(2, passed.size(), passed + " / " + suggestions.proposal().unfixable());
            assertTrue(passed.get(0).startsWith("Point 'skin.png' in hero.mtl"), passed.toString());
            assertTrue(passed.get(1).startsWith("Convert hero.obj to glb") && passed.get(1).contains("Works only together with \"Point 'skin.png'")
                    && passed.get(1).contains("glTFast"), passed.toString());

            var rechecked = manager.checkDecisions(project, suggestions.proposal().inventoryRevision(), Optional.of(UNITY),
                    suggestions.proposal().fixes());
            assertEquals(passed, rechecked.fixes().stream().map(BlockerFixes.Fix::description).toList(), "re-checking adds no second note");

            var conversionOnly = manager.suggestPlanSteps(project, UNITY, model(prompt -> """
                    {"proposals":[{"kind":"CONVERT","assetId":"%s","format":"glb"}]}""".formatted(ids.get("hero.obj"))));
            assertTrue(conversionOnly.proposal().fixes().isEmpty(), "without the fix in the list it is still dropped");
            assertTrue(conversionOnly.proposal().unfixable().getFirst().contains("converter's check fails"));
        }
    }

    @Test void preparingLetsTheAgentChooseToolsAndReturnsOneCheckedBundle() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importProject(manager);
            Deque<String> operator = new ArrayDeque<>(List.of(
                    "{\"tool\":\"SUGGEST_PLAN_STEPS\"}",
                    "{\"tool\":\"ANALYZE\",\"parameters\":{\"kind\":\"relationships\"}}",
                    "{\"tool\":\"FINISH\",\"parameters\":{\"message\":\"Leave out the damaged image and fix the skin reference.\"}}"));
            String corrections = """
                    {"corrections":[{"ownerId":"%s","reference":"skin.png","targetAssetId":"%s","reason":"same name"}],"groups":[]}
                    """.formatted(ids.get("hero.mtl"), ids.get("textures/skin.png"));
            // The AI misses the damaged image; Fix blockers' rule-based fix for it joins the bundle.
            String proposals = """
                    {"proposals":[{"kind":"FIX_REFERENCE","assetId":"%s","reference":"skin.png","targetAssetId":"%s"}]}
                    """.formatted(ids.get("hero.mtl"), ids.get("textures/skin.png"));
            String route = "{\"action\":\"PREPARE\",\"parameters\":{\"target\":\"unity\",\"version\":\"6000.0\",\"output\":\""
                    + output.toString().replace("\\", "\\\\") + "\"},\"message\":\"Preparing\"}";
            LLMClient scripted = model(prompt -> prompt.contains("You route a user's request") ? route
                    : prompt.contains("You operate a game-asset migration tool") ? operator.poll()
                    : prompt.contains("TASK: Suggest the few changes") ? proposals
                    : prompt.contains("TASK: For each entry in DATA.brokenReferences") ? corrections : null);

            var result = manager.command(project, "prepare this project for unity into " + output, scripted);
            assertTrue(result.message().contains("Agent: Leave out the damaged image"), result.message());
            assertTrue(result.message().contains("Nothing was approved or migrated"), result.message());
            var decisions = result.decisions().orElseThrow();
            assertEquals(2, decisions.fixes().size(), decisions.fixes() + " / " + decisions.unfixable());
            assertTrue(decisions.unfixable().isEmpty(), "the same fix from the analysis and Fix blockers is offered once: " + decisions.unfixable());
            assertTrue(decisions.fixes().stream().anyMatch(fix -> fix.source() == BlockerFixes.Source.RULES
                    && fix.description().contains("misc/broken.png")), "Fix blockers fills what the AI missed: " + decisions.fixes());
            assertEquals(PlanStatus.DRAFT, result.draftPlan().orElseThrow().status());
            assertTrue(manager.loadReviews(project).isEmpty(), "the agent saves nothing");
            assertTrue(prompts.stream().filter(p -> p.contains("You operate")).noneMatch(p -> p.contains(source.toString())),
                    "tool results carry relative paths only");

            manager.applyBlockerFixes(project, decisions.inventoryRevision(), decisions.fixes());
            assertEquals(0, blocking(manager.createPlan(project, UNITY, output)), "confirming the bundle unblocks the plan");
        }
    }
}
