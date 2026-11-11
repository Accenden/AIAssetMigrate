package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;

import java.io.IOException;
import java.util.Map;

/**
 * Model conversion strategy. No model converter (such as Blender) is integrated, so this
 * strategy truthfully supports nothing; model conversions are reported as unsupported
 * instead of being simulated.
 */
public final class ModelConverter implements ConversionStrategy {
    public static final String ID = "model-unavailable";

    @Override public String id() { return ID; }

    @Override public boolean supports(Asset asset, String targetFormat) { return false; }

    @Override public void check(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        throw new IOException("No model converter is installed; model conversion is not supported");
    }

    @Override public byte[] convert(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        throw new IOException("No model converter is installed; model conversion is not supported");
    }
}
