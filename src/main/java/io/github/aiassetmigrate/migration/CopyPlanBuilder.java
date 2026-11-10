package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Builds copy-only draft commands from a fresh preview; it does not authorize execution. */
public final class CopyPlanBuilder {
    private CopyPlanBuilder() { }

    public static MigrationPlan draft(InventorySnapshot inventory, List<ReviewedChange> reviews,
                                      OrganizationPreview.Result preview, AssetManager.TargetProfile target,
                                      Path output, UUID planId, Instant now, List<Finding> compatibilityFindings) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(planId, "planId");
        if (!output.isAbsolute()) throw new IllegalArgumentException("Output root must be absolute");
        List<Finding> findings = new ArrayList<>(compatibilityFindings);
        Map<UUID, Path> sourcePaths = new HashMap<>();
        inventory.project().assets().forEach(asset -> sourcePaths.put(asset.id(), asset.relativePath()));
        for (var issue : preview.findings()) {
            Optional<Path> source = issue.assetId().map(sourcePaths::get);
            findings.add(new Finding(UUID.nameUUIDFromBytes((planId + ":" + issue.code() + ":"
                    + issue.assetId().orElse(null) + ":" + issue.message()).getBytes(StandardCharsets.UTF_8)),
                    issue.assetId(), source, Finding.Severity.ERROR, issue.code(), issue.message()));
        }
        return new MigrationPlan(planId, inventory.project().id(), 1, inventory.project().inventoryRevision(),
                target.id(), target.version(), output, reviews, commands(inventory, preview, planId), findings,
                PlanStatus.DRAFT, now);
    }

    /** Reconstruct permitted commands from current inventory and preview; caller checks reviews separately. */
    public static List<MigrationCommand> commands(InventorySnapshot inventory, OrganizationPreview.Result preview, UUID planId) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(planId, "planId");
        if (!inventory.project().id().equals(preview.projectId())
                || inventory.project().inventoryRevision() != preview.inventoryRevision()) {
            throw new IllegalArgumentException("Preview does not match inventory");
        }
        List<MigrationCommand> commands = new ArrayList<>();
        for (var entry : preview.entries()) {
            if (entry.excluded() || entry.destination().isEmpty()) continue;
            Path destination = Path.of(entry.destination().orElseThrow());
            UUID commandId = UUID.nameUUIDFromBytes((planId + ":copy:" + entry.assetId())
                    .getBytes(StandardCharsets.UTF_8));
            commands.add(new AssetOperationCommand(commandId, entry.assetId(), MigrationCommand.Operation.COPY,
                    destination, Map.of(), List.of(), Optional.empty()));
        }
        return List.copyOf(commands);
    }
}
