package io.github.aiassetmigrate.inventory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.*;

/** Immutable review sets, serialized with the inventory repository's project writer lock. */
public final class FileReviewedChangeRepository {
    public static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("reviews-([0-9]+)\\.json");
    private final Path stateDirectory;
    private final int maxBytes;
    private final FileAssetRepository inventories;
    private final AtomicMover mover;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    @FunctionalInterface
    interface AtomicMover { void move(Path source, Path target) throws IOException; }

    /** A complete replacement review set, not an append-only event log. */
    public record ReviewSet(int schemaVersion, UUID projectId, long inventoryRevision,
                            long revision, List<ReviewedChange> changes) {
        public ReviewSet {
            if (schemaVersion != 1 || inventoryRevision < 1 || revision < 1) {
                throw new IllegalArgumentException("Unsupported schema or invalid revision");
            }
            Objects.requireNonNull(projectId, "projectId");
            changes = List.copyOf(changes);
            var ids = new HashSet<UUID>();
            for (ReviewedChange change : changes) {
                if (!ids.add(change.id())) throw new IllegalArgumentException("Duplicate review ID");
            }
        }
    }

    public FileReviewedChangeRepository(Path stateDirectory) throws IOException {
        this(stateDirectory, DEFAULT_MAX_BYTES);
    }

    public FileReviewedChangeRepository(Path stateDirectory, int maxBytes) throws IOException {
        this(stateDirectory, maxBytes, (source, target) -> Files.move(source, target, ATOMIC_MOVE));
    }

    FileReviewedChangeRepository(Path stateDirectory, int maxBytes, AtomicMover mover) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        SafeStoragePaths.validateExistingPath(stateDirectory);
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.inventories = new FileAssetRepository(stateDirectory);
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    /** Reads do not create state; an invalid latest revision is an error, never a fallback. */
    public Optional<ReviewSet> loadLatest(UUID projectId) throws IOException {
        long revision = latestRevision(projectId);
        return revision == 0 ? Optional.empty() : Optional.of(loadRevision(projectId, revision));
    }

    public ReviewSet loadRevision(UUID projectId, long revision) throws IOException {
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Path file = directory(projectId).resolve(name(revision));
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() > maxBytes) throw new IOException("Review set exceeds byte limit");
        byte[] bytes = new byte[(int) before.size()];
        try (FileChannel input = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                checkInterrupted();
                if (input.read(buffer) < 0) throw new IOException("Review set changed while reading");
            }
            if (input.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Review set grew while reading");
        }
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Review set changed while reading");
        try {
            JsonNode root = mapper.readTree(bytes);
            validateJson(root);
            ReviewSet result = mapper.treeToValue(root, ReviewSet.class);
            if (!projectId.equals(result.projectId()) || revision != result.revision()) {
                throw new IOException("Review identity/revision does not match filename");
            }
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid review set: " + file, exception);
        }
    }

    /**
     * Replace the complete set, using optimistic review revision (zero for first save).
     * The supplied inventory must exactly match the latest persisted inventory while locked.
     */
    public ReviewSet save(InventorySnapshot inventory, long expectedRevision, List<ReviewedChange> changes)
            throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid expected revision");
        }
        var project = inventory.project();
        ReviewSet next = new ReviewSet(1, project.id(), project.inventoryRevision(), expectedRevision + 1, changes);
        var assets = new HashSet<UUID>();
        project.assets().forEach(asset -> assets.add(asset.id()));
        for (ReviewedChange change : next.changes()) {
            if (!assets.contains(change.assetId())) throw new IllegalArgumentException("Review references unknown asset");
        }
        byte[] bytes = mapper.writeValueAsBytes(next);
        if (bytes.length > maxBytes) throw new IOException("Review set exceeds byte limit");
        try (var ignored = inventories.beginWrite(project.id(), project.sourceRoot())) {
            checkInterrupted();
            InventorySnapshot latest = inventories.loadLatest(project.id())
                    .orElseThrow(() -> new IOException("Inventory not found"));
            if (!latest.equals(inventory)) throw new IOException("Inventory is stale or does not match persisted inventory");
            long current = loadLatest(project.id()).map(ReviewSet::revision).orElse(0L);
            if (current != expectedRevision) throw new IOException("Review revision conflict: expected " + expectedRevision + ", found " + current);
            Path directory = directory(project.id());
            Path target = directory.resolve(name(next.revision()));
            if (exists(target)) throw new IOException("Review revision already exists: " + target);
            Path temporary = directory.resolve(".reviews-" + UUID.randomUUID() + ".tmp");
            try (FileChannel output = FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    checkInterrupted();
                    output.write(buffer);
                }
                output.force(true);
            }
            checkInterrupted();
            SafeStoragePaths.validateSeparation(project.sourceRoot(), stateDirectory);
            SafeStoragePaths.validateExistingPath(directory);
            SafeStoragePaths.requireRegularFile(temporary);
            if (exists(target)) throw new IOException("Review revision already exists: " + target);
            mover.move(temporary, target);
            return next;
        }
    }

    private long latestRevision(UUID projectId) throws IOException {
        Path directory = directory(projectId);
        SafeStoragePaths.validateExistingPath(directory);
        if (!exists(directory)) return 0;
        long latest = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                checkInterrupted();
                String filename = entry.getFileName().toString();
                if (!filename.startsWith("reviews-")) continue;
                var match = NAME.matcher(filename);
                if (!match.matches()) throw new IOException("Malformed review filename: " + entry);
                long revision;
                try { revision = Long.parseLong(match.group(1)); }
                catch (NumberFormatException exception) { throw new IOException("Invalid review revision: " + entry, exception); }
                if (revision < 1 || !filename.equals(name(revision))) throw new IOException("Noncanonical review filename: " + entry);
                SafeStoragePaths.requireRegularFile(entry);
                latest = Math.max(latest, revision);
            }
        }
        return latest;
    }

    private static void validateJson(JsonNode root) throws IOException {
        if (root == null || !root.isObject() || !integer(root.get("schemaVersion"))
                || root.get("schemaVersion").longValue() != 1 || !integer(root.get("inventoryRevision"))
                || !integer(root.get("revision")) || !text(root.get("projectId"))
                || !root.has("changes") || !root.get("changes").isArray()) throw new IOException("Invalid review set structure");
        for (JsonNode change : root.get("changes")) {
            if (!change.isObject() || !text(change.get("id")) || !text(change.get("assetId"))
                    || !text(change.get("kind")) || !text(change.get("decision"))
                    || !text(change.get("reviewedAt")) || !change.has("values") || !change.get("values").isObject()) {
                throw new IOException("Invalid reviewed change structure");
            }
            var values = change.get("values").elements();
            while (values.hasNext()) if (!text(values.next())) throw new IOException("Review values must be strings");
        }
    }

    private static boolean text(JsonNode node) { return node != null && node.isTextual(); }
    private static boolean integer(JsonNode node) { return node != null && node.isIntegralNumber() && node.canConvertToLong(); }
    private Path directory(UUID id) { return stateDirectory.resolve("projects").resolve(Objects.requireNonNull(id, "projectId").toString()); }
    private static String name(long revision) { return String.format(Locale.ROOT, "reviews-%06d.json", revision); }
    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException exception) { return false; }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Review operation interrupted");
    }
}
