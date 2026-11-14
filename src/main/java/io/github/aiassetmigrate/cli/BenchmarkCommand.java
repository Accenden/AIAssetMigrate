package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
import io.github.aiassetmigrate.benchmark.AiBenchmark;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Scores the rules alone against rules plus AI on a built-in sample with known right answers. */
@Command(name = "benchmark", mixinStandardHelpOptions = true,
        description = {"Measure what the AI adds: builds a messy sample with known right answers in a temporary folder,",
                "runs the analyses, Fix blockers and (with --ai) the AI suggestions on it, and prints a score table.",
                "Nothing outside the temporary folder is read or written, and nothing is approved or migrated."})
public final class BenchmarkCommand implements Callable<Integer> {
    @Option(names = "--ai", description = "Also ask the connected ChatGPT account (see 'auth') and score rules + AI.") boolean ai;
    @Option(names = "--keep", paramLabel = "DIR", description = "Write into this empty or missing folder and keep it, instead of a temporary one.")
    Path keep;
    @Spec CommandSpec spec;

    private final AnalyzeCommand.AssistantFactory assistantFactory;

    public BenchmarkCommand() { this(AnalyzeCommand::connectedAssistant); }
    BenchmarkCommand(AnalyzeCommand.AssistantFactory assistantFactory) { this.assistantFactory = assistantFactory; }

    @Override public Integer call() {
        Path workspace = null;
        try {
            Optional<LLMClient> assistant = Optional.empty();
            if (ai) {
                try { assistant = Optional.of(assistantFactory.create()); }
                catch (LLMClient.AiException e) { assistant = Optional.of(OpenAIAdapter.unavailable(e)); }
            }
            workspace = keep != null ? keep.toAbsolutePath().normalize() : Files.createTempDirectory("aam-benchmark-");
            AiBenchmark.Result result = AiBenchmark.run(workspace, assistant);
            spec.commandLine().getOut().print(result.table());
            if (keep != null) spec.commandLine().getOut().println("Sample and state kept in " + workspace);
            spec.commandLine().getOut().flush();
            return result.notes().stream().anyMatch(note -> note.contains("unavailable")) ? 1 : 0;
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("Benchmark failed: " + ImportCommand.safe(String.valueOf(e.getMessage())));
            return 1;
        } finally {
            if (keep == null && workspace != null) delete(workspace);
        }
    }

    private static void delete(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { /* best effort for a temporary folder */ }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
