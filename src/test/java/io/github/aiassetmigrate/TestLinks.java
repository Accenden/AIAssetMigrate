package io.github.aiassetmigrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Test helpers that skip, rather than fail, where the platform cannot create links. */
public final class TestLinks {
    private TestLinks() {}

    /** Windows requires Developer Mode or elevation to create symbolic links. */
    public static Path symlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    /** Skips when the filesystem cannot report Unix hard-link counts (for example NTFS). */
    public static void requireUnixLinkCount(Path path) throws IOException {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView("unix"), "unix link count unavailable");
    }
}
