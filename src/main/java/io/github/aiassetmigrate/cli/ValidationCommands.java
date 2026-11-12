package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Report;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

/** F08: validate a run's recorded outputs and list saved validation reports. */
public final class ValidationCommands {
    private ValidationCommands() { }

    @Command(name = "validate", mixinStandardHelpOptions = true,
            description = {"Check a migration run's recorded outputs and save a validation report.",
                    "Read-only for outputs. Exit code 0 only when validation PASSED."})
    public static final class Validate extends PlanCommands.Base {
        static final class Target {
            @Option(names = "--run", required = true, paramLabel = "UUID", description = "Run ID to validate.") UUID run;
            @Option(names = "--plan", required = true, paramLabel = "UUID", description = "Validate the run of this plan.") UUID plan;
        }

        @ArgGroup(exclusive = true, multiplicity = "1") Target target;
        @Option(names = "--ai", description = {
                "Add an advisory AI summary from the connected ChatGPT account (see 'auth').",
                "Sends statuses, counts, finding codes and relative paths; never file contents.",
                "The status is computed without AI; if AI fails, the deterministic summary is kept."})
        boolean ai;

        private final AnalyzeCommand.AssistantFactory assistantFactory;

        public Validate() { this(DefaultAssetManager::new, AnalyzeCommand::connectedAssistant); }
        Validate(ImportCommand.ManagerFactory factory) { this(factory, AnalyzeCommand::connectedAssistant); }
        Validate(ImportCommand.ManagerFactory factory, AnalyzeCommand.AssistantFactory assistantFactory) {
            super(factory);
            this.assistantFactory = assistantFactory;
        }

        @Override int execute(AssetManager manager, PrintWriter out) {
            UUID run = target.run != null ? target.run : manager.loadRun(target.plan).id();
            Report report;
            if (ai) {
                LLMClient assistant;
                try { assistant = assistantFactory.create(); }
                catch (LLMClient.AiException e) { assistant = OpenAIAdapter.unavailable(e); }
                report = manager.validate(run, assistant);
            } else {
                report = manager.validate(run);
            }
            printReport(out, report, true);
            return report.validationStatus() == Report.ValidationStatus.PASSED ? 0 : 1;
        }
    }

    @Command(name = "history", mixinStandardHelpOptions = true,
            description = "List saved validation reports for a project, oldest first.")
    public static final class History extends PlanCommands.Base {
        @Option(names = "--project", required = true, paramLabel = "UUID") UUID project;
        @Option(names = "--details", description = "Also print each report's findings.") boolean details;

        public History() { this(DefaultAssetManager::new); }
        History(ImportCommand.ManagerFactory factory) { super(factory); }

        @Override int execute(AssetManager manager, PrintWriter out) {
            List<Report> reports = manager.history(project);
            if (reports.isEmpty()) {
                out.println("No validation history for project " + project + ". 'migrate' saves a report for each run; 'validate' adds one for an existing run.");
                return 0;
            }
            out.println("Validation reports (" + reports.size() + "):");
            for (Report report : reports) {
                out.println();
                printReport(out, report, details);
            }
            return 0;
        }
    }

    static void printReport(PrintWriter out, Report report, boolean findings) {
        out.printf("Report ID: %s%nCreated at: %s%nProject ID: %s%nPlan ID: %s%nRun ID: %s%nRun status: %s%nValidation status: %s%n",
                report.id(), report.createdAt(), report.projectId(), report.planId(), report.runId(),
                report.runStatus(), report.validationStatus());
        out.println("Summary: " + ImportCommand.safe(report.summary()));
        report.aiSummary().ifPresent(text -> out.println("AI summary (advisory; the status above is authoritative): "
                + ImportCommand.safe(text)));
        if (!findings) return;
        out.println("Findings (" + report.findings().size() + "):");
        for (Finding finding : report.findings()) {
            out.printf("  %s %s Source: %s: %s%n", finding.severity(), ImportCommand.safe(finding.code()),
                    finding.sourceRelativePath().map(PortablePath::of).map(ImportCommand::safe).orElse("<run>"),
                    ImportCommand.safe(finding.message()));
        }
    }
}
