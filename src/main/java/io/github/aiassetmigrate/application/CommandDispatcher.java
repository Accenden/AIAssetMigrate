package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.ai.AIEngine;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.PlanStatus;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.domain.StepStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F09 dispatcher: performs one validated intent through the {@link AssetManager} facade, the same
 * operations the explicit controls use. It has no path to approve, migrate or review; parameters
 * from the model are untrusted and checked here, and a missing or invalid one becomes a
 * clarification instead of a guess. Operation failures are returned, not thrown.
 *
 * <p>PREPARE makes the agent the operator (Agent.md section 1.1): the model chooses read-only tools in
 * sequence through {@link AIEngine#operate}, and what they find becomes one bundle of proposed decisions,
 * checked by the program, which the user confirms before anything is saved.
 */
final class CommandDispatcher {
    private static final int MAX_LISTED = 10;
    private static final int MAX_OBSERVED = 40;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final AssetManager manager;
    private final io.github.aiassetmigrate.ai.LLMClient assistant;
    private final String request;

    CommandDispatcher(AssetManager manager, io.github.aiassetmigrate.ai.LLMClient assistant, String request) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.request = Objects.requireNonNull(request, "request");
    }

    AssetManager.CommandResult dispatch(UUID project, AIEngine.Intent intent, List<Finding> interpretation) {
        List<Finding> findings = new ArrayList<>(interpretation);
        try {
            return switch (intent.action()) {
                case SHOW_INVENTORY -> showInventory(project, findings);
                case LIST_TARGETS -> result("Supported targets: " + manager.supportedTargets().stream()
                        .map(t -> t.id() + " " + t.version()).collect(Collectors.joining(", ")) + ".", findings);
                case ANALYZE -> analyze(project, intent, findings);
                case CHECK_COMPATIBILITY -> compatibility(project, intent, findings);
                case PREVIEW_ORGANIZATION -> preview(project, findings);
                case CREATE_PLAN -> createPlan(project, intent, findings);
                case SHOW_PLAN -> showPlan(project, intent, findings);
                case SHOW_RUN -> showRun(project, intent, findings);
                case VALIDATE -> validate(project, intent, findings);
                case HISTORY -> history(project, findings);
                case PREPARE -> prepare(project, intent, findings);
                case CLARIFY -> clarify(intent.message(), findings);
                case UNSUPPORTED -> {
                    findings.add(finding(Finding.Severity.WARNING, "COMMAND_UNSUPPORTED", "No action was taken"));
                    yield result("Not supported: " + intent.message() + " Approving plans, running migrations and saving"
                            + " reviews require the explicit controls.", findings);
                }
            };
        } catch (RuntimeException e) {
            findings.add(finding(Finding.Severity.ERROR, "COMMAND_FAILED", intent.action() + " failed: " + message(e)));
            return result(intent.action() + " failed: " + message(e), findings);
        }
    }

    private AssetManager.CommandResult showInventory(UUID project, List<Finding> findings) {
        InventorySnapshot inventory = manager.loadInventory(project);
        Map<Asset.Type, Integer> types = new EnumMap<>(Asset.Type.class);
        inventory.project().assets().forEach(asset -> types.merge(asset.type(), 1, Integer::sum));
        findings.addAll(inventory.findings());
        return result("Inventory revision " + inventory.project().inventoryRevision() + " (scan " + inventory.scanStatus()
                + ", coverage " + (inventory.hasCompleteCoverage() ? "complete" : "partial") + "): "
                + inventory.project().assets().size() + " asset(s)" + (types.isEmpty() ? "" : " - " + types.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "))) + "; "
                + inventory.findings().size() + " scan finding(s).", findings);
    }

    private AssetManager.CommandResult analyze(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        String kindText = intent.parameters().get("kind");
        AssetManager.AnalysisKind kind;
        try { kind = kindText == null ? null : AssetManager.AnalysisKind.valueOf(kindText.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { kind = null; }
        if (kind == null) return clarify("Which analysis: categories, relationships, materials, names or folders?", findings);
        var result = manager.analyze(project, kind);
        Map<UUID, String> paths = paths(project, result.inventoryRevision());
        findings.addAll(result.findings());
        StringBuilder text = new StringBuilder("Analysis ").append(kind).append(" for inventory revision ")
                .append(result.inventoryRevision()).append(": ").append(result.suggestions().size())
                .append(" suggestion(s). Suggestions are not applied; review them explicitly.");
        result.suggestions().stream().limit(MAX_LISTED).forEach(s -> text.append("\n  ")
                .append(paths.getOrDefault(s.assetId(), s.assetId().toString())).append(": ").append(s.proposedValue())
                .append(s.reference().map(r -> " (reference '" + r + "')").orElse("")));
        if (result.suggestions().size() > MAX_LISTED) text.append("\n  ...and ").append(result.suggestions().size() - MAX_LISTED).append(" more");
        return result(text.toString(), findings);
    }

    private AssetManager.CommandResult compatibility(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<AssetManager.TargetProfile> target = target(intent);
        if (target.isEmpty()) return clarify("Which target? Supported: " + targets() + ".", findings);
        var result = manager.assessCompatibility(project, target.get());
        findings.addAll(result.findings());
        Map<Finding.Severity, Long> counts = result.findings().stream()
                .collect(Collectors.groupingBy(Finding::severity, () -> new EnumMap<>(Finding.Severity.class), Collectors.counting()));
        return result("Compatibility with " + target.get().id() + " " + target.get().version() + " for inventory revision "
                + result.inventoryRevision() + ": " + counts.getOrDefault(Finding.Severity.ERROR, 0L) + " error(s), "
                + counts.getOrDefault(Finding.Severity.WARNING, 0L) + " warning(s), "
                + counts.getOrDefault(Finding.Severity.UNKNOWN, 0L) + " unknown. This is not validation or approval.", findings);
    }

    private AssetManager.CommandResult preview(UUID project, List<Finding> findings) {
        long revision = manager.loadInventory(project).project().inventoryRevision();
        var preview = manager.previewOrganization(project, revision);
        Map<UUID, String> paths = paths(project, revision);
        for (var issue : preview.findings()) {
            findings.add(new Finding(UUID.randomUUID(), issue.assetId(), Optional.empty(), Finding.Severity.ERROR, issue.code(),
                    issue.assetId().map(paths::get).map(p -> p + ": ").orElse("") + issue.message()));
        }
        long excluded = preview.entries().stream().filter(entry -> entry.excluded()).count();
        StringBuilder text = new StringBuilder("Organization preview for revision ").append(revision).append(": ")
                .append(preview.entries().size() - excluded).append(" output(s), ").append(excluded).append(" excluded; ")
                .append(preview.blocked() ? preview.findings().size() + " blocking issue(s)." : "no blocking issues.");
        preview.entries().stream().filter(e -> !e.excluded() && e.destination().isPresent()).limit(MAX_LISTED)
                .forEach(e -> text.append("\n  ").append(PortablePath.of(e.source())).append(" -> ").append(e.destination().get()));
        return result(text.toString(), findings);
    }

    private AssetManager.CommandResult createPlan(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<AssetManager.TargetProfile> target = target(intent);
        if (target.isEmpty()) return clarify("Which target should the plan use? Supported: " + targets() + ".", findings);
        String outputText = intent.parameters().get("output");
        Path output = null;
        try { if (outputText != null) output = Path.of(outputText); } catch (InvalidPathException e) { output = null; }
        if (output == null || !output.isAbsolute()) {
            return clarify("Which output folder? Give an absolute path to an existing folder outside the source.", findings);
        }
        MigrationPlan plan = manager.createPlan(project, target.get(), output);
        findings.addAll(plan.findings());
        long blocking = plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR
                || f.severity() == Finding.Severity.UNKNOWN).count();
        return new AssetManager.CommandResult("Draft plan " + plan.id() + " version " + plan.version() + " created with "
                + plan.commands().size() + " command(s) and " + blocking + " blocking finding(s). It is NOT approved: review it"
                + " (show-plan) and approve it explicitly before migrating.", Optional.of(plan), findings);
    }

    private AssetManager.CommandResult showPlan(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<UUID> planId = uuid(intent.parameters().get("planId"));
        if (planId.isEmpty()) return clarify("Which plan ID?", findings);
        MigrationPlan plan = manager.loadPlan(planId.get());
        if (!plan.projectId().equals(project)) return otherProject("Plan", findings);
        findings.addAll(plan.findings());
        return new AssetManager.CommandResult("Plan " + plan.id() + " version " + plan.version() + ": " + plan.status()
                + ", target " + plan.targetProfile() + " " + plan.targetProfileVersion() + ", " + plan.commands().size()
                + " command(s), output " + plan.outputRoot() + ".",
                plan.status() == PlanStatus.DRAFT ? Optional.of(plan) : Optional.empty(), findings);
    }

    private AssetManager.CommandResult showRun(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<UUID> planId = uuid(intent.parameters().get("planId"));
        if (planId.isEmpty()) return clarify("Which plan ID? Runs are looked up by their plan.", findings);
        MigrationRun run = manager.loadRun(planId.get());
        if (!run.projectId().equals(project)) return otherProject("Run", findings);
        Map<StepStatus, Long> steps = run.stepStatuses().values().stream()
                .collect(Collectors.groupingBy(s -> s, () -> new EnumMap<>(StepStatus.class), Collectors.counting()));
        return result("Run " + run.id() + " for plan " + run.planId() + ": " + run.status() + "; steps " + steps
                + run.message().map(m -> "; " + m).orElse("") + ".", findings);
    }

    private AssetManager.CommandResult validate(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<UUID> runId = uuid(intent.parameters().get("runId"));
        Optional<UUID> planId = uuid(intent.parameters().get("planId"));
        if (runId.isEmpty() && planId.isEmpty()) return clarify("Which run or plan should be validated?", findings);
        if (runId.isEmpty()) {
            MigrationRun run = manager.loadRun(planId.get());
            if (!run.projectId().equals(project)) return otherProject("Run", findings);
            runId = Optional.of(run.id());
        }
        Report report = manager.validate(runId.get());
        findings.addAll(report.findings());
        if (!report.projectId().equals(project)) {
            findings.add(finding(Finding.Severity.WARNING, "COMMAND_OTHER_PROJECT", "The validated run belongs to project " + report.projectId()));
        }
        return result("Validation " + report.validationStatus() + "; report " + report.id() + " saved. " + report.summary(), findings);
    }

    private AssetManager.CommandResult history(UUID project, List<Finding> findings) {
        List<Report> reports = manager.history(project);
        if (reports.isEmpty()) return result("No validation history for this project.", findings);
        StringBuilder text = new StringBuilder(reports.size() + " validation report(s), most recent first:");
        reports.reversed().stream().limit(MAX_LISTED).forEach(r -> text.append("\n  ").append(r.createdAt()).append(' ')
                .append(r.validationStatus()).append(" run ").append(r.runId()).append(" (report ").append(r.id()).append(')'));
        return result(text.toString(), findings);
    }

    // ---------------------------------------------------------------- PREPARE: the agent as operator

    /** What the operator's tools gathered for the bundle. */
    private static final class Gathered {
        final Map<AssetManager.AnalysisKind, AssetManager.AnalysisResult> analyses = new java.util.EnumMap<>(AssetManager.AnalysisKind.class);
        AssetManager.PlanSuggestions plan;
    }

    private AssetManager.CommandResult prepare(UUID project, AIEngine.Intent intent, List<Finding> findings) {
        Optional<AssetManager.TargetProfile> target = target(intent);
        if (target.isEmpty()) return clarify("Which engine should the project be prepared for? Supported: " + targets() + ".", findings);
        Path output = null;
        String outputText = intent.parameters().get("output");
        try { if (outputText != null) output = Path.of(outputText); } catch (InvalidPathException e) { output = null; }
        if (outputText != null && (output == null || !output.isAbsolute())) {
            return clarify("Which output folder? Give an absolute path to an existing folder outside the source, or leave it out.", findings);
        }
        InventorySnapshot inventory = manager.loadInventory(project);
        long revision = inventory.project().inventoryRevision();
        Map<UUID, String> paths = new HashMap<>();
        inventory.project().assets().forEach(asset -> paths.put(asset.id(), PortablePath.of(asset.relativePath())));
        Gathered gathered = new Gathered();
        AIEngine.Toolbox tools = (tool, parameters) -> {
            try {
                return observe(project, target.get(), revision, tool, parameters, paths, gathered);
            } catch (RuntimeException e) {
                findings.add(finding(Finding.Severity.WARNING, "COMMAND_FAILED", tool + " failed: " + message(e)));
                throw e;
            }
        };
        AIEngine.Operation operation = new AIEngine(assistant).operate(request, target.get(), tools);
        findings.addAll(operation.findings());

        // Target-aware plan suggestions first, so an analysis suggestion for the same decision is the one dropped.
        List<BlockerFixes.Fix> candidates = new ArrayList<>();
        if (gathered.plan != null) {
            candidates.addAll(gathered.plan.proposal().fixes());
            findings.addAll(gathered.plan.findings());
        }
        candidates.addAll(fromAnalyses(gathered.analyses, paths));
        BlockerFixes.Proposal decisions = candidates.isEmpty()
                ? new BlockerFixes.Proposal(project, revision, List.of(), List.of())
                : manager.checkDecisions(project, revision, target, candidates);
        List<String> dropped = new ArrayList<>(decisions.unfixable());
        if (gathered.plan != null) dropped.addAll(0, gathered.plan.proposal().unfixable());
        decisions = new BlockerFixes.Proposal(project, revision, decisions.fixes(), dropped);

        StringBuilder text = new StringBuilder("Prepared for ").append(target.get().id()).append(' ').append(target.get().version())
                .append(": the agent ran ").append(operation.steps().isEmpty() ? "no tools" : operation.steps().stream()
                        .map(step -> step.tool().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + (step.parameters().containsKey("kind") ? " " + step.parameters().get("kind").toLowerCase(Locale.ROOT) : "")
                                + (step.ran() ? "" : " (not run)"))
                        .collect(Collectors.joining(", "))).append('.');
        operation.message().ifPresent(note -> text.append("\nAgent: ").append(note));
        if (decisions.fixes().isEmpty()) {
            text.append("\nNo decisions to propose").append(dropped.isEmpty() ? "." : "; " + dropped.size() + " suggestion(s) failed the checks.");
        } else {
            text.append("\n").append(decisions.fixes().size()).append(" proposed decision(s), checked by the program. Nothing is saved")
                    .append(" until you confirm them:");
            decisions.fixes().stream().limit(MAX_LISTED).forEach(fix -> text.append("\n  ")
                    .append(fix.source() == BlockerFixes.Source.AI ? "[AI] " : "[rules] ").append(fix.description()));
            if (decisions.fixes().size() > MAX_LISTED) text.append("\n  ...and ").append(decisions.fixes().size() - MAX_LISTED).append(" more");
            if (!dropped.isEmpty()) text.append("\n").append(dropped.size()).append(" other suggestion(s) failed the checks and are not offered.");
        }
        Optional<MigrationPlan> draft = Optional.empty();
        if (output != null) {
            try {
                MigrationPlan plan = manager.createPlan(project, target.get(), output);
                draft = Optional.of(plan);
                long blocking = plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR
                        || f.severity() == Finding.Severity.UNKNOWN).count();
                text.append("\nDraft plan ").append(plan.id()).append(" (version ").append(plan.version()).append(", ")
                        .append(blocking).append(" blocking finding(s)) shows the project as it is now");
                text.append(decisions.fixes().isEmpty() ? "." : "; confirming the decisions creates a new plan.");
            } catch (RuntimeException e) {
                findings.add(finding(Finding.Severity.WARNING, "COMMAND_FAILED", "Draft plan failed: " + message(e)));
            }
        }
        text.append("\nNothing was approved or migrated.");
        return new AssetManager.CommandResult(text.toString(), draft, findings, Optional.of(decisions));
    }

    /** Runs one operator tool and describes the result with relative paths, counts and codes only. */
    private JsonNode observe(UUID project, AssetManager.TargetProfile target, long revision, AIEngine.Tool tool,
                             Map<String, String> parameters, Map<UUID, String> paths, Gathered gathered) {
        ObjectNode result = JSON.createObjectNode();
        switch (tool) {
            case INVENTORY -> {
                InventorySnapshot inventory = manager.loadInventory(project);
                requireRevision(inventory.project().inventoryRevision(), revision);
                result.put("assets", inventory.project().assets().size()).put("scan", inventory.scanStatus().name())
                        .put("coverage", inventory.hasCompleteCoverage() ? "complete" : "partial");
                ObjectNode types = result.putObject("byType");
                ObjectNode formats = result.putObject("byFormat");
                inventory.project().assets().forEach(asset -> {
                    types.put(asset.type().name(), types.path(asset.type().name()).asInt() + 1);
                    String format = asset.metadata().getOrDefault(Asset.FORMAT, "unknown");
                    formats.put(format, formats.path(format).asInt() + 1);
                });
                codes(result.putObject("scanFindings"), inventory.findings());
            }
            case ANALYZE -> {
                AssetManager.AnalysisKind kind = AssetManager.AnalysisKind.valueOf(parameters.get("kind"));
                AssetManager.AnalysisResult analysis = manager.analyze(project, kind, assistant);
                requireRevision(analysis.inventoryRevision(), revision);
                gathered.analyses.put(kind, analysis);
                result.put("kind", kind.name()).put("suggestions", analysis.suggestions().size())
                        .put("fromAi", analysis.suggestions().stream().filter(s -> s.source() == AssetManager.Source.AI).count());
                ArrayNode items = result.putArray("items");
                for (AssetManager.Suggestion suggestion : analysis.suggestions()) {
                    if (items.size() >= MAX_OBSERVED) break;
                    ObjectNode item = items.addObject().put("path", paths.getOrDefault(suggestion.assetId(), "?"))
                            .put("source", suggestion.source().name());
                    boolean link = kind == AssetManager.AnalysisKind.RELATIONSHIPS || kind == AssetManager.AnalysisKind.MATERIALS;
                    item.put("value", link ? uuid(suggestion.proposedValue()).map(paths::get).orElse("?") : suggestion.proposedValue());
                    suggestion.reference().ifPresent(reference -> item.put("reference", reference));
                }
                codes(result.putObject("findings"), analysis.findings());
            }
            case CHECK_COMPATIBILITY -> {
                AssetManager.CompatibilityResult compatibility = manager.assessCompatibility(project, target);
                requireRevision(compatibility.inventoryRevision(), revision);
                Map<String, List<Finding>> byCode = new java.util.TreeMap<>();
                compatibility.findings().stream().filter(f -> f.severity() != Finding.Severity.INFO && !f.code().equals("CONTENT_UNVERIFIED"))
                        .forEach(f -> byCode.computeIfAbsent(f.code(), c -> new ArrayList<>()).add(f));
                ArrayNode issues = result.putArray("issues");
                byCode.forEach((code, list) -> {
                    ObjectNode issue = issues.addObject().put("code", code).put("severity", list.getFirst().severity().name())
                            .put("count", list.size());
                    ArrayNode examples = issue.putArray("examplePaths");
                    list.stream().flatMap(f -> f.sourceRelativePath().stream()).map(PortablePath::of).distinct().limit(3).forEach(examples::add);
                });
            }
            case PREVIEW_ORGANIZATION -> {
                var preview = manager.previewOrganization(project, revision);
                long excluded = preview.entries().stream().filter(e -> e.excluded()).count();
                result.put("outputs", preview.entries().size() - excluded).put("leftOut", excluded).put("blocked", preview.blocked());
                ArrayNode blockers = result.putArray("blockers");
                preview.findings().stream().limit(MAX_OBSERVED).forEach(f -> blockers.addObject().put("code", f.code())
                        .put("path", f.assetId().map(paths::get).orElse("")));
            }
            case SUGGEST_PLAN_STEPS -> {
                AssetManager.PlanSuggestions plan = manager.suggestPlanSteps(project, target, assistant);
                requireRevision(plan.proposal().inventoryRevision(), revision);
                gathered.plan = plan;
                ArrayNode passed = result.putArray("passedChecks");
                plan.proposal().fixes().stream().limit(MAX_OBSERVED).forEach(fix -> passed.add(fix.description()));
                ArrayNode failed = result.putArray("failedChecks");
                plan.proposal().unfixable().stream().limit(MAX_OBSERVED).forEach(failed::add);
            }
            case FINISH -> throw new IllegalArgumentException("FINISH is not a tool");
        }
        return result;
    }

    private static void requireRevision(long actual, long expected) {
        if (actual != expected) throw new IllegalStateException("The project was rescanned while the agent worked; ask again");
    }

    private static void codes(ObjectNode node, List<Finding> findings) {
        findings.forEach(f -> node.put(f.code(), node.path(f.code()).asInt() + 1));
    }

    /**
     * Proposed decisions from the analyses the operator ran. Where the AI and the rules disagree the AI's suggestion
     * is taken (it was asked exactly where the rules are unsure); a tie between suggestions of the same source is left
     * out. Categories come from the AI only (the rules' are shown in Review), and only corrections of broken references
     * are proposed (the program drops any for a reference that already resolves).
     */
    private static List<BlockerFixes.Fix> fromAnalyses(Map<AssetManager.AnalysisKind, AssetManager.AnalysisResult> analyses,
                                                       Map<UUID, String> paths) {
        List<BlockerFixes.Fix> fixes = new ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        analyses.forEach((kind, analysis) -> {
            boolean link = kind == AssetManager.AnalysisKind.RELATIONSHIPS || kind == AssetManager.AnalysisKind.MATERIALS;
            Map<String, List<AssetManager.Suggestion>> slots = new java.util.LinkedHashMap<>();
            for (AssetManager.Suggestion suggestion : analysis.suggestions()) {
                if (link && suggestion.reference().isEmpty()) continue;
                if (kind == AssetManager.AnalysisKind.CATEGORIES && suggestion.source() != AssetManager.Source.AI) continue;
                slots.computeIfAbsent(suggestion.assetId() + "|" + suggestion.reference().orElse(""), k -> new ArrayList<>()).add(suggestion);
            }
            for (List<AssetManager.Suggestion> group : slots.values()) {
                List<AssetManager.Suggestion> ai = group.stream().filter(s -> s.source() == AssetManager.Source.AI).toList();
                List<AssetManager.Suggestion> chosen = ai.isEmpty() ? group : ai;
                if (chosen.stream().map(AssetManager.Suggestion::proposedValue).distinct().count() != 1) continue;
                AssetManager.Suggestion pick = chosen.getFirst();
                if (pick.proposedValue().equals(io.github.aiassetmigrate.analysis.CategoryAnalyzer.UNCATEGORIZED)) continue;
                String path = paths.getOrDefault(pick.assetId(), pick.assetId().toString());
                String why = pick.reason().map(r -> " (AI: " + r + ")").orElse(pick.source() == AssetManager.Source.AI ? " (suggested by AI)" : "");
                ReviewedChange.Kind reviewKind;
                Map<String, String> values;
                String description;
                switch (kind) {
                    case CATEGORIES -> {
                        reviewKind = ReviewedChange.Kind.CATEGORY;
                        values = Map.of("category", pick.proposedValue());
                        description = "Categorize " + path + " as " + pick.proposedValue();
                    }
                    case NAMES -> {
                        reviewKind = ReviewedChange.Kind.NAME;
                        values = Map.of("name", pick.proposedValue());
                        description = "Rename " + path + " to " + pick.proposedValue();
                    }
                    case FOLDERS -> {
                        reviewKind = ReviewedChange.Kind.FOLDER;
                        values = Map.of("folder", pick.proposedValue());
                        description = "Put " + path + " in the folder '" + pick.proposedValue() + "'";
                    }
                    default -> {
                        Optional<UUID> target = uuid(pick.proposedValue());
                        if (target.isEmpty()) continue;
                        reviewKind = kind == AssetManager.AnalysisKind.MATERIALS ? ReviewedChange.Kind.MATERIAL_MAPPING : ReviewedChange.Kind.RELATIONSHIP;
                        values = Map.of("targetAssetId", target.get().toString(), "reference", pick.reference().orElseThrow());
                        description = "Point '" + pick.reference().get() + "' in " + path + " to " + paths.getOrDefault(target.get(), "?");
                    }
                }
                fixes.add(new BlockerFixes.Fix("AGENT_" + kind, Optional.of(pick.assetId()), description + why,
                        List.of(new ReviewedChange(UUID.randomUUID(), pick.assetId(), reviewKind, ReviewedChange.Decision.ACCEPTED, values, now)),
                        java.util.Set.of(), pick.source() == AssetManager.Source.AI ? BlockerFixes.Source.AI : BlockerFixes.Source.RULES, false));
            }
        });
        return fixes;
    }

    private AssetManager.CommandResult clarify(String question, List<Finding> findings) {
        findings.add(finding(Finding.Severity.INFO, "COMMAND_CLARIFICATION", "No action was taken; more information is needed"));
        return result(question, findings);
    }

    private AssetManager.CommandResult otherProject(String what, List<Finding> findings) {
        findings.add(finding(Finding.Severity.WARNING, "COMMAND_OTHER_PROJECT", what + " belongs to a different project"));
        return result(what + " belongs to a different project; nothing is shown. Select that project first.", findings);
    }

    private Optional<AssetManager.TargetProfile> target(AIEngine.Intent intent) {
        String id = intent.parameters().get("target"), version = intent.parameters().get("version");
        if (id == null || version == null) return Optional.empty();
        return manager.supportedTargets().stream()
                .filter(t -> t.id().equalsIgnoreCase(id) && t.version().equals(version)).findFirst();
    }

    private String targets() {
        return manager.supportedTargets().stream().map(t -> t.id() + " " + t.version()).collect(Collectors.joining(", "));
    }

    private Map<UUID, String> paths(UUID project, long revision) {
        Map<UUID, String> paths = new HashMap<>();
        manager.loadInventory(project, revision).project().assets()
                .forEach(asset -> paths.put(asset.id(), PortablePath.of(asset.relativePath())));
        return paths;
    }

    private static Optional<UUID> uuid(String text) {
        if (text == null) return Optional.empty();
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    private static AssetManager.CommandResult result(String message, List<Finding> findings) {
        return new AssetManager.CommandResult(message, Optional.empty(), findings);
    }

    private static Finding finding(Finding.Severity severity, String code, String message) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), severity, code, message);
    }

    private static String message(Exception e) {
        Throwable cause = e;
        String text = e.getMessage();
        while ((text == null || text.isBlank()) && cause.getCause() != null) { cause = cause.getCause(); text = cause.getMessage(); }
        return text == null ? e.getClass().getSimpleName() : text;
    }
}
