package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MaterialAnalysisTest {
    private final Map<UUID, byte[]> contents = new HashMap<>();
    private final List<Asset> assets = new ArrayList<>();

    private Asset asset(String path, Asset.Type type, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Asset asset = new Asset(UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)), Path.of(path), type,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), Map.of());
        assets.add(asset); contents.put(asset.id(), bytes); return asset;
    }
    private InventorySnapshot snapshot(InventorySnapshot.ScanStatus status, List<PathOutcome> outcomes) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InventorySnapshot(1, 1, new Project(UUID.randomUUID(), Path.of("/source"), 1, assets, now),
                List.of(), status, now, outcomes, List.of());
    }
    private MaterialAnalysis.Result analyze(InventorySnapshot snapshot) {
        return MaterialAnalysis.analyze(snapshot, (asset, max) -> contents.get(asset.id()), MaterialAnalysis.Limits.defaults());
    }

    @Test void linksVerifiedMaterialsAndTexturesDeterministically() throws Exception {
        Asset obj = asset("models/a.obj", Asset.Type.MODEL, "mtllib ../mat/a.mtl\nusemtl Paint\n");
        Asset mtl = asset("mat/a.mtl", Asset.Type.MATERIAL, "newmtl Paint\nmap_Kd ../tex/a.png\n");
        asset("tex/a.png", Asset.Type.TEXTURE, "image");
        InventorySnapshot snapshot = snapshot(InventorySnapshot.ScanStatus.COMPLETE, List.of());
        var result = analyze(snapshot);
        assertEquals(result, analyze(snapshot));
        assertEquals(List.of(new MaterialAnalysis.Mapping(obj.id(), "Paint", MaterialAnalysis.MappingStatus.RESOLVED,
                Optional.of(mtl.id()))), result.mappings());
        assertEquals("Paint", result.textures().getFirst().materialName().orElseThrow());
        assertTrue(result.references().stream().allMatch(r -> r.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED));
    }

    @Test void classifiesUnsafeUnresolvedSkippedAndAmbiguous() throws Exception {
        asset("a.obj", Asset.Type.MODEL, "mtllib ../escape.mtl absent.mtl skip/x.mtl MAT.mtl\nusemtl x\n");
        asset("mat.mtl", Asset.Type.MATERIAL, "newmtl x\n");
        var result = analyze(snapshot(InventorySnapshot.ScanStatus.COMPLETE,
                List.of(new PathOutcome(Path.of("skip"), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.SKIPPED))));
        assertEquals(List.of(AssetReference.ResolutionStatus.UNSAFE, AssetReference.ResolutionStatus.UNRESOLVED,
                AssetReference.ResolutionStatus.SKIPPED, AssetReference.ResolutionStatus.CASE_AMBIGUOUS),
                result.references().stream().map(AssetReference::resolutionStatus).toList());
        assertEquals(MaterialAnalysis.MappingStatus.UNVERIFIED, result.mappings().getFirst().status());
    }

    @Test void duplicateDefinitionsAndUnverifiedContentNeverProduceResolvedMapping() throws Exception {
        asset("a.obj", Asset.Type.MODEL, "mtllib a.mtl b.mtl\nusemtl x\n");
        asset("a.mtl", Asset.Type.MATERIAL, "newmtl x\nnewmtl x\n");
        Asset b = asset("b.mtl", Asset.Type.MATERIAL, "newmtl y\n");
        InventorySnapshot snapshot = snapshot(InventorySnapshot.ScanStatus.COMPLETE, List.of());
        assertEquals(MaterialAnalysis.MappingStatus.AMBIGUOUS, analyze(snapshot).mappings().getFirst().status());
        contents.put(b.id(), "changed".getBytes(StandardCharsets.UTF_8));
        var result = analyze(snapshot);
        assertTrue(result.files().stream().anyMatch(f -> f.status() == MaterialAnalysis.Inspection.FINGERPRINT_MISMATCH));
        assertNotEquals(MaterialAnalysis.MappingStatus.RESOLVED, result.mappings().getFirst().status());
    }

    @Test void budgetsAndUnsupportedTextureSyntaxAreExplicit() throws Exception {
        asset("a.mtl", Asset.Type.MATERIAL, "newmtl x\nmap_Kd -s 1 1 1 x.png\n");
        var snapshot = snapshot(InventorySnapshot.ScanStatus.PARTIAL, List.of());
        assertEquals(AssetReference.ResolutionStatus.UNRESOLVED, analyze(snapshot).references().getFirst().resolutionStatus());
        var limited = MaterialAnalysis.analyze(snapshot, (asset, max) -> contents.get(asset.id()),
                new MaterialAnalysis.Limits(1, 4, 4, 1));
        assertTrue(limited.truncated());
        assertEquals(MaterialAnalysis.Inspection.BUDGET_EXCEEDED, limited.files().getFirst().status());
        assertTrue(limited.references().isEmpty());
    }
}
