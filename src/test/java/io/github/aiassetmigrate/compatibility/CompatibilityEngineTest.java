package io.github.aiassetmigrate.compatibility;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityEngineTest {
    private static final Instant TIME = Instant.parse("2024-01-01T00:00:00Z");
    private static final AssetManager.TargetProfile TARGET = new AssetManager.TargetProfile("godot", "4.3");
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");

    @Test
    void profileAndSnapshotVersionsAreExact() {
        assertEquals(List.of(TARGET, UNITY), CompatibilityEngine.profiles());
        assertThrows(UnsupportedOperationException.class, () -> CompatibilityEngine.profiles().clear());
        InventorySnapshot snapshot = snapshot(List.of(), List.of(), List.of(), InventorySnapshot.ScanStatus.COMPLETE);
        assertThrows(IllegalArgumentException.class, () -> CompatibilityEngine.check(snapshot, new AssetManager.TargetProfile("godot", "4.2")));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityEngine.check(snapshot, new AssetManager.TargetProfile("unity", "6000.1")));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityEngine.check(snapshot, new AssetManager.TargetProfile("other", "4.3")));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityEngine.check(new InventorySnapshot(2, 1, snapshot.project(), List.of(), snapshot.scanStatus(), TIME, List.of(), List.of()), TARGET));
        assertThrows(IllegalArgumentException.class, () -> CompatibilityEngine.check(new InventorySnapshot(1, 2, snapshot.project(), List.of(), snapshot.scanStatus(), TIME, List.of(), List.of()), TARGET));
    }

    @Test
    void unityEvidenceAndProfileSpecificRules() {
        Asset obj = asset("mesh.obj", Asset.Type.MODEL, "obj", "UNKNOWN");
        Asset fbx = asset("mesh.fbx", Asset.Type.MODEL, "fbx", "HEADER_ONLY");
        Asset blend = asset("scene.blend", Asset.Type.MODEL, "blend", "UNVERIFIED");
        Asset png = asset("image.png", Asset.Type.TEXTURE, "png", "UNVERIFIED");
        Asset psd = asset("image.psd", Asset.Type.TEXTURE, "psd", "UNKNOWN");
        Asset mtl = asset("material.mtl", Asset.Type.MATERIAL, "mtl", "UNVERIFIED");
        Asset gltf = asset("scene.gltf", Asset.Type.MODEL, "gltf", "UNKNOWN");
        InventorySnapshot input = snapshot(List.of(obj, fbx, blend, png, psd, mtl, gltf), List.of(), List.of(), InventorySnapshot.ScanStatus.COMPLETE);
        List<Finding> unity = CompatibilityEngine.check(input, UNITY);
        assertEquals(unity, CompatibilityEngine.check(input, UNITY));
        assertEquals(7, unity.stream().filter(f -> f.code().equals("CONTENT_UNVERIFIED") && f.severity() == Finding.Severity.WARNING).count());
        assertEquals(5, unity.stream().filter(f -> f.code().equals("DOCUMENTED_FORMAT_CANDIDATE") && f.message().contains("Unity 6000.0") && f.message().contains("unverified")).count());
        assertTrue(unity.stream().anyMatch(f -> f.code().equals("TARGET_SUPPORT_UNKNOWN") && f.assetId().equals(Optional.of(mtl.id())) && f.message().contains("fidelity")));
        assertTrue(unity.stream().anyMatch(f -> f.code().equals("TARGET_SUPPORT_UNKNOWN") && f.assetId().equals(Optional.of(gltf.id()))));
        for (String code : List.of("BLENDER_REQUIRED", "OBJ_MATERIAL_FIDELITY_UNKNOWN", "TEXTURE_LAYERS_FLATTENED", "CONVERTER_AVAILABILITY_UNKNOWN")) {
            assertTrue(unity.stream().anyMatch(f -> f.code().equals(code) && f.severity() == Finding.Severity.WARNING), code);
        }
        assertFalse(unity.stream().anyMatch(f -> f.code().equals("PNG_PRECISION") || f.code().equals("OBJ_LIMITATIONS")));
        List<Finding> godot = CompatibilityEngine.check(input, TARGET);
        assertTrue(godot.stream().anyMatch(f -> f.code().equals("PNG_PRECISION")));
        assertNotEquals(godot.getFirst().id(), unity.getFirst().id());
    }

    @Test
    void reportsEvidenceSeparatelyAndRetainsInventory() {
        Asset png = asset("fake.png", Asset.Type.TEXTURE, "png", "UNVERIFIED");
        Asset obj = asset("mesh.obj", Asset.Type.MODEL, "obj", "HEADER_ONLY");
        Asset blend = asset("scene.blend", Asset.Type.MODEL, "blend", "UNKNOWN");
        Asset unknown = asset("other.xyz", Asset.Type.OTHER, "xyz", "NOT_PERFORMED");
        AssetReference ref = new AssetReference(obj.id(), AssetReference.Kind.MATERIAL_LIBRARY, "missing.mtl",
                Optional.of(Path.of("missing.mtl")), AssetReference.ResolutionStatus.MISSING);
        PathOutcome skipped = new PathOutcome(Path.of("skipped.xyz"), PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED);
        InventorySnapshot input = snapshot(List.of(png, obj, blend, unknown), List.of(skipped), List.of(ref), InventorySnapshot.ScanStatus.PARTIAL);
        List<Finding> findings = CompatibilityEngine.check(input, TARGET);
        assertEquals(findings, CompatibilityEngine.check(input, TARGET));
        assertEquals(4, findings.stream().filter(f -> f.code().equals("CONTENT_UNVERIFIED") && f.severity() == Finding.Severity.WARNING).count());
        assertEquals(3, findings.stream().filter(f -> f.code().equals("DOCUMENTED_FORMAT_CANDIDATE")).count());
        for (String code : List.of("CONVERTER_AVAILABILITY_UNKNOWN", "PARTIAL_INVENTORY", "COVERAGE_GAP", "REFERENCE_MISSING", "TARGET_SUPPORT_UNKNOWN", "PNG_PRECISION", "OBJ_LIMITATIONS", "BLENDER_REQUIRED")) {
            assertTrue(findings.stream().anyMatch(f -> f.code().equals(code) && f.severity() == Finding.Severity.WARNING), code);
        }
        assertTrue(findings.stream().filter(f -> f.code().equals("DOCUMENTED_FORMAT_CANDIDATE")).allMatch(f -> f.message().contains("unverified")));
        assertEquals(List.of(png, obj, blend, unknown), input.project().assets());
        assertEquals(List.of(ref), input.references());
        assertThrows(UnsupportedOperationException.class, findings::clear);
    }

    @Test
    void doesNotDropAssetsOrAssumeFilenameIsEvidence() {
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < 2000; i++) assets.add(asset(i + ".png", Asset.Type.TEXTURE, "xyz", "UNKNOWN"));
        List<Finding> findings = CompatibilityEngine.check(snapshot(assets, List.of(), List.of(), InventorySnapshot.ScanStatus.COMPLETE), TARGET);
        assertEquals(2000, findings.stream().filter(f -> f.code().equals("CONTENT_UNVERIFIED")).count());
        assertEquals(2000, findings.stream().filter(f -> f.code().equals("TARGET_SUPPORT_UNKNOWN")).count());
        assertFalse(findings.stream().anyMatch(f -> f.code().equals("DOCUMENTED_FORMAT_CANDIDATE")));
        assertEquals(findings.size(), findings.stream().map(Finding::id).distinct().count());
    }

    private static Asset asset(String path, Asset.Type type, String format, String status) {
        return new Asset(UUID.nameUUIDFromBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Path.of(path), type,
                "0".repeat(64), Map.of(Asset.FORMAT, format, Asset.INSPECTION_STATUS, status));
    }

    private static InventorySnapshot snapshot(List<Asset> assets, List<PathOutcome> paths, List<AssetReference> refs, InventorySnapshot.ScanStatus status) {
        Project project = new Project(UUID.fromString("12345678-1234-1234-1234-123456789abc"), Path.of("/source"), 1, assets, TIME);
        return new InventorySnapshot(1, 1, project, List.of(), status, TIME, paths, refs);
    }
}
