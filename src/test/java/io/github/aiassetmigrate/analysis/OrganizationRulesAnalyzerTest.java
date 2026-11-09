package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationRulesAnalyzerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Asset asset(String path, Asset.Type type) {
        return new Asset(UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)), Path.of(path),
                type, "0".repeat(64), Map.of());
    }

    private static InventorySnapshot snapshot(InventorySnapshot.ScanStatus status, List<Asset> assets, List<AssetReference> references) {
        return new InventorySnapshot(1, 1, new Project(PROJECT, Path.of("/source"), 3, assets, NOW),
                List.of(), status, NOW, List.of(), references);
    }

    private static AssetManager.AnalysisResult analyze(List<Asset> assets, AssetManager.NameStyle name, AssetManager.FolderStyle folder) {
        return OrganizationRulesAnalyzer.analyze(snapshot(InventorySnapshot.ScanStatus.COMPLETE, assets, List.of()),
                new AssetManager.OrganizationRules(name, folder));
    }

    private static String value(AssetManager.AnalysisResult result, Asset asset, AssetManager.AnalysisKind kind) {
        return result.suggestions().stream().filter(s -> s.assetId().equals(asset.id()) && s.kind() == kind)
                .findFirst().orElseThrow().proposedValue();
    }

    @Test void appliesStylesAndPreservesExactExtensionAndFolders() {
        Asset model = asset("old/HeroHTTPModel.FbX", Asset.Type.MODEL);
        Asset material = asset("old/Some Material.mTL", Asset.Type.MATERIAL);
        Asset texture = asset("image.PNG", Asset.Type.TEXTURE);
        Asset other = asset("notes", Asset.Type.OTHER);
        var result = analyze(List.of(model, material, texture, other), AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.BY_TYPE);
        assertEquals("hero_http_model.FbX", value(result, model, AssetManager.AnalysisKind.NAMES));
        assertEquals("some_material.mTL", value(result, material, AssetManager.AnalysisKind.NAMES));
        assertEquals("Models", value(result, model, AssetManager.AnalysisKind.FOLDERS));
        assertEquals("Materials", value(result, material, AssetManager.AnalysisKind.FOLDERS));
        assertEquals("Textures", value(result, texture, AssetManager.AnalysisKind.FOLDERS));
        assertEquals("Other", value(result, other, AssetManager.AnalysisKind.FOLDERS));
        assertEquals(8, result.suggestions().size());
        assertTrue(result.findings().isEmpty());
        var kebab = analyze(List.of(model), AssetManager.NameStyle.KEBAB_CASE, AssetManager.FolderStyle.PRESERVE);
        assertEquals("hero-http-model.FbX", value(kebab, model, AssetManager.AnalysisKind.NAMES));
        assertEquals("old", value(kebab, model, AssetManager.AnalysisKind.FOLDERS));
        var preserve = analyze(List.of(model, other), AssetManager.NameStyle.PRESERVE, AssetManager.FolderStyle.PRESERVE);
        assertEquals("HeroHTTPModel.FbX", value(preserve, model, AssetManager.AnalysisKind.NAMES));
        assertEquals("", value(preserve, other, AssetManager.AnalysisKind.FOLDERS));
    }

    @Test void preservesUnicodeUnsafeAndEmptyStemsWithoutDroppingCharacters() {
        for (String filename : List.of("Caf\u00e9 Model.PNG", "e\u0301.png", "\u4e16\u754c.obj", "---.obj", ".hidden", "bad?.png")) {
            Asset a = asset(filename, Asset.Type.OTHER);
            var result = analyze(List.of(a), AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.PRESERVE);
            assertEquals(filename, value(result, a, AssetManager.AnalysisKind.NAMES));
        }
    }

    @Test void reportsPortableAndCaseInsensitiveCollisionsAndInvalidDestinations() {
        Asset a = asset("a/Hero Model.PNG", Asset.Type.MODEL);
        Asset b = asset("b/hero_model.png", Asset.Type.MODEL);
        Asset invalid = asset("CON.txt", Asset.Type.OTHER);
        var result = analyze(List.of(a, b, invalid), AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.BY_TYPE);
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("DESTINATION_CONFLICT")).count());
        assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("INVALID_DESTINATION") && f.assetId().equals(Optional.of(invalid.id()))));
        assertTrue(result.findings().stream().allMatch(f -> f.severity() == Finding.Severity.ERROR));
    }

    @Test void blocksPartialInventoryWithoutClaimingReferenceValidation() {
        Asset a = asset("a.obj", Asset.Type.MODEL);
        var ref = new AssetReference(a.id(), AssetReference.Kind.MATERIAL_LIBRARY, "missing.mtl", Optional.empty(), AssetReference.ResolutionStatus.UNRESOLVED);
        var snapshot = snapshot(InventorySnapshot.ScanStatus.PARTIAL, List.of(a), List.of(ref));
        var result = OrganizationRulesAnalyzer.analyze(snapshot, new AssetManager.OrganizationRules(AssetManager.NameStyle.PRESERVE, AssetManager.FolderStyle.PRESERVE));
        assertEquals(List.of("PARTIAL_INVENTORY"), result.findings().stream().map(Finding::code).toList());
        assertEquals(Finding.Severity.ERROR, result.findings().getFirst().severity());
        assertTrue(result.findings().getFirst().assetId().isEmpty());
    }

    @Test void outputAndIdsAreStableAcrossOrderAndRepeatedCalls() {
        Asset a = asset("a/Hero.png", Asset.Type.TEXTURE), b = asset("b/hero.PNG", Asset.Type.TEXTURE);
        var first = analyze(List.of(a, b), AssetManager.NameStyle.KEBAB_CASE, AssetManager.FolderStyle.BY_TYPE);
        assertEquals(first, analyze(List.of(b, a), AssetManager.NameStyle.KEBAB_CASE, AssetManager.FolderStyle.BY_TYPE));
        assertEquals(first, analyze(List.of(a, b), AssetManager.NameStyle.KEBAB_CASE, AssetManager.FolderStyle.BY_TYPE));
        assertEquals(3, first.inventoryRevision());
        assertEquals(4, first.suggestions().stream().map(AssetManager.Suggestion::id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> first.suggestions().clear());
    }
}
