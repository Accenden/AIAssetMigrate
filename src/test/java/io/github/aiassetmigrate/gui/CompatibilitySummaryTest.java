package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilitySummaryTest {
    private static Finding finding(Finding.Severity severity, String code, String path, String message) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.ofNullable(path).map(Path::of), severity, code, message);
    }

    @Test void groupsIssuesByCodeAndAttachesTheAiExplanationToTheRightOne() {
        var findings = List.of(
                finding(Finding.Severity.WARNING, "TARGET_SUPPORT_UNKNOWN", "a/hero.mtl", "Unity MTL handling is not established."),
                finding(Finding.Severity.WARNING, "TARGET_SUPPORT_UNKNOWN", "b/crate.mtl", "Unity MTL handling is not established."),
                finding(Finding.Severity.ERROR, "UNSUPPORTED_FORMAT", "c/x.blend", "Blend files need Blender."),
                finding(Finding.Severity.UNKNOWN, "FORMAT_LIMITATIONS", null, "Not checked."),
                finding(Finding.Severity.INFO, "INSTALLED_CONVERTERS", null, "info lines are not issues"),
                finding(Finding.Severity.INFO, "AI_EXPLANATION", null,
                        "TARGET_SUPPORT_UNKNOWN: Import the OBJ and MTL together. (AI explanation; advisory)"),
                finding(Finding.Severity.INFO, "AI_SOURCE", null, "AI explanations from a model"));
        var summary = CompatibilitySummary.of(findings);
        assertEquals(List.of("UNSUPPORTED_FORMAT", "FORMAT_LIMITATIONS", "TARGET_SUPPORT_UNKNOWN"),
                summary.issues().stream().map(CompatibilitySummary.Issue::code).toList(), "errors, then not checked, then warnings");
        var mtl = summary.issues().get(2);
        assertEquals(2, mtl.count());
        assertEquals(List.of("a/hero.mtl", "b/crate.mtl"), mtl.examples());
        assertEquals(Optional.of("Import the OBJ and MTL together."), mtl.aiExplanation(), "the advisory suffix is removed");
        assertEquals("Unity MTL handling is not established.", mtl.ruleMessage());
        assertTrue(summary.issues().get(0).aiExplanation().isEmpty());
        assertEquals(1, summary.explained());
        assertEquals(Optional.of("AI explanations from a model"), summary.aiSource());
        assertTrue(summary.notes().isEmpty());
    }

    @Test void aiFailuresBecomeNotesAndRuleWordingIsKept() {
        var summary = CompatibilitySummary.of(List.of(
                finding(Finding.Severity.WARNING, "CONTENT_UNVERIFIED", "a.png", "Content is unverified."),
                finding(Finding.Severity.WARNING, "AI_UNAVAILABLE", null, "AI explanations unavailable (NOT_CONNECTED): Not connected")));
        assertEquals(1, summary.issues().size(), "AI status findings are not issues");
        assertTrue(summary.issues().getFirst().aiExplanation().isEmpty());
        assertEquals(1, summary.notes().size());
        assertTrue(summary.notes().getFirst().contains("unavailable") && summary.notes().getFirst().contains("Not connected"));
        assertEquals(0, summary.explained());
    }

    @Test void examplesAreCappedAndEmptyAssessmentsHaveNoIssues() {
        List<Finding> many = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) many.add(finding(Finding.Severity.WARNING, "CONTENT_UNVERIFIED", "f" + i + ".png", "m"));
        var summary = CompatibilitySummary.of(many);
        assertEquals(7, summary.issues().getFirst().count());
        assertEquals(CompatibilitySummary.MAX_EXAMPLES, summary.issues().getFirst().examples().size());
        assertTrue(CompatibilitySummary.of(List.of()).issues().isEmpty());
    }
}
