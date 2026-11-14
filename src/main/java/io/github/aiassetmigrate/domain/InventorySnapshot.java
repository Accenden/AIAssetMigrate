package io.github.aiassetmigrate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable persisted scan. Coverage is independent of format-validation findings.
 * An inventory revision starts at one; Project.createdAt remains the original
 * project timestamp, while scannedAt belongs to this revision.
 */
public record InventorySnapshot(int schemaVersion, int inspectionVersion, Project project,
                                List<Finding> findings, ScanStatus scanStatus, Instant scannedAt,
                                List<PathOutcome> pathOutcomes, List<AssetReference> references) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int CURRENT_INSPECTION_VERSION = 1;
    public enum ScanStatus { COMPLETE, PARTIAL }

    public InventorySnapshot {
        Objects.requireNonNull(project, "project");
        findings = List.copyOf(findings);
        Objects.requireNonNull(scanStatus, "scanStatus");
        Objects.requireNonNull(scannedAt, "scannedAt");
        pathOutcomes = List.copyOf(pathOutcomes);
        references = List.copyOf(references);
        if (schemaVersion < 1 || inspectionVersion < 1 || project.inventoryRevision() < 1) {
            throw new IllegalArgumentException("Snapshot versions and revision must be positive");
        }
        if (!project.sourceRoot().isAbsolute()) {
            throw new IllegalArgumentException("Snapshot source root must be absolute");
        }
        if (scannedAt.isBefore(project.createdAt())) {
            throw new IllegalArgumentException("Scan cannot precede project creation");
        }
    }

    /**
     * Coverage requires all recorded paths to be accounted for, independently of warning findings. A file the
     * user excluded is accounted for: it is deliberately outside the project, not an unknown.
     */
    public boolean hasCompleteCoverage() {
        if (pathOutcomes.isEmpty()) return scanStatus == ScanStatus.COMPLETE;
        boolean rootTraversed = false;
        java.util.Set<java.nio.file.Path> files = new java.util.HashSet<>();
        java.util.Set<java.nio.file.Path> paths = new java.util.HashSet<>();
        for (PathOutcome outcome : pathOutcomes) {
            if (!paths.add(outcome.relativePath())) return false;
            if (outcome.kind() == PathOutcome.Kind.DIRECTORY) {
                if (outcome.status() != PathOutcome.Status.TRAVERSED) return false;
                if (outcome.relativePath().toString().isEmpty()) rootTraversed = true;
            } else if (outcome.status() == PathOutcome.Status.INVENTORIED) {
                files.add(outcome.relativePath());
            } else if (outcome.status() != PathOutcome.Status.EXCLUDED) {
                return false;
            }
        }
        if (!rootTraversed || files.size() != project.assets().size()) return false;
        return project.assets().stream().allMatch(asset -> files.remove(asset.relativePath())) && files.isEmpty();
    }

    /** Files the user chose to leave out of this revision; a plain rescan keeps them out. */
    public java.util.Set<java.nio.file.Path> excludedFiles() {
        java.util.Set<java.nio.file.Path> excluded = new java.util.LinkedHashSet<>();
        for (PathOutcome outcome : pathOutcomes) {
            if (outcome.status() == PathOutcome.Status.EXCLUDED) excluded.add(outcome.relativePath());
        }
        return java.util.Collections.unmodifiableSet(excluded);
    }

    /** Files that were skipped or could not be read; each can be left out by the user to complete coverage. */
    public java.util.List<PathOutcome> excludableFiles() {
        return pathOutcomes.stream().filter(outcome -> outcome.kind() == PathOutcome.Kind.FILE
                && (outcome.status() == PathOutcome.Status.SKIPPED || outcome.status() == PathOutcome.Status.FAILED)).toList();
    }
}
