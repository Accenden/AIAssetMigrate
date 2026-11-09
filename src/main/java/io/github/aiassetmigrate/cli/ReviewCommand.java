package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "review", mixinStandardHelpOptions = true,
        description = "Record an explicit accepted or rejected change; never accepts suggestions automatically.")
public final class ReviewCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--asset", required = true, paramLabel = "UUID", description = "Asset UUID.")
    private UUID assetId;
    @Option(names = "--kind", required = true, description = "Change kind: ${COMPLETION-CANDIDATES}.")
    private ReviewedChange.Kind kind;
    @Option(names = "--decision", required = true, description = "Explicit decision: ${COMPLETION-CANDIDATES}.")
    private ReviewedChange.Decision decision;
    @Option(names = "--value", paramLabel = "KEY=VALUE", description = {
            "Structured value; repeat for multiple entries.",
            "CATEGORY: category; RELATIONSHIP: targetAssetId;",
            "MATERIAL_MAPPING: targetAssetId and reference; EXCLUSION: reason."})
    private Map<String, String> values = new LinkedHashMap<>();
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public ReviewCommand() { this(DefaultAssetManager::new); }
    ReviewCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    @Override public Integer call() {
        boolean submitted = false;
        try {
            ReviewedChange change = new ReviewedChange(UUID.randomUUID(), assetId, kind, decision, values, Instant.now());
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            List<ReviewedChange> saved;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                saved = manager.review(projectId, List.of(change));
                submitted = true;
            }
            var out = spec.commandLine().getOut();
            out.println("Reviewed changes (" + saved.size() + "):");
            for (var item : saved) {
                out.printf("  ID: %s Asset ID: %s Kind: %s Decision: %s Reviewed at: %s%n",
                        item.id(), item.assetId(), item.kind(), item.decision(), item.reviewedAt());
                new TreeMap<>(item.values()).forEach((key, value) ->
                        out.printf("    %s=%s%n", ImportCommand.safe(key), ImportCommand.safe(value)));
            }
            out.flush();
            if (out.checkError()) throw new java.io.IOException("Could not write review output");
            return 0;
        } catch (Exception e) {
            var err = spec.commandLine().getErr();
            err.println("Review failed: " + ImportCommand.safe(e.getMessage()));
            if (submitted) err.println("The review was submitted and may have been saved.");
            return 1;
        }
    }
}
