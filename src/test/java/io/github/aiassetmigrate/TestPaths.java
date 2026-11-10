package io.github.aiassetmigrate;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Platform-absolute fixture paths: {@code /name} on POSIX, {@code C:\name} on Windows. */
public final class TestPaths {
    private TestPaths() {}

    public static Path absolute(String name) {
        return Path.of("").toAbsolutePath().getRoot().resolve(name);
    }

    /**
     * A relative path containing characters that some platforms reject (control characters,
     * '?', quotes). Skips the calling test where the platform cannot represent the name.
     */
    public static Path hostile(String name) {
        try {
            return Path.of(name);
        } catch (InvalidPathException e) {
            assumeTrue(false, "Platform cannot represent hostile filename: " + e.getReason());
            throw new AssertionError("unreachable");
        }
    }
}
