package io.github.aiassetmigrate.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeStoragePathsTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsEqualAndNestedPathsInBothDirectionsWithoutCreatingState() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path nested = source.resolve("state/missing");
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, source));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, nested));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, temporary));
        assertFalse(Files.exists(source.resolve("state")));
        assertEquals(0, childCount(source));
    }

    @Test
    void acceptsAbsentSiblingStateWithoutCreatingIt() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path state = temporary.resolve("state/inventory");
        SafeStoragePaths.validateSeparation(source, state);
        SafeStoragePaths.validateExistingPath(state);
        assertFalse(Files.exists(temporary.resolve("state")));
        assertEquals(0, childCount(source));
    }

    @Test
    void createsMissingSiblingDirectoriesAndAcceptsExistingDirectories() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path state = temporary.resolve("state/inventory");
        SafeStoragePaths.validateSeparation(source, state);
        SafeStoragePaths.createDirectories(state);
        SafeStoragePaths.createDirectories(state);
        assertTrue(Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, childCount(source));
    }

    @Test
    void rejectsAbsentOrRegularFileSourceAndRegularFileState() throws IOException {
        Path file = Files.writeString(temporary.resolve("file"), "unchanged");
        Path state = temporary.resolve("state");
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(file, state));
        assertThrows(IOException.class,
                () -> SafeStoragePaths.validateSeparation(temporary.resolve("absent"), state));
        Path source = Files.createDirectory(temporary.resolve("source"));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, file));
        assertFalse(Files.exists(state));
        assertEquals("unchanged", Files.readString(file));
    }

    @Test
    void rejectsFileAncestorsAndDirectoryCreationAtFileWithoutMutation() throws IOException {
        Path file = Files.writeString(temporary.resolve("file"), "unchanged");
        Path descendant = file.resolve("missing/child");
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(descendant));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(descendant));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(file));
        assertThrows(IOException.class, () -> SafeStoragePaths.requireRegularFile(descendant));
        assertEquals("unchanged", Files.readString(file));
        assertEquals(1, childCount(temporary));
    }

    @Test
    void requiresExistingRegularFileButAllowsRegularLeafForValidation() throws IOException {
        Path file = Files.writeString(temporary.resolve("file"), "contents");
        SafeStoragePaths.requireRegularFile(file);
        SafeStoragePaths.validateExistingPath(file);
        assertThrows(IOException.class, () -> SafeStoragePaths.requireRegularFile(temporary));
        assertThrows(IOException.class,
                () -> SafeStoragePaths.requireRegularFile(temporary.resolve("absent")));
        assertEquals(1, childCount(temporary));
    }

    @Test
    void rejectsRawParentTraversalBeforeNormalizationForEveryOperation() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path traversal = temporary.resolve("missing/../state");
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(traversal));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(traversal));
        assertThrows(IOException.class, () -> SafeStoragePaths.requireRegularFile(traversal));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, traversal));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(traversal, source));
        assertEquals(1, childCount(temporary));
    }

    @Test
    void rejectsSymbolicLinkRootsAndAncestorsWithoutMutatingTargets() throws IOException {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path source = Files.createDirectory(target.resolve("source"));
        Path link = temporary.resolve("link");
        createSymbolicLinkOrSkip(link, target);
        assertUnsafeDirectoryAlias(link, target, source);
    }

    @Test
    void rejectsSymbolicLinkFileAndDanglingLink() throws IOException {
        Path target = Files.writeString(temporary.resolve("target"), "unchanged");
        Path link = temporary.resolve("link");
        createSymbolicLinkOrSkip(link, target);
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(link));
        assertThrows(IOException.class, () -> SafeStoragePaths.requireRegularFile(link));
        Files.delete(target);
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(link.resolve("child")));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(link));
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsWindowsJunctionRootsAndAncestorsWithoutMutatingTargets() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
                "Windows junction test requires Windows cmd /c mklink /J");
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path source = Files.createDirectory(target.resolve("source"));
        Path junction = temporary.resolve("junction");
        Process process;
        try {
            process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "Cannot run cmd /c mklink /J: " + unavailable);
            return;
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            assumeTrue(finished && process.exitValue() == 0,
                    "Windows junction creation unavailable: " + output);
            assertUnsafeDirectoryAlias(junction, target, source);
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void validatesFilesystemRootAndExistingAncestors() {
        assertDoesNotThrow(() -> SafeStoragePaths.validateExistingPath(temporary.toAbsolutePath().getRoot()));
        assertDoesNotThrow(() -> SafeStoragePaths.validateExistingPath(temporary));
    }

    private void assertUnsafeDirectoryAlias(Path alias, Path target, Path source) throws IOException {
        Path missing = alias.resolve("missing/child");
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(alias));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateExistingPath(missing));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(missing));
        assertThrows(IOException.class, () -> SafeStoragePaths.requireRegularFile(alias.resolve("file")));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(alias, temporary.resolve("state")));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(alias.resolve("source"), temporary.resolve("state")));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, alias.resolve("source")));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, missing));
        assertThrows(IOException.class, () -> SafeStoragePaths.validateSeparation(source, alias));
        assertThrows(IOException.class, () -> SafeStoragePaths.createDirectories(alias.resolve("../escape")));
        assertFalse(Files.exists(target.resolve("missing")));
        assertFalse(Files.exists(temporary.resolve("state")));
        assertFalse(Files.exists(temporary.resolve("escape")));
        assertEquals(1, childCount(target));
        assertEquals(0, childCount(source));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic link creation unavailable (permissions/filesystem): " + unavailable);
        }
    }

    private static long childCount(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.count();
        }
    }
}
