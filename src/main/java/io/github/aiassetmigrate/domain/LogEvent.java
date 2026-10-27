package io.github.aiassetmigrate.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sequence numbers are positive and scoped to a project. */
public record LogEvent(UUID id, UUID projectId, long sequence, Instant occurredAt,
                       Level level, String message, Optional<UUID> runId,
                       Optional<UUID> commandId, Map<String, String> details) {
    public enum Level { DEBUG, INFO, WARNING, ERROR }

    public LogEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(commandId, "commandId");
        details = Map.copyOf(details);
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
