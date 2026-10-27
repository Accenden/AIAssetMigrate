package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Immutable operation description, not an execution or authorization API. */
public sealed interface MigrationCommand permits AssetOperationCommand {
    enum Operation { COPY, CONVERT_TEXTURE, REWRITE_REFERENCES }

    UUID id();
    UUID assetId();
    Operation operation();
    Path relativeOutputPath();
    Map<String, String> parameters();
    List<UUID> dependencies();
    Optional<String> converterRequirement();
}
