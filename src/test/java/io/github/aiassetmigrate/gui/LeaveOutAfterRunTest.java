package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.validation.ValidationNextSteps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Leave out button in the Migrate step's "What to do next" box. */
class LeaveOutAfterRunTest {
    @TempDir Path directory;

    @Test void eachFileStillInTheScanGetsAnExclusionNamingTheProblem() {
        UUID fbx = UUID.randomUUID();
        var step = new ValidationNextSteps.Step("OUTPUT_FORMAT_UNCHECKED", List.of("props/crate_01.fbx", "gone.png"), "a", true);
        List<ReviewedChange> changes = AssetMigrateApplication.leaveOutChanges(step, Map.of("props/crate_01.fbx", fbx), Instant.EPOCH);
        assertEquals(1, changes.size(), "a file no longer in the scan is skipped");
        ReviewedChange change = changes.getFirst();
        assertEquals(fbx, change.assetId());
        assertEquals(ReviewedChange.Kind.EXCLUSION, change.kind());
        assertEquals(ReviewedChange.Decision.ACCEPTED, change.decision());
        assertEquals(Map.of("reason", "Left out after validation (OUTPUT_FORMAT_UNCHECKED)"), change.values());
    }

    @Test void theNextRunGetsAnEmptyFolderBecauseOutputsAreNeverOverwritten() throws Exception {
        Path missing = directory.resolve("fresh");
        assertEquals(missing, AssetMigrateApplication.nextOutputFolder(missing), "a folder that does not exist yet is fine");
        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertEquals(empty, AssetMigrateApplication.nextOutputFolder(empty));
        Path used = Files.createDirectory(directory.resolve("messy"));
        Files.writeString(used.resolve("hero.obj"), "v 0 0 0");
        assertEquals(directory.resolve("messy-2"), AssetMigrateApplication.nextOutputFolder(used));
        Files.createDirectory(directory.resolve("messy-2"));
        assertEquals(directory.resolve("messy-3"), AssetMigrateApplication.nextOutputFolder(used), "an existing sibling is skipped");
        // A run needs an existing output root, so the sibling is created, empty.
        Path fresh = AssetMigrateApplication.freshOutputFolder(used);
        assertEquals(directory.resolve("messy-3"), fresh);
        assertTrue(Files.isDirectory(fresh));
        try (var entries = Files.list(fresh)) { assertTrue(entries.findAny().isEmpty()); }
        assertEquals(empty, AssetMigrateApplication.freshOutputFolder(empty), "an empty folder is used as it is");
    }
}
