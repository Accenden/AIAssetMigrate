package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.application.AssetManager.Source;
import io.github.aiassetmigrate.application.AssetManager.Suggestion;
import io.github.aiassetmigrate.domain.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReviewSummaryTest {
    private final UUID sword = UUID.randomUUID();
    private final UUID texture = UUID.randomUUID();
    private final UUID crate = UUID.randomUUID();
    private final UUID fbx = UUID.randomUUID();
    private final Map<UUID, String> paths = Map.of(sword, "weapons/sword.mtl", texture, "textures/sword_tex.png",
            crate, "props/crate.obj", fbx, "props/crate_01.fbx");

    private static Finding finding(UUID asset, String path, Finding.Severity severity, String code, String message) {
        return new Finding(UUID.randomUUID(), Optional.ofNullable(asset), Optional.ofNullable(path).map(Path::of),
                severity, code, message);
    }

    @Test
    void eachFileListsItsProblemAndTheSuggestionWithTheAiReason() {
        var correction = new Suggestion(UUID.randomUUID(), sword, AnalysisKind.RELATIONSHIPS, texture.toString(),
                Optional.of("sword_tex.png"), Source.AI, Optional.of("same base name"));
        var result = new AssetManager.AnalysisResult(1, List.of(correction), List.of(
                finding(sword, "weapons/sword.mtl", Finding.Severity.WARNING, "REFERENCE_UNRESOLVED",
                        "Reference 'sword_tex.png': UNRESOLVED"),
                finding(sword, "weapons/sword.mtl", Finding.Severity.INFO, "AI_SUGGESTION",
                        "AI suggests correcting 'sword_tex.png' to textures/sword_tex.png (same base name); review before accepting"),
                finding(null, null, Finding.Severity.INFO, "AI_SOURCE", "AI suggestions from OpenAI test")));

        ReviewSummary summary = ReviewSummary.of(result, paths);

        assertEquals(1, summary.items().size());
        ReviewSummary.Item item = summary.items().getFirst();
        assertEquals("weapons/sword.mtl", item.file());
        assertEquals(List.of("Points to 'sword_tex.png', which could not be found where it says."), item.issues(),
                "the reference finding is parsed into a sentence; the AI_SUGGESTION finding is not repeated");
        assertEquals(1, item.suggestions().size());
        ReviewSummary.Line line = item.suggestions().getFirst();
        assertEquals("Point 'sword_tex.png' at textures/sword_tex.png", line.text());
        assertTrue(line.ai());
        assertEquals(Optional.of("same base name"), line.reason());
        assertSame(correction, line.suggestion(), "the line selects the exact table row");
        assertTrue(item.nextStep().isEmpty());
        assertEquals(List.of("AI suggestions from OpenAI test"), summary.notes());
        assertTrue(summary.overview().startsWith("1 file to review, 1 with a problem. 1 suggestion (1 from AI, 0 from rules)."),
                summary.overview());
    }

    @Test
    void filesWithAProblemButNoSuggestionGetANextStepAndComeFirst() {
        var category = new Suggestion(UUID.randomUUID(), crate, AnalysisKind.CATEGORIES, "Props");
        var result = new AssetManager.AnalysisResult(1, List.of(category), List.of(
                finding(fbx, "props/crate_01.fbx", Finding.Severity.WARNING, "INSPECTION_UNSUPPORTED",
                        "Reference inspection is not supported for this format")));

        ReviewSummary summary = ReviewSummary.of(result, paths);

        assertEquals(List.of("props/crate_01.fbx", "props/crate.obj"), summary.items().stream().map(ReviewSummary.Item::file).toList());
        assertTrue(summary.items().get(0).nextStep().orElseThrow().contains("ACKNOWLEDGMENT"));
        ReviewSummary.Line rule = summary.items().get(1).suggestions().getFirst();
        assertEquals("Category: Props", rule.text());
        assertFalse(rule.ai());
        assertTrue(rule.reason().isEmpty());
    }

    @Test
    void suggestionsReadAsActions() {
        assertEquals("Rename to crate_01.fbx", ReviewSummary.describe(
                new Suggestion(UUID.randomUUID(), fbx, AnalysisKind.NAMES, "crate_01.fbx"), paths));
        assertEquals("Move to folder Models", ReviewSummary.describe(
                new Suggestion(UUID.randomUUID(), crate, AnalysisKind.FOLDERS, "Models"), paths));
        assertEquals("Move to the output folder's root", ReviewSummary.describe(
                new Suggestion(UUID.randomUUID(), crate, AnalysisKind.FOLDERS, ""), paths));
        assertEquals("Leave Uncategorized", ReviewSummary.describe(
                new Suggestion(UUID.randomUUID(), crate, AnalysisKind.CATEGORIES, "Uncategorized"), paths));
        assertEquals("Group with textures/sword_tex.png", ReviewSummary.describe(
                new Suggestion(UUID.randomUUID(), crate, AnalysisKind.RELATIONSHIPS, texture.toString()), paths));
    }

    @Test
    void unavailableAiIsExplainedAndAnEmptyCheckSaysSo() {
        var result = new AssetManager.AnalysisResult(1, List.of(), List.of(finding(null, null, Finding.Severity.WARNING,
                "AI_UNAVAILABLE", "AI suggestions unavailable (NOT_CONNECTED): no account; rule-based results kept")));
        ReviewSummary summary = ReviewSummary.of(result, paths);
        assertTrue(summary.items().isEmpty());
        assertTrue(summary.overview().startsWith("Nothing to review"));
        assertTrue(summary.notes().getFirst().startsWith("AI was unavailable, so only rule-based suggestions are shown."));
    }

    @Test
    void longResultsAreCappedAndTheRestCounted() {
        List<Suggestion> many = new ArrayList<>();
        Map<UUID, String> manyPaths = new HashMap<>();
        for (int i = 0; i < ReviewSummary.MAX_ITEMS + 5; i++) {
            UUID id = UUID.randomUUID();
            manyPaths.put(id, "props/item_%02d.obj".formatted(i));
            many.add(new Suggestion(UUID.randomUUID(), id, AnalysisKind.CATEGORIES, "Props"));
        }
        ReviewSummary summary = ReviewSummary.of(new AssetManager.AnalysisResult(1, many, List.of()), manyPaths);
        assertEquals(ReviewSummary.MAX_ITEMS, summary.shown().size());
        assertEquals(5, summary.hiddenItems());
        assertEquals("props/item_00.obj", summary.shown().getFirst().file());
        UUID last = many.getLast().assetId();
        assertEquals("props/item_44.obj", summary.itemFor(last).orElseThrow().file(),
                "a selected file past the listing limit is still found");
        assertTrue(summary.itemFor(UUID.randomUUID()).isEmpty());
    }
}
