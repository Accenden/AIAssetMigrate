package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;

import java.io.IOException;
import java.util.Map;

/**
 * Strategy for converting one asset format to another. The executor selects an installed
 * strategy by {@link #supports} and records its {@link #id} in the approved command, so a
 * plan cannot silently switch to a different converter. A strategy reads only what its
 * {@link ConversionInput} contains.
 */
public interface ConversionStrategy {
    /** Stable identifier stored as a command's converter requirement; includes a version where one applies. */
    String id();

    /** True when this strategy can convert the asset to the target format. */
    boolean supports(Asset asset, String targetFormat);

    /** Canonical name of a target format this strategy can produce (for example {@code jpeg} to {@code jpg}), or null. */
    String normalizeFormat(String format);

    /**
     * Validates reviewed conversion values ({@code format} plus options) without reading content.
     *
     * @throws IllegalArgumentException with a user-facing message
     */
    void validateOptions(String targetFormat, Map<String, String> options);

    /** True when the conversion reads files besides the main file, such as an OBJ's MTL and textures. */
    default boolean needsDependencies() { return false; }

    /**
     * Validates the input content without producing output, for example that a transparent
     * texture converted to JPEG has an approved background colour.
     */
    void check(ConversionInput input) throws IOException;

    /** Converts already fingerprint-verified input. */
    byte[] convert(ConversionInput input) throws IOException;

    /** Single-file convenience for strategies without dependencies. */
    default void check(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        check(ConversionInput.single("source", source, targetFormat, options));
    }

    /** Single-file convenience for strategies without dependencies. */
    default byte[] convert(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        return convert(ConversionInput.single("source", source, targetFormat, options));
    }
}
