package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Objects;

/** Lexical inventory paths only; filesystem safety is checked by the scanner. */
final class InventoryPath {
    private InventoryPath() {}

    static void requireRelative(Path path, boolean allowRoot) {
        Objects.requireNonNull(path, "path");
        if (allowRoot && path.toString().isEmpty()) {
            return;
        }
        if (path.isAbsolute() || path.getRoot() != null || path.toString().isEmpty()) {
            throw new IllegalArgumentException("Inventory path must be source-relative");
        }
        for (Path component : path) {
            String name = component.toString();
            if (name.equals(".") || name.equals("..") || name.contains(":") || name.contains("\\")) {
                throw new IllegalArgumentException("Inventory path must be normalized and portable");
            }
        }
    }
}
