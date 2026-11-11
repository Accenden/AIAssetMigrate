package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "compatibility", mixinStandardHelpOptions = true,
        description = "Assess compatibility or list supported targets without changing the project.")
public final class CompatibilityCommand implements Callable<Integer> {
    @Option(names = "--list-targets", description = "List supported target IDs and versions without a project.")
    private boolean listTargets;
    @Option(names = "--project", paramLabel = "UUID", description = "Project UUID for assessment.")
    private UUID projectId;
    @Option(names = "--target", paramLabel = "ID", description = "Target profile ID.")
    private String targetId;
    @Option(names = "--target-version", paramLabel = "VERSION", description = "Target profile version.")
    private String targetVersion;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Option(names = "--ai", description = {
            "Add advisory AI explanations from the connected ChatGPT account (see 'auth').",
            "Sends issue codes, counts and relative example paths; never file contents.",
            "Every rule-based finding is kept; if AI is unavailable an AI_UNAVAILABLE finding is added."})
    private boolean ai;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;
    private final AnalyzeCommand.AssistantFactory assistantFactory;

    public CompatibilityCommand() { this(DefaultAssetManager::new); }
    CompatibilityCommand(ImportCommand.ManagerFactory managerFactory) { this(managerFactory, AnalyzeCommand::connectedAssistant); }
    CompatibilityCommand(ImportCommand.ManagerFactory managerFactory, AnalyzeCommand.AssistantFactory assistantFactory) {
        this.managerFactory = managerFactory;
        this.assistantFactory = assistantFactory;
    }

    @Override public Integer call() {
        if (listTargets ? projectId != null || targetId != null || targetVersion != null || ai
                : projectId == null || targetId == null || targetId.isBlank()
                        || targetVersion == null || targetVersion.isBlank()) {
            spec.commandLine().getErr().println("Specify --list-targets without project or target options, or --project, --target and --target-version together.");
            return 2;
        }
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                var out = spec.commandLine().getOut();
                if (listTargets) {
                    var targets = manager.supportedTargets();
                    out.println("Supported targets (" + targets.size() + "):");
                    for (var target : targets) {
                        out.printf("  ID: %s Version: %s%n", ImportCommand.safe(target.id()), ImportCommand.safe(target.version()));
                    }
                } else {
                    var target = new AssetManager.TargetProfile(targetId, targetVersion);
                    var result = ai ? manager.assessCompatibility(projectId, target, assistant()) : manager.assessCompatibility(projectId, target);
                    out.printf("Target: %s Version: %s%n", ImportCommand.safe(result.target().id()), ImportCommand.safe(result.target().version()));
                    out.println("Inventory revision: " + result.inventoryRevision());
                    out.println("Findings (" + result.findings().size() + "):");
                    for (var finding : result.findings()) {
                        out.printf("  ID: %s Severity: %s Code: %s Asset ID: %s Path: %s Message: %s%n",
                                finding.id(), finding.severity(), ImportCommand.safe(finding.code()),
                                finding.assetId().map(UUID::toString).orElse("<project>"),
                                finding.sourceRelativePath().map(io.github.aiassetmigrate.domain.PortablePath::of).map(ImportCommand::safe).orElse("<project>"),
                                ImportCommand.safe(finding.message()));
                    }
                    out.println("Compatibility assessment is not validation and does not approve or perform migration.");
                }
                out.flush();
                if (out.checkError()) throw new IOException("Could not write compatibility output");
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Compatibility failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }

    private io.github.aiassetmigrate.ai.LLMClient assistant() {
        try { return assistantFactory.create(); }
        catch (io.github.aiassetmigrate.ai.LLMClient.AiException e) { return io.github.aiassetmigrate.ai.OpenAIAdapter.unavailable(e); }
    }
}
