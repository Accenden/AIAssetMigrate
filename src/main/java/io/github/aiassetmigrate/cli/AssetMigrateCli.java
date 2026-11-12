package io.github.aiassetmigrate.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "aiassetmigrate", mixinStandardHelpOptions = true,
        version = "AIAssetMigrate 0.1.0-SNAPSHOT",
        description = "Prepare game assets for migration.",
        subcommands = {ImportCommand.class, RescanCommand.class, InventoryCommand.class, LogsCommand.class, AuthCommand.class,
                AnalyzeCommand.class, AnalyzeOrganizationCommand.class, ReviewCommand.class, ReviewsCommand.class, PreviewOrganizationCommand.class,
                CompatibilityCommand.class, PlanCommands.Plan.class, PlanCommands.Show.class,
                PlanCommands.Approve.class, PlanCommands.Migrate.class, PlanCommands.ShowRun.class,
                ValidationCommands.Validate.class, ValidationCommands.History.class, AgentCommand.class},
        footer = "AI is optional: --ai on analyze, compatibility, plan, migrate and validate, and the 'command' request, after auth connect. AI never approves or migrates.")
public final class AssetMigrateCli implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AssetMigrateCli()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
