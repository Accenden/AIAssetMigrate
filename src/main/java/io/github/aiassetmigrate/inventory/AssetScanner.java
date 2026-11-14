package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.github.aiassetmigrate.domain.PathOutcome.Kind.*;
import static io.github.aiassetmigrate.domain.PathOutcome.Status.*;

/**
 * Read-only traversal, fingerprints and bounded format inspection, not full validation.
 * Attribute and ancestry checks detect concurrent changes best-effort, not atomically.
 * Providers without file keys and edits restoring timestamps can evade detection;
 * this scanner does not defend against hostile filesystem replacement races.
 */
public final class AssetScanner {
    private final ScanLimits limits;
    private final Io io;

    public AssetScanner() { this(ScanLimits.DEFAULTS); }

    public AssetScanner(ScanLimits limits) { this(limits, new Io()); }

    AssetScanner(ScanLimits limits, Io io) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.io = Objects.requireNonNull(io, "io");
    }

    /** Scan status reflects path coverage; findings can describe unverified content without making a scan partial. */
    public record ScanResult(Path sourceRoot, List<Asset> assets, List<Finding> findings,
                             InventorySnapshot.ScanStatus scanStatus, List<PathOutcome> pathOutcomes,
                             List<AssetReference> references) {
        public ScanResult {
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            assets = List.copyOf(assets);
            findings = List.copyOf(findings);
            Objects.requireNonNull(scanStatus, "scanStatus");
            pathOutcomes = List.copyOf(pathOutcomes);
            references = List.copyOf(references);
        }

        /** Kept for callers which do not yet consume scan-time references. */
        public ScanResult(Path sourceRoot, List<Asset> assets, List<Finding> findings,
                          InventorySnapshot.ScanStatus scanStatus, List<PathOutcome> pathOutcomes) {
            this(sourceRoot, assets, findings, scanStatus, pathOutcomes, List.of());
        }
    }

    // Small fault seams; production safety/containment checks cannot be overridden.
    static class Io {
        BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        DirectoryStream<Path> directory(Path path) throws IOException {
            return Files.newDirectoryStream(path);
        }

        InputStream open(Path path) throws IOException {
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        }
    }

    public ScanResult scan(Path sourceRoot) throws IOException {
        return scan(sourceRoot, Set.of());
    }

    /**
     * As {@link #scan(Path)}, leaving out the given files (relative paths): each one found is recorded as
     * EXCLUDED with an EXCLUDED_BY_USER note and is never opened. Exclusions name files only; a directory
     * with an excluded path is traversed as usual.
     */
    public ScanResult scan(Path sourceRoot, Set<Path> excludedFiles) throws IOException {
        interrupted();
        Set<String> excluded = new HashSet<>();
        for (Path path : Objects.requireNonNull(excludedFiles, "excludedFiles")) {
            excluded.add(portable(new PathOutcome(path, FILE, EXCLUDED).relativePath())); // validates a relative file path
        }
        SafeStoragePaths.validateExistingPath(sourceRoot);
        Path root = sourceRoot.toAbsolutePath().normalize();
        BasicFileAttributes attributes = io.attributes(root);
        if (!attributes.isDirectory() || !Files.isReadable(root)) {
            throw new IOException("Source must be an existing readable directory: " + root);
        }
        State state = new State(root, attributes, excluded);
        state.traverse();
        interrupted();
        state.assets.sort(Comparator.comparing(asset -> portable(asset.relativePath())));
        state.resolveReferences();
        state.findings.sort(Comparator.comparing((Finding finding) ->
                portable(finding.sourceRelativePath().orElseThrow())).thenComparing(Finding::code));
        state.outcomes.sort(Comparator.comparing(outcome -> portable(outcome.relativePath())));
        state.references.sort(Comparator.comparing((AssetReference reference) -> portable(state.assetPaths.get(reference.ownerAssetId())))
                .thenComparing(AssetReference::kind).thenComparing(AssetReference::originalText)
                .thenComparing(AssetReference::resolutionStatus));
        return new ScanResult(root, state.assets, state.findings, scanStatus(state.outcomes), state.outcomes, state.references);
    }

    private final class State {
        private final Path root;
        private final BasicFileAttributes rootAttributes;
        private final List<Asset> assets = new ArrayList<>();
        private final List<Finding> findings = new ArrayList<>();
        private final List<PathOutcome> outcomes = new ArrayList<>();
        private final List<PendingReference> pendingReferences = new ArrayList<>();
        private final List<AssetReference> references = new ArrayList<>();
        private final Map<UUID, Path> assetPaths = new HashMap<>();
        private final ArrayDeque<DirectoryVisit> pending = new ArrayDeque<>();
        private final Set<String> excluded;
        private int entries;

        State(Path root, BasicFileAttributes rootAttributes, Set<String> excluded) {
            this.root = root;
            this.rootAttributes = rootAttributes;
            this.excluded = excluded;
        }

        private void traverse() throws IOException {
            pending.add(new DirectoryVisit(root, 0));
            while (!pending.isEmpty()) {
                DirectoryVisit visit = pending.removeLast();
                directory(visit.path(), visit.depth());
            }
            // Propagate incomplete coverage bottom-up without recursive traversal or Path equality keys.
            Map<String, PathOutcome.Status> incomplete = new HashMap<>();
            outcomes.sort(Comparator.comparingInt((PathOutcome outcome) ->
                    outcome.relativePath().getNameCount()).reversed());
            for (int i = 0; i < outcomes.size(); i++) {
                PathOutcome outcome = outcomes.get(i);
                Path path = outcome.relativePath();
                PathOutcome.Status inherited = incomplete.get(portable(path));
                PathOutcome.Status status = outcome.status();
                if (inherited == UNVISITED || (inherited == FAILED && status != UNVISITED)) status = inherited;
                if (outcome.kind() == DIRECTORY && status != outcome.status()) {
                    outcomes.set(i, new PathOutcome(path, DIRECTORY, status));
                }
                if ((status == FAILED || status == UNVISITED) && !path.toString().isEmpty()) {
                    Path parent = path.getParent();
                    String key = parent == null ? "" : portable(parent);
                    incomplete.merge(key, status, (a, b) -> a == UNVISITED || b == UNVISITED ? UNVISITED : FAILED);
                }
            }
            PathOutcome.Status rootStatus = incomplete.get("");
            if (rootStatus != null) {
                for (int i = 0; i < outcomes.size(); i++) {
                    if (outcomes.get(i).relativePath().toString().isEmpty()) {
                        outcomes.set(i, new PathOutcome(root.relativize(root), DIRECTORY, rootStatus));
                    }
                }
            }
        }

        private PathOutcome.Status directory(Path directory, int depth) throws IOException {
            interrupted();
            Path relative = root.relativize(directory);
            if (depth >= limits.maxDepth()) {
                problem(relative, "DEPTH_LIMIT", "Directory traversal depth limit reached");
                outcome(relative, DIRECTORY, UNVISITED);
                return UNVISITED;
            }
            List<Path> children = new ArrayList<>();
            int remaining = limits.maxEntries() - entries;
            try {
                safe(directory);
                try (DirectoryStream<Path> stream = io.directory(directory)) {
                    for (Path child : stream) {
                        interrupted();
                        children.add(child);
                        if (children.size() > remaining) {
                            problem(relative, "ENTRY_LIMIT", "Directory exceeds remaining entry budget");
                            outcome(relative, DIRECTORY, UNVISITED);
                            entries = limits.maxEntries();
                            return UNVISITED;
                        }
                    }
                }
            } catch (DirectoryIteratorException failure) {
                entries += children.size();
                return directoryFailure(directory, failure.getCause());
            } catch (IOException failure) {
                entries += children.size();
                return directoryFailure(directory, failure);
            }
            entries += children.size();
            children.sort(Comparator.comparing(child -> child.getFileName().toString()));
            Map<String, Integer> names = new HashMap<>();
            for (Path child : children) {
                names.merge(child.getFileName().toString().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
            PathOutcome.Status coverage = TRAVERSED;
            for (Path child : children) {
                interrupted();
                String name = child.getFileName().toString();
                if (!Objects.equals(child.getParent(), directory) || !portableName(name)) {
                    problem(relative, "UNSAFE_PATH", "Entry has no safe portable inventory path");
                    if (coverage != UNVISITED) coverage = FAILED;
                    continue;
                }
                Path childRelative = root.relativize(child);
                if (names.get(name.toUpperCase(Locale.ROOT)) > 1) {
                    problem(childRelative, "CASE_COLLISION", "Case-insensitive sibling path collision");
                    // Unknown kind must cover potential descendants, not merely one file.
                    outcome(childRelative, DIRECTORY, FAILED);
                    if (coverage != UNVISITED) coverage = FAILED;
                    continue;
                }
                BasicFileAttributes before;
                try {
                    before = io.attributes(child);
                    safe(child);
                    if (before.isSymbolicLink() || before.isOther()
                            || (!before.isDirectory() && !before.isRegularFile())) {
                        throw new IOException("Unsafe entry type");
                    }
                } catch (IOException failure) {
                    propagateInterruption(failure);
                    problem(childRelative, "UNSAFE_PATH", "Entry cannot be safely classified or accessed");
                    outcome(childRelative, DIRECTORY, FAILED);
                    if (coverage != UNVISITED) coverage = FAILED;
                    continue;
                }
                PathOutcome.Status status;
                if (before.isDirectory()) {
                    pending.add(new DirectoryVisit(child, depth + 1));
                    status = TRAVERSED;
                } else {
                    status = file(child, before);
                }
                if (status == UNVISITED) coverage = UNVISITED;
                else if (status == FAILED && coverage != UNVISITED) coverage = FAILED;
            }
            outcome(relative, DIRECTORY, coverage);
            return coverage;
        }

        private PathOutcome.Status directoryFailure(Path directory, IOException failure) throws IOException {
            propagateInterruption(failure);
            if (directory.equals(root)) throw failure;
            Path relative = root.relativize(directory);
            problem(relative, "DIRECTORY_READ_FAILED", "Directory could not be fully enumerated");
            outcome(relative, DIRECTORY, FAILED);
            return FAILED;
        }

        private PathOutcome.Status file(Path file, BasicFileAttributes before) throws IOException {
            Path relative = root.relativize(file);
            if (excluded.contains(portable(relative))) {
                problem(relative, Optional.empty(), Finding.Severity.INFO, "EXCLUDED_BY_USER",
                        "Left out of the project by your choice; not read, inventoried or migrated");
                outcome(relative, FILE, EXCLUDED);
                return EXCLUDED;
            }
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            Asset.Type type = switch (extension) {
                case "obj", "fbx" -> Asset.Type.MODEL;
                case "mtl" -> Asset.Type.MATERIAL;
                case "png", "jpg", "jpeg" -> Asset.Type.TEXTURE;
                default -> null;
            };
            if (type == null) {
                problem(relative, "UNSUPPORTED_EXTENSION", "Unsupported file extension");
                outcome(relative, FILE, SKIPPED);
                return SKIPPED;
            }
            if (before.size() > limits.maxFileBytes()) {
                problem(relative, "FILE_SIZE_LIMIT", "File exceeds the configured scan byte limit");
                outcome(relative, FILE, SKIPPED);
                return SKIPPED;
            }
            try {
                MessageDigest digest = sha256();
                safe(file);
                boolean hashOnlyImage = type == Asset.Type.TEXTURE;
                FormatInspection inspection = hashOnlyImage ? null : new FormatInspection(extension, before.size(), limits);
                long bytes = 0;
                boolean grew = false;
                try (InputStream input = io.open(file)) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        interrupted();
                        long remaining = before.size() - bytes;
                        int requested = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
                        int count = input.read(buffer, 0, requested);
                        if (count < 0) break;
                        if (count == 0) throw new IOException("File read made no progress");
                        if (count > remaining) {
                            grew = true;
                            break;
                        }
                        digest.update(buffer, 0, count);
                        if (inspection != null) inspection.accept(buffer, count);
                        bytes += count;
                    }
                }
                interrupted();
                BasicFileAttributes after = io.attributes(file);
                safe(file);
                if (grew || !after.isRegularFile() || !same(before, after) || bytes != before.size()) {
                    problem(relative, "UNSTABLE_FILE", "File changed while being fingerprinted");
                    outcome(relative, FILE, FAILED);
                    return FAILED;
                }
                FormatInspection.Result inspected = inspection == null
                        ? new FormatInspection.Result(false, Asset.InspectionStatus.NOT_PERFORMED, "SHA256_STREAM_ONLY",
                        "SHA-256 fingerprint computed from streamed bytes only; content validation was not performed", List.of(), List.of())
                        : inspection.finish();
                if (inspected.corrupt()) {
                    problem(relative, "CORRUPT_FORMAT", inspected.message());
                    outcome(relative, FILE, SKIPPED);
                    return SKIPPED;
                }
                Finding.Severity severity = inspected.status() == Asset.InspectionStatus.UNKNOWN
                        ? Finding.Severity.UNKNOWN : Finding.Severity.WARNING;
                problem(relative, Optional.empty(), severity,
                        inspected.status() == Asset.InspectionStatus.UNKNOWN ? "INSPECTION_UNKNOWN" : "INSPECTION_UNVERIFIED",
                        inspected.message());
                UUID id = UUID.randomUUID();
                assets.add(new Asset(id, relative, type,
                        HexFormat.of().formatHex(digest.digest()), Map.of(
                        Asset.FORMAT, extension, Asset.SIZE_BYTES, Long.toString(before.size()),
                        Asset.MODIFIED_AT, before.lastModifiedTime().toInstant().toString(),
                        Asset.INSPECTION_STATUS, inspected.status().name(),
                        Asset.INSPECTION_METHOD, inspected.method())));
                assetPaths.put(id, relative);
                for (FormatInspection.ExtractedReference reference : inspected.references()) {
                    pendingReferences.add(new PendingReference(id, relative, reference.kind(), reference.originalText(),
                            reference.unresolved()));
                }
                for (FormatInspection.Limitation limitation : inspected.limitations()) {
                    problem(relative, Optional.of(id), Finding.Severity.WARNING, limitation.code(), limitation.message());
                }
                outcome(relative, FILE, INVENTORIED);
                return INVENTORIED;
            } catch (IOException failure) {
                propagateInterruption(failure);
                problem(relative, "FILE_READ_FAILED", "File could not be safely fingerprinted");
                outcome(relative, FILE, FAILED);
                return FAILED;
            }
        }

        private void safe(Path path) throws IOException {
            SafeStoragePaths.validateExistingPath(path);
            BasicFileAttributes currentRoot = io.attributes(root);
            if (!currentRoot.isDirectory() || !Objects.equals(rootAttributes.fileKey(), currentRoot.fileKey())
                    || !rootAttributes.creationTime().equals(currentRoot.creationTime())
                    || !path.toRealPath().startsWith(root)) {
                throw new IOException("Source ancestry or containment changed");
            }
        }

        private void problem(Path path, String code, String message) {
            problem(path, Optional.empty(), Finding.Severity.WARNING, code, message);
        }

        private void problem(Path path, Optional<UUID> assetId, Finding.Severity severity, String code, String message) {
            findings.add(new Finding(UUID.randomUUID(), assetId, Optional.of(path), severity, code, message));
        }

        private void outcome(Path path, PathOutcome.Kind kind, PathOutcome.Status status) {
            outcomes.add(new PathOutcome(path, kind, status));
        }

        private void resolveReferences() throws InterruptedIOException {
            Map<String, List<Asset>> supported = new HashMap<>();
            Map<String, Integer> foldedSupported = new HashMap<>();
            Map<String, PathOutcome> pathOutcomes = new HashMap<>();
            Map<String, Integer> foldedOutcomes = new HashMap<>();
            for (Asset asset : assets) {
                supported.computeIfAbsent(portable(asset.relativePath()), ignored -> new ArrayList<>()).add(asset);
                foldedSupported.merge(folded(asset.relativePath()), 1, Integer::sum);
            }
            for (PathOutcome outcome : outcomes) {
                pathOutcomes.put(portable(outcome.relativePath()), outcome);
                if (!outcome.relativePath().toString().isEmpty()) {
                    foldedOutcomes.merge(folded(outcome.relativePath()), 1, Integer::sum);
                }
            }
            boolean complete = scanStatus(outcomes) == InventorySnapshot.ScanStatus.COMPLETE;
            for (int index = 0; index < pendingReferences.size(); index++) {
                if ((index & 255) == 0) interrupted();
                PendingReference pending = pendingReferences.get(index);
                AssetReference resolved = resolve(pending, supported, foldedSupported, pathOutcomes, foldedOutcomes, complete);
                references.add(resolved);
                if (resolved.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED) {
                    problem(pending.ownerPath(), Optional.of(pending.ownerAssetId()), Finding.Severity.WARNING,
                            "REFERENCE_" + resolved.resolutionStatus(), "Reference '" + pending.originalText()
                                    + "' is " + resolved.resolutionStatus());
                }
            }
        }

        private AssetReference resolve(PendingReference pending, Map<String, List<Asset>> supported,
                                       Map<String, Integer> foldedSupported, Map<String, PathOutcome> pathOutcomes,
                                       Map<String, Integer> foldedOutcomes, boolean complete) {
            if (pending.unresolved()) {
                return reference(pending, null, AssetReference.ResolutionStatus.UNRESOLVED);
            }
            Path target = normalizedTarget(pending.ownerPath(), pending.originalText());
            if (target == null) return reference(pending, null, AssetReference.ResolutionStatus.UNSAFE);
            List<Asset> exact = supported.getOrDefault(portable(target), List.of());
            int foldedSupportedCount = foldedSupported.getOrDefault(folded(target), 0);
            int foldedOutcomeCount = foldedOutcomes.getOrDefault(folded(target), 0);
            if (foldedSupportedCount > 1 || foldedOutcomeCount > 1 || (exact.isEmpty() && foldedSupportedCount > 0)) {
                return reference(pending, target, AssetReference.ResolutionStatus.CASE_AMBIGUOUS);
            }
            if (exact.size() == 1) {
                Asset.Type expected = pending.kind() == AssetReference.Kind.MATERIAL_LIBRARY
                        ? Asset.Type.MATERIAL : Asset.Type.TEXTURE;
                return reference(pending, target, exact.getFirst().type() == expected
                        ? AssetReference.ResolutionStatus.RESOLVED : AssetReference.ResolutionStatus.UNRESOLVED);
            }
            if (skipped(target, pathOutcomes)) return reference(pending, target, AssetReference.ResolutionStatus.SKIPPED);
            return reference(pending, target, complete ? AssetReference.ResolutionStatus.MISSING
                    : AssetReference.ResolutionStatus.UNRESOLVED);
        }

        private static AssetReference reference(PendingReference pending, Path target,
                                                AssetReference.ResolutionStatus status) {
            return new AssetReference(pending.ownerAssetId(), pending.kind(), pending.originalText(),
                    Optional.ofNullable(target), status);
        }

        private static Path normalizedTarget(Path owner, String original) {
            if (original.isEmpty() || original.startsWith("/") || original.contains("\\")
                    || original.contains(":") || original.indexOf('\0') >= 0) return null;
            try {
                Path raw = Path.of(original);
                Path parent = owner.getParent();
                Path target = (parent == null ? raw : parent.resolve(raw)).normalize();
                if (target.isAbsolute() || target.getRoot() != null || target.toString().isEmpty() || target.startsWith("..")) {
                    return null;
                }
                for (Path component : target) {
                    if (!portableName(component.toString())) return null;
                }
                return target;
            } catch (InvalidPathException exception) {
                return null;
            }
        }

        private static boolean skipped(Path target, Map<String, PathOutcome> outcomes) {
            Path current = target;
            while (current != null) {
                PathOutcome outcome = outcomes.get(portable(current));
                if (outcome != null && (outcome.status() == SKIPPED || outcome.status() == FAILED
                        || outcome.status() == UNVISITED || outcome.status() == EXCLUDED)) return true;
                current = current.getParent();
            }
            PathOutcome root = outcomes.get("");
            return root != null && (root.status() == SKIPPED || root.status() == FAILED || root.status() == UNVISITED);
        }
    }

    private record DirectoryVisit(Path path, int depth) {}
    private record PendingReference(UUID ownerAssetId, Path ownerPath, AssetReference.Kind kind, String originalText,
                                    boolean unresolved) {}

    private static boolean same(BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey()) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.creationTime().equals(after.creationTime());
    }

    private static boolean portableName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains(":") && !name.contains("\\");
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private static String folded(Path path) { return portable(path).toUpperCase(Locale.ROOT); }

    private static InventorySnapshot.ScanStatus scanStatus(List<PathOutcome> outcomes) {
        return outcomes.stream().allMatch(outcome -> outcome.status() == INVENTORIED || outcome.status() == TRAVERSED
                        || outcome.status() == EXCLUDED)
                ? InventorySnapshot.ScanStatus.COMPLETE : InventorySnapshot.ScanStatus.PARTIAL;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Asset scan interrupted");
    }

    private static void propagateInterruption(IOException failure) throws IOException {
        if (failure instanceof InterruptedIOException) throw failure;
        interrupted();
    }
}
