package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.logging.LogPublisher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "logs", mixinStandardHelpOptions = true,
        description = "Replay project events from log files; optionally follow new events.")
public final class LogsCommand implements Callable<Integer> {
    @Option(names = "--directory", required = true, description = "Application log directory (not the asset source folder).")
    private Path directory;
    @Option(names = "--project", required = true, description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--after", defaultValue = "0", description = "Last received event sequence (exclusive).")
    private long after;
    @Option(names = "--follow", description = "Follow new records until interrupted (Ctrl+C).")
    private boolean follow;
    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (after < 0) {
            spec.commandLine().getErr().println("--after must be nonnegative");
            return 2;
        }
        try {
            do {
                for (var event : LogPublisher.readLogs(directory, projectId, after)) {
                    spec.commandLine().getOut().printf("%d %s %-7s %s%n", event.sequence(),
                            event.occurredAt(), event.level(), event.message()
                                    .replace("\r", "\\r").replace("\n", "\\n"));
                    after = event.sequence();
                }
                spec.commandLine().getOut().flush();
                if (follow) Thread.sleep(250);
            } while (follow);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (IOException e) {
            spec.commandLine().getErr().println("Unable to read project logs: " + e.getMessage());
            return 1;
        }
    }
}
