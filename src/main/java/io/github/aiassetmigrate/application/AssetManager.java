package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;

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

    /** Saves explicit user decisions, invalidating affected plan approvals. */
    List<ReviewedChange> review(UUID projectId, List<ReviewedChange> changes);

    List<Finding> checkCompatibility(UUID projectId, TargetProfile target);

    /** Creates a draft only; generating a plan never approves it. */
    MigrationPlan createPlan(UUID projectId, TargetProfile target, Path outputRoot);

    /** Requires explicit user approval of the exact displayed plan version. */
    MigrationPlan approve(UUID planId, int version);

    /** Rechecks approval, source fingerprints, tools, and paths before any write. */
    MigrationRun migrate(UUID planId);

    Report validate(UUID runId);

    List<Report> history(UUID projectId);

    /** Interprets allowlisted actions, but cannot grant approval. */
    CommandResult command(UUID projectId, String request);

    /** Replays events strictly after the cursor, then follows live events. */
    LogSubscription watch(UUID projectId, long afterSequence, LogObserver observer);

    enum AnalysisKind { CATEGORIES, RELATIONSHIPS, MATERIALS, NAMES, FOLDERS }

    record TargetProfile(String id, String version) {
        public TargetProfile {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            if (id.isBlank() || version.isBlank()) {
                throw new IllegalArgumentException("Target profile ID and version must not be blank");
            }
        }
    }

    record ImportResult(InventorySnapshot snapshot) {
        public ImportResult {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Suggestions are not accepted reviews and must never be applied implicitly. */
    record Suggestion(UUID id, UUID assetId, AnalysisKind kind, String proposedValue) {
        public Suggestion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proposedValue, "proposedValue");
        }
    }

    record AnalysisResult(List<Suggestion> suggestions, List<Finding> findings) {
        public AnalysisResult {
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
