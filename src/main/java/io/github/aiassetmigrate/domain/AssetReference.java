package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AssetReference(UUID ownerAssetId, Kind kind, String originalText,
                             Optional<Path> normalizedTargetPath, ResolutionStatus resolutionStatus) {
    public enum Kind { MATERIAL_LIBRARY, TEXTURE }
    public enum ResolutionStatus { RESOLVED, MISSING, SKIPPED, CASE_AMBIGUOUS, UNSAFE, UNRESOLVED }

    public AssetReference {
        Objects.requireNonNull(ownerAssetId, "ownerAssetId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(originalText, "originalText");
        Objects.requireNonNull(normalizedTargetPath, "normalizedTargetPath");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        normalizedTargetPath.ifPresent(path -> InventoryPath.requireRelative(path, false));
        if (resolutionStatus == ResolutionStatus.UNSAFE && normalizedTargetPath.isPresent()) {
            throw new IllegalArgumentException("Unsafe references cannot have a safe target path");
        }
        if (resolutionStatus != ResolutionStatus.UNSAFE && resolutionStatus != ResolutionStatus.UNRESOLVED
                && normalizedTargetPath.isEmpty()) {
            throw new IllegalArgumentException("Resolved target classification requires a target path");
        }
    }
}
