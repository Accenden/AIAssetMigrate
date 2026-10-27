package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AssetOperationCommand(UUID id, UUID assetId, Operation operation,
                                    Path relativeOutputPath, Map<String, String> parameters,
                                    List<UUID> dependencies, Optional<String> converterRequirement)
        implements MigrationCommand {
    public AssetOperationCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(relativeOutputPath, "relativeOutputPath");
        Objects.requireNonNull(converterRequirement, "converterRequirement");
        parameters = Map.copyOf(parameters);
        dependencies = List.copyOf(dependencies);
        if (relativeOutputPath.isAbsolute()) {
            throw new IllegalArgumentException("relativeOutputPath must be relative");
        }
    }
}
