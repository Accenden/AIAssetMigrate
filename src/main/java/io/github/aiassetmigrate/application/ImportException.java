package io.github.aiassetmigrate.application;

import java.util.Objects;
import java.util.Optional;

/** Fatal import/storage/logging failure; recoverable file problems belong in snapshot findings. */
public final class ImportException extends RuntimeException {
    private final Optional<Long> committedRevision;

    public ImportException(String message, Throwable cause) {
        this(message, cause, Optional.empty());
    }

    /** Identifies a saved revision when a later operation (such as completion logging) fails. */
    public ImportException(String message, Throwable cause, Optional<Long> committedRevision) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.committedRevision = Objects.requireNonNull(committedRevision, "committedRevision");
        if (committedRevision.isPresent() && committedRevision.get() < 1) {
            throw new IllegalArgumentException("Committed revision must be positive");
        }
    }

    public Optional<Long> committedRevision() {
        return committedRevision;
    }
}
