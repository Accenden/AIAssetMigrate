package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record Asset(UUID id, Path relativePath, Type type, String fingerprint,
                    Map<String, String> metadata) {
    public enum Type { MODEL, MATERIAL, TEXTURE, OTHER }

    /** Format is a lowercase extension; sizeBytes is decimal; modifiedAt is an ISO-8601 instant. */
    public static final String FORMAT = "format";
    public static final String SIZE_BYTES = "sizeBytes";
    public static final String MODIFIED_AT = "modifiedAt";
    /** Value is an InspectionStatus enum name, not a claim of complete format conformance. */
    public static final String INSPECTION_STATUS = "inspectionStatus";
    /** PNG/JPEG must use NOT_PERFORMED: a fingerprint does not validate image contents. */
    public enum InspectionStatus { NOT_PERFORMED, BASIC_CHECKED, HEADER_ONLY, UNKNOWN }

    public Asset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fingerprint, "fingerprint");
        metadata = Map.copyOf(metadata);
        InventoryPath.requireRelative(relativePath, false);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256 hex");
        }
    }
}
