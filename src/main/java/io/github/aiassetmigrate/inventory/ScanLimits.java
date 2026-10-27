package io.github.aiassetmigrate.inventory;

/** Root depth is zero; entries exclude the root. Text budgets are reserved for format inspection. */
public record ScanLimits(int maxDepth, int maxEntries, int maxTextLineBytes,
                         long maxParsedTextBytes, int maxReferencesPerFile) {
    public static final ScanLimits DEFAULTS = new ScanLimits(64, 100_000, 1024 * 1024,
            64L * 1024 * 1024, 100_000);

    public ScanLimits {
        if (maxDepth < 0 || maxEntries <= 0 || maxTextLineBytes <= 0
                || maxParsedTextBytes <= 0 || maxReferencesPerFile <= 0) {
            throw new IllegalArgumentException("Scan limits must be positive (depth may be zero)");
        }
    }
}
