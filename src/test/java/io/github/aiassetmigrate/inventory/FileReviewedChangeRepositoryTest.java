package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.Project;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileReviewedChangeRepositoryTest {
    @TempDir Path temp;
    private Path source, state;
    private final UUID projectId = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    private FileAssetRepository inventories;
    private FileReviewedChangeRepository reviews;

    @BeforeEach void setup() throws IOException {
        source = Files.createDirectory(temp.resolve("source"));
        state = temp.resolve("state");
        inventories = new FileAssetRepository(state);
        reviews = new FileReviewedChangeRepository(state);
    }

    private InventorySnapshot snapshot(long revision) {
        var asset = new Asset(assetId, Path.of("asset.obj"), Asset.Type.MODEL, "a".repeat(64), Map.of());
        return new InventorySnapshot(1, 1, new Project(projectId, source, revision, List.of(asset), time),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, time.plusSeconds(revision), List.of(), List.of());
    }

    private void saveInventory(long revision) throws IOException {
        try (var writer = inventories.beginWrite(projectId, source)) { writer.saveNextRevision(snapshot(revision)); }
    }

    private ReviewedChange change(UUID asset) {
        return new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.CATEGORY,
                ReviewedChange.Decision.ACCEPTED, Map.of("category", "model"), time);
    }

    private Path projectDir() { return state.resolve("projects").resolve(projectId.toString()); }

    @Test void revisionsRoundTripWithoutChangingSource() throws IOException {
        Path asset = Files.writeString(source.resolve("asset.obj"), "original");
        assertTrue(reviews.loadLatest(projectId).isEmpty());
        assertFalse(Files.exists(state));
        saveInventory(1);
        var item = change(assetId);
        var first = reviews.save(snapshot(1), 0, List.of(item));
        var second = reviews.save(snapshot(1), 1, List.of());
        var reopened = new FileReviewedChangeRepository(state);
        assertEquals(first, reopened.loadRevision(projectId, 1));
        assertEquals(second, reopened.loadLatest(projectId).orElseThrow());
        assertEquals("original", Files.readString(asset));
    }

    @Test void enforcesInventoryAndReviewRevisionsAndProjectLock() throws IOException {
        saveInventory(1);
        var change = change(assetId);
        assertThrows(IllegalArgumentException.class, () -> reviews.save(snapshot(1), 0, List.of(change, change)));
        assertThrows(IllegalArgumentException.class, () -> reviews.save(snapshot(1), 0, List.of(change(UUID.randomUUID()))));
        reviews.save(snapshot(1), 0, List.of(change));
        assertThrows(IOException.class, () -> reviews.save(snapshot(1), 0, List.of()));
        try (var writer = inventories.beginWrite(projectId, source)) {
            assertThrows(IOException.class, () -> reviews.save(snapshot(1), 1, List.of()));
            writer.saveNextRevision(snapshot(2));
        }
        assertThrows(IOException.class, () -> reviews.save(snapshot(1), 1, List.of()));
        assertEquals(2, reviews.save(snapshot(2), 1, List.of()).inventoryRevision());
    }

    @Test void corruptLatestAndUnsafeNamesFailClosed() throws IOException {
        saveInventory(1);
        reviews.save(snapshot(1), 0, List.of(change(assetId)));
        Path second = projectDir().resolve("reviews-000002.json");
        Files.writeString(second, "{\"schemaVersion\":1,\"schemaVersion\":1}");
        assertThrows(IOException.class, () -> reviews.loadLatest(projectId));
        assertThrows(IOException.class, () -> reviews.save(snapshot(1), 1, List.of()));
        Files.delete(second);
        Files.writeString(projectDir().resolve("reviews-1.json"), "{}");
        assertThrows(IOException.class, () -> reviews.loadLatest(projectId));
    }

    @Test void failedAtomicMovePreservesPriorRevisionAndTemporaryFile() throws IOException {
        saveInventory(1);
        var first = reviews.save(snapshot(1), 0, List.of());
        var failing = new FileReviewedChangeRepository(state, 1024 * 1024,
                (from, to) -> { throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test"); });
        assertThrows(IOException.class, () -> failing.save(snapshot(1), 1, List.of()));
        assertEquals(first, reviews.loadLatest(projectId).orElseThrow());
        try (var files = Files.list(projectDir())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith(".reviews-")));
        }
    }

    @Test void sizeAndSeparationAreEnforced() throws IOException {
        saveInventory(1);
        var tiny = new FileReviewedChangeRepository(state, 10);
        assertThrows(IOException.class, () -> tiny.save(snapshot(1), 0, List.of()));
        var unsafe = new FileReviewedChangeRepository(source.resolve("state"));
        assertThrows(IOException.class, () -> unsafe.save(snapshot(1), 0, List.of()));
        assertFalse(Files.exists(source.resolve("state")));
        reviews.save(snapshot(1), 0, List.of());
        assertThrows(IOException.class, () -> tiny.loadLatest(projectId));
    }

    @Test void rejectsSymlinkAndNonStringReviewValues() throws IOException {
        saveInventory(1);
        var item = change(assetId);
        reviews.save(snapshot(1), 0, List.of(item));
        Path first = projectDir().resolve("reviews-000001.json");
        String json = Files.readString(first);
        Files.writeString(first, json.replace("\"category\":\"model\"", "\"category\":42"));
        assertThrows(IOException.class, () -> reviews.loadLatest(projectId));
        Files.delete(first);
        Path outside = Files.writeString(temp.resolve("outside.json"), json);
        io.github.aiassetmigrate.TestLinks.symlinkOrSkip(first, outside);
        assertThrows(IOException.class, () -> reviews.loadLatest(projectId));
        assertEquals(json, Files.readString(outside));
    }
}
