package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Finding(UUID id, Optional<UUID> assetId, Optional<Path> sourceRelativePath, Severity severity,
                      String code, String message) {
    public enum Severity { INFO, WARNING, ERROR, UNKNOWN }

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(sourceRelativePath, "sourceRelativePath");
        sourceRelativePath.ifPresent(path -> InventoryPath.requireRelative(path, true));
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
