package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.ReviewedChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What "Accept all" in the Review step would save. Every suggestion becomes an ACCEPTED review, with one
 * decision per slot ({@link DecisionSlots}, as the manager keeps them):
 * <ul>
 *   <li>identical duplicates (a rule and the AI proposing the same value) are accepted once;</li>
 *   <li>when the rules and the AI disagree, the AI's suggestion is accepted and the rule's is set aside. The
 *       AI is asked exactly where the rules are unsure or could do better (for example files the rules left
 *       Uncategorized), so leaving both would skip the files the AI was used for. Nothing is saved for the
 *       set-aside suggestion: a second decision in the same slot would replace the accepted one;</li>
 *   <li>a real tie (several suggestions from the same source, such as two equally named files that could fix
 *       one broken reference) is left for the user, because guessing could rewrite a reference wrongly;</li>
 *   <li>suggestions that cannot be saved as they are are left for Edit.</li>
 * </ul>
 */
record AcceptAll(List<AssetManager.Suggestion> covered, List<ReviewedChange> changes,
                 List<AssetManager.Suggestion> setAside, List<AssetManager.Suggestion> conflicting,
                 List<AssetManager.Suggestion> invalid) {
    AcceptAll {
        covered = List.copyOf(covered);
        changes = List.copyOf(changes);
        setAside = List.copyOf(setAside);
        conflicting = List.copyOf(conflicting);
        invalid = List.copyOf(invalid);
    }

    static AcceptAll of(List<AssetManager.Suggestion> suggestions) {
        Map<String, List<AssetManager.Suggestion>> slots = new LinkedHashMap<>();
        for (AssetManager.Suggestion suggestion : suggestions) {
            slots.computeIfAbsent(DecisionSlots.of(suggestion), key -> new ArrayList<>()).add(suggestion);
        }
        List<AssetManager.Suggestion> covered = new ArrayList<>();
        List<ReviewedChange> changes = new ArrayList<>();
        List<AssetManager.Suggestion> setAside = new ArrayList<>();
        List<AssetManager.Suggestion> conflicting = new ArrayList<>();
        List<AssetManager.Suggestion> invalid = new ArrayList<>();
        for (List<AssetManager.Suggestion> group : slots.values()) {
            List<AssetManager.Suggestion> chosen = group;
            if (distinctValues(group) > 1) {
                List<AssetManager.Suggestion> fromAi = group.stream().filter(s -> s.source() == AssetManager.Source.AI).toList();
                if (fromAi.isEmpty() || distinctValues(fromAi) > 1) {
                    conflicting.addAll(group);
                    continue;
                }
                String aiValue = fromAi.getFirst().proposedValue();
                chosen = group.stream().filter(s -> s.proposedValue().equals(aiValue)).toList();
            }
            AssetManager.Suggestion first = chosen.getFirst();
            try {
                changes.add(AssetMigrateApplication.makeReview(first, ReviewedChange.Decision.ACCEPTED,
                        first.proposedValue(), first.reference().orElse("")));
            } catch (IllegalArgumentException cannotSave) {
                invalid.addAll(group);
                continue;
            }
            covered.addAll(chosen);
            group.stream().filter(s -> !s.proposedValue().equals(first.proposedValue())).forEach(setAside::add);
        }
        return new AcceptAll(covered, changes, setAside, conflicting, invalid);
    }

    private static long distinctValues(List<AssetManager.Suggestion> group) {
        return group.stream().map(AssetManager.Suggestion::proposedValue).distinct().count();
    }

    long fromAi() {
        return covered.stream().filter(s -> s.source() == AssetManager.Source.AI).count();
    }

    /** Files where the AI's suggestion was taken over a different rule suggestion. */
    long aiPreferredFiles() {
        return setAside.stream().map(AssetManager.Suggestion::assetId).distinct().count();
    }

    /** Files whose suggestions are tied; each needs one suggestion picked by hand. */
    long conflictingFiles() {
        return conflicting.stream().map(AssetManager.Suggestion::assetId).distinct().count();
    }
}
