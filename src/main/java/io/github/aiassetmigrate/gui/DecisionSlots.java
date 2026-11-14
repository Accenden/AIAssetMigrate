package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Matches suggestions to saved decisions by the decision they would fill (the "slot"), the same way the
 * manager replaces an earlier decision with a later one: one per file for categories, names and folders;
 * one per reference for mappings and corrections; a reference-less grouping is its own decision per target.
 */
final class DecisionSlots {
    private DecisionSlots() {}

    static String of(AssetManager.Suggestion suggestion) {
        String reference = suggestion.reference().orElse(null);
        String detail = switch (suggestion.kind()) {
            case CATEGORIES, NAMES, FOLDERS -> "";
            case MATERIALS -> "ref:" + reference;
            case RELATIONSHIPS -> reference != null ? "ref:" + reference : "target:" + suggestion.proposedValue();
        };
        return suggestion.assetId() + "|" + AssetMigrateApplication.suggestionKind(suggestion.kind()) + "|" + detail;
    }

    /** Empty for decisions no check suggests (exclusions, acknowledgments, conversions). */
    static Optional<String> of(ReviewedChange change) {
        String detail = switch (change.kind()) {
            case CATEGORY, NAME, FOLDER -> "";
            case MATERIAL_MAPPING -> "ref:" + change.values().get("reference");
            case RELATIONSHIP -> change.values().containsKey("reference") ? "ref:" + change.values().get("reference")
                    : "target:" + change.values().get("targetAssetId");
            default -> null;
        };
        return detail == null ? Optional.empty() : Optional.of(change.assetId() + "|" + change.kind() + "|" + detail);
    }

    /**
     * The suggestions a saved decision already settles, by suggestion id, with the note shown for them:
     * the same value decided either way, or a different value already accepted for that slot. A suggestion
     * whose slot only holds a rejection of a different value stays open: it is a new option.
     */
    static Map<UUID, String> alreadyDecided(List<AssetManager.Suggestion> suggestions, List<ReviewedChange> saved) {
        Map<String, ReviewedChange> bySlot = new HashMap<>();
        for (ReviewedChange change : saved) of(change).ifPresent(slot -> bySlot.put(slot, change));
        Map<UUID, String> decided = new LinkedHashMap<>();
        for (AssetManager.Suggestion suggestion : suggestions) {
            ReviewedChange change = bySlot.get(of(suggestion));
            if (change == null) continue;
            if (suggestion.proposedValue().trim().equals(value(change))) {
                decided.put(suggestion.id(), change.decision() == ReviewedChange.Decision.ACCEPTED ? "Accepted" : "Rejected");
            } else if (change.decision() == ReviewedChange.Decision.ACCEPTED) {
                decided.put(suggestion.id(), "Not used: another suggestion was accepted");
            }
        }
        return decided;
    }

    private static String value(ReviewedChange change) {
        return switch (change.kind()) {
            case CATEGORY -> change.values().get("category");
            case NAME -> change.values().get("name");
            case FOLDER -> change.values().get("folder");
            default -> change.values().get("targetAssetId");
        };
    }
}
