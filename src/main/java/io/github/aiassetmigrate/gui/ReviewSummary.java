package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Finding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-language summary of one AI-assisted check for the Review step: an overview, check-wide notes,
 * and one entry per file with what was found and what is suggested for it. Pure data, built off the
 * JavaFX thread or in tests; it never accepts anything.
 */
record ReviewSummary(String overview, List<String> notes, List<Item> items) {
    /** Files listed in the box at once; the rest stay in the suggestions table. */
    static final int MAX_ITEMS = 40;

    /** Finding codes the summary shows elsewhere: as a suggestion's reason or as the suggestion itself. */
    private static final List<String> COVERED_BY_SUGGESTIONS = List.of("AI_SUGGESTION", "REFERENCE_CANDIDATE");
    private static final Pattern REFERENCE_MESSAGE = Pattern.compile("Reference '(.*)': [A-Z_]+");

    ReviewSummary {
        notes = List.copyOf(notes);
        items = List.copyOf(items);
    }

    /** The files listed when nothing is selected. */
    List<Item> shown() {
        return items.subList(0, Math.min(items.size(), MAX_ITEMS));
    }

    int hiddenItems() {
        return items.size() - shown().size();
    }

    /** The entry for one file, found even when it is past the listing limit. */
    Optional<Item> itemFor(UUID assetId) {
        return items.stream().filter(item -> item.assetId().equals(assetId)).findFirst();
    }

    /** One file: its problems, and the suggestions for it (or a next step when there are none). */
    record Item(UUID assetId, String file, List<String> issues, List<Line> suggestions, Optional<String> nextStep) {
        Item {
            issues = List.copyOf(issues);
            suggestions = List.copyOf(suggestions);
        }
    }

    /** A suggestion in words; {@code suggestion} identifies the matching table row. */
    record Line(AssetManager.Suggestion suggestion, String text, boolean ai, Optional<String> reason) {}

    static ReviewSummary of(AssetManager.AnalysisResult result, Map<UUID, String> paths) {
        Map<UUID, List<String>> issues = new LinkedHashMap<>();
        Map<UUID, String> issueCodes = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (Finding finding : result.findings()) {
            if (finding.assetId().isEmpty()) {
                notes.add(note(finding));
            } else if (!COVERED_BY_SUGGESTIONS.contains(finding.code())) {
                UUID id = finding.assetId().get();
                issues.computeIfAbsent(id, key -> new ArrayList<>()).add(issue(finding));
                issueCodes.putIfAbsent(id, finding.code());
            }
        }
        Map<UUID, List<Line>> lines = new LinkedHashMap<>();
        int ai = 0;
        for (AssetManager.Suggestion suggestion : result.suggestions()) {
            boolean fromAi = suggestion.source() == AssetManager.Source.AI;
            if (fromAi) ai++;
            lines.computeIfAbsent(suggestion.assetId(), key -> new ArrayList<>())
                    .add(new Line(suggestion, describe(suggestion, paths), fromAi, suggestion.reason()));
        }
        List<UUID> ids = new ArrayList<>(issues.keySet());
        lines.keySet().stream().filter(id -> !issues.containsKey(id)).forEach(ids::add);
        List<Item> items = new ArrayList<>();
        for (UUID id : ids) {
            List<Line> suggested = lines.getOrDefault(id, List.of());
            Optional<String> next = suggested.isEmpty()
                    ? Optional.of(nextStep(issueCodes.getOrDefault(id, ""))) : Optional.empty();
            items.add(new Item(id, paths.getOrDefault(id, id.toString()), issues.getOrDefault(id, List.of()), suggested, next));
        }
        // Problems first, then files the AI had something to say about, then the rest; by path within each.
        items.sort(Comparator.comparingInt(ReviewSummary::rank).thenComparing(Item::file, String.CASE_INSENSITIVE_ORDER));
        String overview = overview(items.size(), (int) items.stream().filter(item -> !item.issues().isEmpty()).count(),
                result.suggestions().size(), ai);
        return new ReviewSummary(overview, notes, items);
    }

    private static int rank(Item item) {
        if (!item.issues().isEmpty()) return 0;
        return item.suggestions().stream().anyMatch(Line::ai) ? 1 : 2;
    }

    private static String overview(int files, int withIssues, int suggestions, int ai) {
        if (files == 0) return "Nothing to review: this check found no problems and has no suggestions.";
        return plural(files, "file") + " to review"
                + (withIssues > 0 ? ", " + withIssues + " with a problem" : "") + ". "
                + plural(suggestions, "suggestion") + " (" + ai + " from AI, " + (suggestions - ai) + " from rules). "
                + "Click a suggestion to select it in the table, then Accept or Reject it.";
    }

    /** What the suggestion would do, in words; reference targets are shown by path. */
    static String describe(AssetManager.Suggestion suggestion, Map<UUID, String> paths) {
        String value = suggestion.proposedValue();
        return switch (suggestion.kind()) {
            case CATEGORIES -> value.equals(io.github.aiassetmigrate.analysis.CategoryAnalyzer.UNCATEGORIZED) ? "Leave Uncategorized" : "Category: " + value;
            case NAMES -> "Rename to " + value;
            case FOLDERS -> value.isEmpty() ? "Move to the output folder's root" : "Move to folder " + value;
            case RELATIONSHIPS, MATERIALS -> {
                String target = target(value, paths);
                yield suggestion.reference().map(reference -> "Point '" + reference + "' at " + target)
                        .orElse("Group with " + target);
            }
        };
    }

    private static String target(String value, Map<UUID, String> paths) {
        try {
            return paths.getOrDefault(UUID.fromString(value), value);
        } catch (IllegalArgumentException notAnId) {
            return value;
        }
    }

    /** A per-file finding in plain words. Reference findings are parsed so the status reads as a sentence. */
    static String issue(Finding finding) {
        Matcher reference = REFERENCE_MESSAGE.matcher(finding.message());
        String quoted = reference.matches() ? "'" + reference.group(1) + "'" : "a file";
        return switch (finding.code()) {
            case "REFERENCE_MISSING" -> "Points to " + quoted + ", which is not in the folder.";
            case "REFERENCE_CASE_AMBIGUOUS" -> "Points to " + quoted + ", but the file's capitals differ (or several match).";
            case "REFERENCE_UNRESOLVED" -> "Points to " + quoted + ", which could not be found where it says.";
            case "REFERENCE_UNSAFE" -> "Points to " + quoted + ", which leaves the asset folder and is ignored.";
            case "REFERENCE_SKIPPED" -> "Points to " + quoted + ", which the scan skipped.";
            case "INSPECTION_UNSUPPORTED" -> "Its references cannot be checked for this format.";
            default -> finding.message();
        };
    }

    private static String note(Finding finding) {
        return switch (finding.code()) {
            case "AI_NOT_NEEDED" -> "AI was not needed: nothing in this check was uncertain.";
            case "AI_UNAVAILABLE" -> "AI was unavailable, so only rule-based suggestions are shown. " + finding.message();
            default -> finding.message();
        };
    }

    /** What to do for a file that has a problem but no suggestion. */
    static String nextStep(String code) {
        if (code.startsWith("REFERENCE_")) {
            return "No matching file was found. Exclude it, or enter the right file yourself under Edit or add a decision.";
        }
        return switch (code) {
            case "INSPECTION_UNSUPPORTED" -> "Add an ACKNOWLEDGMENT to include it anyway, or an EXCLUSION to leave it out, "
                    + "under Edit or add a decision.";
            case "CATEGORY_CONFLICT" -> "Pick the category yourself under Edit or add a decision, or leave it Uncategorized.";
            default -> "Decide what to do with it under Edit or add a decision.";
        };
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
