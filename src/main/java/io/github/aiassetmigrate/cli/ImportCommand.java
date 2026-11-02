package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.Callable;

@Command(name = "import", mixinStandardHelpOptions = true,
        description = "Import a source folder into a new project without modifying its assets.")
public final class ImportCommand implements Callable<Integer> {
    @FunctionalInterface
    interface ManagerFactory {
        AssetManager create(Path stateDirectory, Path logDirectory) throws IOException;
    }

    @Parameters(index = "0", paramLabel = "SOURCE", description = "Source asset folder.")
    private Path source;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR",
            description = "Project state directory outside the source folder.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR",
            description = "Event log directory outside the source folder.")
    private Path logDirectory;
    @Spec
    private CommandSpec spec;

    private final ManagerFactory managerFactory;

    public ImportCommand() {
        this(DefaultAssetManager::new);
    }

    ImportCommand(ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    @Override
    public Integer call() {
        InventorySnapshot committed = null;
        try {
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                committed = manager.importAssets(source).snapshot();
            }
            printInventory(spec.commandLine().getOut(), committed);
            return 0;
        } catch (ImportException e) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("Import failed: " + safe(e.getMessage()));
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                err.println("Cause: " + safe(e.getCause().getMessage()));
            }
            if (e.projectId().isPresent()) {
                err.println("Project ID: " + e.projectId().get());
            } else if (committed != null) {
                err.println("Project ID: " + committed.project().id());
            }
            if (e.committedRevision().isPresent()) {
                err.println("Committed revision: " + e.committedRevision().get());
            } else if (committed != null) {
                err.println("Committed revision: " + committed.project().inventoryRevision());
            }
            return 1;
        } catch (Exception e) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("Import failed: " + safe(e.getMessage()));
            if (committed != null) {
                err.println("Project ID: " + committed.project().id());
                err.println("Committed revision: " + committed.project().inventoryRevision());
            }
            return 1;
        }
    }

    private static void printInventory(PrintWriter out, InventorySnapshot snapshot) throws IOException {
        var project = snapshot.project();
        out.println("Project ID: " + project.id());
        out.println("Revision: " + project.inventoryRevision());
        out.println("Scan status: " + snapshot.scanStatus());
        out.println("Assets (" + project.assets().size() + "):");
        for (var asset : project.assets()) {
            out.printf("  %s [%s] ID: %s SHA-256: %s%n", safe(asset.relativePath().toString()),
                    asset.type(), asset.id(), asset.fingerprint());
            new TreeMap<>(asset.metadata()).forEach((key, value) ->
                    out.printf("    %s: %s%n", safe(key), safe(value)));
        }
        out.println("Findings (" + snapshot.findings().size() + "):");
        for (var finding : snapshot.findings()) {
            out.printf("  %s %s %s: %s%n", finding.severity(), safe(finding.code()),
                    finding.sourceRelativePath().map(Path::toString).map(ImportCommand::safe).orElse("<project>"),
                    safe(finding.message()));
        }
        out.println("Coverage (" + snapshot.pathOutcomes().size() + "):");
        for (var outcome : snapshot.pathOutcomes()) {
            String path = outcome.relativePath().toString();
            out.printf("  %s %s %s%n", outcome.status(), outcome.kind(), path.isEmpty() ? "." : safe(path));
        }
        out.flush();
        if (out.checkError()) {
            throw new IOException("Could not write inventory output");
        }
    }

    private static String safe(String text) {
        if (text == null) return "(no detail)";
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(character -> {
            if (Character.isISOControl(character)) {
                result.append(String.format("\\u%04x", character));
            } else {
                result.appendCodePoint(character);
            }
        });
        return result.toString();
    }
}
