package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Migrate step's report sections: the AI summary card and the findings grouped by outcome. */
class ReportSectionsTest {
    private static Finding finding(Finding.Severity severity, String code, String path, String message) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.ofNullable(path).map(Path::of), severity, code, message);
    }

    private static Report report(Optional<String> ai, List<Finding> findings) {
        return new Report(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RunStatus.SUCCESS,
                Report.ValidationStatus.FAILED, findings, "summary", ai, Instant.EPOCH);
    }

    @Test void problemsAreGroupedByOutcomeAndTheAiStepIsNotAFinding() {
        var sections = AssetMigrateApplication.reportSections(report(Optional.of("Replace misc/broken.png."), List.of(
                finding(Finding.Severity.INFO, "OUTPUT_VERIFIED", "a.png", "ok"),
                finding(Finding.Severity.INFO, "OUTPUT_VERIFIED", "b.png", "ok"),
                finding(Finding.Severity.ERROR, "OUTPUT_FORMAT_INVALID", "misc/broken.png", "does not decode"),
                finding(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", "props/crate_01.fbx", "not read"),
                finding(Finding.Severity.WARNING, "OUTPUT_NOT_PRODUCED", "c.png", "not produced"),
                finding(Finding.Severity.INFO, "AI_SOURCE", null, "AI summary from test model; sent statuses only"))));
        assertEquals(2, sections.passed());
        assertEquals(List.of("OUTPUT_FORMAT_INVALID"), sections.failed().stream().map(Finding::code).toList());
        assertEquals(List.of("OUTPUT_FORMAT_UNCHECKED"), sections.unchecked().stream().map(Finding::code).toList());
        assertEquals(List.of("OUTPUT_NOT_PRODUCED"), sections.warnings().stream().map(Finding::code).toList());
        assertEquals(Optional.of("AI summary from test model; sent statuses only"), sections.aiSource());
        assertTrue(sections.aiMissing().isEmpty());
    }

    @Test void whenTheAiSummaryWasAskedForButNotGivenTheReasonIsKept() {
        var sections = AssetMigrateApplication.reportSections(report(Optional.empty(), List.of(
                finding(Finding.Severity.WARNING, "AI_UNAVAILABLE", null, "AI summary unavailable (NOT_CONNECTED): Not connected"))));
        assertEquals(Optional.of("AI summary unavailable (NOT_CONNECTED): Not connected"), sections.aiMissing());
        assertTrue(sections.warnings().isEmpty(), "the AI step's own note is not listed as a finding about outputs");
    }
}
