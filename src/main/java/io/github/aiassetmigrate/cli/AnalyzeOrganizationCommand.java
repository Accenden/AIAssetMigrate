package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "analyze-organization", mixinStandardHelpOptions = true,
        description = "Inspect organization suggestions without accepting changes.")
public final class AnalyzeOrganizationCommand implements Callable<Integer> {
    enum NameOption { preserve, snake_case, kebab_case }
    enum FolderOption { preserve, by_type, by_category }

    @Option(names = "--project", required = true, paramLabel = "UUID", description = "Project UUID.")
    private UUID projectId;
    @Option(names = "--name-style", defaultValue = "preserve", paramLabel = "STYLE",
            converter = NameConverter.class, description = "Name style: preserve, snake-case, kebab-case (default: preserve).")
    private NameOption nameStyle;
    @Option(names = "--folder-style", defaultValue = "preserve", paramLabel = "STYLE",
            converter = FolderConverter.class, description = "Folder style: preserve, by-type, by-category (accepted categories; default: preserve).")
    private FolderOption folderStyle;
    @Option(names = "--state-directory", required = true, paramLabel = "DIR", description = "Project state directory.")
    private Path stateDirectory;
    @Option(names = "--log-directory", required = true, paramLabel = "DIR", description = "Event log directory.")
    private Path logDirectory;
    @Spec private CommandSpec spec;

    private final ImportCommand.ManagerFactory managerFactory;

    public AnalyzeOrganizationCommand() { this(DefaultAssetManager::new); }
    AnalyzeOrganizationCommand(ImportCommand.ManagerFactory managerFactory) { this.managerFactory = managerFactory; }

    static final class NameConverter implements picocli.CommandLine.ITypeConverter<NameOption> {
        public NameOption convert(String value) {
            return switch (value) {
                case "preserve" -> NameOption.preserve;
                case "snake-case" -> NameOption.snake_case;
                case "kebab-case" -> NameOption.kebab_case;
                default -> throw new picocli.CommandLine.TypeConversionException("Expected preserve, snake-case, or kebab-case");
            };
        }
    }

    static final class FolderConverter implements picocli.CommandLine.ITypeConverter<FolderOption> {
        public FolderOption convert(String value) {
            return switch (value) {
                case "preserve" -> FolderOption.preserve;
                case "by-type" -> FolderOption.by_type;
                case "by-category" -> FolderOption.by_category;
                default -> throw new picocli.CommandLine.TypeConversionException("Expected preserve, by-type, or by-category");
            };
        }
    }

    @Override public Integer call() {
        try {
            var rules = new AssetManager.OrganizationRules(
                    AssetManager.NameStyle.valueOf(nameStyle.name().toUpperCase(java.util.Locale.ROOT)),
                    AssetManager.FolderStyle.valueOf(folderStyle.name().toUpperCase(java.util.Locale.ROOT)));
            AssetManager manager = managerFactory.create(stateDirectory, logDirectory);
            AssetManager.AnalysisResult result;
            try (AutoCloseable resource = () -> {
                if (manager instanceof AutoCloseable closeable) closeable.close();
            }) {
                result = manager.analyzeOrganization(projectId, rules);
            }
            var out = spec.commandLine().getOut();
            out.printf("Rules: name-style=%s folder-style=%s%n",
                    nameStyle.name().replace('_', '-'), folderStyle.name().replace('_', '-'));
            AnalyzeCommand.printResult(out, result);
            out.flush();
            if (out.checkError()) throw new java.io.IOException("Could not write analysis output");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Organization analysis failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }
}
