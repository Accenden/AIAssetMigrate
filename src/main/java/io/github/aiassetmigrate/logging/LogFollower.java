package io.github.aiassetmigrate.logging;

import io.github.aiassetmigrate.application.LogObserver;
import io.github.aiassetmigrate.application.LogSubscription;
import io.github.aiassetmigrate.domain.LogEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only live viewer for a project log (Observer subject for viewers that are not the
 * writer). It replays committed events after a cursor, then polls for new ones, delivering each
 * in order to the observer on its own daemon thread. It never opens the log for writing, so a
 * viewer cannot block or stop a worker; closing it only stops delivery. A read failure is passed
 * to {@link LogObserver#onError} and ends the subscription; reconnect from {@link #cursor()}.
 */
public final class LogFollower implements LogSubscription {
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong cursor;
    private final Thread thread;

    private LogFollower(Path directory, UUID projectId, long afterSequence, LogObserver observer, Duration interval) {
        cursor = new AtomicLong(afterSequence);
        thread = new Thread(() -> run(directory, projectId, observer, interval), "log-follower-" + projectId);
        thread.setDaemon(true);
    }

    public static LogFollower start(Path directory, UUID projectId, long afterSequence, LogObserver observer, Duration interval) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(interval, "interval");
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must be nonnegative");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
        LogFollower follower = new LogFollower(directory, projectId, afterSequence, observer, interval);
        follower.thread.start();
        return follower;
    }

    private void run(Path directory, UUID projectId, LogObserver observer, Duration interval) {
        try {
            while (open.get()) {
                for (LogEvent event : LogPublisher.readLogs(directory, projectId, cursor.get())) {
                    if (!open.get()) return;
                    observer.update(event);
                    cursor.set(event.sequence());
                }
                Thread.sleep(interval.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (open.getAndSet(false)) observer.onError(e);
        }
    }

    /** Sequence of the last event delivered; pass it to {@link #start} to resume without gaps. */
    public long cursor() { return cursor.get(); }

    public boolean isOpen() { return open.get() && thread.isAlive(); }

    @Override public void close() {
        open.set(false);
        thread.interrupt();
    }
}
