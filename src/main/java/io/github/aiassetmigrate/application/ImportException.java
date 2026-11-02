package io.github.aiassetmigrate.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Fatal import/storage/logging failure; recoverable file problems belong in snapshot findings. */
public final class ImportException extends RuntimeException {
    private final Optional<Long> committedRevision;
    private final Optional<UUID> projectId;

    public ImportException(String message, Throwable cause) {
        this(message, cause, Optional.empty());
    }

    /** Identifies a saved revision when a later operation (such as completion logging) fails. */
    public ImportException(String message, Throwable cause, Optional<Long> committedRevision) {
        this(message, cause, Optional.empty(), committedRevision);
    }

    /** Identifies the project and any revision already committed before the failure. */
    public ImportException(String message, Throwable cause, Optional<UUID> projectId,
                           Optional<Long> committedRevision) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.committedRevision = Objects.requireNonNull(committedRevision, "committedRevision");
        if (committedRevision.isPresent() && committedRevision.get() < 1) {
            throw new IllegalArgumentException("Committed revision must be positive");
        }
    }

    public Optional<UUID> projectId() {
        return projectId;
    }

    public Optional<Long> committedRevision() {
        return committedRevision;
    }
}
