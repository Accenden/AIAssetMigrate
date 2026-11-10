package io.github.aiassetmigrate.domain;

import java.nio.file.Path;

/** Formats relative paths with '/' separators so displays and keys match on every platform. */
public final class PortablePath {
    private PortablePath() {}

    public static String of(Path path) {
        StringBuilder text = new StringBuilder();
        if (path == null) return "";
        for (Path part : path) {
            if (!text.isEmpty()) text.append('/');
            text.append(part);
        }
        return text.toString();
    }
}
