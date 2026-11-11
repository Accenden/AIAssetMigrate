package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;

import java.util.List;
import java.util.Optional;

/** The installed conversion strategies, in selection order. */
public final class Converters {
    private static final List<ConversionStrategy> INSTALLED = List.of(new TextureConverter(), new ModelConverter());

    private Converters() {}

    public static List<ConversionStrategy> installed() { return INSTALLED; }

    /** Selects the first installed strategy that supports the conversion. */
    public static Optional<ConversionStrategy> find(Asset asset, String targetFormat) {
        return INSTALLED.stream().filter(strategy -> strategy.supports(asset, targetFormat)).findFirst();
    }

    /** Returns the installed strategy with the given ID, as recorded in an approved command. */
    public static Optional<ConversionStrategy> byId(String id) {
        return INSTALLED.stream().filter(strategy -> strategy.id().equals(id)).findFirst();
    }
}
