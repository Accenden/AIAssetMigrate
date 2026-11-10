package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.application.AssetManager.AnalysisKind;
import io.github.aiassetmigrate.application.AssetManager.AnalysisResult;
import io.github.aiassetmigrate.application.AssetManager.Suggestion;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.PortablePath;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic, conservative category suggestions; these are not reviewed decisions.
 * Whole words of the relative path (folders and file stem, not the extension) are matched
 * case-insensitively against a fixed vocabulary. Exactly one matching category is suggested;
 * conflicting signals yield Uncategorized with an explanatory finding. With no word signal,
 * textures and materials fall back to their file-type category and other assets remain
 * Uncategorized. File type and metadata on the input assets are never modified.
 */
public final class CategoryAnalyzer {
    public static final String UNCATEGORIZED = "Uncategorized";

    /** Ordered vocabulary; keys are the suggested category names. */
    private static final Map<String, Set<String>> VOCABULARY = vocabulary();

    private static Map<String, Set<String>> vocabulary() {
        Map<String, Set<String>> words = new LinkedHashMap<>();
        words.put("Characters", Set.of("character", "characters", "char", "chars", "hero", "heroes", "npc", "npcs",
                "enemy", "enemies", "creature", "creatures", "player", "players", "avatar", "avatars"));
        words.put("Weapons", Set.of("weapon", "weapons", "sword", "swords", "gun", "guns", "rifle", "rifles",
                "pistol", "pistols", "bow", "bows", "axe", "axes", "dagger", "daggers", "shield", "shields"));
        words.put("Props", Set.of("prop", "props", "furniture", "crate", "crates", "barrel", "barrels",
                "chair", "chairs", "table", "tables"));
        words.put("Environment", Set.of("environment", "environments", "env", "terrain", "landscape", "level",
                "levels", "building", "buildings", "tree", "trees", "rock", "rocks", "foliage"));
        words.put("Vehicles", Set.of("vehicle", "vehicles", "car", "cars", "truck", "trucks", "ship", "ships",
                "boat", "boats", "plane", "planes"));
        words.put("UI", Set.of("ui", "hud", "icon", "icons", "button", "buttons", "menu", "menus", "cursor"));
        words.put("Effects", Set.of("vfx", "fx", "effect", "effects", "particle", "particles"));
        words.put("Animations", Set.of("anim", "anims", "animation", "animations"));
        words.put("Textures", Set.of("texture", "textures"));
        words.put("Materials", Set.of("material", "materials"));
        return Map.copyOf(words);
    }

    private static final List<String> ORDER = List.of("Characters", "Weapons", "Props", "Environment", "Vehicles",
            "UI", "Effects", "Animations", "Textures", "Materials");

    /** Returns the category names this analyzer may suggest, in a stable order. */
    public static List<String> categories() {
        List<String> names = new ArrayList<>(ORDER);
        names.add(UNCATEGORIZED);
        return List.copyOf(names);
    }

    /** Returns one suggestion per asset, in input order. */
    public AnalysisResult analyze(List<Asset> assets, long inventoryRevision) {
        List<Suggestion> suggestions = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (Asset asset : List.copyOf(assets)) {
            Set<String> matches = new TreeSet<>(java.util.Comparator.comparingInt(ORDER::indexOf));
            for (String token : tokens(asset)) {
                VOCABULARY.forEach((category, words) -> { if (words.contains(token)) matches.add(category); });
            }
            String category;
            if (matches.size() == 1) {
                category = matches.iterator().next();
            } else if (matches.size() > 1) {
                category = UNCATEGORIZED;
                findings.add(finding(asset, "CATEGORY_CONFLICT", "Conflicting category signals ("
                        + String.join(", ", matches) + "); left Uncategorized for review"));
            } else {
                category = switch (asset.type()) {
                    case TEXTURE -> "Textures";
                    case MATERIAL -> "Materials";
                    case MODEL, OTHER -> UNCATEGORIZED;
                };
            }
            UUID id = UUID.nameUUIDFromBytes(("categories:v2:" + asset.id() + ":" + category)
                    .getBytes(StandardCharsets.UTF_8));
            suggestions.add(new Suggestion(id, asset.id(), AnalysisKind.CATEGORIES, category));
        }
        return new AnalysisResult(inventoryRevision, suggestions, findings);
    }

    private static Set<String> tokens(Asset asset) {
        String path = PortablePath.of(asset.relativePath());
        int extension = path.lastIndexOf('.');
        if (extension > path.lastIndexOf('/')) path = path.substring(0, extension);
        String words = path.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Arrays.stream(words.split("[^\\p{L}\\p{N}]+")).filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
    }

    private static Finding finding(Asset asset, String code, String message) {
        UUID id = UUID.nameUUIDFromBytes(("categories:v2:finding:" + asset.id() + ":" + code)
                .getBytes(StandardCharsets.UTF_8));
        return new Finding(id, Optional.of(asset.id()), Optional.of(asset.relativePath()),
                Finding.Severity.WARNING, code, message);
    }
}
