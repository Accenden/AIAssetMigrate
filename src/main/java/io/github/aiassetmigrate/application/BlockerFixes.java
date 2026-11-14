package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quick fixes for the blocking findings of a draft plan. Every fix is a decision the user could make by
 * hand: a review decision, or leaving files out of the scan. Proposing writes nothing; the user picks the
 * fixes to apply, and applying saves only those decisions (no output is written and nothing is approved).
 *
 * <p>Fixes come from a fixed table of cases (a unique correction candidate, an acknowledgment for an
 * uninspectable model, undoing a conversion or rename that cannot work, a numbered rename for a clash).
 * Where the rules find no single answer for a broken reference, an AI suggestion from the Relationships
 * analysis may choose one; it is marked as AI and, like every suggestion, only applies if the user keeps it.
 * A blocker with no safe fix gets a last-resort fix that leaves the file out, which is never preselected.
 */
public final class BlockerFixes {
    private BlockerFixes() {}

    public enum Source { RULES, AI }

    /**
     * One fix. {@code changes} are review decisions to save; {@code leaveOut} are files to leave out of the
     * scan (relative paths). {@code recommended} fixes are preselected; the others leave files out.
     */
    public record Fix(String code, Optional<UUID> assetId, String description, List<ReviewedChange> changes,
                      Set<Path> leaveOut, Source source, boolean recommended) {
        public Fix {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(source, "source");
            changes = List.copyOf(changes);
            leaveOut = Set.copyOf(leaveOut);
            if (changes.isEmpty() && leaveOut.isEmpty()) throw new IllegalArgumentException("A fix must change something");
        }
    }

    /** Fixes for one plan's blockers, plus plain notes for blockers that need the user. */
    public record Proposal(UUID projectId, long inventoryRevision, List<Fix> fixes, List<String> unfixable) {
        public Proposal {
            fixes = List.copyOf(fixes);
            unfixable = List.copyOf(unfixable);
        }
    }

    /**
     * What applying did. A rescan (to leave files out) creates a new inventory revision; saved decisions
     * are carried over to it for files whose path and contents are unchanged, and {@code decisionsDropped}
     * counts those that could not be (for example decisions about a file that was left out).
     */
    public record Applied(int fixes, int decisionsSaved, int decisionsDropped, boolean rescanned, long inventoryRevision) {}

    static Proposal propose(MigrationPlan plan, InventorySnapshot inventory, List<ReviewedChange> saved,
                            OrganizationPreview.Result preview, List<AssetManager.Suggestion> relationships, Instant now) {
        if (!plan.projectId().equals(inventory.project().id())
                || plan.inventoryRevision() != inventory.project().inventoryRevision()) {
            throw new IllegalArgumentException("The plan does not belong to the current inventory revision; create the plan again");
        }
        Context context = new Context(inventory, saved, preview, relationships, now);
        Set<UUID> inventoryFindings = inventory.findings().stream().map(Finding::id).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (Finding finding : plan.findings()) {
            if (finding.severity() != Finding.Severity.ERROR && finding.severity() != Finding.Severity.UNKNOWN) continue;
            if (!seen.add(finding.code() + "|" + finding.assetId().orElse(null) + "|" + finding.message())) continue;
            if (inventoryFindings.contains(finding.id())) {
                context.scanProblem(finding);
                continue;
            }
            if (context.fbxOwner(finding)) {
                // An FBX is copied as it is: references are fixed by placing the texture, never by editing the file.
                if (finding.code().equals("UNRESOLVED_REFERENCE")) {
                    context.placeForFbx(finding, OrganizationPreview.UNRESOLVED_PREFIX);
                    continue;
                }
                if (finding.code().equals("FBX_REFERENCE_NOT_REWRITABLE")) {
                    context.placeForFbx(finding, OrganizationPreview.FBX_FIXED_PREFIX);
                    continue;
                }
                if (finding.code().startsWith("INSPECTION_")) {
                    context.acknowledgeUnreadableFbx(finding);
                    continue;
                }
            }
            switch (finding.code()) {
                case "PARTIAL_INVENTORY" -> context.partialInventory();
                case "UNRESOLVED_REFERENCE" -> context.unresolvedReference(finding);
                case "INSPECTION_UNSUPPORTED" -> context.acknowledge(finding);
                case "CONVERSION_CHECK_FAILED" -> {
                    if (finding.message().equals(io.github.aiassetmigrate.conversion.TextureConverter.NEEDS_BACKGROUND)) context.addBackground(finding);
                    else context.undo(finding, ReviewedChange.Kind.CONVERSION, "Keep the original format of %s (the conversion cannot be done as reviewed)");
                }
                case "CONVERSION_UNSUPPORTED", "INVALID_CONVERSION", "MODEL_CONVERSION_LAYOUT" ->
                        context.undo(finding, ReviewedChange.Kind.CONVERSION, "Keep the original format of %s (the conversion cannot be done as reviewed)");
                case "INVALID_DESTINATION" -> context.undoDestination(finding);
                case "DESTINATION_CONFLICT" -> context.destinationConflict(finding);
                case "SOURCE_IMAGE_INVALID" -> context.damagedImage(finding);
                case "CONFLICTING_CORRECTIONS" -> context.conflictingCorrections(finding);
                case "INVALID_CORRECTION" -> context.invalidCorrection(finding);
                default -> context.lastResort(finding);
            }
        }
        return new Proposal(inventory.project().id(), inventory.project().inventoryRevision(), context.fixes, context.unfixable);
    }

    /**
     * Maps assets of one revision to the next by exact relative path and fingerprint, so a decision is only
     * carried over to the same, unchanged file.
     */
    static Map<UUID, UUID> carriedIds(InventorySnapshot before, InventorySnapshot after) {
        Map<String, Asset> next = new HashMap<>();
        after.project().assets().forEach(asset -> next.put(PortablePath.of(asset.relativePath()), asset));
        Map<UUID, UUID> ids = new HashMap<>();
        for (Asset asset : before.project().assets()) {
            Asset same = next.get(PortablePath.of(asset.relativePath()));
            if (same != null && same.fingerprint().equals(asset.fingerprint())) ids.put(asset.id(), same.id());
        }
        return ids;
    }

    /** The decisions re-expressed for the new revision; those about files that did not carry over are omitted. */
    static List<ReviewedChange> carry(List<ReviewedChange> changes, Map<UUID, UUID> ids) {
        List<ReviewedChange> carried = new ArrayList<>();
        for (ReviewedChange change : changes) {
            UUID asset = ids.get(change.assetId());
            if (asset == null) continue;
            Map<String, String> values = new HashMap<>(change.values());
            String target = values.get("targetAssetId");
            if (target != null) {
                UUID mapped;
                try { mapped = ids.get(UUID.fromString(target)); }
                catch (IllegalArgumentException invalid) { continue; }
                if (mapped == null) continue;
                values.put("targetAssetId", mapped.toString());
            }
            carried.add(new ReviewedChange(UUID.randomUUID(), asset, change.kind(), change.decision(), values, change.reviewedAt()));
        }
        return carried;
    }

    private static final class Context {
        final InventorySnapshot inventory;
        final List<ReviewedChange> saved;
        final OrganizationPreview.Result preview;
        final List<AssetManager.Suggestion> relationships;
        final Instant now;
        final Map<UUID, Asset> assets = new HashMap<>();
        final List<Fix> fixes = new ArrayList<>();
        final List<String> unfixable = new ArrayList<>();
        final Set<UUID> clashSettled = new HashSet<>();
        /** Texture placements proposed for FBX references, by texture: one texture can be in only one place. */
        final Map<UUID, String> placements = new HashMap<>();
        final Set<UUID> lastResort = new HashSet<>();
        final Set<Path> leftOut = new HashSet<>();
        boolean partialDone, destinationsDone;

        Context(InventorySnapshot inventory, List<ReviewedChange> saved, OrganizationPreview.Result preview,
                List<AssetManager.Suggestion> relationships, Instant now) {
            this.inventory = inventory;
            this.saved = List.copyOf(saved);
            this.preview = preview;
            this.relationships = List.copyOf(relationships);
            this.now = now;
            inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        }

        String path(UUID asset) {
            Asset found = assets.get(asset);
            return found == null ? asset.toString() : PortablePath.of(found.relativePath());
        }

        void add(String code, UUID asset, String description, List<ReviewedChange> changes, Source source) {
            fixes.add(new Fix(code, Optional.ofNullable(asset), description, changes, Set.of(), source, true));
        }

        ReviewedChange decision(UUID asset, ReviewedChange.Kind kind, ReviewedChange.Decision decision, Map<String, String> values) {
            return new ReviewedChange(UUID.randomUUID(), asset, kind, decision, values, now);
        }

        ReviewedChange rejected(ReviewedChange change) {
            return decision(change.assetId(), change.kind(), ReviewedChange.Decision.REJECTED, change.values());
        }

        List<ReviewedChange> accepted(UUID asset, ReviewedChange.Kind kind) {
            return saved.stream().filter(c -> c.assetId().equals(asset) && c.kind() == kind
                    && c.decision() == ReviewedChange.Decision.ACCEPTED).toList();
        }

        void partialInventory() {
            if (partialDone) return;
            partialDone = true;
            List<Path> files = inventory.excludableFiles().stream().map(PathOutcome::relativePath)
                    .sorted(Comparator.comparing(PortablePath::of)).toList();
            if (!files.isEmpty()) {
                String names = files.stream().limit(3).map(PortablePath::of).collect(Collectors.joining(", "))
                        + (files.size() > 3 ? " and " + (files.size() - 3) + " more" : "");
                fixes.add(new Fix("PARTIAL_INVENTORY", Optional.empty(), "Leave out " + files.size()
                        + " skipped file(s) the scan could not use (" + names + "); they are not copied and your folder is not changed",
                        List.of(), new LinkedHashSet<>(files), Source.RULES, true));
                leftOut.addAll(files);
            }
            List<String> folders = inventory.pathOutcomes().stream()
                    .filter(o -> o.kind() == PathOutcome.Kind.DIRECTORY && o.status() != PathOutcome.Status.TRAVERSED)
                    .map(o -> PortablePath.of(o.relativePath())).map(p -> p.isEmpty() ? "(the asset folder)" : p).sorted().toList();
            if (!folders.isEmpty()) {
                unfixable.add(folders.size() + " folder(s) could not be read (" + String.join(", ", folders.stream().limit(3).toList())
                        + "). Fix them on disk (for example permissions), then Rescan in the Project step.");
            }
        }

        void unresolvedReference(Finding finding) {
            UUID owner = finding.assetId().orElse(null);
            if (owner == null || !finding.message().startsWith(OrganizationPreview.UNRESOLVED_PREFIX)) { lastResort(finding); return; }
            String reference = finding.message().substring(OrganizationPreview.UNRESOLVED_PREFIX.length());
            Set<String> fromRules = targets(owner, reference, AssetManager.Source.RULES);
            Set<String> fromAi = targets(owner, reference, AssetManager.Source.AI);
            Source source = fromAi.size() == 1 ? Source.AI : fromRules.size() == 1 ? Source.RULES : null;
            if (source == null) {
                lastResort(finding, fromRules.isEmpty() ? "no file with that name was found" : fromRules.size() + " files match and none is clearly right");
                return;
            }
            UUID target = UUID.fromString((source == Source.AI ? fromAi : fromRules).iterator().next());
            add(finding.code(), owner, "Point '" + reference + "' in " + path(owner) + " to " + path(target)
                    + (source == Source.AI ? " (chosen by AI)" : " (the only matching file)"),
                    List.of(decision(owner, ReviewedChange.Kind.RELATIONSHIP, ReviewedChange.Decision.ACCEPTED,
                            Map.of("targetAssetId", target.toString(), "reference", reference))), source);
        }

        /** Distinct targets suggested for this owner and reference that are other assets in the inventory. */
        Set<String> targets(UUID owner, String reference, AssetManager.Source source) {
            Set<String> found = new LinkedHashSet<>();
            for (AssetManager.Suggestion suggestion : relationships) {
                if (!suggestion.assetId().equals(owner) || suggestion.source() != source
                        || !suggestion.reference().map(reference::equals).orElse(false)) continue;
                try {
                    UUID target = UUID.fromString(suggestion.proposedValue());
                    if (assets.containsKey(target) && !target.equals(owner)) found.add(target.toString());
                } catch (IllegalArgumentException notAnAsset) {
                    // Not a target; ignored.
                }
            }
            return found;
        }

        boolean fbxOwner(Finding finding) {
            return finding.assetId().map(assets::get).filter(io.github.aiassetmigrate.analysis.MaterialAnalysis::fbx).isPresent();
        }

        /** The built-in reader could not read an FBX: include it unchecked, as acknowledging an FBX always did. */
        void acknowledgeUnreadableFbx(Finding finding) {
            UUID asset = finding.assetId().orElseThrow();
            add(finding.code(), asset, "Include " + path(asset) + " without checking it (the built-in FBX reader could not read "
                    + "it: it may be damaged or use an FBX version the reader does not support; validation will report it "
                    + "as not fully checked)",
                    List.of(decision(asset, ReviewedChange.Kind.ACKNOWLEDGMENT, ReviewedChange.Decision.ACCEPTED,
                            Map.of("reason", "FBX the built-in reader could not read; included unchecked (quick fix in the Plan step)"))),
                    Source.RULES);
        }

        /**
         * An FBX cannot be edited, so its texture is moved instead: the texture gets the output name and folder
         * the FBX names, relative to the FBX's own output folder. For a broken reference the texture is the only
         * file with that name; for a texture the layout moved away, it is the file the reference resolves to.
         */
        void placeForFbx(Finding finding, String prefix) {
            UUID owner = finding.assetId().orElseThrow();
            if (!finding.message().startsWith(prefix)) { lastResort(finding); return; }
            String reference = finding.message().substring(prefix.length()).replace('\\', '/');
            OrganizationPreview.Entry ownerEntry = entry(owner).orElse(null);
            if (ownerEntry == null || ownerEntry.destination().isEmpty()) { lastResort(finding); return; }
            String wanted;
            try {
                Path folder = Path.of(ownerEntry.destination().get()).getParent();
                Path place = (folder == null ? Path.of(reference) : folder.resolve(reference)).normalize();
                if (place.isAbsolute() || place.startsWith("..") || reference.startsWith("/") || reference.contains(":")) {
                    lastResort(finding, "the FBX names a file outside the output folder");
                    return;
                }
                wanted = PortablePath.of(place);
            } catch (java.nio.file.InvalidPathException invalid) {
                lastResort(finding);
                return;
            }
            Asset target = finding.code().equals("UNRESOLVED_REFERENCE") ? uniqueByName(reference) : resolvedTarget(owner, reference);
            if (target == null) {
                lastResort(finding, finding.code().equals("UNRESOLVED_REFERENCE") ? "no single file named "
                        + reference.substring(reference.lastIndexOf('/') + 1) + " was found" : null);
                return;
            }
            if (entry(target.id()).map(e -> !e.conversion().isEmpty()).orElse(false)) {
                lastResort(finding, "its texture is being converted, which changes its name");
                return;
            }
            String placed = placements.putIfAbsent(target.id(), wanted);
            if (placed != null) {
                if (!placed.equals(wanted)) lastResort(finding, "two files need " + path(target.id()) + " in different places");
                return;
            }
            int slash = wanted.lastIndexOf('/');
            String name = wanted.substring(slash + 1), folder = slash < 0 ? "" : wanted.substring(0, slash);
            if (!OrganizationPreview.validName(name) || !OrganizationPreview.validFolder(folder)) { lastResort(finding); return; }
            add(finding.code(), owner, "Place " + path(target.id()) + " at " + wanted + " in the output, where " + path(owner)
                    + " looks for it (FBX files cannot be edited)",
                    List.of(decision(target.id(), ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", name)),
                            decision(target.id(), ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", folder))),
                    Source.RULES);
        }

        Optional<OrganizationPreview.Entry> entry(UUID asset) {
            return preview.entries().stream().filter(e -> e.assetId().equals(asset)).findFirst();
        }

        /** The only texture whose file name matches (ignoring case), or null. */
        Asset uniqueByName(String reference) {
            String name = reference.substring(reference.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            List<Asset> matches = assets.values().stream().filter(a -> a.type() == Asset.Type.TEXTURE
                    && a.relativePath().getFileName().toString().toLowerCase(Locale.ROOT).equals(name)).toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        }

        /** The texture a reference resolves to in the source folder, relative to its owner, or null. */
        Asset resolvedTarget(UUID owner, String reference) {
            try {
                Path parent = assets.get(owner).relativePath().getParent();
                String source = PortablePath.of((parent == null ? Path.of(reference) : parent.resolve(reference)).normalize());
                return assets.values().stream().filter(a -> PortablePath.of(a.relativePath()).equals(source)).findFirst().orElse(null);
            } catch (java.nio.file.InvalidPathException invalid) {
                return null;
            }
        }

        void acknowledge(Finding finding) {
            UUID asset = finding.assetId().orElse(null);
            if (asset == null) { lastResort(finding); return; }
            add(finding.code(), asset, "Include " + path(asset) + " without checking its references (this format cannot be "
                    + "inspected; validation will report it as not fully checked)",
                    List.of(decision(asset, ReviewedChange.Kind.ACKNOWLEDGMENT, ReviewedChange.Decision.ACCEPTED,
                            Map.of("reason", "Included without reference inspection (quick fix in the Plan step)"))), Source.RULES);
        }

        void undo(Finding finding, ReviewedChange.Kind kind, String description) {
            UUID asset = finding.assetId().orElse(null);
            List<ReviewedChange> decisions = asset == null ? List.of() : accepted(asset, kind);
            if (decisions.isEmpty()) { lastResort(finding); return; }
            if (fixes.stream().anyMatch(f -> f.changes().stream().anyMatch(c -> c.assetId().equals(asset) && c.kind() == kind))) return;
            add(finding.code(), asset, String.format(description, path(asset)), decisions.stream().map(this::rejected).toList(), Source.RULES);
        }

        /**
         * A transparent texture going to JPEG: keep the conversion the user asked for and flatten onto white,
         * the usual choice for albedo and UI textures. The description says so; unticking it keeps the blocker.
         */
        void addBackground(Finding finding) {
            UUID asset = finding.assetId().orElse(null);
            List<ReviewedChange> decisions = asset == null ? List.of() : accepted(asset, ReviewedChange.Kind.CONVERSION);
            if (decisions.isEmpty()) { lastResort(finding); return; }
            Map<String, String> values = new HashMap<>(decisions.getLast().values());
            values.put("background", "#FFFFFF");
            add(finding.code(), asset, "Convert " + path(asset) + " to " + values.get("format") + " on a white background "
                    + "(JPG cannot keep transparency; uncheck this and change the CONVERSION in Review for another colour)",
                    List.of(decision(asset, ReviewedChange.Kind.CONVERSION, ReviewedChange.Decision.ACCEPTED, values)), Source.RULES);
        }

        /**
         * A damaged image cannot be repaired here, and copied as it is it fails validation for certain; leaving it
         * out of this migration is the only fix the tool can make, so it is preselected and says how to bring it back.
         */
        void damagedImage(Finding finding) {
            UUID asset = finding.assetId().orElse(null);
            if (asset == null || !assets.containsKey(asset)) { lastResort(finding); return; }
            if (!lastResort.add(asset)) return;
            add(finding.code(), asset, "Leave " + path(asset) + " out of the migration (the image is damaged and cannot be "
                    + "decoded; replace it in your folder and Rescan to include it)",
                    List.of(decision(asset, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED,
                            Map.of("reason", "Damaged image left out (quick fix in the Plan step)"))), Source.RULES);
        }

        void undoDestination(Finding finding) {
            UUID asset = finding.assetId().orElse(null);
            List<ReviewedChange> decisions = new ArrayList<>();
            if (asset != null) {
                decisions.addAll(accepted(asset, ReviewedChange.Kind.NAME));
                decisions.addAll(accepted(asset, ReviewedChange.Kind.FOLDER));
            }
            if (decisions.isEmpty()) { lastResort(finding); return; }
            add(finding.code(), asset, "Keep the original name and folder of " + path(asset) + " (the reviewed one is not a valid path)",
                    decisions.stream().map(this::rejected).toList(), Source.RULES);
        }

        /** Same-place clashes: the first file (by source path) keeps its place, the others get a numbered name. */
        void destinationConflict(Finding finding) {
            if (!destinationsDone) {
                destinationsDone = true;
                Map<String, List<OrganizationPreview.Entry>> byPlace = new LinkedHashMap<>();
                Set<String> taken = new HashSet<>();
                preview.entries().stream().filter(e -> e.destination().isPresent())
                        .sorted(Comparator.comparing(e -> PortablePath.of(e.source())))
                        .forEach(e -> {
                            String key = fold(e.destination().orElseThrow());
                            taken.add(key);
                            byPlace.computeIfAbsent(key, ignored -> new ArrayList<>()).add(e);
                        });
                for (List<OrganizationPreview.Entry> group : byPlace.values()) {
                    if (group.size() < 2) continue;
                    String kept = path(group.getFirst().assetId());
                    boolean all = true;
                    for (OrganizationPreview.Entry entry : group.subList(1, group.size())) {
                        Optional<ReviewedChange> change = rename(entry, taken);
                        change.ifPresent(name -> add("DESTINATION_CONFLICT", entry.assetId(), "Rename " + path(entry.assetId())
                                + " to " + name.values().get("name") + " (it would be written to the same place as " + kept + ")",
                                List.of(name), Source.RULES));
                        if (change.isPresent()) clashSettled.add(entry.assetId());
                        else all = false;
                    }
                    if (all) clashSettled.add(group.getFirst().assetId());
                }
            }
            // Both files of a clash report it; renaming the others settles the kept one too.
            // A file-versus-folder clash (one output path inside another) needs a hand fix.
            UUID asset = finding.assetId().orElse(null);
            if (asset == null || !clashSettled.contains(asset)) lastResort(finding);
        }

        Optional<ReviewedChange> rename(OrganizationPreview.Entry entry, Set<String> taken) {
            String destination = entry.destination().orElseThrow();
            int slash = destination.lastIndexOf('/');
            String folder = slash < 0 ? "" : destination.substring(0, slash + 1);
            String outputName = destination.substring(slash + 1);
            String outputExtension = extension(outputName);
            String base = accepted(entry.assetId(), ReviewedChange.Kind.NAME).stream().reduce((a, b) -> b)
                    .map(c -> c.values().get("name")).orElse(entry.source().getFileName().toString());
            String stem = base.substring(0, base.length() - extension(base).length());
            for (int n = 2; n < 1000; n++) {
                String candidateStem = stem + "_" + n;
                String key = fold(folder + candidateStem + outputExtension);
                String name = candidateStem + extension(base);
                if (taken.contains(key) || !OrganizationPreview.validName(name)) continue;
                taken.add(key);
                return Optional.of(decision(entry.assetId(), ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", name)));
            }
            return Optional.empty();
        }

        /** Keeps the newest decision for each reference that has several different targets. */
        void conflictingCorrections(Finding finding) {
            UUID owner = finding.assetId().orElse(null);
            Map<String, List<ReviewedChange>> byReference = new LinkedHashMap<>();
            if (owner != null) {
                saved.stream().filter(c -> c.assetId().equals(owner) && c.decision() == ReviewedChange.Decision.ACCEPTED
                                && (c.kind() == ReviewedChange.Kind.RELATIONSHIP || c.kind() == ReviewedChange.Kind.MATERIAL_MAPPING)
                                && c.values().containsKey("reference") && c.values().containsKey("targetAssetId"))
                        .forEach(c -> byReference.computeIfAbsent(c.values().get("reference"), ignored -> new ArrayList<>()).add(c));
            }
            List<ReviewedChange> undo = new ArrayList<>();
            for (List<ReviewedChange> group : byReference.values()) {
                if (group.stream().map(c -> c.values().get("targetAssetId")).distinct().count() < 2) continue;
                ReviewedChange newest = group.stream().max(Comparator.comparing(ReviewedChange::reviewedAt)).orElseThrow();
                group.stream().filter(c -> c != newest && !c.values().get("targetAssetId").equals(newest.values().get("targetAssetId")))
                        .map(this::rejected).forEach(undo::add);
            }
            if (undo.isEmpty()) { lastResort(finding); return; }
            add(finding.code(), owner, "Keep only your latest correction for each reference in " + path(owner)
                    + " (undo " + undo.size() + " older one(s) that point elsewhere)", undo, Source.RULES);
        }

        /** Undoes corrections that point a reference at the wrong kind of file (.mtl needs a material, others a texture). */
        void invalidCorrection(Finding finding) {
            UUID owner = finding.assetId().orElse(null);
            List<ReviewedChange> undo = owner == null ? List.of() : accepted(owner, ReviewedChange.Kind.RELATIONSHIP).stream()
                    .filter(c -> c.values().containsKey("reference") && c.values().containsKey("targetAssetId"))
                    .filter(c -> {
                        Asset target;
                        try { target = assets.get(UUID.fromString(c.values().get("targetAssetId"))); }
                        catch (IllegalArgumentException invalid) { return true; }
                        Asset.Type expected = c.values().get("reference").toLowerCase(Locale.ROOT).endsWith(".mtl")
                                ? Asset.Type.MATERIAL : Asset.Type.TEXTURE;
                        return target == null || target.type() != expected || target.id().equals(owner);
                    }).map(this::rejected).toList();
            if (undo.isEmpty()) { lastResort(finding); return; }
            add(finding.code(), owner, "Undo " + undo.size() + " correction(s) in " + path(owner)
                    + " that point to the wrong kind of file", undo, Source.RULES);
        }

        /** A finding recorded by the scan itself (for example a file whose content could not be checked). */
        void scanProblem(Finding finding) {
            if (finding.sourceRelativePath().filter(p -> inventory.excludableFiles().stream()
                    .anyMatch(o -> o.relativePath().equals(p))).isPresent()) {
                partialInventory(); // a skipped file: leaving the skipped files out covers it
                return;
            }
            Optional<Path> file = finding.sourceRelativePath()
                    .filter(p -> assets.values().stream().anyMatch(a -> a.relativePath().equals(p)));
            if (file.isEmpty()) {
                unfixable.add(finding.code() + ": " + finding.message() + " (found by the scan; fix it on disk, then Rescan)");
                return;
            }
            if (!leftOut.add(file.get())) return;
            fixes.add(new Fix(finding.code(), finding.assetId(), "Leave " + PortablePath.of(file.get())
                    + " out of the project (" + plain(finding.code()) + ")", List.of(), Set.of(file.get()), Source.RULES, false));
        }

        void lastResort(Finding finding) {
            lastResort(finding, null);
        }

        /** Excludes the file from the migration; never preselected. */
        void lastResort(Finding finding, String why) {
            UUID asset = finding.assetId().orElse(null);
            if (asset == null || !assets.containsKey(asset)) {
                unfixable.add(finding.code() + ": " + finding.message());
                return;
            }
            if (!lastResort.add(asset)) return;
            fixes.add(new Fix(finding.code(), Optional.of(asset), "Leave " + path(asset) + " out of the migration ("
                    + plain(finding.code()) + (why == null ? "" : ": " + why) + ")",
                    List.of(decision(asset, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED,
                            Map.of("reason", "Left out to clear " + finding.code() + " (quick fix in the Plan step)"))),
                    Set.of(), Source.RULES, false));
        }
    }

    private static String plain(String code) {
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String fold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
