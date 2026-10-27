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
import java.nio.ByteBuffer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.*;

/** One writer per project. Persisted events are authoritative; listeners are disposable. */
public final class LogPublisher implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new Jdk8Module()).registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UUID projectId;
    private final Path file;
    private final FileChannel channel;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long sequence;
    private boolean closed;
    private IOException failure;

    public LogPublisher(Path directory, UUID projectId) throws IOException {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        file = logFile(directory.toAbsolutePath().normalize(), projectId);
        Files.createDirectories(directory);
        // Lock a sidecar: Windows file locks can otherwise prevent live readers.
        lockChannel = FileChannel.open(directory.resolve(projectId + ".lock"), CREATE, WRITE);
        FileChannel opened = null;
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("Another publisher owns " + file);
            }
            opened = FileChannel.open(file, CREATE, READ, WRITE);
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                throw new IOException("Incomplete final log record; preserve and repair the file before writing: " + file);
            }
            List<LogEvent> events = decode(bytes, projectId, 0);
            sequence = events.isEmpty() ? 0 : events.getLast().sequence();
            opened.position(opened.size());
            channel = opened;
        } catch (IOException | RuntimeException e) {
            if (opened != null) opened.close();
            lockChannel.close();
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

    /** Read-only snapshot. A trailing uncommitted line is deferred until its newline arrives. */
    public static List<LogEvent> readLogs(Path directory, UUID projectId, long afterSequence) throws IOException {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be nonnegative");
        }
        return decode(Files.readAllBytes(logFile(directory, projectId)), projectId, afterSequence);
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
        byte[] bytes = (JSON.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            failure = e;
            throw new IOException("Cannot persist log event; stop new write steps: " + file, e);
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
                    lockChannel.close();
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
                    report(e);
                } finally {
                    close();
                }
            });
        }

        void offer(LogEvent event) {
            if (active.get() && !pending.offer(event)) {
                close();
                Thread.startVirtualThread(() -> report(new IllegalStateException(
                        "Log viewer fell behind and was disconnected; reconnect using its last sequence")));
            }
        }

        void report(Throwable error) {
            try {
                onFailure.accept(error);
            } catch (RuntimeException ignored) {
                // A broken error display must not affect persistence or other viewers.
            }
        }

        @Override
        public void close() {
            if (active.getAndSet(false)) {
                listeners.remove(this);
                thread.interrupt();
                pending.clear();
            }
        }
    }
}
