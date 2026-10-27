package io.github.aiassetmigrate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Report(UUID id, UUID projectId, UUID runId, RunStatus runStatus,
                     ValidationStatus validationStatus, List<Finding> findings,
                     String summary, Instant createdAt) {
    public enum ValidationStatus { NOT_VALIDATED, PASSED, FAILED, INCOMPLETE }

    public Report {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runStatus, "runStatus");
        Objects.requireNonNull(validationStatus, "validationStatus");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(createdAt, "createdAt");
        findings = List.copyOf(findings);
    }
}
