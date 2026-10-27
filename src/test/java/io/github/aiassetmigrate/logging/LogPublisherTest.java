package io.github.aiassetmigrate.logging;

import io.github.aiassetmigrate.domain.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.*;

class LogPublisherTest {
    @TempDir Path directory;
    private final UUID project = UUID.randomUUID();

    @Test
    void persistsCompleteEventsAndContinuesSequenceAfterRestart() throws Exception {
        LogEvent event;
        try (var publisher = new LogPublisher(directory, project)) {
            event = publisher.publish(LogEvent.Level.INFO, "line one\nline two: \u00e9",
                    Optional.of(UUID.randomUUID()), Optional.of(UUID.randomUUID()), Map.of("step", "copy"));
            assertEquals(List.of(event), LogPublisher.readLogs(directory, project, 0));
            assertEquals(1, Files.readAllLines(LogPublisher.logFile(directory, project)).size());
        }
        try (var publisher = new LogPublisher(directory, project)) {
            assertEquals(2, publisher.publish(LogEvent.Level.WARNING, "next").sequence());
        }
        assertEquals(List.of(2L), LogPublisher.readLogs(directory, project, 1).stream()
                .map(LogEvent::sequence).toList());
        assertTrue(LogPublisher.readLogs(directory, project, 2).isEmpty());
    }

    @Test
    void allowsReadersButRejectsSecondWriterAndIsolatesProjects() throws Exception {
        try (var first = new LogPublisher(directory, project);
             var other = new LogPublisher(directory, UUID.randomUUID())) {
            first.publish(LogEvent.Level.INFO, "first");
            assertThrows(IOException.class, () -> new LogPublisher(directory, project));
            assertEquals(1, LogPublisher.readLogs(directory, project, 0).size());
            assertEquals(1, other.publish(LogEvent.Level.INFO, "other").sequence());
        }
    }

    @Test
    void replaysThenDeliversLiveEventsInOrderAfterPersistence() throws Exception {
        var received = new CopyOnWriteArrayList<Long>();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var delivered = new CountDownLatch(3);
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "one");
            publisher.publish(LogEvent.Level.INFO, "two");
            try (var subscription = publisher.subscribe(0, event -> {
                try {
                    assertTrue(LogPublisher.readLogs(directory, project, 0).contains(event));
                    received.add(event.sequence());
                    delivered.countDown();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, failures::add)) {
                publisher.publish(LogEvent.Level.INFO, "three");
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(1L, 2L, 3L), received);
                assertTrue(failures.isEmpty());
            }
            assertEquals(4, publisher.publish(LogEvent.Level.INFO, "after unsubscribe").sequence());
        }
    }

    @Test
    void brokenAndSlowObserversDoNotBlockPublishing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        try (var publisher = new LogPublisher(directory, project)) {
            try (var slow = publisher.subscribe(0, event -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, error -> {});
                 var broken = publisher.subscribe(0, event -> { throw new IllegalStateException("viewer"); },
                         error -> failed.countDown())) {
                publisher.publish(LogEvent.Level.INFO, "one");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(failed.await(5, TimeUnit.SECONDS));
                assertEquals(2, publisher.publish(LogEvent.Level.INFO, "two").sequence());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void incompleteTailIsDeferredByReadersAndBlocksWriterReopen() throws Exception {
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "complete");
        }
        Path file = LogPublisher.logFile(directory, project);
        Files.writeString(file, "{\"partial\":", APPEND);
        byte[] before = Files.readAllBytes(file);
        assertEquals(1, LogPublisher.readLogs(directory, project, 0).size());
        assertThrows(IOException.class, () -> new LogPublisher(directory, project));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void corruptRecordsMissingHistoryAndInvalidCursorsFailClearly() throws Exception {
        assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
        assertThrows(IllegalArgumentException.class, () -> LogPublisher.readLogs(directory, project, -1));
        Files.writeString(LogPublisher.logFile(directory, project), "not-json\n");
        assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
        assertThrows(IOException.class, () -> new LogPublisher(directory, project));
    }

    @Test
    void wrongProjectAndSequenceGapsAreRejected() throws Exception {
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "one");
        }
        Path file = LogPublisher.logFile(directory, project);
        String original = Files.readString(file);
        Files.writeString(file, original.replace(project.toString(), UUID.randomUUID().toString()));
        assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
        Files.writeString(file, original.replace("\"sequence\":1", "\"sequence\":2"));
        assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
    }

    @Test
    void storageFailurePoisonsPublisherAndDoesNotNotifyListeners() throws Exception {
        var received = new CopyOnWriteArrayList<LogEvent>();
        try (var publisher = new LogPublisher(directory, project);
             var subscription = publisher.subscribe(0, received::add, error -> {})) {
            // Simulate an I/O failure without depending on platform-specific permissions.
            var field = LogPublisher.class.getDeclaredField("channel");
            field.setAccessible(true);
            ((FileChannel) field.get(publisher)).close();
            assertThrows(IOException.class, () -> publisher.publish(LogEvent.Level.INFO, "fails"));
            assertThrows(IOException.class, () -> publisher.publish(LogEvent.Level.INFO, "still fails"));
            assertTrue(received.isEmpty());
            assertTrue(LogPublisher.readLogs(directory, project, 0).isEmpty());
        }
    }
}
