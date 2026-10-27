package io.github.aiassetmigrate.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AssetManagerContractTest {
    @Test
    void analysisSnapshotsSuggestionsWithoutAcceptingThem() {
        var suggestions = new ArrayList<AssetManager.Suggestion>();
        suggestions.add(new AssetManager.Suggestion(UUID.randomUUID(), UUID.randomUUID(),
                AssetManager.AnalysisKind.CATEGORIES, "Characters"));
        var result = new AssetManager.AnalysisResult(suggestions, List.of());
        suggestions.clear();
        assertEquals(1, result.suggestions().size());
        assertThrows(UnsupportedOperationException.class, () -> result.suggestions().clear());
    }

    @Test
    void targetProfileRequiresAnIdentityAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> new AssetManager.TargetProfile("", "1"));
        assertThrows(NullPointerException.class, () -> new AssetManager.TargetProfile("unity", null));
        assertEquals("1", new AssetManager.TargetProfile("unity-6", "1").version());
    }
}
