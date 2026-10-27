package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Project(UUID id, Path sourceRoot, long inventoryRevision,
                      List<Asset> assets, Instant createdAt) {
    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(createdAt, "createdAt");
        assets = List.copyOf(assets);
        if (inventoryRevision < 0) {
            throw new IllegalArgumentException("inventoryRevision must be nonnegative");
        }
    }
}
