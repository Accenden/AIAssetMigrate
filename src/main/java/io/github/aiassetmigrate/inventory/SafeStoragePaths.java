package io.github.aiassetmigrate.inventory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class SafeStoragePaths {
    private SafeStoragePaths() {
    }

    static void validateSeparation(Path sourceRoot, Path stateDirectory) throws IOException {
        Path source = checkedPath(sourceRoot);
        Path state = checkedPath(stateDirectory);
        if (!attributes(source).isDirectory() || !Files.isReadable(source)) {
            throw new IOException("Source must be an existing readable directory: " + sourceRoot);
        }
        try {
            if (!attributes(state).isDirectory()) {
                throw new IOException("State path is not a directory: " + stateDirectory);
            }
        } catch (NoSuchFileException missing) {
            // A missing state directory is valid; validation must not create it.
        }
        if (source.startsWith(state) || state.startsWith(source)) {
            throw new IOException("Source and state directories must be separate: " + source + ", " + state);
        }
    }

    static void validateExistingPath(Path path) throws IOException {
        checkedPath(path);
    }

    static void createDirectories(Path directory) throws IOException {
        Path target = checkedPath(directory);
        Path current = target.getRoot();
        for (Path component : target) {
            current = current.resolve(component);
            checkedPath(current);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException existing) {
                // Recheck rather than trusting an entry another process may have created.
            }
            checkedPath(current);
            if (!attributes(current).isDirectory()) {
                throw new IOException("Not a directory: " + current);
            }
        }
    }

    static void requireRegularFile(Path file) throws IOException {
        Path checked = checkedPath(file);
        if (!attributes(checked).isRegularFile()) {
            throw new IOException("Not a regular file: " + file);
        }
    }

    private static Path checkedPath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Path must not be null");
        }
        for (Path component : path) {
            if (component.toString().equals("..")) {
                throw new IOException("Parent traversal is not permitted: " + path);
            }
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        Path canonical = checkEntry(current, true);
        boolean missing = false;
        for (Path component : absolute) {
            current = current.resolve(component);
            canonical = canonical.resolve(component);
            if (!missing) {
                try {
                    canonical = checkEntry(current, !current.equals(absolute));
                } catch (NoSuchFileException absent) {
                    missing = true;
                }
            }
        }
        return canonical;
    }

    private static Path checkEntry(Path path, boolean mustBeDirectory) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || (!attributes.isDirectory() && !attributes.isRegularFile())
                || (mustBeDirectory && !attributes.isDirectory())) {
            throw new IOException("Unsafe filesystem entry: " + path);
        }
        // NOFOLLOW alone can preserve a junction alias. Follow only to verify identity,
        // never to accept an alternate location or perform a mutation through it.
        Path canonical = path.toRealPath();
        if (!canonical.equals(path)) {
            throw new IOException("Filesystem alias is not permitted: " + path);
        }
        return canonical;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
