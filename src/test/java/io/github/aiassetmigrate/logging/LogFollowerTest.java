package io.github.aiassetmigrate.logging;

import io.github.aiassetmigrate.application.LogObserver;
import io.github.aiassetmigrate.domain.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LogFollowerTest {
    @TempDir Path directory;

    private static final class Recorder implements LogObserver {
        final List<Long> sequences = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile CountDownLatch latch;
        final CountDownLatch errors = new CountDownLatch(1);
        Recorder(int expected) { latch = new CountDownLatch(expected); }
        @Override public void update(LogEvent event) { sequences.add(event.sequence()); latch.countDown(); }
        @Override public void onError(Throwable error) { failure.set(error); errors.countDown(); }
    }

    @Test void replaysAfterTheCursorFollowsWritersAndResumesWithoutGaps() throws Exception {
        Path logs = Files.createDirectory(directory.resolve("logs"));
        UUID project = UUID.randomUUID();
        try (var publisher = new LogPublisher(logs, project)) {
            publisher.publish(LogEvent.Level.INFO, "one");
            publisher.publish(LogEvent.Level.INFO, "two");
            publisher.publish(LogEvent.Level.INFO, "three");
            Recorder recorder = new Recorder(2);
            long cursor;
            try (var follower = LogFollower.start(logs, project, 1, recorder, Duration.ofMillis(20))) {
                assertTrue(recorder.latch.await(5, TimeUnit.SECONDS), "replay after the cursor");
                assertEquals(List.of(2L, 3L), recorder.sequences);
                recorder.latch = new CountDownLatch(1);
                publisher.publish(LogEvent.Level.WARNING, "live");
                assertTrue(recorder.latch.await(5, TimeUnit.SECONDS), "a reader follows while the writer stays open");
                cursor = follower.cursor();
                assertEquals(4, cursor);
            }
            publisher.publish(LogEvent.Level.INFO, "after the viewer closed");
            Recorder resumed = new Recorder(1);
            try (var ignored = LogFollower.start(logs, project, cursor, resumed, Duration.ofMillis(20))) {
                assertTrue(resumed.latch.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(5L), resumed.sequences, "reconnection replays only missed events");
            }
            assertNull(recorder.failure.get());
        }
    }

    @Test void readFailuresAreReportedToTheObserver() throws Exception {
        Recorder recorder = new Recorder(1);
        UUID project = UUID.randomUUID();
        Files.writeString(LogPublisher.logFile(directory, project), "not a committed log\n");
        var follower = LogFollower.start(directory, project, 0, recorder, Duration.ofMillis(20));
        assertTrue(recorder.errors.await(5, TimeUnit.SECONDS));
        assertNotNull(recorder.failure.get());
        follower.close();
        assertFalse(follower.isOpen());
        assertThrows(IllegalArgumentException.class, () -> LogFollower.start(directory, UUID.randomUUID(), -1, recorder, Duration.ofMillis(5)));
    }
}
