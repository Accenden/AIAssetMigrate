package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;
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
    @Option(names = "--inventory-revision", required = true, paramLabel = "REVISION",
            converter = PositiveRevision.class, description = "Positive inventory revision returned by analysis.")
    private long inventoryRevision;
    @Option(names = "--asset", required = true, paramLabel = "UUID", description = "Asset UUID.")
    private UUID assetId;
    @Option(names = "--kind", required = true, description = "Change kind: ${COMPLETION-CANDIDATES}.")
    private ReviewedChange.Kind kind;
    @Option(names = "--decision", required = true, description = "Explicit decision: ${COMPLETION-CANDIDATES}.")
    private ReviewedChange.Decision decision;
    @Option(names = "--value", paramLabel = "KEY=VALUE", description = {
            "Structured value; repeat for multiple entries.",
            "CATEGORY: category; RELATIONSHIP: targetAssetId and optional reference;",
            "MATERIAL_MAPPING: targetAssetId and reference; EXCLUSION: reason;",
            "NAME: name=FILE; FOLDER: folder=RELATIVE (empty for root);",
            "ACKNOWLEDGMENT: reason (accepts unknown FBX or other uninspectable dependencies);",
            "CONVERSION: format=png|jpg, optional background=#RRGGBB and quality=0.1-1.0 (textures).",
            "An accepted RELATIONSHIP or MATERIAL_MAPPING whose reference matches a file",
            "reference corrects that reference; migration rewrites it in the output copy."})
    private Map<String, String> values = new LinkedHashMap<>();
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public ReviewCommand() { this(DefaultAssetManager::new); }
    ReviewCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    public static final class PositiveRevision implements ITypeConverter<Long> {
        @Override public Long convert(String value) {
            try {
                long revision = Long.parseLong(value);
                if (revision > 0) return revision;
            } catch (NumberFormatException ignored) {
                // Report all invalid revisions consistently.
            }
            throw new TypeConversionException("Inventory revision must be a positive integer: " + value);
        }
    }

    @Override public Integer call() {
        boolean submitted = false;
        try {
            ReviewedChange change = new ReviewedChange(UUID.randomUUID(), assetId, kind, decision, values, Instant.now());
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            List<ReviewedChange> saved;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                saved = manager.review(projectId, inventoryRevision, List.of(change));
                submitted = true;
            }
            printReviews(spec.commandLine().getOut(), saved);
            return 0;
        } catch (Exception e) {
            var err = spec.commandLine().getErr();
            err.println("Review failed: " + ImportCommand.safe(e.getMessage()));
            if (submitted) err.println("The review was submitted and may have been saved.");
            return 1;
        }
    }

    static void printReviews(java.io.PrintWriter out, List<ReviewedChange> reviews) throws java.io.IOException {
        out.println("Reviewed changes (" + reviews.size() + "):");
        for (var item : reviews) {
            out.printf("  ID: %s Asset ID: %s Kind: %s Decision: %s Reviewed at: %s%n",
                    item.id(), item.assetId(), item.kind(), item.decision(), item.reviewedAt());
            new TreeMap<>(item.values()).forEach((key, value) ->
                    out.printf("    %s=%s%n", ImportCommand.safe(key), ImportCommand.safe(value)));
        }
        out.flush();
        if (out.checkError()) throw new java.io.IOException("Could not write review output");
    }
}
