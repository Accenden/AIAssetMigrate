package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.ai.SiwcInference.Model;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AiModelChoiceTest {
    private final Model fast = new Model("gpt-fast", "GPT Fast");
    private final Model plain = new Model("gpt-plain", "gpt-plain");
    private final List<Model> models = List.of(fast, plain);

    @Test
    void labelShowsDisplayNameAndSlugOnlyWhenTheyDiffer() {
        assertEquals("GPT Fast (gpt-fast)", AssetMigrateApplication.modelLabel(fast));
        assertEquals("gpt-plain", AssetMigrateApplication.modelLabel(plain));
        assertEquals("gpt-x", AssetMigrateApplication.modelLabel(new Model("gpt-x", "  ")));
    }

    @Test
    void savedModelIsSelectedOnlyWhenStillListed() {
        assertEquals(Optional.of(plain), AssetMigrateApplication.chosenModel(models, Optional.of("gpt-plain")));
        assertEquals(Optional.empty(), AssetMigrateApplication.chosenModel(models, Optional.of("gpt-retired")));
        assertEquals(Optional.empty(), AssetMigrateApplication.chosenModel(models, Optional.empty()));
    }

    @Test
    void statusExplainsTheSelection() {
        assertEquals("Using GPT Fast (gpt-fast).", AssetMigrateApplication.modelStatus(models, Optional.of("gpt-fast")));
        assertTrue(AssetMigrateApplication.modelStatus(models, Optional.of("gpt-retired"))
                .contains("gpt-retired is no longer available"));
        assertEquals("Choose a model to use AI features.", AssetMigrateApplication.modelStatus(models, Optional.empty()));
        assertEquals("No models are available to this account.",
                AssetMigrateApplication.modelStatus(List.of(), Optional.of("gpt-fast")));
    }
}
