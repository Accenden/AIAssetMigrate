package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Displays a persisted inventory without accessing the source tree. */
@Command(name = "inventory", mixinStandardHelpOptions = true,
        description = "Display a saved inventory snapshot without scanning source assets.")
public final class InventoryCommand implements Callable<Integer> {
    @Option(names = {"--state-dir", "--state-directory"}, required = true, paramLabel = "DIR",
            description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--revision", paramLabel = "N", description = "Saved inventory revision (default: latest).")
    private Long revision;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public InventoryCommand() {
        this((state, ignoredManagedLogs) -> new DefaultAssetManager(state));
    }

    InventoryCommand(ImportCommand.ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    @Override
    public Integer call() {
        if (revision != null && revision < 1) {
            spec.commandLine().getErr().println("--revision must be positive");
            return 2;
        }
        try {
            AssetManager manager = managerFactory.create(stateDirectory, ImportCommand.managedLogDirectory(stateDirectory));
            InventorySnapshot snapshot;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                snapshot = revision == null ? manager.loadInventory(projectId) : manager.loadInventory(projectId, revision);
            }
            ImportCommand.printInventory(spec.commandLine().getOut(), snapshot, stateDirectory);
            return ImportCommand.inventoryExitCode(snapshot);
        } catch (Exception e) {
            return ImportCommand.reportFailure(spec, "Inventory", e, null);
        }
    }
}
