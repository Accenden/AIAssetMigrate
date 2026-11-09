package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.nio.file.Path;
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

    public record Entry(UUID assetId, Path source, Optional<String> destination, boolean excluded) {}
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
     * the source filename and parent folder. Other review kinds are ignored.
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
        for (ReviewedChange decision : decisions) {
            if (!reviewIds.add(decision.id())) throw new IllegalArgumentException("Duplicate review ID");
            if (!byId.containsKey(decision.assetId())) throw new IllegalArgumentException("Unknown reviewed asset");
            if (decision.kind() != ReviewedChange.Kind.NAME && decision.kind() != ReviewedChange.Kind.FOLDER
                    && decision.kind() != ReviewedChange.Kind.EXCLUSION) continue;
            if (selected.computeIfAbsent(decision.assetId(), ignored -> new HashMap<>())
                    .putIfAbsent(decision.kind(), decision) != null)
                throw new IllegalArgumentException("Multiple decisions for the same asset and kind");
        }
        List<Finding> findings = new ArrayList<>();
        if (snapshot.scanStatus() != InventorySnapshot.ScanStatus.COMPLETE)
            findings.add(finding("PARTIAL_INVENTORY", null, "Inventory coverage is incomplete"));
        List<Entry> entries = new ArrayList<>();
        Map<UUID, Entry> entryById = new HashMap<>();
        Map<Path, List<Asset>> sourcePaths = new HashMap<>();
        for (Asset asset : assets) sourcePaths.computeIfAbsent(asset.relativePath(), ignored -> new ArrayList<>()).add(asset);
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
            Optional<String> destination = Optional.empty();
            if (!excluded) {
                if (!validName(name) || !validFolder(folder))
                    findings.add(finding("INVALID_DESTINATION", asset.id(), "Destination is not a portable relative path"));
                else destination = Optional.of(folder.isEmpty() ? name : folder + "/" + name);
            }
            Entry entry = new Entry(asset.id(), asset.relativePath(), destination, excluded);
            entries.add(entry); entryById.put(asset.id(), entry);
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
        for (AssetReference ref : refs) {
            Entry owner = entryById.get(ref.ownerAssetId());
            if (owner == null) { findings.add(finding("UNKNOWN_REFERENCE_OWNER", ref.ownerAssetId(), "Reference owner is absent")); continue; }
            if (owner.excluded()) continue;
            List<Asset> targets = ref.normalizedTargetPath().map(p -> sourcePaths.getOrDefault(p, List.of())).orElse(List.of());
            if (ref.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED || targets.size() != 1) {
                findings.add(finding("UNRESOLVED_REFERENCE", owner.assetId(), "Reference is not uniquely resolved: " + ref.originalText()));
                continue;
            }
            Entry target = entryById.get(targets.getFirst().id());
            if (owner.destination().isEmpty() || target.destination().isEmpty()) {
                findings.add(finding("UNAVAILABLE_REFERENCE_TARGET", owner.assetId(), "Reference destination is unavailable: " + ref.originalText()));
                continue;
            }
            // Compare the original relative reference against the proposed destinations, without rewriting content.
            try {
                String text = ref.originalText();
                if (text.isEmpty() || text.contains("\\") || text.contains(":") || text.startsWith("/")) throw new IllegalArgumentException();
                Path ownerPath = Path.of(owner.destination().orElseThrow());
                Path base = ownerPath.getParent();
                Path resolved = (base == null ? Path.of(text) : base.resolve(text)).normalize();
                if (resolved.isAbsolute() || resolved.startsWith("..")
                        || !resolved.toString().replace('\\', '/').equals(target.destination().orElseThrow()))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                findings.add(finding("REFERENCE_REWRITE_REQUIRED", owner.assetId(), "Reference would not resolve unchanged: " + ref.originalText()));
            }
        }
        return new Result(project.id(), project.inventoryRevision(), entries, findings);
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
    private static boolean validFolder(String folder) {
        if (folder == null) return false;
        if (folder.isEmpty()) return true;
        for (String component : folder.split("/", -1)) if (!validName(component)) return false;
        return true;
    }
    private static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(" ") || name.endsWith(".")) return false;
        if (name.length() > 255 || name.chars().anyMatch(c -> c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0)) return false;
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return !stem.matches("CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3]");
    }
}
