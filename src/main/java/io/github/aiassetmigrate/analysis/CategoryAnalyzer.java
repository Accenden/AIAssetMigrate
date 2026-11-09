package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.application.AssetManager.AnalysisResult;
import io.github.aiassetmigrate.application.AssetManager.Suggestion;
import io.github.aiassetmigrate.domain.Asset;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Deterministic, conservative category suggestions; these are not reviewed decisions. */
public final class CategoryAnalyzer {
    private static final Set<String> CHARACTERS = Set.of(
            "character", "characters", "hero", "heroes", "npc", "npcs");
    private static final Set<String> WEAPONS = Set.of(
            "weapon", "weapons", "sword", "swords", "gun", "guns", "rifle", "rifles");
    private static final Set<String> TEXTURES = Set.of("texture", "textures");

    /**
     * Returns one suggestion per asset, in input order. Whole words in the relative
     * path (excluding the extension) are matched case-insensitively. Conflicting
     * or absent category signals yield Uncategorized, regardless of file type.
     * File type and metadata remain on the input assets and are never modified.
     */
    public AnalysisResult analyze(List<Asset> assets) {
        List<Suggestion> suggestions = List.copyOf(assets).stream().map(asset -> {
            String path = asset.relativePath().toString();
            int extension = path.lastIndexOf('.');
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (extension > separator) {
                path = path.substring(0, extension);
            }
            String words = path.replaceAll("([a-z])([A-Z])", "$1 $2")
                    .toLowerCase(Locale.ROOT);
            Set<String> tokens = Arrays.stream(words.split("[^\\p{L}\\p{N}]+"))
                    .collect(Collectors.toSet());
            boolean characters = tokens.stream().anyMatch(CHARACTERS::contains);
            boolean weapons = tokens.stream().anyMatch(WEAPONS::contains);
            boolean textures = tokens.stream().anyMatch(TEXTURES::contains);
            int matches = (characters ? 1 : 0) + (weapons ? 1 : 0) + (textures ? 1 : 0);
            String category = matches != 1 ? "Uncategorized"
                    : characters ? "Characters" : weapons ? "Weapons" : "Textures";
            UUID id = UUID.nameUUIDFromBytes(("categories:v1:" + asset.id() + ":" + category)
                    .getBytes(StandardCharsets.UTF_8));
            return new Suggestion(id, asset.id(), AnalysisKind.CATEGORIES, category);
        }).toList();
        return new AnalysisResult(suggestions, List.of());
    }
}
