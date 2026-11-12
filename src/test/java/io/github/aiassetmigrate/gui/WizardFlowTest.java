package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.PlanStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.aiassetmigrate.gui.WizardFlow.Step.*;
import static org.junit.jupiter.api.Assertions.*;

class WizardFlowTest {
    private static WizardFlow.Progress progress(boolean inventory, boolean reviewed, boolean target, boolean approved, boolean ran) {
        return new WizardFlow.Progress(inventory, reviewed, target, approved, ran);
    }

    @Test void stepsAreOrderedWithinTheRecommendedThreeToSeven() {
        assertEquals(List.of(PROJECT, REVIEW, TARGET, PLAN, MIGRATE), List.of(WizardFlow.Step.values()));
        assertTrue(WizardFlow.Step.values().length >= 3 && WizardFlow.Step.values().length <= 7);
        assertEquals(Optional.of(REVIEW), PROJECT.next());
        assertEquals(Optional.empty(), PROJECT.previous());
        assertEquals(Optional.empty(), MIGRATE.next());
        assertEquals(Optional.of(PLAN), MIGRATE.previous());
        assertEquals(1, PROJECT.number());
    }

    @Test void nothingButProjectIsOpenBeforeAnInventoryExists() {
        var fresh = progress(false, false, false, false, false);
        assertTrue(fresh.unlocked(PROJECT));
        for (var step : List.of(REVIEW, TARGET, PLAN, MIGRATE)) assertFalse(fresh.unlocked(step), step.toString());
        assertFalse(fresh.canContinue(PROJECT));
        assertFalse(fresh.complete(PROJECT));
    }

    @Test void reviewIsOptionalButPlanNeedsATargetAndMigrateNeedsApproval() {
        var loaded = progress(true, false, false, false, false);
        assertTrue(loaded.unlocked(REVIEW) && loaded.unlocked(TARGET), "review can be skipped");
        assertTrue(loaded.canContinue(REVIEW));
        assertFalse(loaded.unlocked(PLAN), "a plan needs a target");
        assertFalse(loaded.canContinue(TARGET));

        var targeted = progress(true, false, true, false, false);
        assertTrue(targeted.unlocked(PLAN));
        assertFalse(targeted.unlocked(MIGRATE), "only an approved plan can be run");
        assertFalse(targeted.canContinue(PLAN));

        var approved = progress(true, true, true, true, false);
        assertTrue(approved.unlocked(MIGRATE) && approved.canContinue(PLAN));
        assertTrue(approved.complete(PLAN) && !approved.complete(MIGRATE));
        assertFalse(approved.canContinue(MIGRATE), "the last step has no Continue");
    }

    @Test void finishedRunKeepsResultsReachableAfterThePlanIsConsumed() {
        var ran = progress(true, true, true, false, true);
        assertTrue(ran.unlocked(MIGRATE));
        assertTrue(ran.complete(PLAN) && ran.complete(MIGRATE));
    }

    @Test void blockerGuidanceGroupsCodesAndExplainsTheNextStep() {
        UUID project = UUID.randomUUID();
        var blockers = List.of(
                finding(Finding.Severity.ERROR, "UNRESOLVED_REFERENCE", "weapons/sword.mtl", "Reference is not uniquely resolved: sword_tex.png"),
                finding(Finding.Severity.ERROR, "UNRESOLVED_REFERENCE", "characters/hero.obj", "Reference is not uniquely resolved: Hero.mtl"),
                finding(Finding.Severity.ERROR, "SOMETHING_NEW", "a.png", "Raw message is shown when there is no hint"),
                finding(Finding.Severity.WARNING, "CONTENT_UNVERIFIED", "b.png", "warnings are not blockers"));
        var plan = new MigrationPlan(UUID.randomUUID(), project, 1, 1, "unity", "6000.0", Path.of("out").toAbsolutePath(),
                List.of(), List.of(), blockers, PlanStatus.DRAFT, Instant.EPOCH);
        String text = AssetMigrateApplication.blockerGuidance(plan, List.of("Unknown source asset ID: x"));
        assertTrue(text.contains("Unknown source asset ID: x"), text);
        assertTrue(text.contains("UNRESOLVED_REFERENCE (weapons/sword.mtl and 1 more): A model or material points"), text);
        assertTrue(text.contains("SOMETHING_NEW (a.png): Raw message is shown"), text);
        assertFalse(text.contains("CONTENT_UNVERIFIED"), text);
    }

    @Test void plainLanguageHelpers() {
        assertEquals("Category conflict", AssetMigrateApplication.label("CATEGORY_CONFLICT"));
        assertEquals("", AssetMigrateApplication.label(""));
        assertEquals("394 B", AssetMigrateApplication.humanBytes("394"));
        assertEquals("1.4 KB", AssetMigrateApplication.humanBytes("1454"));
        assertEquals("n/a", AssetMigrateApplication.humanBytes("n/a"));
        assertEquals("Unity", AssetMigrateApplication.targetName("unity"));
        assertEquals("Some engine", AssetMigrateApplication.targetName("some_engine"));
        for (String code : List.of("PARTIAL_INVENTORY", "UNRESOLVED_REFERENCE", "INSPECTION_UNSUPPORTED", "CONVERSION_CHECK_FAILED",
                "DESTINATION_CONFLICT", "CASE_COLLISION", "INVALID_CORRECTION", "MODEL_CONVERSION_LAYOUT")) {
            assertTrue(AssetMigrateApplication.blockerHint(code).isPresent(), code);
        }
        assertTrue(AssetMigrateApplication.blockerHint("NOT_A_CODE").isEmpty());
    }

    private static Finding finding(Finding.Severity severity, String code, String path, String message) {
        return new Finding(UUID.randomUUID(), Optional.empty(), Optional.of(Path.of(path)), severity, code, message);
    }
}
