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
import io.github.aiassetmigrate.domain.StepStatus;

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
 */
final class CommandDispatcher {
    private static final int MAX_LISTED = 10;
    private final AssetManager manager;

    CommandDispatcher(AssetManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
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
