package io.github.aiassetmigrate.inventory;

/** Root depth is zero; entries exclude the root. Reference budget is reserved for reference extraction. */
public record ScanLimits(int maxDepth, int maxEntries, long maxFileBytes, int maxTextLineBytes,
                          long maxParsedTextBytes, int maxReferencesPerFile) {
    public static final ScanLimits DEFAULTS = new ScanLimits(64, 100_000, 1024L * 1024 * 1024, 1024 * 1024,
            64L * 1024 * 1024, 100_000);

    /** Compatibility constructor retaining the original defaults for the file-size budget. */
    public ScanLimits(int maxDepth, int maxEntries, int maxTextLineBytes,
                      long maxParsedTextBytes, int maxReferencesPerFile) {
        this(maxDepth, maxEntries, DEFAULTS.maxFileBytes, maxTextLineBytes, maxParsedTextBytes,
                maxReferencesPerFile);
    }

    public ScanLimits {
        if (maxDepth < 0 || maxEntries <= 0 || maxFileBytes <= 0 || maxTextLineBytes <= 0
                || maxParsedTextBytes <= 0 || maxReferencesPerFile <= 0) {
            throw new IllegalArgumentException("Scan limits must be positive (depth may be zero)");
        }
    }
}
