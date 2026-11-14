package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.PortablePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

/** F09 CLI: one plain-language request, interpreted by the connected model and run through the facade. */
@Command(name = "command", mixinStandardHelpOptions = true,
        description = {"Run a plain-language request, for example: command --project ID ... check unity 6000.0 compatibility.",
                "Uses the connected ChatGPT account (see 'auth'). Supported: show inventory, list targets, analyze,",
                "check compatibility, preview organization, create a draft plan, show plans and runs, validate, history,",
                "and prepare the project for a target (for example: prepare this for godot 4.3), where the agent runs the",
                "read-only checks it needs and prints checked, proposed decisions with the 'review' command for each.",
                "It never approves, migrates or saves reviews; those need the explicit commands."})
public final class AgentCommand extends PlanCommands.Base {
    @Option(names = "--project", required = true, paramLabel = "UUID") UUID project;
    @Parameters(arity = "1..*", paramLabel = "REQUEST", description = "The request; words are joined with spaces.") List<String> words;

    private final AnalyzeCommand.AssistantFactory assistantFactory;

    public AgentCommand() { this(DefaultAssetManager::new, AnalyzeCommand::connectedAssistant); }
    AgentCommand(ImportCommand.ManagerFactory factory, AnalyzeCommand.AssistantFactory assistantFactory) {
        super(factory);
        this.assistantFactory = assistantFactory;
    }

    @Override int execute(AssetManager manager, PrintWriter out) {
        LLMClient assistant;
        try { assistant = assistantFactory.create(); }
        catch (LLMClient.AiException e) { assistant = OpenAIAdapter.unavailable(e); }
        var result = manager.command(project, String.join(" ", words), assistant);
        print(out, result);
        return result.findings().stream().anyMatch(f -> f.code().equals("COMMAND_FAILED") || f.code().equals("AI_UNAVAILABLE")
                || f.code().equals("AI_OUTPUT_REJECTED")) ? 1 : 0;
    }

    static void print(PrintWriter out, AssetManager.CommandResult result) {
        out.println(ImportCommand.safe(result.message()).replace("\\u000a", System.lineSeparator()));
        result.draftPlan().ifPresent(plan -> out.printf("Draft plan: %s version %d (%s); approve it explicitly with 'approve'.%n",
                plan.id(), plan.version(), plan.status()));
        result.decisions().filter(d -> !d.fixes().isEmpty() || !d.unfixable().isEmpty()).ifPresent(decisions -> {
            out.println("Proposed decisions (" + decisions.fixes().size() + "; none is saved). To accept one, run its review command"
                    + " with your --state-directory and --log-directory, then create a new plan:");
            for (var fix : decisions.fixes()) {
                out.println("  " + (fix.source() == io.github.aiassetmigrate.application.BlockerFixes.Source.AI ? "[AI] " : "[rules] ")
                        + ImportCommand.safe(fix.description()));
                for (var change : fix.changes()) {
                    StringBuilder command = new StringBuilder("    review --project ").append(decisions.projectId())
                            .append(" --inventory-revision ").append(decisions.inventoryRevision()).append(" --asset ").append(change.assetId())
                            .append(" --kind ").append(change.kind()).append(" --decision ").append(change.decision());
                    new java.util.TreeMap<>(change.values()).forEach((key, value) -> command.append(" --value \"")
                            .append(key).append('=').append(value.replace("\"", "")).append('"'));
                    out.println(ImportCommand.safe(command.toString()));
                }
            }
            if (!decisions.unfixable().isEmpty()) {
                out.println("Dropped by the program's checks (" + decisions.unfixable().size() + "):");
                decisions.unfixable().forEach(line -> out.println("  " + ImportCommand.safe(line)));
            }
        });
        out.println("Findings (" + result.findings().size() + "):");
        for (var finding : result.findings()) {
            out.printf("  %s %s %s%s%n", finding.severity(), ImportCommand.safe(finding.code()),
                    finding.sourceRelativePath().map(PortablePath::of).map(p -> ImportCommand.safe(p) + ": ").orElse(""),
                    ImportCommand.safe(finding.message()));
        }
    }
}
