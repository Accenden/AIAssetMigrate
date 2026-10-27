package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.InventorySnapshot;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.*;

/**
 * Immutable JSON revisions guarded by a cooperative per-project writer lock.
 * Construction and reads never create state. Filesystem checks are best-effort
 * protection against accidental aliases, not hostile concurrent path replacement.
 */
public final class FileAssetRepository {
    /** Maximum encoded inventory size on both reads and writes: 64 MiB. */
    public static final int DEFAULT_MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    private static final Pattern REVISION = Pattern.compile("inventory-([0-9]+)\\.json");
    // On POSIX, closing any channel for a locked file can release this process's lock.
    private static final Set<Path> ACTIVE_WRITERS = ConcurrentHashMap.newKeySet();
    private final Path stateDirectory;
    private final int maxSnapshotBytes;
    private final InventoryJsonCodec codec = new InventoryJsonCodec();
    private final AtomicMover mover;

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    public FileAssetRepository(Path stateDirectory) throws IOException {
        this(stateDirectory, DEFAULT_MAX_SNAPSHOT_BYTES);
    }

    public FileAssetRepository(Path stateDirectory, int maxSnapshotBytes) throws IOException {
        this(stateDirectory, maxSnapshotBytes, (source, target) -> Files.move(source, target, ATOMIC_MOVE));
    }

    FileAssetRepository(Path stateDirectory, int maxSnapshotBytes, AtomicMover mover) throws IOException {
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        if (maxSnapshotBytes < 1) {
            throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        }
        SafeStoragePaths.validateExistingPath(stateDirectory);
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    /** Returns empty only when no revision exists; corruption never triggers fallback. */
    public Optional<InventorySnapshot> loadLatest(UUID projectId) throws IOException {
        long revision = latestRevision(projectId);
        return revision == 0 ? Optional.empty() : Optional.of(loadRevision(projectId, revision));
    }

    public InventorySnapshot loadRevision(UUID projectId, long revision) throws IOException {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Path file = projectDirectory(projectId).resolve(revisionName(revision));
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        long size = before.size();
        if (size > maxSnapshotBytes) {
            throw new IOException("Inventory exceeds " + maxSnapshotBytes + " byte limit: " + file);
        }
        byte[] bytes = new byte[(int) size];
        try (FileChannel channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                checkInterrupted();
                if (channel.read(buffer) < 0) {
                    throw new IOException("Inventory changed while reading: " + file);
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("Inventory grew while reading: " + file);
            }
        }
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (after.size() != size || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !Objects.equals(after.fileKey(), before.fileKey())) {
            throw new IOException("Inventory changed while reading: " + file);
        }
        InventorySnapshot snapshot;
        try {
            snapshot = codec.decode(bytes);
        } catch (IOException exception) {
            throw new IOException("Invalid inventory revision: " + file, exception);
        }
        if (!snapshot.project().id().equals(projectId) || snapshot.project().inventoryRevision() != revision) {
            throw new IOException("Inventory identity/revision does not match filename: " + file);
        }
        return snapshot;
    }

    /**
     * Validate separation before creating state. Keep the returned handle open
     * across scan, save and completion publication; close it on every failure.
     * This repository does not construct or own a LogPublisher.
     */
    public Writer beginWrite(UUID projectId, Path sourceRoot) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        checkInterrupted();
        SafeStoragePaths.validateSeparation(sourceRoot, stateDirectory);
        Path directory = projectDirectory(projectId);
        SafeStoragePaths.createDirectories(directory);
        Path lockFile = directory.resolve("project.lock");
        SafeStoragePaths.validateExistingPath(lockFile);
        if (exists(lockFile)) {
            SafeStoragePaths.requireRegularFile(lockFile);
        }
        if (!ACTIVE_WRITERS.add(lockFile)) {
            throw new IOException("Another writer owns project " + projectId);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Another writer owns project " + projectId, exception);
            }
            if (lock == null) {
                throw new IOException("Another writer owns project " + projectId);
            }
            return new Writer(projectId, sourceRoot.toAbsolutePath().normalize(), lockFile, channel, lock);
        } catch (IOException | RuntimeException exception) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            } finally {
                ACTIVE_WRITERS.remove(lockFile);
            }
            throw exception;
        }
    }

    public final class Writer implements AutoCloseable {
        private final UUID projectId;
        private final Path sourceRoot;
        private final Path lockFile;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Writer(UUID projectId, Path sourceRoot, Path lockFile, FileChannel channel, FileLock lock) {
            this.projectId = projectId;
            this.sourceRoot = sourceRoot;
            this.lockFile = lockFile;
            this.channel = channel;
            this.lock = lock;
        }

        /** The supplied snapshot must carry exactly the next revision, starting at one. */
        public synchronized InventorySnapshot saveNextRevision(InventorySnapshot snapshot) throws IOException {
            Objects.requireNonNull(snapshot, "snapshot");
            if (closed || !lock.isValid()) {
                throw new IOException("Project writer is closed");
            }
            checkInterrupted();
            SafeStoragePaths.validateSeparation(sourceRoot, stateDirectory);
            if (!snapshot.project().id().equals(projectId)
                    || !snapshot.project().sourceRoot().equals(sourceRoot)) {
                throw new IOException("Snapshot project/root does not match writer");
            }
            if (snapshot.schemaVersion() != InventorySnapshot.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported inventory schemaVersion: " + snapshot.schemaVersion());
            }
            Optional<InventorySnapshot> previous = loadLatest(projectId);
            long lastRevision = previous.map(value -> value.project().inventoryRevision()).orElse(0L);
            if (lastRevision == Long.MAX_VALUE || snapshot.project().inventoryRevision() != lastRevision + 1) {
                throw new IOException("Snapshot must use the next inventory revision after " + lastRevision);
            }
            if (previous.isPresent() && (!previous.get().project().sourceRoot().equals(sourceRoot)
                    || !previous.get().project().createdAt().equals(snapshot.project().createdAt()))) {
                throw new IOException("Rescan must preserve project source root and creation timestamp");
            }
            byte[] bytes = codec.encode(snapshot);
            if (bytes.length > maxSnapshotBytes) {
                throw new IOException("Inventory exceeds " + maxSnapshotBytes + " byte limit");
            }
            Path directory = projectDirectory(projectId);
            SafeStoragePaths.validateExistingPath(directory);
            Path target = directory.resolve(revisionName(snapshot.project().inventoryRevision()));
            if (exists(target)) {
                throw new IOException("Inventory revision already exists: " + target);
            }
            Path temporary = directory.resolve(".inventory-" + UUID.randomUUID() + ".tmp");
            // Preserve failed temporary files as evidence; readers ignore them.
            try (FileChannel output = FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    checkInterrupted();
                    output.write(buffer);
                }
                output.force(true);
            }
            checkInterrupted();
            SafeStoragePaths.validateExistingPath(directory);
            SafeStoragePaths.requireRegularFile(temporary);
            // All cooperating writers hold project.lock, so an existing target is never replaced.
            if (exists(target)) {
                throw new IOException("Inventory revision already exists: " + target);
            }
            mover.move(temporary, target);
            return snapshot;
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                // Closing the channel also releases its lock, even if an explicit release would fail.
                try {
                    channel.close();
                } finally {
                    ACTIVE_WRITERS.remove(lockFile);
                }
            }
        }
    }

    private Path projectDirectory(UUID projectId) {
        return stateDirectory.resolve("projects").resolve(Objects.requireNonNull(projectId, "projectId").toString());
    }

    private long latestRevision(UUID projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        SafeStoragePaths.validateExistingPath(directory);
        if (!exists(directory)) {
            return 0;
        }
        long latest = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                checkInterrupted();
                String name = entry.getFileName().toString();
                var matcher = REVISION.matcher(name);
                if (!matcher.matches()) {
                    if (name.startsWith("inventory-")) {
                        throw new IOException("Malformed inventory filename: " + entry);
                    }
                    continue;
                }
                long revision;
                try {
                    revision = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid inventory revision number: " + entry, exception);
                }
                if (revision < 1 || !name.equals(revisionName(revision))) {
                    throw new IOException("Noncanonical inventory filename: " + entry);
                }
                SafeStoragePaths.requireRegularFile(entry);
                latest = Math.max(latest, revision);
            }
        }
        return latest;
    }

    private static String revisionName(long revision) {
        return String.format(Locale.ROOT, "inventory-%06d.json", revision);
    }

    private static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Inventory operation interrupted");
        }
    }
}
