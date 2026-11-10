package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CopyPlanBuilderTest {
    @Test void buildsDeterministicCopyCommandsAndNeverApproves() {
        var asset = new Asset(UUID.randomUUID(), Path.of("hero.obj"), Asset.Type.MODEL,
                "a".repeat(64), Map.of());
        var project = new Project(UUID.randomUUID(), io.github.aiassetmigrate.TestPaths.absolute("source"), 1, List.of(asset), Instant.EPOCH);
        var snapshot = new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE, Instant.EPOCH, List.of(), List.of());
        var review = new ReviewedChange(UUID.randomUUID(), asset.id(), ReviewedChange.Kind.NAME,
                ReviewedChange.Decision.ACCEPTED, Map.of("name", "hero_new.obj"), Instant.EPOCH);
        var preview = OrganizationPreview.preview(snapshot, project.id(), 1, List.of(review));
        var id = UUID.randomUUID();
        var target = new AssetManager.TargetProfile("unity", "6000.0");
        var plan = CopyPlanBuilder.draft(snapshot, List.of(review), preview, target, io.github.aiassetmigrate.TestPaths.absolute("output"), id,
                Instant.EPOCH, List.of());
        assertEquals(PlanStatus.DRAFT, plan.status());
        assertEquals(1, plan.commands().size());
        assertEquals(MigrationCommand.Operation.COPY, plan.commands().getFirst().operation());
        assertEquals(Path.of("hero_new.obj"), plan.commands().getFirst().relativeOutputPath());
        assertEquals(plan.commands(), CopyPlanBuilder.commands(snapshot, preview, id));
    }

    @Test void blockingPreviewFindingsRemainErrorsAndStalePreviewIsRejected() {
        var project = new Project(UUID.randomUUID(), io.github.aiassetmigrate.TestPaths.absolute("source"), 1, List.of(), Instant.EPOCH);
        var snapshot = new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE, Instant.EPOCH, List.of(), List.of());
        var preview = new OrganizationPreview.Result(project.id(), 1, List.of(),
                List.of(new OrganizationPreview.Finding("BLOCKED", Optional.empty(), "Requires review")));
        var plan = CopyPlanBuilder.draft(snapshot, List.of(), preview, new AssetManager.TargetProfile("unity", "6000.0"),
                io.github.aiassetmigrate.TestPaths.absolute("output"), UUID.randomUUID(), Instant.EPOCH, List.of());
        assertEquals(Finding.Severity.ERROR, plan.findings().getFirst().severity());
        var stale = new OrganizationPreview.Result(project.id(), 2, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> CopyPlanBuilder.commands(snapshot, stale, plan.id()));
    }
}
