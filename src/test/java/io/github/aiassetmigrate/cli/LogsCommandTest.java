package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.domain.LogEvent;
import io.github.aiassetmigrate.logging.LogPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
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
