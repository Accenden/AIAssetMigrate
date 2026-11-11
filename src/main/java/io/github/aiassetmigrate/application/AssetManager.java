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
     */
    ImportResult rescan(UUID projectId, Path sourceRoot) throws ImportException;

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
     * Hybrid F06: as {@link #createPlan(UUID, TargetProfile, Path)}, with steps proposed by the
     * explicitly supplied model and checked by the controller. Proposals are recorded as non-blocking
     * findings and never change the plan's commands; a new step needs a review and a new plan.
     */
    default MigrationPlan createPlan(UUID projectId, TargetProfile target, Path outputRoot,
                                     io.github.aiassetmigrate.ai.LLMClient assistant) {
        throw new UnsupportedOperationException("AI-assisted planning is not supported by this manager");
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

    /** Suggestions are not accepted reviews and must never be applied implicitly. */
    record Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue, Optional<String> reference,
                      Source source) {
        public Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue) {
            this(id, assetId, kind, proposedValue, Optional.empty());
        }
        public Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue, Optional<String> reference) {
            this(id, assetId, kind, proposedValue, reference, Source.RULES);
        }
        public Suggestion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proposedValue, "proposedValue");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(source, "source");
        }
    }

    record AnalysisResult(long inventoryRevision, List<Suggestion> suggestions, List<Finding> findings) {
        public AnalysisResult {
            if (inventoryRevision < 1) throw new IllegalArgumentException("Inventory revision must be positive");
            suggestions = List.copyOf(suggestions);
            findings = List.copyOf(findings);
        }
    }

    record CommandResult(String message, Optional<MigrationPlan> draftPlan, List<Finding> findings) {
        public CommandResult {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(draftPlan, "draftPlan");
            findings = List.copyOf(findings);
        }
    }
}
