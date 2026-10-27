package io.github.aiassetmigrate.domain;

import io.github.aiassetmigrate.application.AssetManager.ImportResult;
import io.github.aiassetmigrate.application.ImportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InventoryContractsTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    @TempDir Path directory;

    private InventorySnapshot snapshot(int schema, int inspection, long revision, Instant scannedAt) {
        return new InventorySnapshot(schema, inspection, new Project(ID, directory, revision, List.of(), TIME),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, scannedAt, List.of(), List.of());
    }

    @Test
    void validatesVersionsRevisionAndTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 1, 1, TIME));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, 0, 1, TIME));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, 1, 0, TIME));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, 1, 1, TIME.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(1, 1,
                new Project(ID, Path.of("relative"), 1, List.of(), TIME), List.of(),
                InventorySnapshot.ScanStatus.COMPLETE, TIME, List.of(), List.of()));
    }

    @Test
    void snapshotCollectionsAreImmutableAndCoverageIsIndependent() {
        var findings = new ArrayList<>(List.of(new Finding(ID, Optional.empty(), Optional.of(Path.of("lost.png")),
                Finding.Severity.WARNING, "UNREADABLE", "Cannot read file")));
        var outcomes = new ArrayList<>(List.of(new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY,
                PathOutcome.Status.TRAVERSED)));
        var references = new ArrayList<>(List.of(new AssetReference(ID, AssetReference.Kind.TEXTURE,
                "missing.png", Optional.of(Path.of("missing.png")), AssetReference.ResolutionStatus.MISSING)));
        var snapshot = new InventorySnapshot(1, 1, new Project(ID, directory, 1, List.of(), TIME), findings,
                InventorySnapshot.ScanStatus.PARTIAL, TIME, outcomes, references);
        findings.clear();
        outcomes.clear();
        references.clear();
        assertEquals(1, snapshot.findings().size());
        assertEquals(1, snapshot.pathOutcomes().size());
        assertEquals(1, snapshot.references().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.findings().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.pathOutcomes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.references().clear());
        assertSame(snapshot, new ImportResult(snapshot).snapshot());
        assertThrows(NullPointerException.class, () -> new ImportResult(null));
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, snapshot(1, 1, 1, TIME).scanStatus());
    }

    @Test
    void fingerprintsAndRelativePathsAreValidated() {
        for (String hash : List.of("hash", "A".repeat(64), "a".repeat(63), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Asset(ID, Path.of("image.png"), Asset.Type.TEXTURE, hash, Map.of()));
        }
        for (Path path : List.of(Path.of(""), Path.of("..", "escape.png"), Path.of("a", "..", "b"), directory)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Asset(ID, path, Asset.Type.TEXTURE, "a".repeat(64), Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new Finding(ID, Optional.empty(),
                Optional.of(Path.of("..")), Finding.Severity.WARNING, "BAD", "Bad path"));
        assertThrows(IllegalArgumentException.class, () -> new PathOutcome(Path.of(""),
                PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED));
        assertThrows(IllegalArgumentException.class, () -> new PathOutcome(Path.of("folder"),
                PathOutcome.Kind.DIRECTORY, PathOutcome.Status.INVENTORIED));
        assertThrows(IllegalArgumentException.class, () -> new AssetReference(ID, AssetReference.Kind.TEXTURE,
                "bad", Optional.empty(), AssetReference.ResolutionStatus.RESOLVED));
        assertThrows(IllegalArgumentException.class, () -> new AssetReference(ID, AssetReference.Kind.TEXTURE,
                "bad", Optional.of(Path.of("safe")), AssetReference.ResolutionStatus.UNSAFE));
    }

    @Test
    void nullCollectionEntriesAreRejected() {
        var project = new Project(ID, directory, 1, List.of(), TIME);
        assertThrows(NullPointerException.class, () -> new InventorySnapshot(1, 1, project,
                java.util.Arrays.asList((Finding) null), InventorySnapshot.ScanStatus.PARTIAL,
                TIME, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new InventorySnapshot(1, 1, project,
                List.of(), InventorySnapshot.ScanStatus.PARTIAL, TIME,
                java.util.Arrays.asList((PathOutcome) null), List.of()));
        assertThrows(NullPointerException.class, () -> new InventorySnapshot(1, 1, project,
                List.of(), InventorySnapshot.ScanStatus.PARTIAL, TIME,
                List.of(), java.util.Arrays.asList((AssetReference) null)));
    }

    @Test
    void fatalFailuresPreserveCauseAndCommittedRevision() {
        var cause = new java.io.IOException("Log failure");
        var failure = new ImportException("Completion failed", cause, Optional.of(2L));
        assertSame(cause, failure.getCause());
        assertEquals(Optional.of(2L), failure.committedRevision());
        assertTrue(new ImportException("Scan failed", cause).committedRevision().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ImportException("Bad", cause, Optional.of(0L)));
        assertThrows(NullPointerException.class, () -> new ImportException("Bad", cause, null));
    }
}
