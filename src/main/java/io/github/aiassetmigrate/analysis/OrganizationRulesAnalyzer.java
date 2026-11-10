package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only rule suggestions and lexical destination checks, not reviews or reference validation. */
public final class OrganizationRulesAnalyzer {
    private OrganizationRulesAnalyzer() {}

    public static AssetManager.AnalysisResult analyze(InventorySnapshot snapshot, AssetManager.OrganizationRules rules) {
        return analyze(snapshot, rules, List.of());
    }

    /**
     * Reviews supply accepted CATEGORY decisions for {@link AssetManager.FolderStyle#BY_CATEGORY};
     * assets without a usable accepted category use their type folder instead.
     */
    public static AssetManager.AnalysisResult analyze(InventorySnapshot snapshot, AssetManager.OrganizationRules rules,
                                                      List<ReviewedChange> reviews) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(rules.nameStyle(), "nameStyle");
        Objects.requireNonNull(rules.folderStyle(), "folderStyle");
        List<AssetManager.Suggestion> suggestions = new ArrayList<>();
        List<ReviewedChange> decisions = new ArrayList<>();
        Map<UUID, String> categories = new java.util.HashMap<>();
        for (ReviewedChange review : List.copyOf(reviews)) {
            String category = review.values().get("category");
            if (review.kind() == ReviewedChange.Kind.CATEGORY && review.decision() == ReviewedChange.Decision.ACCEPTED
                    && category != null && OrganizationPreview.validName(category.strip())) {
                categories.put(review.assetId(), category.strip());
            }
        }
        List<Asset> assets = snapshot.project().assets().stream()
                .sorted(Comparator.comparing((Asset a) -> a.relativePath().toString()).thenComparing(Asset::id)).toList();
        for (Asset asset : assets) {
            String name = name(asset.relativePath().getFileName().toString(), rules.nameStyle());
            Path parent = asset.relativePath().getParent();
            String folder = rules.folderStyle() == AssetManager.FolderStyle.PRESERVE
                    ? parent == null ? "" : java.util.stream.StreamSupport.stream(parent.spliterator(), false)
                            .map(Path::toString).collect(Collectors.joining("/"))
                    : rules.folderStyle() == AssetManager.FolderStyle.BY_CATEGORY && categories.containsKey(asset.id())
                    ? categories.get(asset.id())
                    : typeFolder(asset.type());
            for (var kind : List.of(AssetManager.AnalysisKind.NAMES, AssetManager.AnalysisKind.FOLDERS)) {
                boolean isName = kind == AssetManager.AnalysisKind.NAMES;
                String value = isName ? name : folder;
                UUID id = id("suggestion:" + asset.id() + ":" + kind + ":" + value);
                suggestions.add(new AssetManager.Suggestion(id, asset.id(), kind, value));
                decisions.add(new ReviewedChange(id, asset.id(), isName ? ReviewedChange.Kind.NAME : ReviewedChange.Kind.FOLDER,
                        ReviewedChange.Decision.ACCEPTED, Map.of(isName ? "name" : "folder", value), snapshot.scannedAt()));
            }
        }
        var paths = assets.stream().collect(Collectors.toMap(Asset::id, Asset::relativePath));
        var preview = OrganizationPreview.preview(snapshot, snapshot.project().id(), snapshot.project().inventoryRevision(), decisions);
        List<Finding> findings = preview.findings().stream()
                .filter(f -> f.code().equals("INVALID_DESTINATION") || f.code().equals("DESTINATION_CONFLICT")
                        || f.code().equals("PARTIAL_INVENTORY") || f.code().equals("REFERENCE_REWRITE_UNSUPPORTED"))
                .map(f -> new Finding(id("finding:" + snapshot.project().id() + ":" + snapshot.project().inventoryRevision()
                                + ":" + f.code() + ":" + f.assetId().map(UUID::toString).orElse("") + ":" + f.message()),
                        f.assetId(), f.assetId().flatMap(assetId -> Optional.ofNullable(paths.get(assetId))),
                        Finding.Severity.ERROR, f.code(), f.message()))
                .toList();
        return new AssetManager.AnalysisResult(snapshot.project().inventoryRevision(), suggestions, findings);
    }

    private static String typeFolder(Asset.Type type) {
        return switch (type) {
            case MODEL -> "Models";
            case MATERIAL -> "Materials";
            case TEXTURE -> "Textures";
            case OTHER -> "Other";
        };
    }

    private static String name(String filename, AssetManager.NameStyle style) {
        if (style == AssetManager.NameStyle.PRESERVE || filename.startsWith(".")) return filename;
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        if (!stem.matches("[A-Za-z0-9 ._-]+")) return filename;
        String separator = style == AssetManager.NameStyle.SNAKE_CASE ? "_" : "-";
        String words = stem.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("^[ ._-]+|[ ._-]+$", "")
                .replaceAll("[ ._-]+", separator).toLowerCase(Locale.ROOT);
        return words.isEmpty() ? filename : words + extension;
    }

    private static UUID id(String text) {
        return UUID.nameUUIDFromBytes(("organization-rules:v1:" + text).getBytes(StandardCharsets.UTF_8));
    }
}
