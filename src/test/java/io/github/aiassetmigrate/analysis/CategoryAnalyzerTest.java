package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.domain.Asset;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CategoryAnalyzerTest {
    private final CategoryAnalyzer analyzer = new CategoryAnalyzer();

    @Test
    void suggestsCategoriesFromWholePathTokensWithoutReplacingFileTypes() {
        Asset character = asset("Characters/heroKnight.fbx", Asset.Type.MODEL);
        Asset weapon = asset("props/SWORD_01.png", Asset.Type.TEXTURE);
        Asset texture = asset("textures/stone.png", Asset.Type.TEXTURE);
        var result = analyzer.analyze(List.of(character, weapon, texture), 1);

        assertEquals(List.of("Characters", "Weapons", "Textures"), result.suggestions().stream()
                .map(s -> s.proposedValue()).toList());
        assertEquals(List.of(character.id(), weapon.id(), texture.id()), result.suggestions().stream()
                .map(s -> s.assetId()).toList());
        assertTrue(result.suggestions().stream().allMatch(s -> s.kind() == AnalysisKind.CATEGORIES));
        assertEquals(Asset.Type.TEXTURE, weapon.type());
        assertEquals("png", weapon.metadata().get(Asset.FORMAT));
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void missingConflictingAndPartialSignalsAreUncategorized() {
        var assets = List.of(asset("misc/unknown.png", Asset.Type.TEXTURE),
                asset("characters/weapon.fbx", Asset.Type.MODEL),
                asset("misc/swordfish.fbx", Asset.Type.MODEL),
                asset("misc/object.weapon", Asset.Type.OTHER));
        assertEquals(List.of("Uncategorized", "Uncategorized", "Uncategorized", "Uncategorized"),
                analyzer.analyze(assets, 1).suggestions().stream().map(s -> s.proposedValue()).toList());
    }

    @Test
    void resultsAreRepeatableAndImmutable() {
        var assets = List.of(asset("weapons/rifle.fbx", Asset.Type.MODEL));
        var result = analyzer.analyze(assets, 1);
        assertEquals(result, analyzer.analyze(assets, 1));
        assertThrows(UnsupportedOperationException.class, () -> result.suggestions().clear());
        assertTrue(analyzer.analyze(List.of(), 1).suggestions().isEmpty());
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null, 1));
    }

    private static Asset asset(String path, Asset.Type type) {
        return new Asset(UUID.nameUUIDFromBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Path.of(path), type, "0".repeat(64), Map.of(Asset.FORMAT, "png"));
    }
}
