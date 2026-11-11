package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
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
    enum Kind { categories, relationships, materials, names, folders }

    @Parameters(index = "0", paramLabel = "KIND", description = "Analysis kind: ${COMPLETION-CANDIDATES}.")
    private Kind kind;
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Option(names = "--ai", description = {
            "Add validated AI suggestions from the connected ChatGPT account (see 'auth').",
            "Sends relative paths, asset types, formats and reference text; never file contents.",
            "If AI is unavailable, rule-based results are shown with an AI_UNAVAILABLE finding."})
    private boolean ai;
    @Spec private CommandSpec spec;

    /** Creates the model client for --ai; failures are reported as AI_UNAVAILABLE, not fatal. */
    @FunctionalInterface
    interface AssistantFactory { LLMClient create() throws LLMClient.AiException; }

    private final ImportCommand.ManagerFactory managerFactory;
    private final AssistantFactory assistantFactory;

    public AnalyzeCommand() { this(DefaultAssetManager::new, AnalyzeCommand::connectedAssistant); }
    AnalyzeCommand(ImportCommand.ManagerFactory managerFactory) { this(managerFactory, AnalyzeCommand::connectedAssistant); }
    AnalyzeCommand(ImportCommand.ManagerFactory managerFactory, AssistantFactory assistantFactory) {
        this.managerFactory = managerFactory;
        this.assistantFactory = assistantFactory;
    }

    static LLMClient connectedAssistant() { return OpenAIAdapter.connectedOrUnavailable(); }

    @Override public Integer call() {
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            AssetManager.AnalysisResult result;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                var analysisKind = AssetManager.AnalysisKind.valueOf(kind.name().toUpperCase(java.util.Locale.ROOT));
                if (ai) {
                    LLMClient assistant;
                    try { assistant = assistantFactory.create(); }
                    catch (LLMClient.AiException e) { assistant = OpenAIAdapter.unavailable(e); }
                    result = manager.analyze(projectId, analysisKind, assistant);
                } else {
                    result = manager.analyze(projectId, analysisKind);
                }
            }
            var out = spec.commandLine().getOut();
            printResult(out, result);
            out.flush();
            if (out.checkError()) throw new java.io.IOException("Could not write analysis output");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Analysis failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }

    static void printResult(java.io.PrintWriter out, AssetManager.AnalysisResult result) {
        out.println("Inventory revision: " + result.inventoryRevision());
        out.println("Suggestions (" + result.suggestions().size() + "):");
        for (var suggestion : result.suggestions()) {
            out.printf("  ID: %s Asset ID: %s Kind: %s Value: %s%s%n", suggestion.id(), suggestion.assetId(),
                    suggestion.kind(), ImportCommand.safe(suggestion.proposedValue()),
                    suggestion.source() == AssetManager.Source.AI ? " Source: AI" : "");
            suggestion.reference().ifPresent(reference -> out.printf("    Reference: %s%n", ImportCommand.safe(reference)));
        }
        out.println("Findings (" + result.findings().size() + "):");
        for (var finding : result.findings()) {
            out.printf("  %s %s Asset ID: %s Path: %s: %s%n", finding.severity(),
                    ImportCommand.safe(finding.code()), finding.assetId().map(UUID::toString).orElse("<project>"),
                    finding.sourceRelativePath().map(io.github.aiassetmigrate.domain.PortablePath::of).map(ImportCommand::safe).orElse("<project>"),
                    ImportCommand.safe(finding.message()));
        }
    }
}
