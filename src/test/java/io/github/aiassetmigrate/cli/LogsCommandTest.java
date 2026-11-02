package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.domain.LogEvent;
import io.github.aiassetmigrate.logging.LogPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LogsCommandTest {
    @TempDir Path directory;

    @Test
    void replaysOnlyEventsAfterCursor() throws Exception {
        UUID project = UUID.randomUUID();
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "hidden");
            publisher.publish(LogEvent.Level.WARNING, "visible\nnext");
        }
        var output = new StringWriter();
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("logs", "--directory", directory.toString(), "--project",
                project.toString(), "--after", "1"));
        assertFalse(output.toString().contains("hidden"));
        assertTrue(output.toString().contains("visible\\nnext"));
    }

    @Test
    void followsNewEventsAndStopsOnInterruption() throws Exception {
        UUID project = UUID.randomUUID();
        var output = new StringWriter();
        var firstRead = new CountDownLatch(1);
        var secondRead = new CountDownLatch(1);
        var exitCode = new AtomicInteger(-1);
        PrintWriter writer = new PrintWriter(output) {
            @Override public void flush() {
                super.flush();
                firstRead.countDown();
                if (output.toString().contains("later")) secondRead.countDown();
            }
        };
        try (var publisher = new LogPublisher(directory, project)) {
            publisher.publish(LogEvent.Level.INFO, "initial");
            var cli = new CommandLine(new AssetMigrateCli()).setOut(writer);
            Thread viewer = Thread.ofVirtual().start(() -> exitCode.set(cli.execute("logs", "--directory",
                    directory.toString(), "--project", project.toString(), "--follow")));
            try {
                assertTrue(firstRead.await(5, TimeUnit.SECONDS));
                publisher.publish(LogEvent.Level.INFO, "later");
                assertTrue(secondRead.await(5, TimeUnit.SECONDS));
            } finally {
                viewer.interrupt();
                viewer.join(5000);
            }
            assertFalse(viewer.isAlive());
            assertEquals(130, exitCode.get());
            assertEquals(1, output.toString().split("initial", -1).length - 1);
            assertEquals(1, output.toString().split("later", -1).length - 1);
        }
    }

    @Test
    void replayStopsOnWriteOrFlushFailureAndRetriesFailedEvent() throws Exception {
        for (boolean failOnFlush : new boolean[] {false, true}) {
            UUID project = UUID.randomUUID();
            try (var publisher = new LogPublisher(directory, project)) {
                publisher.publish(LogEvent.Level.INFO, "first");
                publisher.publish(LogEvent.Level.INFO, "second");
                publisher.publish(LogEvent.Level.INFO, "third");
            }
            var command = new LogsCommand();
            var errors = new StringWriter();
            var failing = new FailingWriter(failOnFlush);
            var cli = new CommandLine(command).setOut(new PrintWriter(failing)).setErr(new PrintWriter(errors));

            assertEquals(1, cli.execute("--directory", directory.toString(), "--project", project.toString()));
            assertTrue(errors.toString().contains("Unable to write project logs"));
            assertTrue(failing.output.toString().contains("first"));
            assertFalse(failing.output.toString().contains("third"));

            var resumed = new StringWriter();
            cli.setOut(new PrintWriter(resumed));
            assertEquals(0, command.call());
            assertFalse(resumed.toString().contains("first"));
            assertTrue(resumed.toString().contains("second"));
            assertTrue(resumed.toString().contains("third"));
        }
    }

    @Test
    void followStopsOnWriteOrFlushFailureAndRetriesFailedEvent() throws Exception {
        for (boolean failOnFlush : new boolean[] {false, true}) {
            UUID project = UUID.randomUUID();
            try (var publisher = new LogPublisher(directory, project)) {
                publisher.publish(LogEvent.Level.INFO, "first");
                var command = new LogsCommand();
                var failing = new FailingWriter(failOnFlush);
                var cli = new CommandLine(command).setOut(new PrintWriter(failing))
                        .setErr(new PrintWriter(new StringWriter()));
                var exitCode = new AtomicInteger(-1);
                Thread viewer = Thread.ofVirtual().start(() -> exitCode.set(cli.execute("--directory",
                        directory.toString(), "--project", project.toString(), "--follow")));
                try {
                    assertTrue(failing.firstFlushed.await(5, TimeUnit.SECONDS));
                    publisher.publish(LogEvent.Level.INFO, "second");
                    viewer.join(5000);
                    assertFalse(viewer.isAlive());
                    assertEquals(1, exitCode.get());
                } finally {
                    viewer.interrupt();
                    viewer.join(5000);
                }

                var resumed = new StringWriter();
                var retried = new CountDownLatch(1);
                cli.setOut(new PrintWriter(resumed) {
                    @Override public void flush() {
                        super.flush();
                        if (resumed.toString().contains("second")) retried.countDown();
                    }
                });
                var retryCode = new AtomicInteger(-1);
                Thread retry = Thread.ofVirtual().start(() -> retryCode.set(command.call()));
                try {
                    assertTrue(retried.await(5, TimeUnit.SECONDS));
                } finally {
                    retry.interrupt();
                    retry.join(5000);
                }
                assertFalse(retry.isAlive());
                assertEquals(130, retryCode.get());
                assertFalse(resumed.toString().contains("first"));
                assertTrue(resumed.toString().contains("second"));
            }
        }
    }

    private static final class FailingWriter extends Writer {
        private final boolean failOnFlush;
        private final StringBuilder output = new StringBuilder();
        private final CountDownLatch firstFlushed = new CountDownLatch(1);

        private FailingWriter(boolean failOnFlush) {
            this.failOnFlush = failOnFlush;
        }

        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (!failOnFlush && firstFlushed.getCount() == 0) throw new IOException("write failed");
            output.append(chars, offset, length);
        }

        @Override public void flush() throws IOException {
            if (failOnFlush && output.toString().contains("second")) throw new IOException("flush failed");
            if (output.toString().contains("first")) firstFlushed.countDown();
        }

        @Override public void close() { }
    }

    @Test
    void missingHistoryAndNegativeCursorReturnErrors() {
        var errors = new StringWriter();
        var cli = new CommandLine(new AssetMigrateCli()).setErr(new PrintWriter(errors));
        String project = UUID.randomUUID().toString();
        assertEquals(1, cli.execute("logs", "--directory", directory.toString(), "--project", project));
        assertTrue(errors.toString().contains("Unable to read project logs"));
        assertEquals(2, cli.execute("logs", "--directory", directory.toString(), "--project", project,
                "--after", "-1"));
    }
}
