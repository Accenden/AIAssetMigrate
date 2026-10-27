package io.github.aiassetmigrate.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReviewedChange(UUID id, UUID assetId, Kind kind, Decision decision,
                             Map<String, String> values, Instant reviewedAt) {
    public enum Kind { CATEGORY, RELATIONSHIP, MATERIAL_MAPPING, NAME, FOLDER, EXCLUSION }
    public enum Decision { ACCEPTED, REJECTED }

    public ReviewedChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        values = Map.copyOf(values);
    }
}
