package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Explicit, separate plan lifecycle operations. */
public final class PlanCommands {
    private PlanCommands() { }

    abstract static class Base implements Callable<Integer> {
        @Option(names = "--state-directory", required = true, paramLabel = "DIR") Path state;
        @Option(names = "--log-directory", required = true, paramLabel = "DIR") Path logs;
        @Spec CommandSpec spec;
        final ImportCommand.ManagerFactory factory;
        Base(ImportCommand.ManagerFactory factory) { this.factory = factory; }
        abstract int execute(AssetManager manager, PrintWriter out) throws Exception;

        @Override public Integer call() {
            boolean returned = false;
            try {
                AssetManager manager = factory.create(state, logs);
                try (AutoCloseable resource = () -> {
                    if (manager instanceof AutoCloseable closeable) closeable.close();
                }) {
                    PrintWriter out = spec.commandLine().getOut();
                    int result = execute(manager, out);
                    returned = true;
                    out.flush();
                    if (out.checkError()) throw new IOException("Could not write command output; operation may have completed");
                    return result;
                }
            } catch (Exception e) {
                spec.commandLine().getErr().println("Command failed: " + ImportCommand.safe(e.getMessage()));
                if (returned) spec.commandLine().getErr().println("The operation returned before this failure; check saved state before retrying.");
                return 1;
            }
        }
    }

    @Command(name = "plan", mixinStandardHelpOptions = true, description = "Create and display a draft plan; does not approve or migrate.")
    public static final class Plan extends Base {
        @Option(names = "--project", required = true, paramLabel = "UUID") UUID project;
        @Option(names = "--target", required = true, paramLabel = "ID") String target;
        @Option(names = "--target-version", required = true, paramLabel = "VERSION") String version;
        @Option(names = "--output", required = true, paramLabel = "DIR") Path output;
        @Option(names = "--ai", description = {
                "Ask the connected ChatGPT account (see 'auth') for conversions, files to leave out and reference fixes",
                "for the target. The program checks each one; those that pass are listed as AI_PLAN_PROPOSAL findings with",
                "the review that applies them. They never change commands or approve the plan."})
        boolean ai;
        private final AnalyzeCommand.AssistantFactory assistantFactory;
        public Plan() { this(DefaultAssetManager::new); }
        Plan(ImportCommand.ManagerFactory factory) { this(factory, AnalyzeCommand::connectedAssistant); }
        Plan(ImportCommand.ManagerFactory factory, AnalyzeCommand.AssistantFactory assistantFactory) {
            super(factory);
            this.assistantFactory = assistantFactory;
        }
        @Override public Integer call() {
            if (target == null || target.isBlank() || version == null || version.isBlank()) {
                spec.commandLine().getErr().println("Target ID and version must not be blank.");
                return 2;
            }
            return super.call();
        }
        @Override int execute(AssetManager manager, PrintWriter out) throws Exception {
            var profile = new AssetManager.TargetProfile(target, version);
            MigrationPlan created;
            if (ai) {
                io.github.aiassetmigrate.ai.LLMClient assistant;
                try { assistant = assistantFactory.create(); }
                catch (io.github.aiassetmigrate.ai.LLMClient.AiException e) { assistant = io.github.aiassetmigrate.ai.OpenAIAdapter.unavailable(e); }
                created = manager.createPlan(project, profile, output, assistant);
            } else {
                created = manager.createPlan(project, profile, output);
            }
            printPlan(manager, out, created, true);
            return 0;
        }
    }

    @Command(name = "show-plan", mixinStandardHelpOptions = true, description = "Display a saved plan without approving or migrating it.")
    public static final class Show extends Base {
        @Option(names = "--plan", required = true, paramLabel = "UUID") UUID plan;
        public Show() { this(DefaultAssetManager::new); }
        Show(ImportCommand.ManagerFactory factory) { super(factory); }
        @Override int execute(AssetManager manager, PrintWriter out) throws Exception {
            printPlan(manager, out, manager.loadPlan(plan), false);
            return 0;
        }
    }

    @Command(name = "approve", mixinStandardHelpOptions = true, description = "Explicitly approve the exact reviewed plan version.")
    public static final class Approve extends Base {
        @Option(names = "--plan", required = true, paramLabel = "UUID") UUID plan;
        @Option(names = "--version", required = true, paramLabel = "N") int version;
        public Approve() { this(DefaultAssetManager::new); }
        Approve(ImportCommand.ManagerFactory factory) { super(factory); }
        @Override public Integer call() {
            if (version < 1) {
                spec.commandLine().getErr().println("Plan version must be positive.");
                return 2;
            }
            return super.call();
        }
        @Override int execute(AssetManager manager, PrintWriter out) throws Exception {
            printPlan(manager, out, manager.approve(plan, version), true);
            return 0;
        }
    }

    @Command(name = "migrate", mixinStandardHelpOptions = true,
            description = {"Execute an explicitly approved plan, then validate its outputs and save a report.",
                    "Exit code 0 only when the run succeeded and validation PASSED."})
    public static final class Migrate extends Base {
        @Option(names = "--plan", required = true, paramLabel = "UUID") UUID plan;
        @Option(names = "--ai", description = {
                "Add an advisory AI summary to the validation report (see 'validate --ai').",
                "AI never runs, approves or changes the migration."})
        boolean ai;

        private final AnalyzeCommand.AssistantFactory assistantFactory;

        public Migrate() { this(DefaultAssetManager::new, AnalyzeCommand::connectedAssistant); }
        Migrate(ImportCommand.ManagerFactory factory) { this(factory, AnalyzeCommand::connectedAssistant); }
        Migrate(ImportCommand.ManagerFactory factory, AnalyzeCommand.AssistantFactory assistantFactory) {
            super(factory);
            this.assistantFactory = assistantFactory;
        }

        @Override int execute(AssetManager manager, PrintWriter out) {
            Optional<LLMClient> assistant = Optional.empty();
            if (ai) {
                try { assistant = Optional.of(assistantFactory.create()); }
                catch (LLMClient.AiException e) { assistant = Optional.of(OpenAIAdapter.unavailable(e)); }
            }
            AssetManager.MigrationOutcome outcome = manager.migrateAndValidate(plan, assistant);
            printRun(out, outcome.run());
            outcome.migrationFailure().ifPresent(failure -> out.println("Migration interrupted: " + quote(failure)));
            out.println();
            if (outcome.report().isPresent()) {
                ValidationCommands.printReport(out, outcome.report().get(), true);
            } else {
                out.println("Validation failed: " + quote(outcome.validationFailure().orElseThrow()));
                out.println("No report was saved; run 'validate --run " + outcome.run().id() + "' to retry.");
            }
            return outcome.run().status() == RunStatus.SUCCESS && outcome.report()
                    .filter(report -> report.validationStatus() == Report.ValidationStatus.PASSED).isPresent() ? 0 : 1;
        }
    }

    @Command(name = "show-run", mixinStandardHelpOptions = true, description = "Display the saved run for a plan.")
    public static final class ShowRun extends Base {
        @Option(names = "--plan", required = true, paramLabel = "UUID") UUID plan;
        public ShowRun() { this(DefaultAssetManager::new); }
        ShowRun(ImportCommand.ManagerFactory factory) { super(factory); }
        @Override int execute(AssetManager manager, PrintWriter out) {
            printRun(out, manager.loadRun(plan));
            return 0;
        }
    }

    static void printRun(PrintWriter out, MigrationRun run) {
            out.printf("Run ID: %s%nProject ID: %s%nPlan ID: %s%nPlan version: %d%nOutput root: %s%nStatus: %s%nCreated at: %s%nStarted at: %s%nCompleted at: %s%n",
                    run.id(), run.projectId(), run.planId(), run.planVersion(), quote(run.outputRoot().toString()),
                    run.status(), run.createdAt(), run.startedAt().map(Object::toString).orElse("<none>"),
                    run.completedAt().map(Object::toString).orElse("<none>"));
            out.println("Message: " + run.message().map(PlanCommands::quote).orElse("<none>"));
            out.println("Steps (" + run.stepStatuses().size() + "):");
            new TreeMap<>(run.stepStatuses()).forEach((id, status) -> {
                out.printf("  Command ID: %s Status: %s%n", id, status);
                if (run.stepDetails().containsKey(id)) out.println("    Detail: " + quote(run.stepDetails().get(id)));
            });
    }

    static void printPlan(AssetManager manager, PrintWriter out, MigrationPlan plan, boolean changed) throws Exception {
        Map<UUID, Path> sources = new HashMap<>();
        try {
            var inventory = manager.loadInventory(plan.projectId(), plan.inventoryRevision());
            if (!inventory.project().id().equals(plan.projectId())
                    || inventory.project().inventoryRevision() != plan.inventoryRevision()) {
                throw new IllegalStateException("Inventory project or revision does not match the plan");
            }
            for (var asset : inventory.project().assets()) {
                if (sources.putIfAbsent(asset.id(), asset.relativePath()) != null) {
                    throw new IllegalStateException("Duplicate inventory asset ID: " + asset.id());
                }
            }
            for (var command : plan.commands()) {
                if (!sources.containsKey(command.assetId())) {
                    throw new IllegalStateException("Inventory does not contain source asset ID: " + command.assetId());
                }
            }
        } catch (Exception e) {
            String context = changed ? "Plan operation returned plan " + plan.id() + " version " + plan.version()
                    + " with status " + plan.status() + "; saved state may have changed. " : "";
            throw new IllegalStateException(context + "Cannot display plan sources: " + e.getMessage(), e);
        }
        out.printf("Plan ID: %s%nProject ID: %s%nVersion: %d%nInventory revision: %d%nTarget: %s%nTarget version: %s%nOutput root: %s%nStatus: %s%nCreated at: %s%n",
                plan.id(), plan.projectId(), plan.version(), plan.inventoryRevision(), quote(plan.targetProfile()),
                quote(plan.targetProfileVersion()), quote(plan.outputRoot().toString()), plan.status(), plan.createdAt());
        out.println("Reviewed changes (" + plan.reviewedChanges().size() + "):");
        for (var change : plan.reviewedChanges()) {
            out.printf("  ID: %s Asset ID: %s Kind: %s Decision: %s Reviewed at: %s%n",
                    change.id(), change.assetId(), change.kind(), change.decision(), change.reviewedAt());
            new TreeMap<>(change.values()).forEach((key, value) -> out.printf("    Value %s: %s%n", quote(key), quote(value)));
        }
        out.println("Commands (" + plan.commands().size() + "):");
        for (var command : plan.commands()) {
            out.printf("  ID: %s Source asset ID: %s Source relative path: %s Operation: %s Relative destination: %s%n",
                    command.id(), command.assetId(), quote(io.github.aiassetmigrate.domain.PortablePath.of(sources.get(command.assetId()))),
                    command.operation(), quote(io.github.aiassetmigrate.domain.PortablePath.of(command.relativeOutputPath())));
            out.println("    Converter requirement: " + command.converterRequirement().map(PlanCommands::quote).orElse("<none>"));
            out.println("    Dependencies: " + command.dependencies());
            new TreeMap<>(command.parameters()).forEach((key, value) -> out.printf("    Parameter %s: %s%n", quote(key), quote(value)));
        }
        out.println("Findings (" + plan.findings().size() + "):");
        for (var finding : plan.findings()) {
            out.printf("  ID: %s Severity: %s Code: %s Asset ID: %s Source path: %s Message: %s%n",
                    finding.id(), finding.severity(), quote(finding.code()),
                    finding.assetId().map(UUID::toString).orElse("<none>"),
                    finding.sourceRelativePath().map(io.github.aiassetmigrate.domain.PortablePath::of).map(PlanCommands::quote).orElse("<none>"), quote(finding.message()));
        }
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        value.codePoints().forEach(c -> {
            if (c == '\\' || c == '"') text.append('\\').appendCodePoint(c);
            else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                text.append(String.format("\\u%04x", c));
            else text.appendCodePoint(c);
        });
        return text.append('"').toString();
    }
}
