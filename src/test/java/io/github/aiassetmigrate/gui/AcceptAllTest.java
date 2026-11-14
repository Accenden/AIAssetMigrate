package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AcceptAllTest {
    private final UUID hero = UUID.randomUUID(), sword = UUID.randomUUID(), rock = UUID.randomUUID(),
            mtl = UUID.randomUUID(), png = UUID.randomUUID();

    private static AssetManager.Suggestion suggestion(UUID asset, AnalysisKind kind, String value, String reference,
                                                      AssetManager.Source source) {
        return new AssetManager.Suggestion(UUID.randomUUID(), asset, kind, value, Optional.ofNullable(reference), source);
    }

    @Test void acceptsEverythingThatDoesNotCompete() {
        var name = suggestion(hero, AnalysisKind.NAMES, "hero_model.obj", null, AssetManager.Source.RULES);
        var folder = suggestion(hero, AnalysisKind.FOLDERS, "Models", null, AssetManager.Source.AI);
        var correction = suggestion(sword, AnalysisKind.RELATIONSHIPS, png.toString(), "sword_tex.png", AssetManager.Source.RULES);
        var grouping = suggestion(sword, AnalysisKind.RELATIONSHIPS, png.toString(), null, AssetManager.Source.RULES);
        var otherGrouping = suggestion(sword, AnalysisKind.RELATIONSHIPS, mtl.toString(), null, AssetManager.Source.RULES);
        var batch = AcceptAll.of(List.of(name, folder, correction, grouping, otherGrouping));
        assertEquals(List.of(name, folder, correction, grouping, otherGrouping), batch.covered());
        assertEquals(5, batch.changes().size());
        assertTrue(batch.changes().stream().allMatch(c -> c.decision() == ReviewedChange.Decision.ACCEPTED));
        assertEquals("sword_tex.png", batch.changes().get(2).values().get("reference"), "references are kept, so corrections apply");
        assertTrue(batch.conflicting().isEmpty() && batch.invalid().isEmpty());
        assertEquals(1, batch.fromAi());
    }

    @Test void whenTheRulesAndTheAiDisagreeTheAiSuggestionIsAcceptedAndTheRuleSetAside() {
        // The AI is asked about exactly the files the rules left Uncategorized: the reported case.
        var uncategorized = suggestion(sword, AnalysisKind.CATEGORIES, "Uncategorized", null, AssetManager.Source.RULES);
        var aiCategory = suggestion(sword, AnalysisKind.CATEGORIES, "Weapons", null, AssetManager.Source.AI);
        var ruleName = suggestion(hero, AnalysisKind.NAMES, "hero_model.obj", null, AssetManager.Source.RULES);
        var aiName = suggestion(hero, AnalysisKind.NAMES, "knight.obj", null, AssetManager.Source.AI);
        var ruleFix = suggestion(mtl, AnalysisKind.RELATIONSHIPS, png.toString(), "tex.png", AssetManager.Source.RULES);
        var aiFix = suggestion(mtl, AnalysisKind.RELATIONSHIPS, rock.toString(), "tex.png", AssetManager.Source.AI);
        var batch = AcceptAll.of(List.of(uncategorized, aiCategory, ruleName, aiName, ruleFix, aiFix));
        assertEquals(List.of(aiCategory, aiName, aiFix), batch.covered());
        assertEquals(List.of(uncategorized, ruleName, ruleFix), batch.setAside(), "set aside, so they leave the table too");
        assertTrue(batch.conflicting().isEmpty(), "nothing is left behind");
        assertEquals(List.of("Weapons", "knight.obj", rock.toString()), batch.changes().stream()
                .map(c -> c.values().getOrDefault("category", c.values().getOrDefault("name", c.values().get("targetAssetId")))).toList());
        assertEquals("tex.png", batch.changes().get(2).values().get("reference"));
        assertEquals(3, batch.aiPreferredFiles());
    }

    @Test void identicalSuggestionsAreAcceptedOnceAndRealTiesAreLeft() {
        var ruleCategory = suggestion(rock, AnalysisKind.CATEGORIES, "Environment", null, AssetManager.Source.RULES);
        var aiCategory = suggestion(rock, AnalysisKind.CATEGORIES, "Environment", null, AssetManager.Source.AI);
        // Two equally named textures could fix one broken reference: guessing could rewrite it wrongly.
        var fixA = suggestion(sword, AnalysisKind.MATERIALS, png.toString(), "tex.png", AssetManager.Source.RULES);
        var fixB = suggestion(sword, AnalysisKind.MATERIALS, mtl.toString(), "tex.png", AssetManager.Source.RULES);
        var batch = AcceptAll.of(List.of(ruleCategory, aiCategory, fixA, fixB));
        assertEquals(List.of(fixA, fixB), batch.conflicting());
        assertEquals(1, batch.conflictingFiles());
        assertEquals(List.of(ruleCategory, aiCategory), batch.covered(), "both rows leave the table");
        assertTrue(batch.setAside().isEmpty());
        assertEquals(1, batch.changes().size(), "the same decision is saved once");
        assertEquals(1, batch.fromAi());
    }

    @Test void aRuleTieThatTheAiSettlesIsAccepted() {
        var fixA = suggestion(sword, AnalysisKind.MATERIALS, png.toString(), "tex.png", AssetManager.Source.RULES);
        var fixB = suggestion(sword, AnalysisKind.MATERIALS, mtl.toString(), "tex.png", AssetManager.Source.RULES);
        var aiFix = suggestion(sword, AnalysisKind.MATERIALS, png.toString(), "tex.png", AssetManager.Source.AI);
        var batch = AcceptAll.of(List.of(fixA, fixB, aiFix));
        assertEquals(List.of(fixA, aiFix), batch.covered());
        assertEquals(List.of(fixB), batch.setAside());
        assertEquals(1, batch.changes().size());
    }

    @Test void suggestionsThatCannotBeSavedAreLeftForEditing() {
        var mappingWithoutReference = suggestion(sword, AnalysisKind.MATERIALS, png.toString(), null, AssetManager.Source.RULES);
        var batch = AcceptAll.of(List.of(mappingWithoutReference));
        assertEquals(List.of(mappingWithoutReference), batch.invalid());
        assertTrue(batch.changes().isEmpty() && batch.covered().isEmpty());
    }

    @Test void confirmationSaysWhatIsSavedWhatIsLeftAndThatNothingIsWritten() {
        var batch = AcceptAll.of(List.of(
                suggestion(hero, AnalysisKind.NAMES, "a.obj", null, AssetManager.Source.RULES),
                suggestion(hero, AnalysisKind.NAMES, "b.obj", null, AssetManager.Source.AI),
                suggestion(rock, AnalysisKind.FOLDERS, "Models", null, AssetManager.Source.AI),
                suggestion(sword, AnalysisKind.MATERIALS, png.toString(), "tex.png", AssetManager.Source.RULES),
                suggestion(sword, AnalysisKind.MATERIALS, mtl.toString(), "tex.png", AssetManager.Source.RULES)));
        String text = AssetMigrateApplication.acceptAllMessage(batch);
        assertTrue(text.contains("2 of them come from AI"), text);
        assertTrue(text.contains("For 1 file(s) the rules and the AI suggest different values; the AI's suggestion is accepted"), text);
        assertTrue(text.contains("1 file(s) have several equally likely suggestions (2 in all)"), text);
        assertTrue(text.contains("Nothing is written until you approve and run a plan"), text);
        assertFalse(text.contains("cannot be saved"), text);
    }
}
