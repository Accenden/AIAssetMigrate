package io.github.aiassetmigrate;

import io.github.aiassetmigrate.cli.AssetMigrateCli;
import io.github.aiassetmigrate.gui.AssetMigrateApplication;
import javafx.application.Application;

import java.util.Arrays;

/**
 * Entry point of the runnable JAR: {@code java -jar AIAssetMigrate.jar} opens the GUI,
 * {@code java -jar AIAssetMigrate.jar gui [ARGS]} opens it with GUI arguments (for example
 * {@code --smoke-test}), and any other arguments run the CLI.
 *
 * <p>This class deliberately does not extend {@link Application}: when the JAR's main class
 * is an Application, the java launcher requires JavaFX on the module path and refuses to start
 * from a single classpath JAR.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            Application.launch(AssetMigrateApplication.class);
        } else if (args[0].equals("gui")) {
            Application.launch(AssetMigrateApplication.class, Arrays.copyOfRange(args, 1, args.length));
        } else {
            AssetMigrateCli.main(args);
        }
    }
}
