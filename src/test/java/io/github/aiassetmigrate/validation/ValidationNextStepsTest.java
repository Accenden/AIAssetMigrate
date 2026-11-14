package io.github.aiassetmigrate.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.ai.AIEngine;
import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ValidationNextStepsTest {
    private static Finding finding(Finding.Severity severity, String code, String path) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.ofNullable(path).map(Path::of), severity, code, "message");
    }

    /** The two problems from the messy sample project, plus passes that need nothing. */
    private static final List<Finding> MESSY = List.of(
            finding(Finding.Severity.INFO, "OUTPUT_VERIFIED", "textures/sword_tex.png"),
            finding(Finding.Severity.ERROR, "OUTPUT_FORMAT_INVALID", "misc/broken.png"),
            finding(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", "props/crate_01.fbx"),
            finding(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", "textures/huge.png"));

    @Test void eachProblemGetsAnActionThisToolCanDoIncludingAnFbxTheReaderCannotRead() {
        var steps = ValidationNextSteps.of(MESSY);
        assertEquals(3, steps.size(), "the FBX and the oversized image need different advice");
        assertEquals(List.of("misc/broken.png"), steps.get(0).files());
        assertTrue(steps.get(0).action().contains("Replace it in your asset folder and Rescan"));
        assertEquals(List.of("props/crate_01.fbx"), steps.get(1).files());
        assertTrue(steps.get(0).canLeaveOut() && steps.get(1).canLeaveOut(), "a damaged or unreadable file can be left out");
        assertTrue(steps.get(1).action().contains("built-in FBX reader could not read"));
        assertTrue(steps.get(1).action().contains("binary FBX (version 7.4 or later)"));
        assertFalse(steps.stream().anyMatch(s -> s.action().toLowerCase().contains("checker can")), "no imaginary checkers");
        String text = ValidationNextSteps.describe(steps);
        assertTrue(text.startsWith("What to do next:\n• misc/broken.png: The file does not decode"), text);
        assertTrue(text.endsWith(ValidationNextSteps.NEW_RUN), text);
    }

    @Test void filesWithTheSameProblemAreGroupedAndAPassNeedsNothing() {
        var steps = ValidationNextSteps.of(List.of(
                finding(Finding.Severity.ERROR, "OUTPUT_MISSING", "a.png"), finding(Finding.Severity.ERROR, "OUTPUT_CHANGED", "b.png"),
                finding(Finding.Severity.ERROR, "OUTPUT_MISSING", "c.png")));
        assertEquals(List.of("a.png", "c.png"), steps.getFirst().files());
        assertFalse(steps.getFirst().canLeaveOut(), "an output edited after the run is not fixed by leaving the source out");
        assertTrue(ValidationNextSteps.of(List.of(finding(Finding.Severity.INFO, "OUTPUT_VERIFIED", "a.png"))).isEmpty());
        assertEquals("", ValidationNextSteps.describe(List.of()));
    }

    @Test void theAiSummaryIsGivenOnlyTheseStepsToRecommend() throws Exception {
        List<String> prompts = new ArrayList<>();
        LLMClient model = new LLMClient() {
            @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) {
                prompts.add(prompt);
                return new ObjectMapper().createObjectNode().put("summary", "Replace misc/broken.png; the FBX cannot be inspected.");
            }
            @Override public String description() { return "test model"; }
        };
        var summary = new AIEngine(model).summarizeValidation(Report.ValidationStatus.FAILED, RunStatus.SUCCESS,
                new OutputValidator.Counts(4, 4, 1, 1, 2, 0), MESSY);
        assertTrue(summary.summary().isPresent());
        String prompt = prompts.getFirst();
        assertTrue(prompt.contains("suggest tools, external checkers or steps that are not in DATA.nextSteps"), prompt);
        assertTrue(prompt.contains("do not repeat them"), prompt);
        assertTrue(prompt.contains("\"code\":\"OUTPUT_FORMAT_INVALID\""), prompt);
        assertTrue(prompt.contains("\"files\":[\"props/crate_01.fbx\"]"), prompt);
        assertTrue(prompt.contains("\"afterChanges\""), prompt);
        assertFalse(prompt.contains("\"message\""), "finding messages are still not sent");
    }
}
