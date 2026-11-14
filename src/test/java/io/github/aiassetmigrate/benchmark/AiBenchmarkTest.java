package io.github.aiassetmigrate.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiassetmigrate.ai.LLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The benchmark's rules-only baseline is fixed, so a later change that helps or hurts shows up here. A model that
 * answers from the key must reach full marks, which proves every AI path (analyses and checked plan suggestions)
 * carries a right answer through the program's validation; its wrong proposals must be dropped by the checks.
 */
class AiBenchmarkTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    private static Map<String, String> score(AiBenchmark.Result result, boolean ai) {
        Map<String, String> rows = new HashMap<>();
        for (AiBenchmark.Measure measure : result.measures()) {
            AiBenchmark.Count count = ai ? measure.withAi().orElseThrow() : measure.rules();
            rows.put(measure.name(), count.right() + "/" + measure.key() + " wrong " + count.wrong() + " neutral " + count.neutral());
        }
        return rows;
    }

    @Test void rulesBaselineIsFixed() throws Exception {
        AiBenchmark.Result result = AiBenchmark.run(directory, Optional.empty());
        assertEquals(24, result.files());
        assertEquals(Map.of(
                "Categories right", "20/24 wrong 4 neutral 0",
                "Broken references fixed right", "1/2 wrong 0 neutral 0",
                "Unreferenced textures grouped right", "1/2 wrong 0 neutral 2",
                "Names that match the key", "2/5 wrong 3 neutral 0",
                "Plan steps for godot 4.3", "2/3 wrong 0 neutral 0",
                "Plan steps for unity 6000.0", "2/3 wrong 0 neutral 0"), score(result, false), result.table());
        assertTrue(result.measures().stream().allMatch(m -> m.withAi().isEmpty()));
        assertTrue(result.table().contains("rules alone (add --ai to compare)"), result.table());
    }

    @Test void anAnswerKeyModelReachesFullMarksAndItsWrongProposalsAreDropped() throws Exception {
        AiBenchmark.Result result = AiBenchmark.run(directory, Optional.of(new AnswerKeyModel()));
        assertEquals(Map.of(
                "Categories right", "24/24 wrong 0 neutral 0",
                "Broken references fixed right", "2/2 wrong 0 neutral 0",
                "Unreferenced textures grouped right", "2/2 wrong 0 neutral 2",
                "Names that match the key", "5/5 wrong 0 neutral 0",
                "Plan steps for godot 4.3", "3/3 wrong 0 neutral 0",
                "Plan steps for unity 6000.0", "3/3 wrong 0 neutral 0"), score(result, true), result.table());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("ui/IMG_0101.png") && note.contains("usable file")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("grass_alpha.png") && note.contains("background")), result.notes().toString());
        assertTrue(result.table().contains("Rules + AI"), result.table());
    }

    /** Answers each AI request from {@link BenchmarkSample}'s key, plus two wrong plan proposals the checks must drop. */
    private static final class AnswerKeyModel implements LLMClient {
        @Override public JsonNode generate(String prompt) throws AiException {
            JsonNode data;
            try { data = JSON.readTree(prompt.substring(prompt.indexOf("DATA:") + 5)); }
            catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "unreadable prompt"); }
            Map<String, String> ids = new HashMap<>();
            data.path("assets").forEach(asset -> ids.put(asset.path("path").asText(), asset.path("id").asText()));
            ObjectNode reply = JSON.createObjectNode();
            if (prompt.contains("TASK: Choose a category")) {
                ArrayNode items = reply.putArray("categories");
                data.path("assets").forEach(asset -> {
                    List<String> key = BenchmarkSample.CATEGORIES.get(asset.path("path").asText()).stream()
                            .sorted((a, b) -> Boolean.compare(a.equals("Textures") || a.equals("Materials"), b.equals("Textures") || b.equals("Materials")))
                            .toList();
                    items.addObject().put("assetId", asset.path("id").asText()).put("category", key.getFirst());
                });
            } else if (prompt.contains("TASK: For each entry in DATA.brokenReferences")) {
                Map<String, String> paths = new HashMap<>();
                ids.forEach((path, id) -> paths.put(id, path));
                ArrayNode corrections = reply.putArray("corrections");
                data.path("brokenReferences").forEach(broken -> BenchmarkSample.CORRECTIONS.stream()
                        .filter(c -> c.owner().equals(paths.get(broken.path("ownerId").asText())) && c.reference().equals(broken.path("reference").asText()))
                        .forEach(c -> corrections.addObject().put("ownerId", ids.get(c.owner())).put("reference", c.reference())
                                .put("targetAssetId", ids.get(c.target()))));
                ArrayNode groups = reply.putArray("groups");
                BenchmarkSample.GROUPINGS.forEach(g -> groups.addObject().put("ownerId", ids.get(g.owner())).put("targetAssetId", ids.get(g.texture())));
            } else if (prompt.contains("TASK: Propose clear, consistent filenames")) {
                ArrayNode names = reply.putArray("names");
                names.addObject().put("assetId", ids.get("import/mdl_0001.obj")).put("name", "knight.obj");
                names.addObject().put("assetId", ids.get("import/IMG_0042.PNG")).put("name", "knight_diffuse.PNG");
                names.addObject().put("assetId", ids.get("misc/model_final_final.obj")).put("name", "chest.obj");
            } else if (prompt.contains("TASK: Suggest the few changes")) {
                ArrayNode proposals = reply.putArray("proposals");
                proposals.addObject().put("kind", "EXCLUDE").put("assetId", ids.get("misc/broken.png")).put("reason", "damaged");
                for (BenchmarkSample.Correction c : BenchmarkSample.CORRECTIONS) {
                    proposals.addObject().put("kind", "FIX_REFERENCE").put("assetId", ids.get(c.owner())).put("reference", c.reference())
                            .put("targetAssetId", ids.get(c.target()));
                }
                proposals.addObject().put("kind", "EXCLUDE").put("assetId", ids.get("ui/IMG_0101.png")).put("reason", "vague name");
                proposals.addObject().put("kind", "CONVERT").put("assetId", ids.get("environment/grass_alpha.png")).put("format", "jpg");
            } else {
                throw new AiException(AiException.Reason.INVALID_OUTPUT, "unexpected request");
            }
            return reply;
        }

        @Override public String description() { return "answer key"; }
    }
}
