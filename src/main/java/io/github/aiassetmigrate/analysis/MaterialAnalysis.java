package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only, conservative OBJ/MTL analysis. Content is supplied by the caller, never opened here. */
public final class MaterialAnalysis {
    private MaterialAnalysis() {}

    /** The provider must enforce its own safe, bounded acquisition; null means unavailable. */
    @FunctionalInterface public interface Content { byte[] read(Asset asset, int maxBytes) throws Exception; }

    public record Limits(int maxFiles, int maxBytesPerFile, long maxTotalBytes, int maxRecords) {
        public Limits {
            if (maxFiles < 1 || maxBytesPerFile < 1 || maxTotalBytes < 1 || maxRecords < 1)
                throw new IllegalArgumentException("Limits must be positive");
        }
        public static Limits defaults() { return new Limits(1000, 1024 * 1024, 32L * 1024 * 1024, 100000); }
    }

    public enum Inspection { VERIFIED, UNAVAILABLE, BUDGET_EXCEEDED, FINGERPRINT_MISMATCH, INVALID_TEXT }
    public enum MappingStatus { RESOLVED, MISSING, AMBIGUOUS, UNVERIFIED }
    public record FileInspection(UUID assetId, Inspection status) {}
    public record Mapping(UUID modelId, String materialName, MappingStatus status, Optional<UUID> materialAssetId) {}
    public record TextureBinding(Optional<String> materialName, AssetReference reference) {}
    public record Result(List<AssetReference> references, List<Mapping> mappings,
                         List<TextureBinding> textures, List<FileInspection> files, boolean truncated) {
        public Result {
            references = List.copyOf(references); mappings = List.copyOf(mappings);
            textures = List.copyOf(textures); files = List.copyOf(files);
        }
    }

    /** Results describe this snapshot only and are not reviewed changes or migration approval. */
    public static Result analyze(InventorySnapshot snapshot, Content content, Limits limits) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(content); Objects.requireNonNull(limits);
        return new Run(snapshot, content, limits).analyze();
    }

    private static final class Run {
        final InventorySnapshot snapshot; final Content content; final Limits limits;
        final List<Asset> assets; final Map<Path, List<Asset>> paths = new HashMap<>();
        final Map<String, Integer> foldedPaths = new HashMap<>();
        final List<AssetReference> references = new ArrayList<>();
        final List<TextureBinding> textures = new ArrayList<>();
        final List<Mapping> mappings = new ArrayList<>(); final List<FileInspection> files = new ArrayList<>();
        final Map<UUID, Parsed> parsed = new HashMap<>();
        long bytes; int records; boolean truncated;

        Run(InventorySnapshot snapshot, Content content, Limits limits) {
            this.snapshot = snapshot; this.content = content; this.limits = limits;
            assets = snapshot.project().assets().stream().sorted(Comparator.comparing((Asset a) -> a.relativePath().toString())
                    .thenComparing(Asset::id)).toList();
            assets.forEach(a -> {
                paths.computeIfAbsent(a.relativePath(), ignored -> new ArrayList<>()).add(a);
                foldedPaths.merge(a.relativePath().toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
            });
        }

        Result analyze() {
            for (Asset asset : assets) {
                String name = asset.relativePath().toString().toLowerCase(Locale.ROOT);
                if ((asset.type() == Asset.Type.MODEL && name.endsWith(".obj"))
                        || (asset.type() == Asset.Type.MATERIAL && name.endsWith(".mtl"))) inspect(asset, name.endsWith(".obj"));
            }
            for (Asset model : assets) {
                Parsed obj = parsed.get(model.id());
                if (obj == null || !obj.obj) continue;
                List<Parsed> libraries = new ArrayList<>(); boolean incomplete = !obj.complete;
                for (AssetReference ref : obj.refs) {
                    if (ref.kind() != AssetReference.Kind.MATERIAL_LIBRARY) continue;
                    if (ref.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED) { incomplete = true; continue; }
                    List<Asset> targets = paths.get(ref.normalizedTargetPath().orElseThrow());
                    Parsed library = targets == null || targets.size() != 1 ? null : parsed.get(targets.getFirst().id());
                    if (library == null || library.obj || !library.complete) incomplete = true;
                    else libraries.add(library);
                }
                for (String use : obj.names) {
                    if (!reserve()) break;
                    List<UUID> matches = new ArrayList<>();
                    for (Parsed library : libraries) for (String name : library.names)
                        if (name.equals(use)) matches.add(library.asset.id());
                    MappingStatus status = matches.size() > 1 ? MappingStatus.AMBIGUOUS
                            : incomplete ? MappingStatus.UNVERIFIED : matches.isEmpty() ? MappingStatus.MISSING : MappingStatus.RESOLVED;
                    mappings.add(new Mapping(model.id(), use, status,
                            status == MappingStatus.RESOLVED ? Optional.of(matches.getFirst()) : Optional.empty()));
                }
            }
            return new Result(references, mappings, textures, files, truncated);
        }

        void inspect(Asset asset, boolean obj) {
            Inspection status; byte[] data = null;
            if (files.size() >= limits.maxFiles() || bytes >= limits.maxTotalBytes()) {
                status = Inspection.BUDGET_EXCEEDED; truncated = true;
            } else {
                int cap = (int) Math.min(limits.maxBytesPerFile(), limits.maxTotalBytes() - bytes);
                try {
                    data = content.read(asset, cap);
                    if (data == null) status = Inspection.UNAVAILABLE;
                    else if (data.length > cap) { status = Inspection.BUDGET_EXCEEDED; truncated = true; data = null; }
                    else {
                        bytes += data.length;
                        status = hash(data).equals(asset.fingerprint()) ? Inspection.VERIFIED : Inspection.FINGERPRINT_MISMATCH;
                    }
                } catch (Exception e) { status = Inspection.UNAVAILABLE; }
            }
            String text = null;
            if (status == Inspection.VERIFIED) {
                try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
                catch (CharacterCodingException e) { status = Inspection.INVALID_TEXT; }
            }
            files.add(new FileInspection(asset.id(), status));
            if (text == null) return;
            Parsed result = new Parsed(asset, obj); parsed.put(asset.id(), result);
            for (String line : text.split("\\R", -1)) {
                int comment = line.indexOf('#');
                String clean = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (clean.isEmpty()) continue;
                int split = 0; while (split < clean.length() && !Character.isWhitespace(clean.charAt(split))) split++;
                String directive = clean.substring(0, split);
                String value = clean.substring(split).trim();
                if ((obj && directive.equals("usemtl")) || (!obj && directive.equals("newmtl"))) {
                    if (value.isEmpty() || !reserve()) { result.complete = false; continue; }
                    result.names.add(value);
                    if (!obj) result.currentMaterial = value;
                } else if ((obj && directive.equals("mtllib")) || (!obj && isTexture(directive))) {
                    if (obj && !value.isEmpty()) {
                        for (String token : value.split("\\s+")) addReference(result, AssetReference.Kind.MATERIAL_LIBRARY, token);
                    } else addReference(result, obj ? AssetReference.Kind.MATERIAL_LIBRARY : AssetReference.Kind.TEXTURE, value);
                }
            }
        }

        void addReference(Parsed owner, AssetReference.Kind kind, String value) {
            if (!reserve()) { owner.complete = false; return; }
            AssetReference ref = resolve(owner.asset, kind, value);
            owner.refs.add(ref); references.add(ref);
            if (kind == AssetReference.Kind.TEXTURE) textures.add(new TextureBinding(Optional.ofNullable(owner.currentMaterial), ref));
        }

        boolean reserve() {
            if (records >= limits.maxRecords()) { truncated = true; return false; }
            records++; return true;
        }

        AssetReference resolve(Asset owner, AssetReference.Kind kind, String value) {
            if (value.isEmpty() || value.matches(".*\\s+.*") || value.startsWith("-") || value.contains("\"") || value.contains("'"))
                return ref(owner, kind, value, null, AssetReference.ResolutionStatus.UNRESOLVED);
            if (value.startsWith("/") || value.contains("\\") || value.contains(":"))
                return ref(owner, kind, value, null, AssetReference.ResolutionStatus.UNSAFE);
            Path target;
            try {
                Path parent = owner.relativePath().getParent();
                target = (parent == null ? Path.of(value) : parent.resolve(value)).normalize();
                if (target.isAbsolute() || target.toString().isEmpty() || target.startsWith(".."))
                    return ref(owner, kind, value, null, AssetReference.ResolutionStatus.UNSAFE);
            } catch (InvalidPathException e) { return ref(owner, kind, value, null, AssetReference.ResolutionStatus.UNSAFE); }
            List<Asset> exact = paths.getOrDefault(target, List.of());
            int folded = foldedPaths.getOrDefault(target.toString().toLowerCase(Locale.ROOT), 0);
            AssetReference.ResolutionStatus status;
            if (folded > 1 || (exact.isEmpty() && folded > 0)) status = AssetReference.ResolutionStatus.CASE_AMBIGUOUS;
            else if (exact.size() == 1) status = exact.getFirst().type() == (kind == AssetReference.Kind.MATERIAL_LIBRARY
                    ? Asset.Type.MATERIAL : Asset.Type.TEXTURE) ? AssetReference.ResolutionStatus.RESOLVED : AssetReference.ResolutionStatus.UNRESOLVED;
            else if (snapshot.pathOutcomes().stream().anyMatch(o -> (o.relativePath().equals(target)
                    || (o.kind() == PathOutcome.Kind.DIRECTORY && (o.relativePath().toString().isEmpty() || target.startsWith(o.relativePath()))))
                    && (o.status() == PathOutcome.Status.SKIPPED || o.status() == PathOutcome.Status.FAILED || o.status() == PathOutcome.Status.UNVISITED)))
                status = AssetReference.ResolutionStatus.SKIPPED;
            else status = snapshot.scanStatus() == InventorySnapshot.ScanStatus.COMPLETE
                    ? AssetReference.ResolutionStatus.MISSING : AssetReference.ResolutionStatus.UNRESOLVED;
            return ref(owner, kind, value, target, status);
        }
    }

    private static AssetReference ref(Asset owner, AssetReference.Kind kind, String text, Path target, AssetReference.ResolutionStatus status) {
        return new AssetReference(owner.id(), kind, text, Optional.ofNullable(target), status);
    }
    private static boolean isTexture(String directive) {
        return directive.startsWith("map_") || directive.equals("bump") || directive.equals("disp") || directive.equals("decal") || directive.equals("norm");
    }
    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class Parsed {
        final Asset asset; final boolean obj; boolean complete = true; String currentMaterial;
        final List<String> names = new ArrayList<>(); final List<AssetReference> refs = new ArrayList<>();
        Parsed(Asset asset, boolean obj) { this.asset = asset; this.obj = obj; }
    }
}
