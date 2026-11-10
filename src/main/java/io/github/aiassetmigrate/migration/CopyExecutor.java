package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/**
 * Copy-only executor. The caller supplies the approved immutable plan and inventory and is
 * responsible for authenticating the approval and excluding concurrent plan/review changes;
 * a plan's APPROVED status is not itself authorization. All output directories must
 * already exist. Source, destination, state and log namespaces and their ancestors must
 * be trusted against concurrent replacement; path and identity checks are best effort,
 * not a handle-anchored security boundary.
 *
 * Publication requires filesystem hard-link support and source unix:nlink support; other
 * providers fail closed. No durability or rollback is promised.
 */
public final class CopyExecutor {
    public enum Status { COMPLETED, FAILED, SKIPPED }
    public record Step(UUID commandId, Status status, String detail) {}
    public record Result(boolean preflightPassed, String error, List<Step> steps) {
        public Result { steps = List.copyOf(steps); }
    }

    // Package-private seam for deterministic race and failure tests.
    interface Hook { void run(int index, String stage) throws IOException; }
    private final Hook hook;
    public CopyExecutor() { this((index, stage) -> {}); }
    CopyExecutor(Hook hook) { this.hook = Objects.requireNonNull(hook); }

    /** Checks retryable execution prerequisites without creating outputs. */
    public void validatePreflight(InventorySnapshot snapshot, MigrationPlan plan, Path state, Path logs) throws IOException {
        preflight(snapshot, plan, state, logs);
    }

    /** Returns a structured preflight or execution result. No writes occur before preflight succeeds. */
    public Result execute(InventorySnapshot snapshot, MigrationPlan plan, Path stateDirectory, Path logDirectory) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(plan);
        List<MigrationCommand> commands = plan.commands();
        List<Step> steps = new ArrayList<>();
        List<Prepared> prepared;
        try {
            prepared = preflight(snapshot, plan, stateDirectory, logDirectory);
        } catch (IOException | IllegalArgumentException | SecurityException | UnsupportedOperationException e) {
            for (MigrationCommand command : commands) steps.add(new Step(command.id(), Status.SKIPPED, "Preflight failed"));
            return new Result(false, message(e), steps);
        }
        String error = "";
        for (int i = 0; i < prepared.size(); i++) {
            Prepared p = prepared.get(i);
            if (!error.isEmpty()) {
                steps.add(new Step(p.command.id(), Status.SKIPPED, "Earlier step failed"));
                continue;
            }
            try {
                String detail = copy(p, i);
                steps.add(new Step(p.command.id(), Status.COMPLETED, detail));
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                error = message(e);
                steps.add(new Step(p.command.id(), Status.FAILED, error));
            }
        }
        return new Result(true, error, steps);
    }

    private record Prepared(MigrationCommand command, Asset asset, Path source, Path destination,
                            Map<Path, Object> identities) {}

    private static List<Prepared> preflight(InventorySnapshot snapshot, MigrationPlan plan, Path state, Path log)
            throws IOException {
        Project project = snapshot.project();
        if (!plan.projectId().equals(project.id()) || plan.inventoryRevision() != project.inventoryRevision()
                || plan.status() != PlanStatus.APPROVED || !snapshot.hasCompleteCoverage())
            throw new IOException("Plan is not approved for this complete inventory revision");
        if (plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN))
            throw new IOException("Plan contains blocking findings");
        Path source = absolute(project.sourceRoot()), output = absolute(plan.outputRoot());
        state = absolute(state); log = absolute(log);
        SafeStoragePaths.validateSeparation(source, output);
        SafeStoragePaths.validateSeparation(source, state);
        SafeStoragePaths.validateSeparation(source, log);
        SafeStoragePaths.validateExistingPath(output);
        SafeStoragePaths.validateExistingPath(state);
        SafeStoragePaths.validateExistingPath(log);
        if (overlap(output, state) || overlap(output, log)) throw new IOException("Output overlaps state or log");
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output root must exist");
        var preview = OrganizationPreview.preview(snapshot, plan.projectId(), plan.inventoryRevision(), plan.reviewedChanges());
        if (preview.blocked()) throw new IOException("Organization preview is blocked");
        Map<UUID, OrganizationPreview.Entry> entries = new HashMap<>();
        for (var entry : preview.entries()) if (entries.put(entry.assetId(), entry) != null) throw new IOException("Duplicate preview asset");
        Map<UUID, Asset> assets = new HashMap<>();
        for (Asset asset : project.assets()) if (assets.put(asset.id(), asset) != null) throw new IOException("Duplicate asset");
        Set<UUID> ids = new HashSet<>(), usedAssets = new HashSet<>(), completed = new HashSet<>();
        NavigableSet<String> paths = new TreeSet<>();
        List<Prepared> result = new ArrayList<>();
        for (MigrationCommand command : plan.commands()) {
            if (!ids.add(command.id()) || !usedAssets.add(command.assetId())) throw new IOException("Duplicate command or asset");
            if (command.operation() != MigrationCommand.Operation.COPY || !command.parameters().isEmpty()
                    || command.converterRequirement().isPresent()) throw new IOException("Only unmodified COPY is supported");
            if (!completed.containsAll(command.dependencies()) || command.dependencies().contains(command.id()))
                throw new IOException("Dependencies must precede the operation");
            completed.add(command.id());
            Asset asset = assets.get(command.assetId());
            var entry = entries.get(command.assetId());
            if (asset == null || entry == null || entry.excluded() || entry.destination().isEmpty()) throw new IOException("Asset is not available in preview");
            Path relative = command.relativeOutputPath();
            String dest = portable(relative);
            if (!dest.equals(entry.destination().orElseThrow())) throw new IOException("Output differs from reviewed preview");
            String folded = Normalizer.normalize(dest, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
            if (paths.contains(folded)) throw new IOException("Output paths collide");
            for (int slash = folded.indexOf('/'); slash >= 0; slash = folded.indexOf('/', slash + 1)) {
                if (paths.contains(folded.substring(0, slash))) throw new IOException("Output paths collide");
            }
            String descendant = paths.ceiling(folded + "/");
            if (descendant != null && descendant.startsWith(folded + "/")) throw new IOException("Output paths collide");
            paths.add(folded);
            Path src = source.resolve(asset.relativePath()), target = output.resolve(relative);
            SafeStoragePaths.validateExistingPath(src);
            SafeStoragePaths.validateExistingPath(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists: " + target);
            Map<Path, Object> identities = new LinkedHashMap<>();
            captureChain(src.getParent(), identities);
            captureChain(target.getParent(), identities);
            identities.put(src, sourceIdentity(src));
            if (!hash(src).equals(asset.fingerprint())) throw new IOException("Source fingerprint changed: " + src);
            verify(identities);
            result.add(new Prepared(command, asset, src, target, Map.copyOf(identities)));
        }
        return result;
    }

    private String copy(Prepared p, int index) throws IOException {
        hook.run(index, "before-step");
        verify(p.identities);
        if (!hash(p.source).equals(p.asset.fingerprint())) throw new IOException("Source fingerprint changed");
        Path temp = p.destination.resolveSibling(".migration-" + UUID.randomUUID() + ".tmp");
        boolean created = false;
        boolean published = false;
        String detail = "Published";
        try {
            MessageDigest digest = digest();
            try (InputStream in = Files.newInputStream(p.source, LinkOption.NOFOLLOW_LINKS);
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                byte[] buffer = new byte[65536];
                for (int count; (count = in.read(buffer)) != -1;) { digest.update(buffer, 0, count); out.write(buffer, 0, count); }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(p.asset.fingerprint())) throw new IOException("Copied bytes differ from inventory");
            hook.run(index, "before-publish");
            verify(p.identities);
            if (!hash(p.source).equals(p.asset.fingerprint())) throw new IOException("Source fingerprint changed before publish");
            verify(p.identities);
            // Unlike ATOMIC_MOVE, createLink is specified to fail if the destination exists.
            Files.createLink(p.destination, temp);
            published = true;
        } finally {
            if (created) {
                try {
                    Path parent = temp.getParent();
                    for (Path current = parent; current != null; current = current.getParent()) {
                        if (!Objects.equals(p.identities.get(current), directoryIdentity(current)))
                            throw new IOException("Destination parent changed; temporary file not removed: " + temp);
                    }
                    Files.deleteIfExists(temp);
                }
                catch (IOException | SecurityException e) {
                    if (!published) throw e;
                    detail = "Published; temporary file cleanup failed: " + message(e);
                }
            }
        }
        return detail;
    }

    private static Path absolute(Path path) throws IOException {
        if (path == null || !path.isAbsolute()) throw new IOException("Paths must be absolute");
        for (Path part : path) if (part.toString().equals("..")) throw new IOException("Parent traversal is forbidden");
        return path.normalize();
    }
    private static boolean overlap(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
    private static String portable(Path path) throws IOException {
        if (path.isAbsolute() || path.getNameCount() == 0) throw new IOException("Output must be relative");
        List<String> parts = new ArrayList<>();
        for (Path part : path) {
            String s = part.toString();
            if (!OrganizationPreview.validName(s)) throw new IOException("Invalid output component: " + s);
            parts.add(s);
        }
        return String.join("/", parts);
    }
    private static void captureChain(Path directory, Map<Path, Object> identities) throws IOException {
        Path current = directory.getRoot();
        identities.put(current, directoryIdentity(current));
        for (Path part : directory) { current = current.resolve(part); identities.put(current, directoryIdentity(current)); }
    }
    private static Object directoryIdentity(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.fileKey() == null) throw new IOException("Unsafe or unidentified directory: " + path);
        return attrs.fileKey();
    }
    private static Object sourceIdentity(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.fileKey() == null) throw new IOException("Unsafe or unidentified source: " + path);
        try {
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number) || ((Number) links).longValue() != 1) throw new IOException("Source has hard links: " + path);
        } catch (UnsupportedOperationException | IllegalArgumentException e) { throw new IOException("Cannot verify source link count", e); }
        return attrs.fileKey();
    }
    private static void verify(Map<Path, Object> identities) throws IOException {
        for (var entry : identities.entrySet()) {
            Path path = entry.getKey();
            var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object key = attrs.isDirectory() ? directoryIdentity(path) : sourceIdentity(path);
            if (!entry.getValue().equals(key)) throw new IOException("Filesystem identity changed: " + path);
        }
    }
    private static String hash(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
}
