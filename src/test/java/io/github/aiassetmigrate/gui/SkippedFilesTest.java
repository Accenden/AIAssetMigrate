package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkippedFilesTest {
    private static Finding finding(String path, String code) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(Path.of(path)), Finding.Severity.WARNING, code, "m");
    }

    @Test void skippedFilesAreListedWithAPlainReasonAndLeftOutFilesAreNot() {
        var project = new Project(UUID.randomUUID(), io.github.aiassetmigrate.TestPaths.absolute("source"), 1, List.of(), Instant.EPOCH);
        var snapshot = new InventorySnapshot(1, 1, project,
                List.of(finding("misc/notes.txt", "UNSUPPORTED_EXTENSION"), finding("big.png", "FILE_SIZE_LIMIT"),
                        finding("broken.obj", "CORRUPT_FORMAT")),
                InventorySnapshot.ScanStatus.PARTIAL, Instant.EPOCH, List.of(
                new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.TRAVERSED),
                new PathOutcome(Path.of("misc", "notes.txt"), PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED),
                new PathOutcome(Path.of("big.png"), PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED),
                new PathOutcome(Path.of("broken.obj"), PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED),
                new PathOutcome(Path.of("locked.mtl"), PathOutcome.Kind.FILE, PathOutcome.Status.FAILED),
                new PathOutcome(Path.of("old.psd"), PathOutcome.Kind.FILE, PathOutcome.Status.EXCLUDED)), List.of());
        assertEquals(List.of("misc/notes.txt (unsupported file type)", "big.png (too large to scan)",
                "broken.obj (damaged or not a valid file)", "locked.mtl (could not be read)"),
                AssetMigrateApplication.skippedFileLines(snapshot));
        assertEquals(java.util.Set.of(Path.of("old.psd")), snapshot.excludedFiles());
        assertFalse(snapshot.hasCompleteCoverage());
    }

    @Test void excludedFilesCountAsCoveredButOtherGapsDoNot() {
        var project = new Project(UUID.randomUUID(), io.github.aiassetmigrate.TestPaths.absolute("source"), 1, List.of(), Instant.EPOCH);
        var excludedOnly = new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE, Instant.EPOCH, List.of(
                new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.TRAVERSED),
                new PathOutcome(Path.of("notes.txt"), PathOutcome.Kind.FILE, PathOutcome.Status.EXCLUDED)), List.of());
        assertTrue(excludedOnly.hasCompleteCoverage());
        assertTrue(excludedOnly.excludableFiles().isEmpty());
        var unreadFolder = new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.PARTIAL, Instant.EPOCH, List.of(
                new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.TRAVERSED),
                new PathOutcome(Path.of("locked"), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.FAILED)), List.of());
        assertFalse(unreadFolder.hasCompleteCoverage());
        assertTrue(unreadFolder.excludableFiles().isEmpty(), "folders cannot be left out");
    }
}
