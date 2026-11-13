package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Review step explains, for the selected suggestion, what Accept and Reject would each do. */
class DecisionHintTest {
    private final UUID model = UUID.randomUUID(), material = UUID.randomUUID();
    private final Map<UUID, String> paths = Map.of(model, "characters/Hero Model.obj", material, "characters/hero.mtl");

    private String hint(AnalysisKind kind, String value, Optional<String> reference) {
        return AssetMigrateApplication.decisionHint(
                new AssetManager.Suggestion(UUID.randomUUID(), model, kind, value, reference), paths);
    }

    @Test void everyHintStatesBothOutcomesAndTheSharedRules() {
        for (AnalysisKind kind : AnalysisKind.values()) {
            String value = kind == AnalysisKind.RELATIONSHIPS || kind == AnalysisKind.MATERIALS ? material.toString() : "Props";
            String text = hint(kind, value, Optional.of("Hero.mtl"));
            assertTrue(text.startsWith("Accept: "), text);
            assertTrue(text.contains("\nReject: "), text);
            assertTrue(text.contains("nothing is written until you approve and run a plan"), text);
            assertTrue(text.contains("a later decision for this file replaces this one"), text);
            assertTrue(text.contains("characters/Hero Model.obj") || kind == AnalysisKind.CATEGORIES && text.contains("Props"), text);
            assertFalse(text.contains(model.toString()), "files are named by path, not ID: " + text);
        }
    }

    @Test void outcomesAreSpecificToTheKindOfChange() {
        String rename = hint(AnalysisKind.NAMES, "hero_model.obj", Optional.empty());
        assertTrue(rename.contains("the output copy of characters/Hero Model.obj is named hero_model.obj"), rename);
        assertTrue(rename.contains("Reject: it keeps its current name."), rename);

        String move = hint(AnalysisKind.FOLDERS, "", Optional.empty());
        assertTrue(move.contains("goes in the output folder's root"), move);
        assertTrue(move.contains("Reject: it stays in its current folder."), move);

        String category = hint(AnalysisKind.CATEGORIES, "Characters", Optional.empty());
        assertTrue(category.contains("recorded under Characters") && category.contains("The file itself does not change."), category);
        assertTrue(hint(AnalysisKind.CATEGORIES, "Uncategorized", Optional.empty()).contains("recorded as Uncategorized"));

        String correction = hint(AnalysisKind.RELATIONSHIPS, material.toString(), Optional.of("Hero.mtl"));
        assertTrue(correction.contains("the plan uses characters/hero.mtl for 'Hero.mtl'"), correction);
        assertTrue(correction.contains("the plan stays blocked until you fix or exclude it"), correction);

        String grouping = hint(AnalysisKind.RELATIONSHIPS, material.toString(), Optional.empty());
        assertTrue(grouping.contains("belongs with characters/hero.mtl. The output does not change."), grouping);
        assertTrue(grouping.contains("Reject: the grouping is not recorded."), grouping);
    }
}
