package io.github.aiassetmigrate.domain;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MigrationRun(UUID id, UUID projectId, UUID planId, int planVersion,
                           Path outputRoot, RunStatus status, Map<UUID, StepStatus> stepStatuses,
                           Instant createdAt, Optional<Instant> startedAt, Optional<Instant> completedAt,
                           @JsonSetter(nulls = Nulls.AS_EMPTY) Optional<String> message,
                           @JsonSetter(nulls = Nulls.AS_EMPTY) Map<UUID, String> stepDetails) {
    public MigrationRun(UUID id, UUID projectId, UUID planId, int planVersion, Path outputRoot,
                        RunStatus status, Map<UUID, StepStatus> stepStatuses, Instant createdAt,
                        Optional<Instant> startedAt, Optional<Instant> completedAt) {
        this(id, projectId, planId, planVersion, outputRoot, status, stepStatuses, createdAt,
                startedAt, completedAt, Optional.empty(), Map.of());
    }

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
        message = Objects.requireNonNull(message, "message").map(MigrationRun::bounded);
        stepDetails = Map.copyOf(stepDetails);
        if (!stepStatuses.keySet().containsAll(stepDetails.keySet())) {
            throw new IllegalArgumentException("Step details must refer to run steps");
        }
        stepDetails = stepDetails.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> bounded(entry.getValue())));
        if (planVersion < 1) {
            throw new IllegalArgumentException("planVersion must be positive");
        }
    }

    private static String bounded(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(2048).forEach(c -> result.appendCodePoint(
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? ' ' : c));
        return result.toString();
    }
}
