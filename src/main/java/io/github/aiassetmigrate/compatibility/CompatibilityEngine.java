package io.github.aiassetmigrate.compatibility;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Offline, bounded-evidence checks; these do not validate files or perform conversions.
 * Godot 4.3 sources: https://docs.godotengine.org/en/4.3/tutorials/assets_pipeline/importing_3d_scenes/available_formats.html
 * and https://docs.godotengine.org/en/4.3/tutorials/assets_pipeline/importing_images.html
 * Unity 6.0 sources: https://docs.unity3d.com/6000.0/Documentation/Manual/3D-formats.html
 * and https://docs.unity3d.com/6000.0/Documentation/Manual/ImportingTextures.html
 */
public final class CompatibilityEngine {
    private static final AssetManager.TargetProfile GODOT = new AssetManager.TargetProfile("godot", "4.3");
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    private static final Set<String> MODELS = Set.of("gltf", "glb", "blend", "dae", "obj", "fbx");
    private static final Set<String> IMAGES = Set.of("bmp", "dds", "ktx", "exr", "hdr", "jpg", "jpeg", "png", "tga", "svg", "webp");
    private static final Set<String> UNITY_MODELS = Set.of("fbx", "dae", "dxf", "obj", "blend");
    private static final Set<String> UNITY_IMAGES = Set.of("bmp", "exr", "gif", "hdr", "iff", "jpg", "pict", "png", "psd", "tga", "tiff");

    private CompatibilityEngine() { }

    public static List<AssetManager.TargetProfile> profiles() {
        return List.of(GODOT, UNITY);
    }

    /** Returns only new compatibility findings; retain the snapshot and its original findings separately. */
    public static List<Finding> check(InventorySnapshot snapshot, AssetManager.TargetProfile target) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(target, "target");
        if (!GODOT.equals(target) && !UNITY.equals(target)) {
            throw new IllegalArgumentException("Unknown target profile or version: " + target);
        }
        if (snapshot.schemaVersion() != InventorySnapshot.CURRENT_SCHEMA_VERSION
                || snapshot.inspectionVersion() != InventorySnapshot.CURRENT_INSPECTION_VERSION) {
            throw new IllegalArgumentException("Unsupported inventory schema or inspection version");
        }
        List<Finding> result = new ArrayList<>();
        add(result, snapshot, target, null, null, Finding.Severity.WARNING, "CONVERTER_AVAILABILITY_UNKNOWN",
                "Converter availability was not checked; no conversion choices are asserted.");
        if (!snapshot.hasCompleteCoverage()) {
            add(result, snapshot, target, null, null, Finding.Severity.WARNING, "PARTIAL_INVENTORY",
                    "The scan is partial; compatibility cannot be assessed for unvisited entries.");
        }
        Map<UUID, Asset> assets = new HashMap<>();
        for (Asset asset : snapshot.project().assets()) {
            assets.put(asset.id(), asset);
            String format = asset.metadata().get(Asset.FORMAT);
            String status = asset.metadata().get(Asset.INSPECTION_STATUS);
            if (status == null || !Set.of("NOT_PERFORMED", "BASIC_CHECKED", "HEADER_ONLY", "UNKNOWN", "UNVERIFIED").contains(status)) {
                status = "missing or unrecognized";
            }
            add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "CONTENT_UNVERIFIED",
                    "Inspection status " + status
                            + " does not establish complete format validity or successful import.");
            boolean godot = GODOT.equals(target);
            if (format != null && ((asset.type() == Asset.Type.MODEL && (godot ? MODELS : UNITY_MODELS).contains(format))
                    || (asset.type() == Asset.Type.TEXTURE && (godot ? IMAGES : UNITY_IMAGES).contains(format))
                    || (godot && asset.type() == Asset.Type.MATERIAL && format.equals("mtl")))) {
                add(result, snapshot, target, asset, null, Finding.Severity.INFO, "DOCUMENTED_FORMAT_CANDIDATE",
                        "The recorded " + format + " format is documented for " + (godot ? "Godot 4.3" : "Unity 6000.0")
                                + "; actual content and import are unverified.");
                if (format.equals("blend") && godot) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "BLENDER_REQUIRED",
                            "Godot .blend import requires Blender 3.0 or later; its availability was not checked.");
                } else if (format.equals("blend")) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "BLENDER_REQUIRED",
                            "Unity .blend import requires Blender installed; its availability was not checked.");
                } else if (godot && format.equals("obj")) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "OBJ_LIMITATIONS",
                            "Godot documents OBJ limitations including no skinning, animation, UV2 or PBR materials.");
                } else if (!godot && format.equals("obj")) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "OBJ_MATERIAL_FIDELITY_UNKNOWN",
                            "Unity OBJ material and MTL handling and fidelity are not established by this profile.");
                } else if (godot && format.equals("png")) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "PNG_PRECISION",
                            "Godot documents PNG import precision limited to 8 bits per channel; source precision is unknown.");
                } else if (godot && (format.equals("bmp") || format.equals("ktx") || format.equals("svg"))) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "FORMAT_LIMITATIONS",
                            "Godot documents restrictions for " + format + "; the relevant content was not checked.");
                } else if (!godot && (format.equals("psd") || format.equals("tiff"))) {
                    add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "TEXTURE_LAYERS_FLATTENED",
                            "Unity flattens imported PSD and TIFF layers; the source file is not changed.");
                }
            } else {
                add(result, snapshot, target, asset, null, Finding.Severity.WARNING, "TARGET_SUPPORT_UNKNOWN",
                        !godot && asset.type() == Asset.Type.MATERIAL && "mtl".equals(format)
                                ? "Unity MTL handling and material fidelity are not established by this profile."
                                : "Target support for the recorded type and format is not established by this profile.");
            }
        }
        for (PathOutcome outcome : snapshot.pathOutcomes()) {
            if (outcome.status() == PathOutcome.Status.SKIPPED || outcome.status() == PathOutcome.Status.FAILED
                    || outcome.status() == PathOutcome.Status.UNVISITED) {
                add(result, snapshot, target, null, outcome.relativePath(), Finding.Severity.WARNING, "COVERAGE_GAP",
                        "Inventory entry was " + outcome.status() + "; target compatibility is unknown.");
            }
        }
        for (AssetReference ref : snapshot.references()) {
            if (ref.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED) {
                Asset owner = assets.get(ref.ownerAssetId());
                add(result, snapshot, target, owner, null, Finding.Severity.WARNING, "REFERENCE_" + ref.resolutionStatus(),
                        "A " + ref.kind() + " reference is " + ref.resolutionStatus()
                                + "; resolve or review it before migration.");
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Finding> findings, InventorySnapshot snapshot, AssetManager.TargetProfile target, Asset asset, Path path,
                            Finding.Severity severity, String code, String message) {
        Optional<UUID> assetId = asset == null ? Optional.empty() : Optional.of(asset.id());
        Optional<Path> relativePath = asset == null ? Optional.ofNullable(path) : Optional.of(asset.relativePath());
        String key = snapshot.project().id() + ":" + snapshot.project().inventoryRevision() + ":"
                + target.id() + ":" + target.version() + ":" + findings.size();
        findings.add(new Finding(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), assetId,
                relativePath, severity, code, message));
    }
}
