package io.github.aiassetmigrate.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.aiassetmigrate.application.LogObserver;
import io.github.aiassetmigrate.application.LogSubscription;
import io.github.aiassetmigrate.domain.LogEvent;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** One writer per project. Persisted events are authoritative; listeners are disposable. */
public final class LogPublisher implements AutoCloseable {
    private static final java.util.Set<Path> LOCAL_OWNERS = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new Jdk8Module()).registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UUID projectId;
    private final Path file;
    private final FileChannel channel;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final CommittedLog storage;
    private final Object logKey;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long sequence;
    private boolean closed;
    private IOException failure;

    public LogPublisher(Path directory, UUID projectId) throws IOException {
        this(directory, projectId, false);
    }

    /** Require new files if the filesystem cannot report Unix link counts. */
    public LogPublisher(Path directory, UUID projectId, boolean protectUnverifiableLinks) throws IOException {
        this(directory, projectId, protectUnverifiableLinks, false);
    }

    LogPublisher(Path directory, UUID projectId, boolean protectUnverifiableLinks,
                 boolean forceNewFiles) throws IOException {
        this(directory, projectId, protectUnverifiableLinks, forceNewFiles, CommittedLog.NO_FAULTS);
    }

    LogPublisher(Path directory, UUID projectId, boolean protectUnverifiableLinks,
                 boolean forceNewFiles, CommittedLog.Faults faults) throws IOException {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        io.github.aiassetmigrate.inventory.SafeStoragePaths.validateExistingPath(directory);
        Files.createDirectories(directory);
        storage = new CommittedLog(directory, projectId, faults);
        file = storage.log;
        boolean newFiles = forceNewFiles || (protectUnverifiableLinks
                && !Files.getFileStore(directory).supportsFileAttributeView("unix"));
        if (!LOCAL_OWNERS.add(storage.owner)) throw new IOException("Another publisher owns " + file);
        try {
            lockChannel = CommittedLog.open(storage.owner, newFiles);
        } catch (IOException | RuntimeException e) {
            LOCAL_OWNERS.remove(storage.owner);
            throw e;
        }
        FileChannel opened = null;
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("Another publisher owns " + file);
            }
            boolean existing = Files.exists(file, NOFOLLOW_LINKS);
            if (!existing && Files.exists(storage.marker, NOFOLLOW_LINKS)) {
                throw new IOException("Commit marker exists without its log: " + file);
            }
            if (existing && !Files.exists(storage.marker, NOFOLLOW_LINKS)) {
                throw new IOException("Missing commit marker; legacy adoption or recovery is not yet available: " + file);
            }
            opened = CommittedLog.open(file, newFiles || !existing);
            FileChannel logChannel = opened;
            CommittedLog.Snapshot snapshot = storage.gated(!existing, () -> {
                if (!existing) storage.initialize(logChannel);
                return storage.snapshot();
            });
            checkedDecode(snapshot, projectId, 0);
            if (opened.size() != snapshot.boundary().offset()) {
                throw new IOException("Uncommitted log tail requires recovery, which is not yet available: " + file);
            }
            sequence = snapshot.boundary().sequence();
            opened.position(opened.size());
            channel = opened;
            logKey = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
            if (logKey == null) throw new IOException("Cannot verify log identity: " + file);
        } catch (IOException | RuntimeException e) {
            try {
                if (opened != null) opened.close();
            } finally {
                try {
                    lockChannel.close();
                } finally {
                    LOCAL_OWNERS.remove(storage.owner);
                }
            }
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("Another publisher owns " + file, e);
            }
            throw e;
        }
    }

    public static Path logFile(Path directory, UUID projectId) {
        return Objects.requireNonNull(directory, "directory").resolve(
                Objects.requireNonNull(projectId, "projectId") + ".log");
    }

    /** Read the validated committed prefix without acquiring the publisher lifetime lock. */
    public static List<LogEvent> readLogs(Path directory, UUID projectId, long afterSequence) throws IOException {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be nonnegative");
        }
        CommittedLog storage = new CommittedLog(directory, Objects.requireNonNull(projectId), CommittedLog.NO_FAULTS);
        if (Files.exists(storage.log, NOFOLLOW_LINKS) && !Files.exists(storage.marker, NOFOLLOW_LINKS)) {
            throw new IOException("Missing commit marker; legacy adoption or recovery is not yet available: " + storage.log);
        }
        return storage.gated(false, () -> checkedDecode(storage.snapshot(), projectId, afterSequence));
    }

    private static List<LogEvent> checkedDecode(CommittedLog.Snapshot snapshot, UUID projectId, long afterSequence)
            throws IOException {
        List<LogEvent> all = decode(snapshot.bytes(), projectId, 0);
        long last = all.isEmpty() ? 0 : all.getLast().sequence();
        if (last != snapshot.boundary().sequence()) throw new IOException("Commit marker sequence mismatch");
        return all.stream().filter(event -> event.sequence() > afterSequence).toList();
    }

    private static List<LogEvent> decode(byte[] bytes, UUID projectId, long afterSequence) throws IOException {
        List<LogEvent> events = new ArrayList<>();
        int start = 0;
        long expected = 1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            try {
                LogEvent event = JSON.readValue(bytes, start, i - start, LogEvent.class);
                if (!projectId.equals(event.projectId()) || event.sequence() != expected) {
                    throw new IOException("Wrong project or non-contiguous sequence at record " + expected);
                }
                if (event.sequence() > afterSequence) {
                    events.add(event);
                }
            } catch (RuntimeException | IOException e) {
                throw new IOException("Invalid log record " + expected, e);
            }
            expected++;
            start = i + 1;
        }
        return List.copyOf(events);
    }

    public LogEvent publish(LogEvent.Level level, String message) throws IOException {
        return publish(level, message, Optional.empty(), Optional.empty(), Map.of());
    }

    /** An IOException is fatal to this publisher; callers must stop new write steps. */
    public synchronized LogEvent publish(LogEvent.Level level, String message, Optional<UUID> runId,
                                         Optional<UUID> commandId, Map<String, String> details) throws IOException {
        checkOpen();
        LogEvent event = new LogEvent(UUID.randomUUID(), projectId, Math.incrementExact(sequence),
                Instant.now(), level, message, runId, commandId, details);
        try {
            byte[] bytes = (JSON.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
            storage.gated(false, () -> {
                CommittedLog.safeFile(file);
                Object currentKey = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                        NOFOLLOW_LINKS).fileKey();
                if (!logKey.equals(currentKey)) throw new IOException("Log identity changed: " + file);
                CommittedLog.Snapshot snapshot = storage.snapshot();
                checkedDecode(snapshot, projectId, 0);
                if (snapshot.boundary().sequence() != sequence || channel.size() != snapshot.boundary().offset()) {
                    throw new IOException("Log boundary changed or uncommitted tail requires recovery: " + file);
                }
                if ((long) snapshot.bytes().length + bytes.length > Integer.MAX_VALUE) {
                    throw new IOException("Committed log too large: " + file);
                }
                channel.position(snapshot.boundary().offset());
                int split = bytes.length / 2;
                CommittedLog.write(channel, java.util.Arrays.copyOf(bytes, split));
                storage.faults.at(CommittedLog.Stage.PARTIAL_APPEND);
                CommittedLog.write(channel, java.util.Arrays.copyOfRange(bytes, split, bytes.length));
                storage.faults.at(CommittedLog.Stage.AFTER_APPEND);
                storage.faults.at(CommittedLog.Stage.LOG_FORCE);
                channel.force(true);
                byte[] prefix = java.util.Arrays.copyOf(snapshot.bytes(), snapshot.bytes().length + bytes.length);
                System.arraycopy(bytes, 0, prefix, snapshot.bytes().length, bytes.length);
                storage.commit(prefix, event.sequence());
                return null;
            });
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException io ? io : new IOException("Log storage failed", e);
            listeners.forEach(listener -> listener.fail(failure));
            throw new IOException("Cannot persist log event; stop new write steps; marker-stage outcome may be indeterminate: " + file, failure);
        }
        sequence = event.sequence();
        for (Listener listener : listeners) {
            listener.offer(event);
        }
        return event;
    }

    /** Replay and registration share the publisher lock, preventing a replay/live gap. */
    public synchronized LogSubscription subscribe(long afterSequence, LogObserver observer,
                                                   Consumer<Throwable> onFailure) throws IOException {
        checkOpen();
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(onFailure, "onFailure");
        if (afterSequence < 0 || afterSequence > sequence) {
            throw new IllegalArgumentException("afterSequence must be between 0 and " + sequence);
        }
        List<LogEvent> replay = readLogs(file.getParent(), projectId, afterSequence);
        Listener listener = new Listener(observer, onFailure, replay);
        listeners.add(listener);
        listener.thread.start();
        return listener;
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Log publisher is closed");
        }
        if (failure != null) {
            throw new IOException("Log publisher failed; stop new write steps", failure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            listeners.forEach(Listener::close);
            try {
                lock.release();
            } finally {
                try {
                    channel.close();
                } finally {
                    try {
                        lockChannel.close();
                    } finally {
                        LOCAL_OWNERS.remove(storage.owner);
                    }
                }
            }
        }
    }

    private final class Listener implements LogSubscription {
        private final ArrayBlockingQueue<LogEvent> pending = new ArrayBlockingQueue<>(1024);
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Consumer<Throwable> onFailure;
        private final Thread thread;

        Listener(LogObserver observer, Consumer<Throwable> onFailure, List<LogEvent> replay) {
            this.onFailure = onFailure;
            thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    for (LogEvent event : replay) {
                        if (!active.get()) return;
                        observer.update(event);
                    }
                    while (active.get()) {
                        LogEvent event = pending.take();
                        if (active.get()) observer.update(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    fail(e);
                } finally {
                    close();
                }
            });
        }

        void offer(LogEvent event) {
            if (active.get() && !pending.offer(event)) {
                fail(new IllegalStateException(
                        "Log viewer fell behind and was disconnected; reconnect using its last sequence"));
            }
        }

        void fail(Throwable error) {
            if (disconnect()) {
                Thread.startVirtualThread(() -> {
                    try {
                        onFailure.accept(error);
                    } catch (RuntimeException ignored) {
                        // A broken error display must not affect persistence or other viewers.
                    }
                });
            }
        }

        @Override
        public void close() {
            disconnect();
        }

        private boolean disconnect() {
            if (active.getAndSet(false)) {
                listeners.remove(this);
                thread.interrupt();
                pending.clear();
                return true;
            }
            return false;
        }
    }
}
