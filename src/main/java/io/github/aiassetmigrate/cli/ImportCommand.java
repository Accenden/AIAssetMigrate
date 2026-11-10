package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "import", mixinStandardHelpOptions = true,
        description = "Import a source folder into a new project without modifying its assets.")
public final class ImportCommand implements Callable<Integer> {
    @FunctionalInterface
    interface ManagerFactory {
        AssetManager create(Path stateDirectory, Path managedLogDirectory) throws IOException;
    }

    @Parameters(index = "0", paramLabel = "SOURCE", description = "Source asset folder.")
    private Path source;
    @Option(names = {"--state-dir", "--state-directory"}, required = true, paramLabel = "DIR",
            description = "Project state directory outside the source folder.")
    private Path stateDirectory;
    @Option(names = "--log-directory", paramLabel = "DIR",
            description = "Legacy compatibility option; must be <state-dir>/logs.")
    private Path logDirectory;
    @Option(names = "--project", paramLabel = "UUID",
            description = "Existing project UUID to rescan instead of creating a project.")
    private UUID projectId;
    @Spec
    private CommandSpec spec;

    private final ManagerFactory managerFactory;

    public ImportCommand() {
        this((state, ignoredManagedLogs) -> new DefaultAssetManager(state));
    }

    ImportCommand(ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    @Override
    public Integer call() {
        InventorySnapshot committed = null;
        validateManagedLogDirectory(spec, stateDirectory, logDirectory);
        String operation = projectId == null ? "Import" : "Rescan";
        try {
            AssetManager manager = managerFactory.create(stateDirectory, managedLogDirectory(stateDirectory));
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                committed = (projectId == null ? manager.importAssets(source) : manager.rescan(projectId, source)).snapshot();
            }
            printInventory(spec.commandLine().getOut(), committed, stateDirectory);
            return inventoryExitCode(committed);
        } catch (ImportException e) {
            return reportFailure(spec, operation, e, committed);
        } catch (Exception e) {
            return reportFailure(spec, operation, e, committed);
        }
    }

    static Path managedLogDirectory(Path stateDirectory) {
        return stateDirectory.resolve("logs");
    }

    static void validateManagedLogDirectory(CommandSpec spec, Path stateDirectory, Path logDirectory) {
        if (logDirectory == null) return;
        Path expected = managedLogDirectory(stateDirectory).toAbsolutePath().normalize();
        Path actual = logDirectory.toAbsolutePath().normalize();
        if (!actual.equals(expected)) {
            throw new ParameterException(spec.commandLine(), "--log-directory must match the managed log directory: " + expected);
        }
    }

    static int inventoryExitCode(InventorySnapshot snapshot) {
        return snapshot.scanStatus() == InventorySnapshot.ScanStatus.COMPLETE ? 0 : 3;
    }

    static int reportFailure(CommandSpec spec, String operation, Throwable failure, InventorySnapshot committed) {
        if (isInterrupted(failure)) {
            Thread.currentThread().interrupt();
            return 130;
        }
        PrintWriter err = spec.commandLine().getErr();
        err.println(operation + " failed: " + safe(failure.getMessage()));
        if (failure.getCause() != null && failure.getCause().getMessage() != null) {
            err.println("Cause: " + safe(failure.getCause().getMessage()));
        }
        if (failure instanceof ImportException importFailure) {
            if (importFailure.projectId().isPresent()) {
                err.println("Project ID: " + importFailure.projectId().get());
            } else if (committed != null) {
                err.println("Project ID: " + committed.project().id());
            }
            if (importFailure.committedRevision().isPresent()) {
                err.println("Committed revision: " + importFailure.committedRevision().get());
            } else if (committed != null) {
                err.println("Committed revision: " + committed.project().inventoryRevision());
            }
        } else if (committed != null) {
            err.println("Project ID: " + committed.project().id());
            err.println("Committed revision: " + committed.project().inventoryRevision());
        }
        return 1;
    }

    static boolean isInterrupted(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof InterruptedIOException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    static void printInventory(PrintWriter out, InventorySnapshot snapshot, Path stateDirectory) throws IOException {
        var project = snapshot.project();
        out.println("Project ID: " + project.id());
        out.println("Revision: " + project.inventoryRevision());
        out.println("Source root: " + safe(project.sourceRoot().toString()));
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
        out.printf("Logs: aiassetmigrate logs --directory %s --project %s%n",
                commandArgument(managedLogDirectory(stateDirectory)), project.id());
        out.flush();
        if (out.checkError()) {
            throw new IOException("Could not write inventory output");
        }
    }

    private static String commandArgument(Path path) {
        String value = safe(path.toString());
        return value.chars().anyMatch(Character::isWhitespace)
                ? '"' + value.replace("\"", "\\\"") + '"' : value;
    }

    static String safe(String text) {
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
