package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.PortablePath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Target step's "Issues to look at": one entry per non-INFO finding code with its worst severity,
 * how many findings and which example files it covers, the rule's own wording, and the AI explanation
 * when one was requested and accepted. AI status findings become notes. Pure data; the rule-based
 * findings decide severities, never the AI text.
 */
record CompatibilitySummary(List<Issue> issues, List<String> notes, Optional<String> aiSource) {
    static final int MAX_EXAMPLES = 3;
    private static final Pattern EXPLANATION = Pattern.compile("([A-Z0-9_]+): (.*?)(?: \\(AI explanation; advisory\\))?",
            Pattern.DOTALL);

    CompatibilitySummary {
        issues = List.copyOf(issues);
        notes = List.copyOf(notes);
    }

    record Issue(String code, Finding.Severity severity, int count, List<String> examples, String ruleMessage,
                 Optional<String> aiExplanation) {
        Issue {
            examples = List.copyOf(examples);
        }
    }

    static CompatibilitySummary of(List<Finding> findings) {
        Map<String, List<Finding>> byCode = new LinkedHashMap<>();
        Map<String, String> explanations = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Optional<String> source = Optional.empty();
        for (Finding finding : findings) {
            switch (finding.code()) {
                case "AI_EXPLANATION" -> {
                    Matcher matcher = EXPLANATION.matcher(finding.message());
                    if (matcher.matches()) explanations.putIfAbsent(matcher.group(1), matcher.group(2).strip());
                }
                case "AI_SOURCE" -> source = Optional.of(finding.message());
                case "AI_NOT_NEEDED" -> notes.add("AI was not asked: there were no issues to explain.");
                case "AI_UNAVAILABLE" -> notes.add("AI explanations are unavailable, so the rules' own wording is shown. "
                        + finding.message());
                case "AI_OUTPUT_REJECTED", "AI_LIMIT" -> notes.add(finding.message());
                default -> {
                    if (finding.severity() != Finding.Severity.INFO) {
                        byCode.computeIfAbsent(finding.code(), code -> new ArrayList<>()).add(finding);
                    }
                }
            }
        }
        List<Issue> issues = new ArrayList<>();
        for (var entry : byCode.entrySet()) {
            List<Finding> group = entry.getValue();
            Finding.Severity worst = group.stream().map(Finding::severity).min(Comparator.comparingInt(CompatibilitySummary::rank))
                    .orElseThrow();
            List<String> examples = group.stream().flatMap(f -> f.sourceRelativePath().stream()).map(PortablePath::of)
                    .distinct().limit(MAX_EXAMPLES).toList();
            issues.add(new Issue(entry.getKey(), worst, group.size(), examples, group.getFirst().message(),
                    Optional.ofNullable(explanations.get(entry.getKey()))));
        }
        // Errors first (they block a plan), then checks that could not be done, then warnings.
        issues.sort(Comparator.comparingInt((Issue issue) -> rank(issue.severity())).thenComparing(Issue::code));
        return new CompatibilitySummary(issues, notes, source);
    }

    long explained() {
        return issues.stream().filter(issue -> issue.aiExplanation().isPresent()).count();
    }

    private static int rank(Finding.Severity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case UNKNOWN -> 1;
            case WARNING -> 2;
            case INFO -> 3;
        };
    }
}
