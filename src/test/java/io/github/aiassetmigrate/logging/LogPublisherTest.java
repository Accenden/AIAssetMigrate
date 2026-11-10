package io.github.aiassetmigrate.logging;

import io.github.aiassetmigrate.domain.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(unixLinkCountsAvailable(), "reopening needs verified link counts");
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
    void subscriptionRejectsFutureAndNegativeCursorsButAcceptsCurrentCursor() throws Exception {
        var received = new CopyOnWriteArrayList<Long>();
        var delivered = new CountDownLatch(1);
        try (var publisher = new LogPublisher(directory, project)) {
            assertThrows(IllegalArgumentException.class, () -> publisher.subscribe(-1, event -> {}, error -> {}));
            assertThrows(IllegalArgumentException.class, () -> publisher.subscribe(1, event -> {}, error -> {}));
            publisher.publish(LogEvent.Level.INFO, "one");
            assertThrows(IllegalArgumentException.class, () -> publisher.subscribe(2, event -> {}, error -> {}));
            try (var subscription = publisher.subscribe(1, event -> {
                received.add(event.sequence());
                delivered.countDown();
            }, error -> fail(error))) {
                publisher.publish(LogEvent.Level.INFO, "two");
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(2L), received);
            }
        }
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
    void storageFailurePoisonsPublisherAndDisconnectsAndNotifiesListeners() throws Exception {
        var received = new CopyOnWriteArrayList<LogEvent>();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var notified = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var publisher = new LogPublisher(directory, project);
             var subscription = publisher.subscribe(0, received::add, error -> {
                 failures.add(error);
                 notified.countDown();
                 try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                 throw new IllegalStateException("broken failure handler");
             });
             var other = publisher.subscribe(0, received::add, error -> {
                 failures.add(error);
                 notified.countDown();
             })) {
            // Simulate an I/O failure without depending on platform-specific permissions.
            var field = LogPublisher.class.getDeclaredField("channel");
            field.setAccessible(true);
            ((FileChannel) field.get(publisher)).close();
            assertThrows(IOException.class, () -> publisher.publish(LogEvent.Level.INFO, "fails"));
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            assertEquals(2, failures.size());
            assertTrue(failures.stream().allMatch(IOException.class::isInstance));
            assertThrows(IOException.class, () -> publisher.publish(LogEvent.Level.INFO, "still fails"));
            assertThrows(IOException.class, () -> publisher.subscribe(0, received::add, error -> {}));
            assertTrue(received.isEmpty());
            assertTrue(LogPublisher.readLogs(directory, project, 0).isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void normalPublisherCloseDoesNotReportFailure() throws Exception {
        var failures = new CopyOnWriteArrayList<Throwable>();
        var publisher = new LogPublisher(directory, project);
        var subscription = publisher.subscribe(0, event -> {}, failures::add);
        publisher.close();
        subscription.close();
        assertTrue(failures.isEmpty());
    }

    @Test
    void unavailableLinkCountsAllowRestartForAnchoredStrictSidecars() throws Exception {
        assumeFalse(unixLinkCountsAvailable(), "Unix link counts available");
        var delivered = new CountDownLatch(1);
        try (var publisher = new LogPublisher(directory, project, true);
             var subscription = publisher.subscribe(0, event -> delivered.countDown(), error -> fail(error))) {
            LogEvent event = publisher.publish(LogEvent.Level.INFO, "fresh");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(event), LogPublisher.readLogs(directory, project, 0));
        }

        try (var publisher = new LogPublisher(directory, project, true)) {
            assertEquals(2, publisher.publish(LogEvent.Level.INFO, "after restart").sequence());
        }
        assertEquals(List.of(1L, 2L), LogPublisher.readLogs(directory, project, 0).stream()
                .map(LogEvent::sequence).toList());
    }

    @Test
    void unavailableLinkCountsRejectPreexistingLogSidecars() throws Exception {
        assumeFalse(unixLinkCountsAvailable(), "Unix link counts available");
        for (String suffix : List.of(".log", ".lock", ".gate", ".commit")) {
            UUID id = UUID.randomUUID();
            Files.createFile(directory.resolve(id + suffix));
            if (suffix.equals(".log")) Files.createFile(directory.resolve(id + ".commit"));
            assertThrows(IOException.class, () -> new LogPublisher(directory, id, true), suffix);
        }
    }

    @Test
    void strictFallbackNeverOpensExistingHardLinkedLogOrSidecar() throws Exception {
        Path asset = Files.createFile(directory.resolve("source-asset"));
        for (String suffix : List.of(".log", ".lock", ".gate", ".commit")) {
            UUID id = UUID.randomUUID();
            Path link = directory.resolve(id + suffix);
            try { Files.createLink(link, asset); }
            catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Hard links unavailable"); }
            assertThrows(IOException.class, () -> new LogPublisher(directory, id, true, true));
            assertEquals(0, Files.size(asset));
        }
    }

    @Test
    void strictFallbackCreatesNewFilesButRefusesReuse() throws Exception {
        try (var publisher = new LogPublisher(directory, project, true, true)) {
            publisher.publish(LogEvent.Level.INFO, "first");
        }
        byte[] before = Files.readAllBytes(LogPublisher.logFile(directory, project));
        assertThrows(IOException.class, () -> new LogPublisher(directory, project, true, true));
        assertArrayEquals(before, Files.readAllBytes(LogPublisher.logFile(directory, project)));
    }

    @Test
    void opensRejectSymlinkedLogsAndSidecars() throws Exception {
        Path target = Files.createFile(directory.resolve("target"));
        for (String suffix : List.of(".log", ".lock", ".gate", ".commit")) {
            UUID id = UUID.randomUUID();
            Path link = directory.resolve(id + suffix);
            try { Files.createSymbolicLink(link, target); }
            catch (UnsupportedOperationException | IOException | SecurityException e) {
                assumeTrue(false, "Symbolic links unavailable");
            }
            assertThrows(IOException.class, () -> new LogPublisher(directory, id, true));
            if (suffix.equals(".log")) {
                assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, id, 0));
            }
            assertEquals(0, Files.size(target));
        }
    }

    @Test
    void strictPublisherRejectsHardLinkedExistingSidecars() throws Exception {
        Path target = Files.createFile(directory.resolve("protected-source"));
        for (String suffix : List.of(".log", ".lock", ".gate", ".commit")) {
            UUID id = UUID.randomUUID();
            try { Files.createLink(directory.resolve(id + suffix), target); }
            catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "Hard links unavailable"); }
            assertThrows(IOException.class, () -> new LogPublisher(directory, id, true));
            assertEquals(0, Files.size(target));
        }
    }

    @Test
    void markerlessLogsIncludingEmptyLogsAreNeverAdopted() throws Exception {
        Path log = LogPublisher.logFile(directory, project);
        Files.writeString(log, "");
        assertTrue(assertThrows(IOException.class, () -> new LogPublisher(directory, project))
                .getMessage().contains("not yet available"));
        assertTrue(assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0))
                .getMessage().contains("not yet available"));
        assertEquals(0, Files.size(log));
    }

    @Test
    void markerValidationRejectsInvalidMetadataAndDamagedPrefix() throws Exception {
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "one");
        }
        Path marker = directory.resolve(project + ".commit");
        String original = Files.readString(marker);
        for (String invalid : List.of("not json", "x".repeat(4097),
                original.replace("\"version\":1", "\"version\":2"),
                original.replace("\"version\":1", "\"version\":1,\"version\":1"),
                original.replaceFirst("\"offset\":[0-9]+,", ""),
                original.replace(project.toString(), UUID.randomUUID().toString()),
                original.replace("\"sequence\":1", "\"sequence\":2"),
                original.replaceFirst("\"offset\":[0-9]+", "\"offset\":9999999"),
                original.replaceFirst("[0-9a-f]{64}", "0".repeat(64)))) {
            Files.writeString(marker, invalid);
            assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
            assertThrows(IOException.class, () -> new LogPublisher(directory, project));
        }
        Files.writeString(marker, original);
        Files.writeString(LogPublisher.logFile(directory, project), "bad\n");
        assertThrows(IOException.class, () -> LogPublisher.readLogs(directory, project, 0));
    }

    @Test
    void injectedFailuresPreserveTailAndNeverPrematurelyCommit() throws Exception {
        assumeTrue(unixLinkCountsAvailable(), "reopening needs verified link counts");
        for (CommittedLog.Stage stage : CommittedLog.Stage.values()) {
            UUID id = UUID.randomUUID();
            AtomicBoolean armed = new AtomicBoolean();
            try (var publisher = new LogPublisher(directory, id, false, false, current -> {
                if (armed.get() && current == stage) throw new IOException("injected " + stage);
            })) {
                publisher.publish(LogEvent.Level.INFO, "committed");
                armed.set(true);
                IOException error = assertThrows(IOException.class,
                        () -> publisher.publish(LogEvent.Level.INFO, "uncertain"));
                assertTrue(error.getMessage().contains("indeterminate"));
                assertThrows(IOException.class, () -> publisher.publish(LogEvent.Level.INFO, "poisoned"));
            }
            Path file = LogPublisher.logFile(directory, id);
            byte[] original = Files.readAllBytes(file);
            List<LogEvent> events = LogPublisher.readLogs(directory, id, 0);
            assertEquals(stage == CommittedLog.Stage.DIRECTORY_FORCE ? 2 : 1, events.size(), stage.toString());
            if (stage != CommittedLog.Stage.DIRECTORY_FORCE) {
                assertThrows(IOException.class, () -> new LogPublisher(directory, id), stage.toString());
            } else {
                try (var reopened = new LogPublisher(directory, id)) {
                    assertEquals(3, reopened.publish(LogEvent.Level.INFO, "next").sequence());
                }
            }
            if (stage != CommittedLog.Stage.DIRECTORY_FORCE) assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void separateJvmReaderWaitsForCommitGateAfterCompleteAppend() throws Exception {
        checkSeparateJvmReader(false);
        checkSeparateJvmReader(true);
    }

    private void checkSeparateJvmReader(boolean failForce) throws Exception {
        assumeTrue(unixLinkCountsAvailable(), "cross-process reads need verified link counts");
        UUID id = UUID.randomUUID();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        try (var publisher = new LogPublisher(directory, id, false, false, stage -> {
            if (armed.get() && stage == CommittedLog.Stage.AFTER_APPEND) {
                appended.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (armed.get() && stage == CommittedLog.Stage.LOG_FORCE && failForce) {
                throw new IOException("injected force failure");
            }
        }); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            armed.set(true);
            var writer = threads.submit(() -> publisher.publish(LogEvent.Level.INFO, "event"));
            assertTrue(appended.await(5, TimeUnit.SECONDS));
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, ReaderProcess.class.getName(), directory.toString(), id.toString())
                    .redirectErrorStream(true).start();
            try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("READY", output.readLine());
                var result = threads.submit(output::readLine);
                assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));
                release.countDown();
                if (failForce) {
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> writer.get(5, TimeUnit.SECONDS));
                } else {
                    assertEquals(1, writer.get(5, TimeUnit.SECONDS).sequence());
                }
                assertEquals(failForce ? "0" : "1", result.get(5, TimeUnit.SECONDS));
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                release.countDown();
                process.destroyForcibly();
            }
        }
    }

    public static final class ReaderProcess {
        public static void main(String[] args) throws Exception {
            System.out.println("READY");
            System.out.flush();
            System.out.println(LogPublisher.readLogs(Path.of(args[0]), UUID.fromString(args[1]), 0).size());
        }
    }

    @Test
    void rejectedLocalOwnerDoesNotReleaseCrossProcessOwnership() throws Exception {
        try (var publisher = new LogPublisher(directory, project)) {
            assertThrows(IOException.class, () -> new LogPublisher(directory, project));
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, WriterProcess.class.getName(), directory.toString(), project.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    assertEquals("REJECTED", output.readLine());
                }
                assertEquals(0, process.exitValue());
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public static final class WriterProcess {
        public static void main(String[] args) throws Exception {
            try (var ignored = new LogPublisher(Path.of(args[0]), UUID.fromString(args[1]))) {
                System.out.println("ACQUIRED");
            } catch (IOException e) {
                System.out.println("REJECTED");
            }
        }
    }

    private boolean unixLinkCountsAvailable() throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView("unix");
    }
}
