package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Lexical, read-only preview. It neither inspects the output filesystem nor authorizes operations. */
public final class OrganizationPreview {
    private OrganizationPreview() {}

    /**
     * Rewrites are output-copy reference replacements. A non-empty conversion holds the
     * accepted CONVERSION values (normalized "format" plus options); the destination then
     * carries the target extension. Both empty means the source bytes are copied unchanged.
     */
    public record Entry(UUID assetId, Path source, Optional<String> destination, boolean excluded,
                        List<MaterialAnalysis.Rewrite> rewrites, Map<String, String> conversion) {
        public Entry(UUID assetId, Path source, Optional<String> destination, boolean excluded) {
            this(assetId, source, destination, excluded, List.of(), Map.of());
        }
        public Entry(UUID assetId, Path source, Optional<String> destination, boolean excluded,
                     List<MaterialAnalysis.Rewrite> rewrites) {
            this(assetId, source, destination, excluded, rewrites, Map.of());
        }
        public Entry { rewrites = List.copyOf(rewrites); conversion = Map.copyOf(conversion); }
    }
    public record Finding(String code, Optional<UUID> assetId, String message) {}
    public record Result(UUID projectId, long inventoryRevision, List<Entry> entries, List<Finding> findings) {
        public Result { entries = List.copyOf(entries); findings = List.copyOf(findings); }
        public boolean blocked() { return !findings.isEmpty(); }
    }

    /**
     * Decisions are a complete set for the identified inventory revision. Accepted NAME uses
     * values["name"] (a filename including extension); accepted FOLDER uses values["folder"]
     * (slash-separated output-relative folder, empty for root). Accepted EXCLUSION omits an asset.
     * Rejected decisions leave the corresponding default unchanged. Missing decisions preserve
     * the source filename and parent folder.
     *
     * Accepted RELATIONSHIP or MATERIAL_MAPPING decisions whose values["reference"] equals a
     * file reference of their asset replace that reference's target (a correction). A
     * MATERIAL_MAPPING from a model whose reference is not a file reference names a material
     * used by the model; its target must already be one of the model's material libraries.
     * When an owner or target moves, references are rewritten in the owner's output copy
     * relative to the new destinations. Other review kinds are ignored.
     */
    public static Result preview(InventorySnapshot snapshot, UUID reviewProjectId, long reviewInventoryRevision,
                                 List<ReviewedChange> decisions) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(reviewProjectId);
        decisions = List.copyOf(decisions);
        var project = snapshot.project();
        if (!project.id().equals(reviewProjectId) || project.inventoryRevision() != reviewInventoryRevision)
            throw new IllegalArgumentException("Decisions do not belong to this inventory revision");
        List<Asset> assets = project.assets().stream().sorted(Comparator.comparing((Asset a) -> a.relativePath().toString())
                .thenComparing(Asset::id)).toList();
        Map<UUID, Asset> byId = new HashMap<>();
        for (Asset asset : assets) if (byId.putIfAbsent(asset.id(), asset) != null)
            throw new IllegalArgumentException("Duplicate asset ID");
        Map<UUID, Map<ReviewedChange.Kind, ReviewedChange>> selected = new HashMap<>();
        Set<UUID> reviewIds = new HashSet<>();
        Map<UUID, Map<String, UUID>> corrections = new HashMap<>();
        Set<UUID> conflictingCorrections = new HashSet<>();
        for (ReviewedChange decision : decisions) {
            if (!reviewIds.add(decision.id())) throw new IllegalArgumentException("Duplicate review ID");
            if (!byId.containsKey(decision.assetId())) throw new IllegalArgumentException("Unknown reviewed asset");
            if (decision.kind() == ReviewedChange.Kind.RELATIONSHIP || decision.kind() == ReviewedChange.Kind.MATERIAL_MAPPING) {
                String reference = decision.values().get("reference");
                String target = decision.values().get("targetAssetId");
                if (accepted(decision) && reference != null && target != null) {
                    UUID targetId;
                    try { targetId = UUID.fromString(target); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid reviewed target", e); }
                    var previous = corrections.computeIfAbsent(decision.assetId(), ignored -> new HashMap<>())
                            .putIfAbsent(reference, targetId);
                    if (previous != null && !previous.equals(targetId)) conflictingCorrections.add(decision.assetId());
                }
                continue;
            }
            if (decision.kind() != ReviewedChange.Kind.NAME && decision.kind() != ReviewedChange.Kind.FOLDER
                    && decision.kind() != ReviewedChange.Kind.EXCLUSION
                    && decision.kind() != ReviewedChange.Kind.CONVERSION) continue;
            if (selected.computeIfAbsent(decision.assetId(), ignored -> new HashMap<>())
                    .putIfAbsent(decision.kind(), decision) != null)
                throw new IllegalArgumentException("Multiple decisions for the same asset and kind");
        }
        List<Finding> findings = new ArrayList<>();
        if (!snapshot.hasCompleteCoverage())
            findings.add(finding("PARTIAL_INVENTORY", null, "Inventory coverage is incomplete"));
        List<Entry> entries = new ArrayList<>();
        Map<UUID, Entry> entryById = new HashMap<>();
        // Portable text keys: WindowsPath equality ignores case and would hide case mismatches.
        Map<String, List<Asset>> sourcePaths = new HashMap<>();
        for (Asset asset : assets) sourcePaths.computeIfAbsent(MaterialAnalysis.key(asset.relativePath()), ignored -> new ArrayList<>()).add(asset);
        for (Asset asset : assets) {
            var choices = selected.getOrDefault(asset.id(), Map.of());
            boolean excluded = accepted(choices.get(ReviewedChange.Kind.EXCLUSION));
            String name = asset.relativePath().getFileName().toString();
            Path parent = asset.relativePath().getParent();
            String folder = parent == null ? "" : parent.toString().replace('\\', '/');
            var rename = choices.get(ReviewedChange.Kind.NAME);
            var move = choices.get(ReviewedChange.Kind.FOLDER);
            if (accepted(rename)) name = rename.values().get("name");
            if (accepted(move)) folder = move.values().get("folder");
            Map<String, String> conversion = Map.of();
            var convert = choices.get(ReviewedChange.Kind.CONVERSION);
            if (accepted(convert) && !excluded) {
                String format = io.github.aiassetmigrate.conversion.Converters.normalizeFormat(convert.values().get("format"));
                var strategy = format == null ? Optional.<io.github.aiassetmigrate.conversion.ConversionStrategy>empty()
                        : io.github.aiassetmigrate.conversion.Converters.find(asset, format);
                if (format != null && format.equals(io.github.aiassetmigrate.conversion.Converters.normalizeFormat(
                        asset.metadata().get(Asset.FORMAT)))) {
                    // Already in the requested format: copied unchanged.
                } else if (strategy.isEmpty()) {
                    findings.add(finding("CONVERSION_UNSUPPORTED", asset.id(), "No installed converter supports converting this asset to "
                            + convert.values().get("format")));
                } else {
                    try {
                        strategy.get().validateOptions(format, convert.values());
                        Map<String, String> values = new HashMap<>(convert.values());
                        values.put("format", format);
                        conversion = Map.copyOf(values);
                        int dot = name.lastIndexOf('.');
                        name = (dot > 0 ? name.substring(0, dot) : name) + "." + format;
                    } catch (IllegalArgumentException e) {
                        findings.add(finding("INVALID_CONVERSION", asset.id(), e.getMessage()));
                    }
                }
            }
            Optional<String> destination = Optional.empty();
            if (!excluded) {
                if (!validName(name) || !validFolder(folder))
                    findings.add(finding("INVALID_DESTINATION", asset.id(), "Destination is not a portable relative path"));
                else destination = Optional.of(folder.isEmpty() ? name : folder + "/" + name);
            }
            Entry entry = new Entry(asset.id(), asset.relativePath(), destination, excluded, List.of(), conversion);
            entries.add(entry); entryById.put(asset.id(), entry);
        }
        for (UUID owner : conflictingCorrections.stream().sorted().toList()) {
            if (!entryById.get(owner).excluded())
                findings.add(finding("CONFLICTING_CORRECTIONS", owner, "Accepted reviews map one reference to different targets"));
        }
        record Destination(Entry entry, String key) {}
        List<Destination> destinations = entries.stream().filter(e -> e.destination().isPresent())
                .map(e -> new Destination(e, fold(e.destination().orElseThrow())))
                .sorted(Comparator.comparing(Destination::key, OrganizationPreview::comparePaths)
                        .thenComparing(d -> d.entry().assetId())).toList();
        var ancestors = new ArrayDeque<Destination>();
        Map<UUID, UUID> conflicts = new HashMap<>();
        for (Destination current : destinations) {
            while (!ancestors.isEmpty() && !current.key().equals(ancestors.peek().key())
                    && !current.key().startsWith(ancestors.peek().key() + "/")) ancestors.pop();
            if (!ancestors.isEmpty()) {
                Destination other = ancestors.peek();
                conflicts.putIfAbsent(current.entry().assetId(), other.entry().assetId());
                conflicts.putIfAbsent(other.entry().assetId(), current.entry().assetId());
                if (current.key().equals(other.key())) ancestors.pop();
            }
            ancestors.push(current);
        }
        for (Entry entry : entries) {
            UUID other = conflicts.get(entry.assetId());
            if (other != null)
                findings.add(finding("DESTINATION_CONFLICT", entry.assetId(), "Destination conflicts with asset " + other));
        }
        List<AssetReference> refs = snapshot.references().stream().sorted(Comparator
                .comparing(AssetReference::ownerAssetId).thenComparing(r -> r.kind().name())
                .thenComparing(AssetReference::originalText)
                .thenComparing(r -> r.normalizedTargetPath().map(Path::toString).orElse(""))
                .thenComparing(r -> r.resolutionStatus().name())).toList();
        Map<UUID, Map<String, MaterialAnalysis.Rewrite>> rewrites = new HashMap<>();
        Map<UUID, Set<String>> fileReferences = new HashMap<>();
        Map<UUID, Set<UUID>> libraries = new HashMap<>();
        for (AssetReference ref : refs) {
            Entry owner = entryById.get(ref.ownerAssetId());
            if (owner == null) { findings.add(finding("UNKNOWN_REFERENCE_OWNER", ref.ownerAssetId(), "Reference owner is absent")); continue; }
            fileReferences.computeIfAbsent(owner.assetId(), ignored -> new HashSet<>()).add(ref.originalText());
            if (owner.excluded()) continue;
            UUID corrected = corrections.getOrDefault(owner.assetId(), Map.of()).get(ref.originalText());
            Asset targetAsset;
            if (corrected != null) {
                targetAsset = byId.get(corrected);
                Asset.Type expected = ref.kind() == AssetReference.Kind.MATERIAL_LIBRARY ? Asset.Type.MATERIAL : Asset.Type.TEXTURE;
                if (targetAsset == null || targetAsset.type() != expected || corrected.equals(owner.assetId())) {
                    findings.add(finding("INVALID_CORRECTION", owner.assetId(),
                            "Reviewed target is not a " + expected + " asset for reference: " + ref.originalText()));
                    continue;
                }
            } else {
                List<Asset> targets = ref.normalizedTargetPath()
                        .map(p -> sourcePaths.getOrDefault(MaterialAnalysis.key(p), List.of())).orElse(List.of());
                if (ref.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED || targets.size() != 1) {
                    findings.add(finding("UNRESOLVED_REFERENCE", owner.assetId(), "Reference is not uniquely resolved: " + ref.originalText()));
                    continue;
                }
                targetAsset = targets.getFirst();
            }
            if (ref.kind() == AssetReference.Kind.MATERIAL_LIBRARY)
                libraries.computeIfAbsent(owner.assetId(), ignored -> new HashSet<>()).add(targetAsset.id());
            Entry target = entryById.get(targetAsset.id());
            if (owner.destination().isEmpty() || target.destination().isEmpty()) {
                findings.add(finding("UNAVAILABLE_REFERENCE_TARGET", owner.assetId(), "Reference destination is unavailable: " + ref.originalText()));
                continue;
            }
            if (resolvesTo(owner.destination().orElseThrow(), ref.originalText(), target.destination().orElseThrow())) continue;
            String replacement = relative(owner.destination().orElseThrow(), target.destination().orElseThrow());
            if (!MaterialAnalysis.rewritableReference(ref.originalText()) || !MaterialAnalysis.rewritableReference(replacement)) {
                findings.add(finding("REFERENCE_REWRITE_UNSUPPORTED", owner.assetId(),
                        "Reference cannot be rewritten safely (spaces, options or reserved characters): " + ref.originalText()));
                continue;
            }
            var rewrite = new MaterialAnalysis.Rewrite(ref.kind(), ref.originalText(), replacement);
            var existing = rewrites.computeIfAbsent(owner.assetId(), ignored -> new java.util.TreeMap<>())
                    .putIfAbsent(ref.originalText(), rewrite);
            if (existing != null && !existing.equals(rewrite))
                findings.add(finding("CONFLICTING_CORRECTIONS", owner.assetId(), "Reference has more than one target: " + ref.originalText()));
        }
        for (ReviewedChange decision : decisions.stream().sorted(Comparator.comparing(ReviewedChange::assetId)
                .thenComparing(ReviewedChange::id)).toList()) {
            if (decision.kind() != ReviewedChange.Kind.MATERIAL_MAPPING || !accepted(decision)) continue;
            Entry owner = entryById.get(decision.assetId());
            String reference = decision.values().get("reference");
            if (owner.excluded() || reference == null
                    || fileReferences.getOrDefault(owner.assetId(), Set.of()).contains(reference)) continue;
            UUID material = corrections.get(owner.assetId()).get(reference);
            if (byId.get(owner.assetId()).type() != Asset.Type.MODEL) {
                findings.add(finding("MAPPING_REFERENCE_NOT_FOUND", owner.assetId(),
                        "Reviewed reference is not a texture reference of this material: " + reference));
            } else if (!libraries.getOrDefault(owner.assetId(), Set.of()).contains(material)) {
                findings.add(finding("MAPPING_LIBRARY_NOT_REFERENCED", owner.assetId(), "Material '" + reference
                        + "' maps to " + material + ", which is not one of this model's material libraries;"
                        + " correct the model's mtllib reference first"));
            }
        }
        // A converted OBJ reads its MTL files from its own output folder (textures may be elsewhere).
        for (Entry entry : entries) {
            if (entry.conversion().isEmpty() || entry.destination().isEmpty() || byId.get(entry.assetId()).type() != Asset.Type.MODEL) continue;
            Path folder = Path.of(entry.destination().get()).getParent();
            for (UUID library : libraries.getOrDefault(entry.assetId(), Set.of())) {
                var target = entryById.get(library);
                if (target == null || target.destination().isEmpty()) continue;
                Path libraryFolder = Path.of(target.destination().get()).getParent();
                if (!Objects.equals(folder == null ? "" : MaterialAnalysis.key(folder), libraryFolder == null ? "" : MaterialAnalysis.key(libraryFolder))) {
                    findings.add(finding("MODEL_CONVERSION_LAYOUT", entry.assetId(), "A converted model's material library ("
                            + target.destination().get() + ") must be in the same output folder as the model"));
                }
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            var changes = rewrites.get(entry.assetId());
            if (changes != null) entries.set(i, new Entry(entry.assetId(), entry.source(), entry.destination(),
                    entry.excluded(), List.copyOf(changes.values()), entry.conversion()));
        }
        return new Result(project.id(), project.inventoryRevision(), entries, findings);
    }

    /** Output-relative reference text from the owner's destination folder to the target destination. */
    static String relative(String ownerDestination, String targetDestination) {
        Path owner = Path.of(ownerDestination).getParent();
        Path target = Path.of(targetDestination);
        return MaterialAnalysis.key(owner == null ? target : owner.relativize(target));
    }

    private static boolean resolvesTo(String ownerDestination, String text, String targetDestination) {
        if (text.isEmpty() || text.contains("\\") || text.contains(":") || text.startsWith("/")) return false;
        try {
            Path base = Path.of(ownerDestination).getParent();
            Path resolved = (base == null ? Path.of(text) : base.resolve(text)).normalize();
            return !resolved.isAbsolute() && !resolved.startsWith("..")
                    && MaterialAnalysis.key(resolved).equals(targetDestination);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private static boolean accepted(ReviewedChange change) {
        return change != null && change.decision() == ReviewedChange.Decision.ACCEPTED;
    }
    private static Finding finding(String code, UUID id, String message) {
        return new Finding(code, Optional.ofNullable(id), message);
    }
    private static String fold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
    private static int comparePaths(String left, String right) {
        int end = Math.min(left.length(), right.length());
        for (int i = 0; i < end; i++) {
            char a = left.charAt(i), b = right.charAt(i);
            if (a != b) return a == '/' ? -1 : b == '/' ? 1 : Character.compare(a, b);
        }
        return Integer.compare(left.length(), right.length());
    }
    public static boolean validFolder(String folder) {
        if (folder == null) return false;
        if (folder.isEmpty()) return true;
        for (String component : folder.split("/", -1)) if (!validName(component)) return false;
        return true;
    }
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(" ") || name.endsWith(".")) return false;
        if (name.length() > 255 || name.getBytes(StandardCharsets.UTF_8).length > 255
                || name.chars().anyMatch(c -> c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0)) return false;
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return !stem.matches("CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3]");
    }
}
