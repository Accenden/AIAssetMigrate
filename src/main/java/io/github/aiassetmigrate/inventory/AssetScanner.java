package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.github.aiassetmigrate.domain.PathOutcome.Kind.*;
import static io.github.aiassetmigrate.domain.PathOutcome.Status.*;

/**
 * Read-only, bounded traversal and fingerprints; no format validation is performed.
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

    public record ScanResult(Path sourceRoot, List<Asset> assets, List<Finding> findings,
                             InventorySnapshot.ScanStatus scanStatus, List<PathOutcome> pathOutcomes) {
        public ScanResult {
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            assets = List.copyOf(assets);
            findings = List.copyOf(findings);
            Objects.requireNonNull(scanStatus, "scanStatus");
            pathOutcomes = List.copyOf(pathOutcomes);
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
        interrupted();
        SafeStoragePaths.validateExistingPath(sourceRoot);
        Path root = sourceRoot.toAbsolutePath().normalize();
        BasicFileAttributes attributes = io.attributes(root);
        if (!attributes.isDirectory() || !Files.isReadable(root)) {
            throw new IOException("Source must be an existing readable directory: " + root);
        }
        State state = new State(root, attributes);
        state.traverse();
        interrupted();
        state.assets.sort(Comparator.comparing(asset -> portable(asset.relativePath())));
        state.findings.sort(Comparator.comparing((Finding finding) ->
                portable(finding.sourceRelativePath().orElseThrow())).thenComparing(Finding::code));
        state.outcomes.sort(Comparator.comparing(outcome -> portable(outcome.relativePath())));
        return new ScanResult(root, state.assets, state.findings, state.findings.isEmpty()
                ? InventorySnapshot.ScanStatus.COMPLETE : InventorySnapshot.ScanStatus.PARTIAL, state.outcomes);
    }

    private final class State {
        private final Path root;
        private final BasicFileAttributes rootAttributes;
        private final List<Asset> assets = new ArrayList<>();
        private final List<Finding> findings = new ArrayList<>();
        private final List<PathOutcome> outcomes = new ArrayList<>();
        private final ArrayDeque<DirectoryVisit> pending = new ArrayDeque<>();
        private int entries;

        State(Path root, BasicFileAttributes rootAttributes) {
            this.root = root;
            this.rootAttributes = rootAttributes;
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
            try {
                MessageDigest digest = sha256();
                safe(file);
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
                        if (count > remaining) {
                            grew = true;
                            break;
                        }
                        digest.update(buffer, 0, count);
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
                assets.add(new Asset(UUID.randomUUID(), relative, type,
                        HexFormat.of().formatHex(digest.digest()), Map.of(
                        Asset.FORMAT, extension, Asset.SIZE_BYTES, Long.toString(before.size()),
                        Asset.MODIFIED_AT, before.lastModifiedTime().toInstant().toString(),
                        Asset.INSPECTION_STATUS, Asset.InspectionStatus.NOT_PERFORMED.name())));
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
            findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(path),
                    Finding.Severity.WARNING, code, message));
        }

        private void outcome(Path path, PathOutcome.Kind kind, PathOutcome.Status status) {
            outcomes.add(new PathOutcome(path, kind, status));
        }
    }

    private record DirectoryVisit(Path path, int depth) {}

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
