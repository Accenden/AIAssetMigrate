package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileAssetRepositoryTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    @TempDir Path directory;
    private Path source;
    private Path state;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectory(directory.resolve("source"));
        state = directory.resolve("state");
    }

    private InventorySnapshot snapshot(long revision) {
        return new InventorySnapshot(1, 1, new Project(ID, source, revision, List.of(), CREATED),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, CREATED.plusSeconds(revision), List.of(), List.of());
    }

    private Path projectDirectory() {
        return state.resolve("projects").resolve(ID.toString());
    }

    private void saveFirst(FileAssetRepository repository) throws IOException {
        try (var writer = repository.beginWrite(ID, source)) {
            assertEquals(snapshot(1), writer.saveNextRevision(snapshot(1)));
        }
    }

    @Test
    void roundTripsAcrossRestartAndPreservesImmutableNumberedRevisions() throws IOException {
        FileAssetRepository repository = new FileAssetRepository(state);
        byte[] sourceBytes = {1, 2, 3, 4};
        Path asset = Files.write(source.resolve("image.png"), sourceBytes);
        saveFirst(repository);
        Path first = projectDirectory().resolve("inventory-000001.json");
        byte[] original = Files.readAllBytes(first);
        repository = new FileAssetRepository(state);
        assertEquals(snapshot(1), repository.loadLatest(ID).orElseThrow());
        try (var writer = repository.beginWrite(ID, source)) {
            writer.saveNextRevision(snapshot(2));
            assertEquals(snapshot(2), repository.loadLatest(ID).orElseThrow());
            writer.saveNextRevision(snapshot(3));
        }
        assertEquals(snapshot(1), repository.loadRevision(ID, 1));
        assertEquals(snapshot(2), repository.loadRevision(ID, 2));
        assertEquals(snapshot(3), new FileAssetRepository(state).loadLatest(ID).orElseThrow());
        assertArrayEquals(original, Files.readAllBytes(first));
        assertArrayEquals(sourceBytes, Files.readAllBytes(asset));
        try (var entries = Files.list(projectDirectory())) {
            assertEquals(List.of("inventory-000001.json", "inventory-000002.json", "inventory-000003.json", "project.lock"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertFalse(Files.exists(state.resolve("logs")));
    }

    @Test
    void readingMissingStateDoesNotCreateAnything() throws IOException {
        var repository = new FileAssetRepository(state);
        assertTrue(repository.loadLatest(ID).isEmpty());
        assertThrows(NoSuchFileException.class, () -> repository.loadRevision(ID, 1));
        assertFalse(Files.exists(state));
    }

    @Test
    void readingSavedInventoryDoesNotRequireSourceToExist() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        Files.delete(source);
        assertEquals(snapshot(1), repository.loadLatest(ID).orElseThrow());
        assertFalse(Files.exists(source));
    }

    @Test
    void writerLockSurvivesSavesAndRejectsAnotherRepositoryUntilClosed() throws IOException {
        var repository = new FileAssetRepository(state);
        var competitor = new FileAssetRepository(state);
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> competitor.beginWrite(ID, source));
            writer.saveNextRevision(snapshot(1));
            assertEquals(snapshot(1), competitor.loadLatest(ID).orElseThrow());
            assertThrows(IOException.class, () -> competitor.beginWrite(ID, source));
        }
        try (var writer = competitor.beginWrite(ID, source)) {
            writer.saveNextRevision(snapshot(2));
        }
    }

    @Test
    void differentProjectsHaveIndependentWriterLocks() throws IOException {
        var repository = new FileAssetRepository(state);
        try (var first = repository.beginWrite(ID, source);
             var second = repository.beginWrite(UUID.randomUUID(), source)) {
            first.saveNextRevision(snapshot(1));
        }
    }

    @Test
    void writerLockAlsoExcludesAnotherJvm() throws Exception {
        var repository = new FileAssetRepository(state);
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> new FileAssetRepository(state).beginWrite(ID, source));
            assertProbeResult(2, "Another writer owns project");
        }
        assertProbeResult(0, "acquired");
    }

    private void assertProbeResult(int exitCode, String expectedOutput) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
                LockProbe.class.getName(), state.toString(), source.toString(), ID.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Writer probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(exitCode, process.exitValue(), output);
            assertTrue(output.contains(expectedOutput), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(20, TimeUnit.SECONDS);
            }
        }
    }

    public static final class LockProbe {
        public static void main(String[] arguments) throws Exception {
            var repository = new FileAssetRepository(Path.of(arguments[0]));
            try (var writer = repository.beginWrite(UUID.fromString(arguments[2]), Path.of(arguments[1]))) {
                System.out.println("acquired");
            } catch (IOException exception) {
                System.out.println(exception.getMessage());
                System.exit(2);
            }
        }
    }

    @Test
    void closedWriterCannotSaveAndCloseIsIdempotent() throws IOException {
        var repository = new FileAssetRepository(state);
        var writer = repository.beginWrite(ID, source);
        writer.close();
        writer.close();
        assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(1)));
        assertTrue(repository.loadLatest(ID).isEmpty());
    }

    @Test
    void requiresNextRevisionAndNeverOverwritesExistingRevision() throws IOException {
        var repository = new FileAssetRepository(state);
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(2)));
            writer.saveNextRevision(snapshot(1));
            byte[] original = Files.readAllBytes(projectDirectory().resolve("inventory-000001.json"));
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(1)));
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(3)));
            assertArrayEquals(original, Files.readAllBytes(projectDirectory().resolve("inventory-000001.json")));
            writer.saveNextRevision(snapshot(2));
        }
    }

    @Test
    void atomicMoveFailurePreservesPriorRevisionAndAbandonedTemporaryFile() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        byte[] first = Files.readAllBytes(projectDirectory().resolve("inventory-000001.json"));
        var failing = new FileAssetRepository(state, FileAssetRepository.DEFAULT_MAX_SNAPSHOT_BYTES,
                (temporary, target) -> {
                    assertTrue(Files.isRegularFile(temporary));
                    throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "injected");
                });
        try (var writer = failing.beginWrite(ID, source)) {
            assertThrows(AtomicMoveNotSupportedException.class, () -> writer.saveNextRevision(snapshot(2)));
        }
        assertFalse(Files.exists(projectDirectory().resolve("inventory-000002.json")));
        assertEquals(snapshot(1), repository.loadLatest(ID).orElseThrow());
        assertArrayEquals(first, Files.readAllBytes(projectDirectory().resolve("inventory-000001.json")));
        List<Path> abandoned;
        try (var entries = Files.list(projectDirectory())) {
            abandoned = entries.filter(path -> path.getFileName().toString().startsWith(".inventory-")).toList();
        }
        assertEquals(1, abandoned.size());
        try (var writer = repository.beginWrite(ID, source)) {
            writer.saveNextRevision(snapshot(2));
        }
        assertTrue(Files.exists(abandoned.getFirst()));
        assertEquals(snapshot(2), repository.loadLatest(ID).orElseThrow());
    }

    @Test
    void failedCommitBeforeFirstRevisionDoesNotPublishAnInventory() throws IOException {
        var repository = new FileAssetRepository(state, FileAssetRepository.DEFAULT_MAX_SNAPSHOT_BYTES,
                (temporary, target) -> { throw new IOException("Injected storage failure"); });
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(1)));
        }
        assertTrue(new FileAssetRepository(state).loadLatest(ID).isEmpty());
    }

    @Test
    void corruptLatestDoesNotFallBackOrAllowAnotherSave() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        Path corrupt = Files.writeString(projectDirectory().resolve("inventory-000002.json"), "{broken");
        assertThrows(IOException.class, () -> repository.loadLatest(ID));
        assertEquals(snapshot(1), repository.loadRevision(ID, 1));
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(3)));
        }
        assertEquals("{broken", Files.readString(corrupt));
        assertFalse(Files.exists(projectDirectory().resolve("inventory-000003.json")));
    }

    @Test
    void unsupportedLatestSchemaIsRejectedWithoutFallback() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        var next = snapshot(2);
        var future = new InventorySnapshot(2, 1, next.project(), next.findings(), next.scanStatus(), next.scannedAt(),
                next.pathOutcomes(), next.references());
        Files.write(projectDirectory().resolve("inventory-000002.json"), new InventoryJsonCodec().encode(future));
        assertThrows(IOException.class, () -> repository.loadLatest(ID));
        assertEquals(snapshot(1), repository.loadRevision(ID, 1));
    }

    @Test
    void enforcesReadAndWriteSizeLimits() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        var limited = new FileAssetRepository(state, 64);
        IOException failure = assertThrows(IOException.class, () -> limited.loadLatest(ID));
        assertTrue(failure.getMessage().contains("byte limit"));
        Path otherState = directory.resolve("small-state");
        var smallWriter = new FileAssetRepository(otherState, 64);
        try (var writer = smallWriter.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(snapshot(1)));
        }
        assertTrue(smallWriter.loadLatest(ID).isEmpty());
        assertEquals(snapshot(1), repository.loadLatest(ID).orElseThrow());
    }

    @Test
    void snapshotIdentityAndRevisionMustMatchStorageLocation() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        Files.write(projectDirectory().resolve("inventory-000002.json"), new InventoryJsonCodec().encode(snapshot(1)));
        assertThrows(IOException.class, () -> repository.loadLatest(ID));
        Path differentProject = state.resolve("projects").resolve(UUID.randomUUID().toString());
        Files.createDirectory(differentProject);
        Files.copy(projectDirectory().resolve("inventory-000001.json"), differentProject.resolve("inventory-000001.json"));
        assertThrows(IOException.class, () -> repository.loadLatest(UUID.fromString(differentProject.getFileName().toString())));
    }

    @Test
    void malformedNumberedRevisionIsNotIgnored() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        Path invalid = Files.writeString(projectDirectory().resolve("inventory-2.json"), "{}");
        assertThrows(IOException.class, () -> repository.loadLatest(ID));
        Files.delete(invalid);
        Files.writeString(projectDirectory().resolve("inventory-99999999999999999999999.json"), "{}");
        assertThrows(IOException.class, () -> repository.loadLatest(ID));
    }

    @Test
    void snapshotRootIdentityAndCreationTimeCannotChange() throws IOException {
        var repository = new FileAssetRepository(state);
        saveFirst(repository);
        var second = snapshot(2);
        var changedTime = new InventorySnapshot(1, 1,
                new Project(ID, source, 2, List.of(), CREATED.plusSeconds(1)), List.of(), second.scanStatus(),
                second.scannedAt(), List.of(), List.of());
        try (var writer = repository.beginWrite(ID, source)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(changedTime));
        }
        Path otherSource = Files.createDirectory(directory.resolve("other-source"));
        var changedRoot = new InventorySnapshot(1, 1, new Project(ID, otherSource, 2, List.of(), CREATED),
                List.of(), second.scanStatus(), second.scannedAt(), List.of(), List.of());
        try (var writer = repository.beginWrite(ID, otherSource)) {
            assertThrows(IOException.class, () -> writer.saveNextRevision(changedRoot));
        }
        assertEquals(snapshot(1), repository.loadLatest(ID).orElseThrow());
    }

    @Test
    void rejectsOverlappingRootsBeforeCreatingStateOrLocks() throws IOException {
        Path nested = source.resolve("new-state");
        var repository = new FileAssetRepository(nested);
        assertThrows(IOException.class, () -> repository.beginWrite(ID, source));
        assertFalse(Files.exists(nested));
        assertThrows(IOException.class, () -> new FileAssetRepository(source).beginWrite(ID, source));
        assertThrows(IOException.class, () -> new FileAssetRepository(directory).beginWrite(ID, source));
        assertFalse(Files.exists(source.resolve("projects")));
        assertFalse(Files.exists(directory.resolve("projects")));
    }

    @Test
    void interruptedSaveDoesNotPublishAndKeepsInterruptFlag() throws IOException {
        var repository = new FileAssetRepository(state);
        try (var writer = repository.beginWrite(ID, source)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> writer.saveNextRevision(snapshot(1)));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        assertTrue(repository.loadLatest(ID).isEmpty());
    }

    @Test
    void validatesArgumentsWithoutCreatingState() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new FileAssetRepository(state, 0));
        assertThrows(NullPointerException.class, () -> new FileAssetRepository(null));
        var repository = new FileAssetRepository(state);
        assertThrows(IllegalArgumentException.class, () -> repository.loadRevision(ID, 0));
        assertThrows(NullPointerException.class, () -> repository.loadLatest(null));
        assertFalse(Files.exists(state));
    }
}
