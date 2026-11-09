package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "preview-organization", mixinStandardHelpOptions = true,
        description = "Preview organization without writing assets or approving migration.")
public final class PreviewOrganizationCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--inventory-revision", required = true, paramLabel = "REVISION",
            converter = ReviewCommand.PositiveRevision.class, description = "Expected positive inventory revision.")
    private long inventoryRevision;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public PreviewOrganizationCommand() { this(DefaultAssetManager::new); }
    PreviewOrganizationCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    @Override public Integer call() {
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            OrganizationPreview.Result result;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                result = manager.previewOrganization(projectId, inventoryRevision);
            }
            var out = spec.commandLine().getOut();
            out.println("Inventory revision: " + result.inventoryRevision());
            out.println("Blocked: " + result.blocked());
            out.println("Preview only: no asset writes or migration approval.");
            out.println("Entries (" + result.entries().size() + "):");
            for (var entry : result.entries()) {
                out.printf("  Asset ID: %s Source: %s Destination: %s Excluded: %s%n", entry.assetId(),
                        ImportCommand.safe(entry.source().toString()),
                        entry.destination().map(ImportCommand::safe).orElse("<none>"), entry.excluded());
            }
            out.println("Findings (" + result.findings().size() + "):");
            for (var finding : result.findings()) {
                out.printf("  %s Asset ID: %s: %s%n", ImportCommand.safe(finding.code()),
                        finding.assetId().map(UUID::toString).orElse("<project>"), ImportCommand.safe(finding.message()));
            }
            out.flush();
            if (out.checkError()) throw new java.io.IOException("Could not write preview output");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Organization preview failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }
}
