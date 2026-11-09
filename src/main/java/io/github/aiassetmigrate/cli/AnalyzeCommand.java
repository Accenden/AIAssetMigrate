package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "analyze", mixinStandardHelpOptions = true,
        description = "Inspect suggestions and findings without accepting changes.")
public final class AnalyzeCommand implements Callable<Integer> {
    enum Kind { categories, relationships, materials }

    @Parameters(index = "0", paramLabel = "KIND", description = "Analysis kind: ${COMPLETION-CANDIDATES}.")
    private Kind kind;
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public AnalyzeCommand() { this(DefaultAssetManager::new); }
    AnalyzeCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    @Override public Integer call() {
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            AssetManager.AnalysisResult result;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                result = manager.analyze(projectId, AssetManager.AnalysisKind.valueOf(kind.name().toUpperCase(java.util.Locale.ROOT)));
            }
            var out = spec.commandLine().getOut();
            out.println("Suggestions (" + result.suggestions().size() + "):");
            for (var suggestion : result.suggestions()) {
                out.printf("  ID: %s Asset ID: %s Kind: %s Value: %s%n", suggestion.id(), suggestion.assetId(),
                        suggestion.kind(), ImportCommand.safe(suggestion.proposedValue()));
                suggestion.reference().ifPresent(reference -> out.printf("    Reference: %s%n", ImportCommand.safe(reference)));
            }
            out.println("Findings (" + result.findings().size() + "):");
            for (var finding : result.findings()) {
                out.printf("  %s %s Asset ID: %s Path: %s: %s%n", finding.severity(),
                        ImportCommand.safe(finding.code()), finding.assetId().map(UUID::toString).orElse("<project>"),
                        finding.sourceRelativePath().map(Path::toString).map(ImportCommand::safe).orElse("<project>"),
                        ImportCommand.safe(finding.message()));
            }
            out.flush();
            if (out.checkError()) throw new java.io.IOException("Could not write analysis output");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Analysis failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }
}
