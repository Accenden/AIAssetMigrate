package io.github.aiassetmigrate.conversion;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything a converter may read: the fingerprint-verified main file and the files it depends on.
 * Dependency keys are portable paths relative to the main file's folder (for example {@code hero.mtl}
 * or {@code tex/hero.png}); a converter must never read anything that is not in this input.
 * Bytes are not copied defensively; callers must not modify arrays after construction.
 *
 * @param sourceName file name of the main file, used for format detection (for example {@code hero.obj})
 */
public record ConversionInput(String sourceName, byte[] source, Map<String, byte[]> dependencies,
                              String targetFormat, Map<String, String> options) {
    public ConversionInput {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetFormat, "targetFormat");
        dependencies = java.util.Collections.unmodifiableMap(new TreeMap<>(dependencies));
        options = Map.copyOf(options);
        if (sourceName.isBlank() || sourceName.contains("/") || sourceName.contains("\\")) {
            throw new IllegalArgumentException("sourceName must be a bare file name");
        }
        for (String key : dependencies.keySet()) {
            if (!key.equals(key(key))) throw new IllegalArgumentException("Dependency keys must be normalized portable paths: " + key);
        }
    }

    /** A single file with no dependencies (textures). */
    public static ConversionInput single(String sourceName, byte[] source, String targetFormat, Map<String, String> options) {
        return new ConversionInput(sourceName, source, Map.of(), targetFormat, options);
    }

    /** Normalized portable key for a path relative to the main file's folder; also used to match converter requests. */
    public static String key(String path) {
        String text = path.replace('\\', '/');
        while (text.startsWith("./")) text = text.substring(2);
        StringBuilder out = new StringBuilder();
        for (Path part : Path.of(text.isEmpty() ? "." : text).normalize()) {
            if (!out.isEmpty()) out.append('/');
            out.append(part);
        }
        return out.toString();
    }
}
