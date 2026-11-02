package io.github.aiassetmigrate.logging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Storage protocol. Lock order: publisher ownership, JVM gate, filesystem gate. */
final class CommittedLog {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ConcurrentHashMap<Path, ReentrantLock> GATES = new ConcurrentHashMap<>();
    private static final int MAX_MARKER = 4096;

    record Boundary(int version, UUID projectId, long offset, long sequence, String sha256) { }
    record Snapshot(Boundary boundary, byte[] bytes) { }

    enum Stage { PARTIAL_APPEND, AFTER_APPEND, LOG_FORCE, MARKER_WRITE, MARKER_FORCE, REPLACE, DIRECTORY_FORCE }
    @FunctionalInterface interface Faults { void at(Stage stage) throws IOException; }
    static final Faults NO_FAULTS = stage -> { };

    final Path directory, log, gate, marker, owner;
    final UUID project;
    final Faults faults;

    CommittedLog(Path directory, UUID project, Faults faults) throws IOException {
        SafeStoragePaths.validateExistingPath(directory);
        this.directory = directory.toAbsolutePath().normalize();
        this.project = project;
        this.faults = faults;
        log = this.directory.resolve(project + ".log");
        gate = this.directory.resolve(project + ".gate");
        marker = this.directory.resolve(project + ".commit");
        owner = this.directory.resolve(project + ".lock");
    }

    static void safeFile(Path path) throws IOException {
        SafeStoragePaths.requireRegularFile(path);
        try {
            Object links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
            if (!(links instanceof Number n) || n.longValue() != 1) {
                throw new IOException("File must have exactly one link: " + path);
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException("Cannot verify file link count: " + path, e);
        }
    }

    static FileChannel open(Path path, boolean createNewOnly) throws IOException {
        if (!createNewOnly && Files.exists(path, NOFOLLOW_LINKS)) safeFile(path);
        FileChannel result = FileChannel.open(path, createNewOnly ? CREATE_NEW : CREATE, READ, WRITE, NOFOLLOW_LINKS);
        try {
            safeFile(path);
            return result;
        } catch (IOException | RuntimeException e) {
            result.close();
            throw e;
        }
    }

    interface Operation<T> { T run() throws IOException; }
    <T> T gated(boolean create, Operation<T> action) throws IOException {
        ReentrantLock local = GATES.computeIfAbsent(gate, ignored -> new ReentrantLock());
        local.lock();
        try {
            if (!create) safeFile(gate);
            try (FileChannel channel = create ? open(gate, false)
                    : FileChannel.open(gate, READ, WRITE, NOFOLLOW_LINKS)) {
                safeFile(gate);
                try (FileLock ignored = channel.lock()) {
                    safeFile(gate);
                    return action.run();
                }
            }
        } finally {
            local.unlock();
        }
    }

    void initialize(FileChannel channel) throws IOException {
        channel.force(true);
        try {
            syncDirectory();
        } catch (IOException e) {
            throw new IOException("Cannot establish boundary durability; recovery required: " + marker, e);
        }
        commit(new byte[0], 0);
    }

    Snapshot snapshot() throws IOException {
        safeFile(log);
        safeFile(marker);
        byte[] metadata;
        try (FileChannel input = FileChannel.open(marker, READ, WRITE, NOFOLLOW_LINKS)) {
            if (input.size() > MAX_MARKER) throw new IOException("Oversized commit marker: " + marker);
            metadata = read(input, input.size());
            // Attempt to complete an interrupted marker publication before exposing it.
            input.force(true);
        } catch (IOException e) {
            throw new IOException("Cannot establish boundary durability; recovery required: " + marker, e);
        }
        try {
            syncDirectory();
        } catch (IOException e) {
            throw new IOException("Cannot establish boundary durability; recovery required: " + marker, e);
        }
        Boundary boundary;
        try {
            boundary = JSON.readValue(metadata, Boundary.class);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Invalid commit marker: " + marker, e);
        }
        if (boundary == null || boundary.version != 1 || !project.equals(boundary.projectId)
                || boundary.offset < 0 || boundary.offset > Integer.MAX_VALUE || boundary.sequence < 0
                || boundary.sha256 == null || !boundary.sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid or unsupported commit marker: " + marker);
        }
        byte[] bytes;
        try (FileChannel input = FileChannel.open(log, READ, NOFOLLOW_LINKS)) {
            if (input.size() < boundary.offset) throw new IOException("Commit boundary exceeds log size: " + log);
            bytes = read(input, boundary.offset);
        }
        if (!hash(bytes).equals(boundary.sha256) || (bytes.length > 0 && bytes[bytes.length - 1] != '\n')) {
            throw new IOException("Committed log prefix integrity failure: " + log);
        }
        return new Snapshot(boundary, bytes);
    }

    void commit(byte[] prefix, long sequence) throws IOException {
        Boundary boundary = new Boundary(1, project, prefix.length, sequence, hash(prefix));
        byte[] bytes = JSON.writeValueAsBytes(boundary);
        Path temporary = directory.resolve(project + ".commit." + UUID.randomUUID() + ".tmp");
        boolean created = false;
        try {
            try (FileChannel output = open(temporary, true)) {
                created = true;
                faults.at(Stage.MARKER_WRITE);
                write(output, bytes);
                faults.at(Stage.MARKER_FORCE);
                output.force(true);
            }
            if (Files.exists(marker, NOFOLLOW_LINKS)) safeFile(marker);
            safeFile(temporary);
            faults.at(Stage.REPLACE);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            created = false;
            safeFile(marker);
            faults.at(Stage.DIRECTORY_FORCE);
            syncDirectory();
        } finally {
            if (created && Files.exists(temporary, NOFOLLOW_LINKS)) {
                safeFile(temporary);
                Files.delete(temporary);
            }
        }
    }

    void syncDirectory() throws IOException {
        SafeStoragePaths.validateExistingPath(directory);
        // Sync every parent entry as well, including directories created with the log.
        for (Path path = directory; path != null; path = path.getParent()) {
            try (FileChannel dir = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
                dir.force(true);
            } catch (UnsupportedOperationException e) {
                throw new IOException("Directory durability unavailable; recovery required: " + path, e);
            }
        }
    }

    static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    static byte[] read(FileChannel channel, long length) throws IOException {
        if (length > Integer.MAX_VALUE) throw new IOException("Log too large to read");
        byte[] bytes = new byte[(int) length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Unexpected end of file");
        }
        return bytes;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
