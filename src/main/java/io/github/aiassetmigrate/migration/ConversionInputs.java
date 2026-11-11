package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.analysis.MaterialAnalysis;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.conversion.ConversionInput;
import io.github.aiassetmigrate.conversion.ConversionStrategy;
import io.github.aiassetmigrate.conversion.Converters;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds exactly what a converter may read for one reviewed conversion. Inputs are in output
 * space: an OBJ and its MTL files carry their reviewed reference rewrites, and textures carry
 * their reviewed conversions, so the converted model refers to what the run publishes. The OBJ's
 * material libraries must share its output folder; dependency keys are relative to that folder.
 * The caller supplies fingerprint-verified source bytes; nothing else is read.
 */
public final class ConversionInputs {
    /** Returns the source bytes of an inventoried asset, verified against its fingerprint. */
    @FunctionalInterface
    public interface SourceBytes {
        byte[] read(Asset asset) throws IOException;
    }

    private ConversionInputs() { }

    /** The strategy for a reviewed conversion entry, or an exception if none is installed. */
    public static ConversionStrategy strategy(Asset asset, OrganizationPreview.Entry entry) throws IOException {
        String format = entry.conversion().get("format");
        return Converters.find(asset, format).orElseThrow(() -> new IOException("No installed converter supports this conversion"));
    }

    public static ConversionInput build(InventorySnapshot snapshot, OrganizationPreview.Result preview, UUID assetId,
                                        SourceBytes sources) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(sources, "sources");
        Map<UUID, Asset> assets = new HashMap<>();
        snapshot.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        Map<UUID, OrganizationPreview.Entry> entries = new LinkedHashMap<>();
        preview.entries().forEach(entry -> entries.put(entry.assetId(), entry));
        Asset asset = assets.get(assetId);
        OrganizationPreview.Entry entry = entries.get(assetId);
        if (asset == null || entry == null || entry.excluded() || entry.destination().isEmpty() || entry.conversion().isEmpty()) {
            throw new IOException("Asset has no reviewed conversion in this preview");
        }
        ConversionStrategy strategy = strategy(asset, entry);
        String format = entry.conversion().get("format");
        Map<String, String> options = new HashMap<>(entry.conversion());
        options.remove("format");
        String sourceName = asset.relativePath().getFileName().toString();
        if (!strategy.needsDependencies()) return ConversionInput.single(sourceName, sources.read(asset), format, options);
        if (!sourceName.toLowerCase(Locale.ROOT).endsWith(".obj")) {
            // FBX: textures must already be inside the file; the converter rejects external ones.
            return ConversionInput.single(sourceName, sources.read(asset), format, options);
        }
        return objInput(snapshot, assets, entries, asset, entry, sourceName, format, options, sources);
    }

    private static ConversionInput objInput(InventorySnapshot snapshot, Map<UUID, Asset> assets,
                                            Map<UUID, OrganizationPreview.Entry> entries, Asset model,
                                            OrganizationPreview.Entry modelEntry, String sourceName, String format,
                                            Map<String, String> options, SourceBytes sources) throws IOException {
        Path destination = Path.of(modelEntry.destination().orElseThrow());
        Path folder = destination.getParent();
        Path modelPath = folder == null ? Path.of(sourceName) : folder.resolve(sourceName);
        byte[] modelBytes = textOutput(model, modelEntry, true, sources);

        // An inventory of output-space files, so references resolve exactly as the converter will see them.
        Map<UUID, byte[]> texts = new HashMap<>();
        texts.put(model.id(), modelBytes);
        List<Asset> outputs = new ArrayList<>();
        outputs.add(new Asset(model.id(), modelPath, Asset.Type.MODEL, sha(modelBytes), Map.of(Asset.FORMAT, "obj")));
        for (var other : entries.values()) {
            Asset asset = assets.get(other.assetId());
            if (asset == null || asset.id().equals(model.id()) || other.excluded() || other.destination().isEmpty()) continue;
            Path path = Path.of(other.destination().get());
            if (asset.type() == Asset.Type.MATERIAL && MaterialAnalysis.inspectable(asset)) {
                byte[] bytes = textOutput(asset, other, false, sources);
                texts.put(asset.id(), bytes);
                outputs.add(new Asset(asset.id(), path, Asset.Type.MATERIAL, sha(bytes), Map.of(Asset.FORMAT, "mtl")));
            } else if (asset.type() == Asset.Type.TEXTURE) {
                outputs.add(new Asset(asset.id(), path, Asset.Type.TEXTURE, asset.fingerprint(), asset.metadata()));
            }
        }
        Project project = new Project(snapshot.project().id(), snapshot.project().sourceRoot(), 1, outputs, Instant.EPOCH);
        InventorySnapshot space = new InventorySnapshot(InventorySnapshot.CURRENT_SCHEMA_VERSION,
                InventorySnapshot.CURRENT_INSPECTION_VERSION, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE,
                Instant.EPOCH, List.of(), List.of());
        var analysis = MaterialAnalysis.analyze(space, (asset, cap) -> {
            byte[] bytes = texts.get(asset.id());
            return bytes == null ? null : bytes.length > cap ? new byte[cap + 1] : bytes;
        }, MaterialAnalysis.Limits.defaults());
        if (analysis.truncated()) throw new IOException("Reference analysis for the model exceeded its budget");
        Map<UUID, MaterialAnalysis.Inspection> inspections = new HashMap<>();
        analysis.files().forEach(file -> inspections.put(file.assetId(), file.status()));
        Map<UUID, Asset> byId = new HashMap<>();
        outputs.forEach(output -> byId.put(output.id(), output));

        Map<String, byte[]> dependencies = new HashMap<>();
        List<UUID> libraries = new ArrayList<>();
        requireVerified(inspections, model, sourceName);
        for (AssetReference reference : analysis.references()) {
            if (!reference.ownerAssetId().equals(model.id()) || reference.kind() != AssetReference.Kind.MATERIAL_LIBRARY) continue;
            Asset library = resolved(reference, byId, sourceName);
            Path libraryFolder = library.relativePath().getParent();
            if (!java.util.Objects.equals(libraryFolder == null ? null : PortablePath.of(libraryFolder),
                    folder == null ? null : PortablePath.of(folder))) {
                throw new IOException("Material library " + PortablePath.of(library.relativePath())
                        + " must be in the same output folder as the converted model");
            }
            requireVerified(inspections, library, PortablePath.of(library.relativePath()));
            libraries.add(library.id());
            dependencies.put(key(folder, library.relativePath()), texts.get(library.id()));
        }
        for (AssetReference reference : analysis.references()) {
            if (!libraries.contains(reference.ownerAssetId()) || reference.kind() != AssetReference.Kind.TEXTURE) continue;
            String owner = PortablePath.of(byId.get(reference.ownerAssetId()).relativePath());
            Asset texture = resolved(reference, byId, owner);
            Asset source = assets.get(texture.id());
            dependencies.putIfAbsent(key(folder, texture.relativePath()), textureOutput(source, entries.get(texture.id()), sources));
        }
        return new ConversionInput(sourceName, modelBytes, dependencies, format, options);
    }

    private static Asset resolved(AssetReference reference, Map<UUID, Asset> outputs, String owner) throws IOException {
        if (reference.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED || reference.normalizedTargetPath().isEmpty()) {
            throw new IOException("Reference '" + reference.originalText() + "' in " + owner
                    + " does not resolve to a reviewed output (" + reference.resolutionStatus() + ")");
        }
        String target = PortablePath.of(reference.normalizedTargetPath().get());
        return outputs.values().stream().filter(asset -> PortablePath.of(asset.relativePath()).equals(target)).findFirst()
                .orElseThrow(() -> new IOException("Reference '" + reference.originalText() + "' in " + owner + " has no output"));
    }

    private static void requireVerified(Map<UUID, MaterialAnalysis.Inspection> inspections, Asset asset, String name) throws IOException {
        var status = inspections.get(asset.id());
        if (status != MaterialAnalysis.Inspection.VERIFIED) throw new IOException(name + " could not be inspected for conversion (" + status + ")");
    }

    /** OBJ/MTL bytes as the run publishes them: reviewed reference rewrites applied. */
    private static byte[] textOutput(Asset asset, OrganizationPreview.Entry entry, boolean obj, SourceBytes sources) throws IOException {
        byte[] source = sources.read(asset);
        if (entry.rewrites().isEmpty()) return source;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(PortablePath.of(asset.relativePath()) + " is not valid UTF-8 text", e);
        }
        try {
            return MaterialAnalysis.rewrite(text, obj, entry.rewrites()).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Reference rewrite failed for " + PortablePath.of(asset.relativePath()) + ": " + e.getMessage(), e);
        }
    }

    /** Texture bytes as the run publishes them: converted when a texture conversion was reviewed. */
    private static byte[] textureOutput(Asset texture, OrganizationPreview.Entry entry, SourceBytes sources) throws IOException {
        byte[] source = sources.read(texture);
        if (entry == null || entry.conversion().isEmpty()) return source;
        var strategy = strategy(texture, entry);
        Map<String, String> options = new HashMap<>(entry.conversion());
        String format = options.remove("format");
        return strategy.convert(ConversionInput.single(texture.relativePath().getFileName().toString(), source, format, options));
    }

    private static String key(Path folder, Path target) {
        return ConversionInput.key(PortablePath.of(folder == null ? target : folder.relativize(target)));
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
