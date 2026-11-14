package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.analysis.OrganizationPreview;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared application boundary for CLI and GUI clients. Implementations are added
 * in subsequent milestones and injected into clients through constructors.
 * Methods may block and must not be called on the JavaFX application thread.
 * No implementation or execution authority is provided by this contract alone.
 * Import implementations receive their state directory, repository, scanner and
 * logger factory through constructor injection. Until supported, unrelated
 * operations must throw UnsupportedOperationException, not fabricate results.
 */
public interface AssetManager {
    /** Creates a new project. Recoverable file failures are returned in the snapshot. */
    ImportResult importAssets(Path sourceRoot) throws ImportException;

    /**
     * Rescans an existing project; sourceRoot must match its original root.
     * Preserves project identity and createdAt while advancing inventoryRevision.
     * Files left out in the previous revision stay left out.
     */
    ImportResult rescan(UUID projectId, Path sourceRoot) throws ImportException;

    /**
     * As {@link #rescan(UUID, Path)}, leaving out exactly these files (relative paths), replacing the previous
     * set; an empty set includes everything again. A left-out file is recorded as EXCLUDED, which counts as
     * complete coverage, and is never read, inventoried or migrated. A reference to it still blocks a plan,
     * so leaving out a file that a model or material needs cannot go unnoticed. Source files are not changed.
     */
    default ImportResult rescan(UUID projectId, Path sourceRoot, java.util.Set<Path> excludedFiles) throws ImportException {
        throw new UnsupportedOperationException("Leaving files out is not supported by this manager");
    }

    /** Loads the latest inventory without scanning or creating state. */
    InventorySnapshot loadInventory(UUID projectId) throws ImportException;

    /** Loads a positive, explicit inventory revision without scanning or creating state. */
    InventorySnapshot loadInventory(UUID projectId, long revision) throws ImportException;

    AnalysisResult analyze(UUID projectId, AnalysisKind kind);

    /**
     * Hybrid analysis: the rule-based result plus validated suggestions from the explicitly
     * supplied model. Only relative paths, asset types, formats and reference text are sent,
     * never file contents or absolute paths. If the model fails, the rule-based result is
     * returned with an AI_UNAVAILABLE finding. Suggestions are never accepted automatically.
     */
    default AnalysisResult analyze(UUID projectId, AnalysisKind kind, io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI-assisted analysis is not supported by this manager");
    }

    /** Suggests an organization from explicit rules; no decision or output is written. */
    AnalysisResult analyzeOrganization(UUID projectId, OrganizationRules rules);

    /** Saves explicit decisions only for the inventory revision the user reviewed. */
    List<ReviewedChange> review(UUID projectId, long expectedInventoryRevision, List<ReviewedChange> changes);

    /** Returns decisions for the current inventory, or an empty list if none are current. */
    List<ReviewedChange> loadReviews(UUID projectId);

    /** Read-only lexical output preview for an exact inventory revision, not approval to write. */
    OrganizationPreview.Result previewOrganization(UUID projectId, long expectedInventoryRevision);

    /**
     * Proposes quick fixes for the blocking findings of a draft plan (see {@link BlockerFixes}); nothing is
     * saved. With an assistant, the AI may choose the target of a broken reference the rules cannot settle;
     * such fixes are marked as AI and, like all fixes, apply only if the caller passes them back.
     */
    default BlockerFixes.Proposal proposeBlockerFixes(UUID projectId, MigrationPlan plan,
                                                      Optional<io.github.aiassetmigrate.ai.LLMClient> assistant) {
        Objects.requireNonNull(plan, "plan");
        InventorySnapshot inventory = loadInventory(projectId);
        long revision = inventory.project().inventoryRevision();
        if (!plan.projectId().equals(projectId) || plan.inventoryRevision() != revision) {
            throw new IllegalStateException("The project was rescanned after this plan was created; create the plan again");
        }
        AnalysisResult relationships = assistant.isPresent()
                ? analyze(projectId, AnalysisKind.RELATIONSHIPS, assistant.get())
                : analyze(projectId, AnalysisKind.RELATIONSHIPS);
        OrganizationPreview.Result preview = previewOrganization(projectId, revision);
        List<ReviewedChange> saved = loadReviews(projectId);
        if (loadInventory(projectId).project().inventoryRevision() != revision || relationships.inventoryRevision() != revision) {
            throw new IllegalStateException("The project changed while fixes were prepared; try again");
        }
        return BlockerFixes.propose(plan, inventory, saved, preview, relationships.suggestions(), java.time.Instant.now());
    }

    /**
     * Applies the chosen fixes for the given inventory revision: saves their decisions and, if any leave files
     * out, rescans first and carries the saved decisions over for files whose path and contents are unchanged.
     * Source files are never changed, no output is written and no plan is approved; create a new plan after.
     */
    default BlockerFixes.Applied applyBlockerFixes(UUID projectId, long inventoryRevision, List<BlockerFixes.Fix> fixes) {
        List<BlockerFixes.Fix> chosen = List.copyOf(fixes);
        if (chosen.isEmpty()) throw new IllegalArgumentException("Choose at least one fix");
        InventorySnapshot inventory = loadInventory(projectId);
        if (inventory.project().inventoryRevision() != inventoryRevision) {
            throw new IllegalStateException("The project was rescanned after these fixes were proposed; create the plan again");
        }
        List<ReviewedChange> changes = chosen.stream().flatMap(fix -> fix.changes().stream()).toList();
        java.util.Set<Path> leaveOut = new java.util.LinkedHashSet<>();
        chosen.forEach(fix -> leaveOut.addAll(fix.leaveOut()));
        if (leaveOut.isEmpty()) {
            review(projectId, inventoryRevision, changes);
            return new BlockerFixes.Applied(chosen.size(), changes.size(), 0, false, inventoryRevision);
        }
        List<ReviewedChange> before = loadReviews(projectId);
        java.util.Set<Path> excluded = new java.util.LinkedHashSet<>(inventory.excludedFiles());
        excluded.addAll(leaveOut);
        InventorySnapshot rescanned = rescan(projectId, inventory.project().sourceRoot(), excluded).snapshot();
        long revision = rescanned.project().inventoryRevision();
        var ids = BlockerFixes.carriedIds(inventory, rescanned);
        List<ReviewedChange> kept = BlockerFixes.carry(before, ids);
        List<ReviewedChange> added = BlockerFixes.carry(changes, ids);
        // Earlier decisions first, then the fixes, so a fix replaces the decision it corrects.
        if (!kept.isEmpty()) review(projectId, revision, kept);
        if (!added.isEmpty()) review(projectId, revision, added);
        return new BlockerFixes.Applied(chosen.size(), kept.size() + added.size(),
                before.size() - kept.size() + changes.size() - added.size(), true, revision);
    }

    List<Finding> checkCompatibility(UUID projectId, TargetProfile target);

    /** Lists explicitly supported, versioned compatibility profiles. */
    List<TargetProfile> supportedTargets();

    /** Performs a read-only compatibility assessment bound to an inventory revision. */
    CompatibilityResult assessCompatibility(UUID projectId, TargetProfile target);

    /**
     * Hybrid F05: the rule-based assessment plus AI_EXPLANATION findings from the explicitly supplied
     * model. Every rule-based finding is kept; if the model fails an AI_UNAVAILABLE finding is added.
     */
    default CompatibilityResult assessCompatibility(UUID projectId, TargetProfile target,
                                                    io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI-assisted compatibility is not supported by this manager");
    }

    /** Creates a draft only; generating a plan never approves it. */
    MigrationPlan createPlan(UUID projectId, TargetProfile target, Path outputRoot);

    /**
     * Hybrid F06: as {@link #createPlan(UUID, TargetProfile, Path)}, plus the checked suggestions of
     * {@link #suggestPlanSteps(UUID, TargetProfile, io.github.aiassetmigrate.ai.LLMClient)} recorded as non-blocking
     * AI_PLAN_PROPOSAL findings (and AI_PLAN_PROPOSAL_DROPPED for those the checks rejected). Suggestions never change
     * the plan's commands; one applies only once the user accepts it and creates a new plan.
     */
    default MigrationPlan createPlan(UUID projectId, TargetProfile target, Path outputRoot,
                                     io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI-assisted planning is not supported by this manager");
    }

    /**
     * Hybrid F05/F06: asks the explicitly supplied model which changes would make migrating to the target better or
     * unblock the plan (conversions, leaving out files that cannot be migrated, fixes for broken references). Each
     * proposal becomes review decisions that the program checks exactly as it checks a plan: converter support and
     * the converter's own check, image decoding and the organization preview (destinations and references). Those
     * that pass are returned as AI fixes for the user to accept or reject; the others are listed with the reason.
     * Nothing is saved; accepted fixes apply through {@link #applyBlockerFixes(UUID, long, List)}. If the model fails,
     * the result is empty with an AI_UNAVAILABLE finding.
     */
    default PlanSuggestions suggestPlanSteps(UUID projectId, TargetProfile target, io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI plan suggestions are not supported by this manager");
    }

    /**
     * Checks proposed decisions for the given inventory revision the way AI plan suggestions are checked (review rules,
     * the organization preview and, with a target, conversion checks) and returns those that pass, plus a line for each
     * that does not. Decisions that change nothing (already saved, or a correction for a reference that already
     * resolves) are left out silently. Nothing is saved.
     */
    default BlockerFixes.Proposal checkDecisions(UUID projectId, long inventoryRevision, Optional<TargetProfile> target,
                                                 List<BlockerFixes.Fix> candidates) {
        throw new UnsupportedOperationException("Checking proposed decisions is not supported by this manager");
    }

    /** Loads a saved plan for inspection; status alone is not execution authority. */
    MigrationPlan loadPlan(UUID planId);

    /** Requires explicit user approval of the exact displayed plan version. */
    MigrationPlan approve(UUID planId, int version);

    /** Rechecks approval, source fingerprints, tools, and paths before any write. */
    MigrationRun migrate(UUID planId);

    /** Loads the single-use run record for a plan, including interrupted runs. */
    MigrationRun loadRun(UUID planId);

    /**
     * UC07/UC08: runs {@link #migrate(UUID)}, then validates the run it recorded and saves a
     * report, so every attempted run leaves a success, partial, failed or incomplete report.
     * A migration blocked before any run started (unapproved, stale or already consumed plan,
     * failed rechecks) throws exactly as {@code migrate} does and nothing is validated. A run that
     * started but was interrupted is still validated (normally INCOMPLETE) and the interruption is
     * returned in {@code migrationFailure}. A validation failure never hides the run outcome: it
     * is returned in {@code validationFailure} and no report is present. The optional assistant
     * only adds the advisory summary described in {@link #validate(UUID, io.github.aiassetmigrate.ai.LLMClient)}.
     */
    default MigrationOutcome migrateAndValidate(UUID planId, Optional<io.github.aiassetmigrate.ai.LLMClient> assistant) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(assistant, "assistant");
        boolean startedBefore = existingRun(planId).isPresent();
        MigrationRun run;
        Optional<String> migrationFailure = Optional.empty();
        try {
            run = migrate(planId);
        } catch (RuntimeException failure) {
            // Only a run this call reserved is validated; a consumed plan's earlier run is not.
            Optional<MigrationRun> interrupted = startedBefore ? Optional.empty() : existingRun(planId);
            if (interrupted.isEmpty()) throw failure;
            run = interrupted.get();
            migrationFailure = Optional.of(describe(failure));
        }
        try {
            Report report = assistant.isPresent() ? validate(run.id(), assistant.get()) : validate(run.id());
            return new MigrationOutcome(run, Optional.of(report), migrationFailure, Optional.empty());
        } catch (RuntimeException failure) {
            return new MigrationOutcome(run, Optional.empty(), migrationFailure, Optional.of(describe(failure)));
        }
    }

    private Optional<MigrationRun> existingRun(UUID planId) {
        try {
            return Optional.of(loadRun(planId));
        } catch (RuntimeException notStarted) {
            return Optional.empty();
        }
    }

    private static String describe(RuntimeException failure) {
        Throwable cause = failure;
        String text = failure.getMessage();
        while ((text == null || text.isBlank()) && cause.getCause() != null) {
            cause = cause.getCause();
            text = cause.getMessage();
        }
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }

    /**
     * Read-only check of the outputs a run recorded (existence, readability, published hash,
     * PNG/JPEG decoding, OBJ/MTL text and references); unsupported checks are UNKNOWN, never
     * passed. Saves and returns a new Report with a deterministic summary. Fails if the run is
     * unknown or the project is busy (for example, a migration is running).
     */
    Report validate(UUID runId);

    /**
     * As {@link #validate(UUID)}, adding an advisory AI summary from the explicitly supplied
     * model. The status and findings are computed first and never depend on the model; the
     * model sees statuses, counts, finding codes and relative paths only. If the model fails or
     * its text is rejected, the report keeps only the deterministic summary.
     */
    default Report validate(UUID runId, io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI-assisted validation is not supported by this manager");
    }

    /** Saved validation reports for the project, oldest first; empty when there is no history. */
    List<Report> history(UUID projectId);

    /**
     * Natural-language commands need a model; without one this returns an AI_UNAVAILABLE result
     * and performs no action.
     */
    CommandResult command(UUID projectId, String request);

    /**
     * F09: interprets the request with the explicitly supplied model as one allowlisted action and
     * dispatches it through this facade. It can show, analyze, check, preview, draft plans and
     * validate, but never approve, migrate or save reviews; file changes still need an approved plan.
     * A request to prepare the project for a target lets the model operate: it chooses read-only tools in
     * sequence and the result carries one bundle of checked, proposed decisions for the user to confirm.
     * Ambiguous requests return a clarification; failures are returned as findings, not thrown.
     */
    default CommandResult command(UUID projectId, String request, io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("Natural-language commands are not supported by this manager");
    }

    /**
     * Replays events strictly after the cursor, then follows live events. Setup failures
     * are thrown to the caller (I/O failures as UncheckedIOException); asynchronous
     * disconnections reach observer.onError, whose default implementation does nothing.
     * Closing a subscription does not close the project publisher or stop work.
     * DefaultAssetManager retains a watched publisher until manager close; another
     * process cannot own the same project publisher while it is retained. If a write
     * is in progress when the manager closes, publisher closure waits for that write.
     */
    LogSubscription watch(UUID projectId, long afterSequence, LogObserver observer);

    enum AnalysisKind { CATEGORIES, RELATIONSHIPS, MATERIALS, NAMES, FOLDERS }

    enum NameStyle { PRESERVE, SNAKE_CASE, KEBAB_CASE }
    /** BY_CATEGORY uses accepted CATEGORY reviews, falling back to the type folder. */
    enum FolderStyle { PRESERVE, BY_TYPE, BY_CATEGORY }

    record OrganizationRules(NameStyle nameStyle, FolderStyle folderStyle) {
        public OrganizationRules {
            Objects.requireNonNull(nameStyle, "nameStyle");
            Objects.requireNonNull(folderStyle, "folderStyle");
        }
    }

    record TargetProfile(String id, String version) {
        public TargetProfile {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            if (id.isBlank() || version.isBlank()) {
                throw new IllegalArgumentException("Target profile ID and version must not be blank");
            }
        }
    }

    record CompatibilityResult(long inventoryRevision, TargetProfile target, List<Finding> findings) {
        public CompatibilityResult {
            if (inventoryRevision < 1) throw new IllegalArgumentException("Inventory revision must be positive");
            Objects.requireNonNull(target, "target");
            findings = List.copyOf(findings);
        }
    }

    record ImportResult(InventorySnapshot snapshot) {
        public ImportResult {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** RULES suggestions are deterministic; AI suggestions came from a model and were validated. */
    enum Source { RULES, AI }

    /**
     * Suggestions are not accepted reviews and must never be applied implicitly. {@code reason} is the
     * model's short, sanitized explanation for an AI suggestion; it is advisory text only.
     */
    record Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue, Optional<String> reference,
                      Source source, Optional<String> reason) {
        public Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue) {
            this(id, assetId, kind, proposedValue, Optional.empty());
        }
        public Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue, Optional<String> reference) {
            this(id, assetId, kind, proposedValue, reference, Source.RULES);
        }
        public Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue, Optional<String> reference,
                          Source source) {
            this(id, assetId, kind, proposedValue, reference, source, Optional.empty());
        }
        public Suggestion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proposedValue, "proposedValue");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record AnalysisResult(long inventoryRevision, List<Suggestion> suggestions, List<Finding> findings) {
        public AnalysisResult {
            if (inventoryRevision < 1) throw new IllegalArgumentException("Inventory revision must be positive");
            suggestions = List.copyOf(suggestions);
            findings = List.copyOf(findings);
        }
    }

    /** Exactly one of {@code report} and {@code validationFailure} is present. */
    record MigrationOutcome(MigrationRun run, Optional<Report> report, Optional<String> migrationFailure,
                            Optional<String> validationFailure) {
        public MigrationOutcome {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(migrationFailure, "migrationFailure");
            Objects.requireNonNull(validationFailure, "validationFailure");
            if (report.isPresent() == validationFailure.isPresent()) {
                throw new IllegalArgumentException("Exactly one of report and validationFailure must be present");
            }
            if (report.isPresent() && !report.get().runId().equals(run.id())) {
                throw new IllegalArgumentException("Report does not belong to the run");
            }
        }
    }

    /**
     * {@code decisions}: proposed decisions the agent gathered and the program checked, for the user to confirm
     * (through {@link #applyBlockerFixes(UUID, long, List)}); nothing in it is saved.
     */
    record CommandResult(String message, Optional<MigrationPlan> draftPlan, List<Finding> findings,
                         Optional<BlockerFixes.Proposal> decisions) {
        public CommandResult(String message, Optional<MigrationPlan> draftPlan, List<Finding> findings) {
            this(message, draftPlan, findings, Optional.empty());
        }
        public CommandResult {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(draftPlan, "draftPlan");
            findings = List.copyOf(findings);
            Objects.requireNonNull(decisions, "decisions");
        }
    }

    /** Checked AI suggestions for one target ({@code proposal.fixes()}), the dropped ones with reasons, and AI status findings. */
    record PlanSuggestions(TargetProfile target, BlockerFixes.Proposal proposal, List<Finding> findings) {
        public PlanSuggestions {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(proposal, "proposal");
            findings = List.copyOf(findings);
        }
    }
}
