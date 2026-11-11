package io.github.aiassetmigrate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A saved validation of one migration run's recorded outputs. {@code validationStatus} and
 * {@code summary} are deterministic; {@code aiSummary} is optional advisory text from a model
 * and never affects the status or findings it describes.
 */
public record Report(UUID id, UUID projectId, UUID planId, UUID runId, RunStatus runStatus,
                     ValidationStatus validationStatus, List<Finding> findings,
                     String summary, Optional<String> aiSummary, Instant createdAt) {
    /**
     * NOT_VALIDATED: no recorded outputs to check. PASSED: every recorded output passed every
     * check and the run succeeded. FAILED: at least one check failed. INCOMPLETE: nothing failed,
     * but some check could not be performed or some planned outputs were not produced.
     */
    public enum ValidationStatus { NOT_VALIDATED, PASSED, FAILED, INCOMPLETE }

    public static final int MAX_SUMMARY_LENGTH = 4096;

    public Report {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runStatus, "runStatus");
        Objects.requireNonNull(validationStatus, "validationStatus");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(aiSummary, "aiSummary");
        Objects.requireNonNull(createdAt, "createdAt");
        findings = List.copyOf(findings);
        if (summary.length() > MAX_SUMMARY_LENGTH || aiSummary.map(String::length).orElse(0) > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("Report summaries are limited to " + MAX_SUMMARY_LENGTH + " characters");
        }
    }
}
