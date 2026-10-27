package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record Asset(UUID id, Path relativePath, Type type, String fingerprint,
                    Map<String, String> metadata) {
    public enum Type { MODEL, MATERIAL, TEXTURE, OTHER }

    public Asset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fingerprint, "fingerprint");
        metadata = Map.copyOf(metadata);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("relativePath must be relative");
        }
    }
}
