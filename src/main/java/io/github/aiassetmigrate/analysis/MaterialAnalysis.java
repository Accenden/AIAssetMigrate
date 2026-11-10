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

    /** True for the OBJ models and MTL materials whose references this analysis can inspect. */
    public static boolean inspectable(Asset asset) {
        String name = asset.relativePath().getFileName().toString().toLowerCase(Locale.ROOT);
        return (asset.type() == Asset.Type.MODEL && name.endsWith(".obj"))
                || (asset.type() == Asset.Type.MATERIAL && name.endsWith(".mtl"));
    }

    /** Results describe this snapshot only and are not reviewed changes or migration approval. */
    public static Result analyze(InventorySnapshot snapshot, Content content, Limits limits) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(content); Objects.requireNonNull(limits);
        return new Run(snapshot, content, limits).analyze();
    }

    private static final class Run {
        final InventorySnapshot snapshot; final Content content; final Limits limits;
        final boolean completeCoverage;
        final List<Asset> assets;
        // Keyed by portable text: WindowsPath equality ignores case and would hide case mismatches.
        final Map<String, List<Asset>> paths = new HashMap<>();
        final Map<String, Integer> foldedPaths = new HashMap<>();
        final List<AssetReference> references = new ArrayList<>();
        final List<TextureBinding> textures = new ArrayList<>();
        final List<Mapping> mappings = new ArrayList<>(); final List<FileInspection> files = new ArrayList<>();
        final Map<UUID, Parsed> parsed = new HashMap<>();
        long bytes; int records; boolean truncated;

        Run(InventorySnapshot snapshot, Content content, Limits limits) {
            this.snapshot = snapshot; this.content = content; this.limits = limits;
            this.completeCoverage = snapshot.hasCompleteCoverage();
            assets = snapshot.project().assets().stream().sorted(Comparator.comparing((Asset a) -> a.relativePath().toString())
                    .thenComparing(Asset::id)).toList();
            assets.forEach(a -> {
                paths.computeIfAbsent(key(a.relativePath()), ignored -> new ArrayList<>()).add(a);
                foldedPaths.merge(key(a.relativePath()).toLowerCase(Locale.ROOT), 1, Integer::sum);
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
                    List<Asset> targets = paths.get(key(ref.normalizedTargetPath().orElseThrow()));
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
            List<Asset> exact = paths.getOrDefault(key(target), List.of());
            int folded = foldedPaths.getOrDefault(key(target).toLowerCase(Locale.ROOT), 0);
            AssetReference.ResolutionStatus status;
            if (folded > 1 || (exact.isEmpty() && folded > 0)) status = AssetReference.ResolutionStatus.CASE_AMBIGUOUS;
            else if (exact.size() == 1) status = exact.getFirst().type() == (kind == AssetReference.Kind.MATERIAL_LIBRARY
                    ? Asset.Type.MATERIAL : Asset.Type.TEXTURE) ? AssetReference.ResolutionStatus.RESOLVED : AssetReference.ResolutionStatus.UNRESOLVED;
            else if (snapshot.pathOutcomes().stream().anyMatch(o -> (o.relativePath().equals(target)
                    || (o.kind() == PathOutcome.Kind.DIRECTORY && (o.relativePath().toString().isEmpty() || target.startsWith(o.relativePath()))))
                    && (o.status() == PathOutcome.Status.SKIPPED || o.status() == PathOutcome.Status.FAILED || o.status() == PathOutcome.Status.UNVISITED)))
                status = AssetReference.ResolutionStatus.SKIPPED;
            else status = completeCoverage
                    ? AssetReference.ResolutionStatus.MISSING : AssetReference.ResolutionStatus.UNRESOLVED;
            return ref(owner, kind, value, target, status);
        }
    }

    private static AssetReference ref(Asset owner, AssetReference.Kind kind, String text, Path target, AssetReference.ResolutionStatus status) {
        return new AssetReference(owner.id(), kind, text, Optional.ofNullable(target), status);
    }
    /**
     * One reference replacement in an output copy: {@code original} is the exact reference text
     * as parsed by {@link #analyze}, {@code replacement} the output-relative text to write.
     */
    public record Rewrite(AssetReference.Kind kind, String original, String replacement) {
        public Rewrite {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    /** True when text can be written as a single OBJ/MTL reference token without quoting. */
    public static boolean rewritableReference(String text) {
        return text != null && !text.isEmpty() && text.length() <= 4096 && !text.startsWith("-")
                && text.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '#' || c == '"' || c == '\'' || c == '\\' || c == ':')
                && !text.startsWith("/");
    }

    /**
     * Applies reference rewrites to OBJ ({@code mtllib} tokens) or MTL (texture directive values)
     * text using the same line, comment and directive rules as {@link #analyze}. Everything else,
     * including line endings, is preserved byte for byte. Every rewrite must match at least once.
     *
     * @throws IllegalArgumentException if a rewrite does not match the content or is unsafe
     */
    public static String rewrite(String text, boolean obj, List<Rewrite> rewrites) {
        Objects.requireNonNull(text, "text");
        Map<String, String> libraries = new HashMap<>(), textures = new HashMap<>();
        for (Rewrite rewrite : rewrites) {
            if (!rewritableReference(rewrite.original()) || !rewritableReference(rewrite.replacement()))
                throw new IllegalArgumentException("Reference cannot be rewritten safely: " + rewrite.original());
            var target = rewrite.kind() == AssetReference.Kind.MATERIAL_LIBRARY ? libraries : textures;
            if ((rewrite.kind() == AssetReference.Kind.MATERIAL_LIBRARY) != obj)
                throw new IllegalArgumentException("Rewrite kind does not match the file format");
            if (target.putIfAbsent(rewrite.original(), rewrite.replacement()) != null)
                throw new IllegalArgumentException("Duplicate rewrite: " + rewrite.original());
        }
        java.util.Set<String> applied = new java.util.HashSet<>();
        StringBuilder out = new StringBuilder(text.length() + 64);
        var lines = java.util.regex.Pattern.compile("\\R").matcher(text);
        int start = 0;
        while (true) {
            boolean more = lines.find();
            int end = more ? lines.start() : text.length();
            out.append(rewriteLine(text.substring(start, end), obj, libraries, textures, applied));
            if (!more) break;
            out.append(lines.group());
            start = lines.end();
        }
        for (Rewrite rewrite : rewrites) {
            if (!applied.contains(rewrite.original()))
                throw new IllegalArgumentException("Reference not found in content: " + rewrite.original());
        }
        return out.toString();
    }

    private static String rewriteLine(String line, boolean obj, Map<String, String> libraries,
                                      Map<String, String> textures, java.util.Set<String> applied) {
        int comment = line.indexOf('#');
        int contentEnd = comment < 0 ? line.length() : comment;
        int first = 0;
        while (first < contentEnd && Character.isWhitespace(line.charAt(first))) first++;
        int split = first;
        while (split < contentEnd && !Character.isWhitespace(line.charAt(split))) split++;
        String directive = line.substring(first, split);
        if (obj && directive.equals("mtllib")) {
            StringBuilder result = new StringBuilder(line.substring(0, split));
            var tokens = java.util.regex.Pattern.compile("\\S+").matcher(line.substring(split, contentEnd));
            int last = 0;
            String rest = line.substring(split, contentEnd);
            while (tokens.find()) {
                result.append(rest, last, tokens.start());
                String replacement = libraries.get(tokens.group());
                if (replacement != null) applied.add(tokens.group());
                result.append(replacement == null ? tokens.group() : replacement);
                last = tokens.end();
            }
            return result.append(rest.substring(last)).append(line.substring(contentEnd)).toString();
        }
        if (!obj && isTexture(directive)) {
            int valueStart = split;
            while (valueStart < contentEnd && Character.isWhitespace(line.charAt(valueStart))) valueStart++;
            int valueEnd = contentEnd;
            while (valueEnd > valueStart && Character.isWhitespace(line.charAt(valueEnd - 1))) valueEnd--;
            String value = line.substring(valueStart, valueEnd);
            String replacement = textures.get(value);
            if (replacement != null) {
                applied.add(value);
                return line.substring(0, valueStart) + replacement + line.substring(valueEnd);
            }
        }
        return line;
    }

    /** Case-sensitive, separator-independent text for an inventory-relative path. */
    static String key(Path path) {
        StringBuilder text = new StringBuilder();
        for (Path part : path) { if (!text.isEmpty()) text.append('/'); text.append(part); }
        return text.toString();
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
