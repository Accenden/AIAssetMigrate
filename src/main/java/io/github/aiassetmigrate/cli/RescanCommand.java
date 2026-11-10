package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

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
    @Option(names = {"--state-dir", "--state-directory"}, required = true, paramLabel = "DIR",
            description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", paramLabel = "DIR",
            description = "Legacy compatibility option; must be <state-dir>/logs.")
    private Path logDirectory;
    @Spec
    private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public RescanCommand() {
        this((state, ignoredManagedLogs) -> new DefaultAssetManager(state));
    }

    RescanCommand(ImportCommand.ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    @Override
    public Integer call() {
        InventorySnapshot committed = null;
        ImportCommand.validateManagedLogDirectory(spec, stateDirectory, logDirectory);
        try {
            AssetManager manager = managerFactory.create(stateDirectory, ImportCommand.managedLogDirectory(stateDirectory));
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                committed = manager.rescan(projectId, source).snapshot();
            }
            ImportCommand.printInventory(spec.commandLine().getOut(), committed, stateDirectory);
            return ImportCommand.inventoryExitCode(committed);
        } catch (ImportException e) {
            return ImportCommand.reportFailure(spec, "Rescan", e, committed);
        } catch (Exception e) {
            return ImportCommand.reportFailure(spec, "Rescan", e, committed);
        }
    }
}
