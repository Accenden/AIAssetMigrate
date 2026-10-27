package io.github.aiassetmigrate.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "aiassetmigrate", mixinStandardHelpOptions = true,
        version = "AIAssetMigrate 0.1.0-SNAPSHOT",
        description = "Prepare game assets for migration. Bootstrap build: help and version only.",
        footer = "Import, analysis, approval, migration, AI, and persistence are not implemented yet.")
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
