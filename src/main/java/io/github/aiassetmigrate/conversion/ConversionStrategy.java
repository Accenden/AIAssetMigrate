package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;

import java.io.IOException;
import java.util.Map;

/**
 * Strategy for converting one asset format to another. The executor selects an installed
 * strategy by {@link #supports} and records its {@link #id} in the approved command, so a
 * plan cannot silently switch to a different converter.
 */
public interface ConversionStrategy {
    /** Stable identifier stored as a command's converter requirement. */
    String id();

    /** True when this strategy can convert the asset to the target format. */
    boolean supports(Asset asset, String targetFormat);

    /**
     * Validates options and source content without producing output, for example that a
     * transparent texture converted to JPEG has an approved background colour.
     */
    void check(byte[] source, String targetFormat, Map<String, String> options) throws IOException;

    /** Converts already fingerprint-verified source bytes. */
    byte[] convert(byte[] source, String targetFormat, Map<String, String> options) throws IOException;
}
