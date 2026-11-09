package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "reviews", mixinStandardHelpOptions = true,
        description = "List the current project's reviewed decisions without changing them.")
public final class ReviewsCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public ReviewsCommand() { this(DefaultAssetManager::new); }
    ReviewsCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    @Override public Integer call() {
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            List<ReviewedChange> reviews;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                reviews = manager.loadReviews(projectId);
            }
            ReviewCommand.printReviews(spec.commandLine().getOut(), reviews);
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Loading reviews failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }
}
