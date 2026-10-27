package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MigrationRun(UUID id, UUID projectId, UUID planId, int planVersion,
                           Path outputRoot, RunStatus status, Map<UUID, StepStatus> stepStatuses,
                           Instant createdAt, Optional<Instant> startedAt, Optional<Instant> completedAt) {
    public MigrationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        stepStatuses = Map.copyOf(stepStatuses);
        if (planVersion < 1) {
            throw new IllegalArgumentException("planVersion must be positive");
        }
    }
}
