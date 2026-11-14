package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.BlockerFixes;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FixBlockersTextTest {
    private static BlockerFixes.Fix fix(boolean recommended) {
        UUID asset = UUID.randomUUID();
        return new BlockerFixes.Fix("INSPECTION_UNSUPPORTED", Optional.of(asset), "d", List.of(new ReviewedChange(UUID.randomUUID(),
                asset, ReviewedChange.Kind.ACKNOWLEDGMENT, ReviewedChange.Decision.ACCEPTED, Map.of("reason", "r"), Instant.EPOCH)),
                Set.of(), BlockerFixes.Source.RULES, recommended);
    }

    @Test void headerSaysWhetherEveryBlockerHasAFix() {
        UUID project = UUID.randomUUID();
        assertEquals("A fix was found for every blocker.",
                AssetMigrateApplication.fixHeader(new BlockerFixes.Proposal(project, 1, List.of(fix(true)), List.of())));
        assertEquals("1 fix(es) found; some blockers need a choice from you.",
                AssetMigrateApplication.fixHeader(new BlockerFixes.Proposal(project, 1, List.of(fix(true), fix(false)), List.of())));
        assertEquals("No quick fix was found for these blockers.",
                AssetMigrateApplication.fixHeader(new BlockerFixes.Proposal(project, 1, List.of(), List.of("ANALYSIS_LIMIT: m"))));
    }

    @Test void summaryReportsTheRescanAndAnyDecisionsNotKept() {
        assertEquals("Applied 2 fix(es).", AssetMigrateApplication.fixSummary(new BlockerFixes.Applied(2, 2, 0, false, 1)));
        assertEquals("Applied 3 fix(es). Rescanned to revision 2. 1 decision(s) about files that changed or were left out were not kept.",
                AssetMigrateApplication.fixSummary(new BlockerFixes.Applied(3, 5, 1, true, 2)));
    }

    @Test void aFixMustChangeSomething() {
        assertThrows(IllegalArgumentException.class, () -> new BlockerFixes.Fix("X", Optional.empty(), "d", List.of(),
                Set.<Path>of(), BlockerFixes.Source.RULES, true));
    }
}
