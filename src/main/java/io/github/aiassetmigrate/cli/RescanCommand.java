package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "rescan", mixinStandardHelpOptions = true,
        description = "Rescan an existing project without modifying its source assets.")
public final class RescanCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "SOURCE", description = "Project source folder.")
    private Path source;
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Existing project UUID.")
    private UUID projectId;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR",
            description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR",
            description = "Event log directory.")
    private Path logDirectory;
    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        InventorySnapshot committed = null;
        try {
            try (DefaultAssetManager manager = new DefaultAssetManager(stateDirectory, logDirectory)) {
                committed = manager.rescan(projectId, source).snapshot();
            }
            ImportCommand.printInventory(spec.commandLine().getOut(), committed);
            return 0;
        } catch (ImportException e) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("Rescan failed: " + ImportCommand.safe(e.getMessage()));
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                err.println("Cause: " + ImportCommand.safe(e.getCause().getMessage()));
            }
            e.projectId().ifPresent(id -> err.println("Project ID: " + id));
            e.committedRevision().ifPresent(revision -> err.println("Committed revision: " + revision));
            return 1;
        } catch (IOException e) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("Rescan failed: " + ImportCommand.safe(e.getMessage()));
            if (committed != null) {
                err.println("Project ID: " + committed.project().id());
                err.println("Committed revision: " + committed.project().inventoryRevision());
            }
            return 1;
        } catch (RuntimeException e) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("Rescan failed: " + ImportCommand.safe(e.getMessage()));
            if (committed != null) {
                err.println("Project ID: " + committed.project().id());
                err.println("Committed revision: " + committed.project().inventoryRevision());
            }
            return 1;
        }
    }
}
