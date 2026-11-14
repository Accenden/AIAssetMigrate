package io.github.aiassetmigrate.benchmark;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.application.AssetManager.AnalysisResult;
import io.github.aiassetmigrate.application.AssetManager.Source;
import io.github.aiassetmigrate.application.AssetManager.Suggestion;
import io.github.aiassetmigrate.application.AssetManager.TargetProfile;
import io.github.aiassetmigrate.application.BlockerFixes;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Scores the rules alone against the rules plus AI on {@link BenchmarkSample} (AI plan, step 1). Everything runs
 * through the real facade in a fresh workspace, exactly as the GUI would: the same analyses, Fix blockers and AI plan
 * suggestions. "Rules + AI" is what a user gets with AI switched on: the AI's suggestion where it made one (it wins over
 * a disagreeing rule, as Accept all does), the rules' otherwise, and for plan steps both Fix blockers and the checked AI
 * suggestions. Nothing is approved or migrated.
 */
public final class AiBenchmark {
    private AiBenchmark() {}

    public static final List<TargetProfile> TARGETS = List.of(new TargetProfile("godot", "4.3"), new TargetProfile("unity", "6000.0"));

    /** Right and wrong answers; {@code neutral} counts acceptable plan steps that are neither expected nor wrong. */
    public record Count(int right, int wrong, int neutral) {}

    /** One measure: the size of its answer key, the rules' score and, when an AI was used, the combined score. */
    public record Measure(String name, int key, Count rules, Optional<Count> withAi) {
        public Measure {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(rules, "rules");
            Objects.requireNonNull(withAi, "withAi");
        }
    }

    /** The scores, plus notes such as AI failures, so a bad run is never mistaken for a measurement. */
    public record Result(int files, Optional<String> model, List<Measure> measures, List<String> notes) {
        public Result {
            measures = List.copyOf(measures);
            notes = List.copyOf(notes);
        }

        public String table() {
            StringBuilder text = new StringBuilder("AI benchmark: ").append(files).append(" files with known right answers; ")
                    .append(model.map(m -> "rules alone vs rules + AI (" + m + ")").orElse("rules alone (add --ai to compare)"))
                    .append(System.lineSeparator());
            text.append(String.format("%-40s %4s  %-18s %s%n", "Measure", "Key", "Rules", model.isPresent() ? "Rules + AI" : ""));
            for (Measure measure : measures) {
                text.append(String.format("%-40s %4d  %-18s %s%n", measure.name(), measure.key(), cell(measure.rules()),
                        measure.withAi().map(AiBenchmark::cell).orElse("")));
            }
            notes.forEach(note -> text.append("Note: ").append(note).append(System.lineSeparator()));
            return text.toString();
        }
    }

    private static String cell(Count count) {
        return count.right() + " (" + count.wrong() + " wrong" + (count.neutral() > 0 ? ", " + count.neutral() + " neutral" : "") + ")";
    }

    /** Writes the sample under {@code workspace} (which must be empty or missing) and scores it. */
    public static Result run(Path workspace, Optional<LLMClient> assistant) throws IOException {
        Objects.requireNonNull(assistant, "assistant");
        Path source = workspace.resolve("source");
        BenchmarkSample.write(source);
        Path output = Files.createDirectories(workspace.resolve("output"));
        List<Measure> measures = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (DefaultAssetManager manager = new DefaultAssetManager(Files.createDirectories(workspace.resolve("state")))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            InventorySnapshot inventory = manager.loadInventory(project);
            Map<UUID, String> paths = new HashMap<>();
            Map<String, Asset> byPath = new HashMap<>();
            inventory.project().assets().forEach(asset -> {
                paths.put(asset.id(), PortablePath.of(asset.relativePath()));
                byPath.put(PortablePath.of(asset.relativePath()), asset);
            });

            measures.add(categories(manager, project, paths, assistant, notes));
            AnalysisResult rules = manager.analyze(project, AnalysisKind.RELATIONSHIPS);
            Optional<AnalysisResult> ai = assistant.map(model -> note(manager.analyze(project, AnalysisKind.RELATIONSHIPS, model), "relationships", notes));
            measures.add(corrections(rules, ai, paths));
            measures.add(groupings(rules, ai, paths, byPath));
            measures.add(names(manager, project, paths, assistant, notes));
            for (TargetProfile target : TARGETS) {
                Path folder = Files.createDirectories(output.resolve(target.id()));
                MigrationPlan plan = manager.createPlan(project, target, folder);
                List<BlockerFixes.Fix> fromRules = manager.proposeBlockerFixes(project, plan, Optional.empty()).fixes().stream()
                        .filter(BlockerFixes.Fix::recommended).toList();
                Optional<List<BlockerFixes.Fix>> fromAi = assistant.map(model -> {
                    var suggestions = manager.suggestPlanSteps(project, target, model);
                    suggestions.findings().stream().filter(f -> f.code().equals("AI_UNAVAILABLE") || f.code().equals("AI_OUTPUT_REJECTED"))
                            .forEach(f -> notes.add("plan " + target.id() + ": " + f.message()));
                    suggestions.proposal().unfixable().forEach(line -> notes.add("plan " + target.id() + " dropped by checks: " + line));
                    return suggestions.proposal().fixes();
                });
                measures.add(planSteps(target, fromRules, fromAi, paths));
            }
        }
        return new Result(BenchmarkSample.fileCount(), assistant.map(LLMClient::description), measures, notes);
    }

    private static AnalysisResult note(AnalysisResult result, String what, List<String> notes) {
        for (Finding finding : result.findings()) {
            if (finding.code().equals("AI_UNAVAILABLE") || finding.code().equals("AI_OUTPUT_REJECTED")) notes.add(what + ": " + finding.message());
        }
        return result;
    }

    /** The value a user ends up with per asset: the AI's single suggestion if it made one, else the rules'. */
    private static Map<UUID, String> chosen(AnalysisResult rules, Optional<AnalysisResult> ai) {
        Map<UUID, String> values = new HashMap<>();
        for (Suggestion suggestion : rules.suggestions()) values.putIfAbsent(suggestion.assetId(), suggestion.proposedValue());
        ai.ifPresent(result -> {
            Map<UUID, Set<String>> fromAi = new HashMap<>();
            result.suggestions().stream().filter(s -> s.source() == Source.AI)
                    .forEach(s -> fromAi.computeIfAbsent(s.assetId(), k -> new LinkedHashSet<>()).add(s.proposedValue()));
            fromAi.forEach((asset, proposed) -> { if (proposed.size() == 1) values.put(asset, proposed.iterator().next()); });
        });
        return values;
    }

    private static Measure categories(AssetManager manager, UUID project, Map<UUID, String> paths,
                                      Optional<LLMClient> assistant, List<String> notes) {
        AnalysisResult rules = manager.analyze(project, AnalysisKind.CATEGORIES);
        Optional<AnalysisResult> ai = assistant.map(model -> note(manager.analyze(project, AnalysisKind.CATEGORIES, model), "categories", notes));
        return new Measure("Categories right", BenchmarkSample.CATEGORIES.size(), categoryScore(chosen(rules, Optional.empty()), paths),
                ai.map(result -> categoryScore(chosen(rules, Optional.of(result)), paths)));
    }

    private static Count categoryScore(Map<UUID, String> values, Map<UUID, String> paths) {
        int right = 0, wrong = 0;
        for (var entry : values.entrySet()) {
            Set<String> key = BenchmarkSample.CATEGORIES.get(paths.get(entry.getKey()));
            if (key == null) continue;
            if (key.contains(entry.getValue())) right++; else wrong++;
        }
        return new Count(right, wrong, 0);
    }

    /** For each broken reference: the AI's single target if it chose one, else the rules' single candidate, else none. */
    private static Measure corrections(AnalysisResult rules, Optional<AnalysisResult> ai, Map<UUID, String> paths) {
        return new Measure("Broken references fixed right", BenchmarkSample.CORRECTIONS.size(),
                correctionScore(rules, Optional.empty(), paths), ai.map(result -> correctionScore(rules, Optional.of(result), paths)));
    }

    private static Count correctionScore(AnalysisResult rules, Optional<AnalysisResult> ai, Map<UUID, String> paths) {
        int right = 0, wrong = 0;
        for (BenchmarkSample.Correction correction : BenchmarkSample.CORRECTIONS) {
            Set<String> fromAi = ai.map(result -> targets(result, Source.AI, correction, paths)).orElse(Set.of());
            Set<String> fromRules = targets(rules, Source.RULES, correction, paths);
            Set<String> chosen = fromAi.size() == 1 ? fromAi : fromRules.size() == 1 ? fromRules : Set.of();
            if (chosen.isEmpty()) continue;
            if (chosen.contains(correction.target())) right++; else wrong++;
        }
        return new Count(right, wrong, 0);
    }

    private static Set<String> targets(AnalysisResult result, Source source, BenchmarkSample.Correction correction, Map<UUID, String> paths) {
        Set<String> targets = new LinkedHashSet<>();
        for (Suggestion suggestion : result.suggestions()) {
            if (suggestion.source() != source || !correction.owner().equals(paths.get(suggestion.assetId()))
                    || !suggestion.reference().map(correction.reference()::equals).orElse(false)) continue;
            try { targets.add(paths.getOrDefault(UUID.fromString(suggestion.proposedValue()), "?")); }
            catch (IllegalArgumentException notAnAsset) { targets.add("?"); }
        }
        return targets;
    }

    /** Groupings of unreferenced textures: suggestions without a reference whose target is a texture. */
    private static Measure groupings(AnalysisResult rules, Optional<AnalysisResult> ai, Map<UUID, String> paths, Map<String, Asset> byPath) {
        return new Measure("Unreferenced textures grouped right", BenchmarkSample.GROUPINGS.size(),
                groupingScore(List.of(rules), paths, byPath), ai.map(result -> groupingScore(List.of(rules, result), paths, byPath)));
    }

    private static Count groupingScore(List<AnalysisResult> results, Map<UUID, String> paths, Map<String, Asset> byPath) {
        Set<BenchmarkSample.Grouping> found = new LinkedHashSet<>();
        for (AnalysisResult result : results) {
            for (Suggestion suggestion : result.suggestions()) {
                if (suggestion.reference().isPresent()) continue;
                String target;
                try { target = paths.get(UUID.fromString(suggestion.proposedValue())); }
                catch (IllegalArgumentException notAnAsset) { continue; }
                if (target == null || byPath.get(target).type() != Asset.Type.TEXTURE) continue;
                found.add(new BenchmarkSample.Grouping(paths.get(suggestion.assetId()), target));
            }
        }
        int right = (int) found.stream().filter(BenchmarkSample.GROUPINGS::contains).count();
        int neutral = (int) found.stream().filter(BenchmarkSample.ACCEPTABLE_GROUPINGS::contains).count();
        return new Count(right, found.size() - right - neutral, neutral);
    }

    private static Measure names(AssetManager manager, UUID project, Map<UUID, String> paths,
                                 Optional<LLMClient> assistant, List<String> notes) {
        AnalysisResult rules = manager.analyze(project, AnalysisKind.NAMES);
        Optional<AnalysisResult> ai = assistant.map(model -> note(manager.analyze(project, AnalysisKind.NAMES, model), "names", notes));
        return new Measure("Names that match the key", BenchmarkSample.NAMES.size(), nameScore(chosen(rules, Optional.empty()), paths),
                ai.map(result -> nameScore(chosen(rules, Optional.of(result)), paths)));
    }

    private static Count nameScore(Map<UUID, String> values, Map<UUID, String> paths) {
        int right = 0, wrong = 0;
        for (var entry : paths.entrySet()) {
            var pattern = BenchmarkSample.NAMES.get(entry.getValue());
            if (pattern == null) continue;
            String current = entry.getValue().substring(entry.getValue().lastIndexOf('/') + 1);
            if (pattern.matcher(values.getOrDefault(entry.getKey(), current)).matches()) right++; else wrong++;
        }
        return new Count(right, wrong, 0);
    }

    private static Measure planSteps(TargetProfile target, List<BlockerFixes.Fix> rules, Optional<List<BlockerFixes.Fix>> ai,
                                     Map<UUID, String> paths) {
        Set<String> fromRules = steps(rules, paths);
        return new Measure("Plan steps for " + target.id() + " " + target.version(), BenchmarkSample.expectedSteps().size(),
                planScore(target, fromRules), ai.map(fixes -> {
                    Set<String> both = new LinkedHashSet<>(fromRules);
                    both.addAll(steps(fixes, paths));
                    return planScore(target, both);
                }));
    }

    /** Each decision of the fixes as a comparable step, such as "CONVERT props/barrel.fbx glb". */
    static Set<String> steps(List<BlockerFixes.Fix> fixes, Map<UUID, String> paths) {
        Set<String> steps = new LinkedHashSet<>();
        for (BlockerFixes.Fix fix : fixes) {
            for (ReviewedChange change : fix.changes()) {
                if (change.decision() != ReviewedChange.Decision.ACCEPTED) continue;
                String path = paths.getOrDefault(change.assetId(), "?");
                switch (change.kind()) {
                    case EXCLUSION -> steps.add(BenchmarkSample.exclude(path));
                    case CONVERSION -> steps.add(BenchmarkSample.convert(path, change.values().get("format")));
                    case RELATIONSHIP, MATERIAL_MAPPING -> {
                        String reference = change.values().get("reference");
                        if (reference == null) continue;
                        String target;
                        try { target = paths.getOrDefault(UUID.fromString(change.values().get("targetAssetId")), "?"); }
                        catch (IllegalArgumentException invalid) { target = "?"; }
                        steps.add(BenchmarkSample.fix(path, reference, target));
                    }
                    default -> steps.add(change.kind() + " " + path);
                }
            }
        }
        return steps;
    }

    private static Count planScore(TargetProfile target, Set<String> steps) {
        int right = 0, wrong = 0, neutral = 0;
        for (String step : steps) {
            switch (BenchmarkSample.judge(target, step)) {
                case EXPECTED -> right++;
                case ACCEPTABLE -> neutral++;
                case WRONG -> wrong++;
            }
        }
        return new Count(right, wrong, neutral);
    }
}
