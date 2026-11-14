package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DecisionSlotsTest {
    @TempDir Path directory;

    private final UUID hero = UUID.randomUUID(), mtl = UUID.randomUUID(), png = UUID.randomUUID();

    private static AssetManager.Suggestion suggestion(UUID asset, AnalysisKind kind, String value, String reference) {
        return new AssetManager.Suggestion(UUID.randomUUID(), asset, kind, value, Optional.ofNullable(reference));
    }

    private static ReviewedChange decision(UUID asset, ReviewedChange.Kind kind, ReviewedChange.Decision decision,
                                           Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, decision, values, Instant.EPOCH);
    }

    @Test void savedDecisionsSettleTheSameValueAndAnAcceptedSlotButNotARejectedOtherValue() {
        var acceptedCategory = suggestion(hero, AnalysisKind.CATEGORIES, "Characters", null);
        var otherCategory = suggestion(hero, AnalysisKind.CATEGORIES, "Uncategorized", null);
        var rejectedName = suggestion(hero, AnalysisKind.NAMES, "hero_model.obj", null);
        var newName = suggestion(hero, AnalysisKind.NAMES, "knight.obj", null);
        var correction = suggestion(mtl, AnalysisKind.MATERIALS, png.toString(), "tex.png");
        var otherReference = suggestion(mtl, AnalysisKind.MATERIALS, png.toString(), "bump.png");
        var grouping = suggestion(hero, AnalysisKind.RELATIONSHIPS, png.toString(), null);
        var undecided = suggestion(png, AnalysisKind.FOLDERS, "Textures", null);
        List<ReviewedChange> saved = List.of(
                decision(hero, ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED, Map.of("category", "Characters")),
                decision(hero, ReviewedChange.Kind.NAME, ReviewedChange.Decision.REJECTED, Map.of("name", "hero_model.obj")),
                decision(mtl, ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Decision.ACCEPTED,
                        Map.of("targetAssetId", png.toString(), "reference", "tex.png")),
                decision(hero, ReviewedChange.Kind.RELATIONSHIP, ReviewedChange.Decision.ACCEPTED, Map.of("targetAssetId", png.toString())),
                decision(png, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED, Map.of("reason", "x")));
        var decided = DecisionSlots.alreadyDecided(List.of(acceptedCategory, otherCategory, rejectedName, newName,
                correction, otherReference, grouping, undecided), saved);
        assertEquals(Map.of(acceptedCategory.id(), "Accepted", otherCategory.id(), "Not used: another suggestion was accepted",
                rejectedName.id(), "Rejected", correction.id(), "Accepted", grouping.id(), "Accepted"), decided);
    }

    @Test void acceptedSuggestionsStayHiddenAfterSwitchingChecksAndComingBack() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source/props"));
        Files.writeString(source.resolve("crate.obj"), "mtllib crate.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl wood\nf 1 2 3\n");
        Files.writeString(source.resolve("crate.mtl"), "newmtl wood\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(directory.resolve("source")).snapshot().project().id();
            var categories = AssetMigrateApplication.checkRun(manager, id, manager.analyze(id, AnalysisKind.CATEGORIES));
            assertTrue(categories.saved().isEmpty());
            AcceptAll batch = AcceptAll.of(categories.result().suggestions());
            manager.review(id, categories.result().inventoryRevision(), batch.changes());

            var relationships = AssetMigrateApplication.checkRun(manager, id, manager.analyze(id, AnalysisKind.RELATIONSHIPS));
            assertTrue(DecisionSlots.alreadyDecided(relationships.result().suggestions(), relationships.saved()).isEmpty(),
                    "another check's suggestions are still open");
            var again = AssetMigrateApplication.checkRun(manager, id, manager.analyze(id, AnalysisKind.CATEGORIES));
            assertFalse(again.result().suggestions().isEmpty());
            assertEquals(again.result().suggestions().size(),
                    DecisionSlots.alreadyDecided(again.result().suggestions(), again.saved()).size(), "nothing pops up again");
        }
    }
}
