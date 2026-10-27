package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A snapshot; status alone is not evidence of execution authorization. */
public record MigrationPlan(UUID id, UUID projectId, int version, long inventoryRevision,
                            String targetProfile, String targetProfileVersion, Path outputRoot,
                            List<ReviewedChange> reviewedChanges, List<MigrationCommand> commands,
                            List<Finding> findings, PlanStatus status, Instant createdAt) {
    public MigrationPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(targetProfile, "targetProfile");
        Objects.requireNonNull(targetProfileVersion, "targetProfileVersion");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        reviewedChanges = List.copyOf(reviewedChanges);
        commands = List.copyOf(commands);
        findings = List.copyOf(findings);
        if (version < 1 || inventoryRevision < 0) {
            throw new IllegalArgumentException("version must be positive and inventoryRevision nonnegative");
        }
    }
}
