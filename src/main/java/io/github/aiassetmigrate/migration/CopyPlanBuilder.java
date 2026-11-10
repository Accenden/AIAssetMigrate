package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.analysis.MaterialAnalysis;
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

/**
 * Builds draft COPY and REWRITE_REFERENCES commands from a fresh preview; it does not
 * authorize execution. Rewrites change only OBJ/MTL reference text in the output copy.
 */
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
            boolean rewrite = !entry.rewrites().isEmpty();
            UUID commandId = UUID.nameUUIDFromBytes((planId + (rewrite ? ":rewrite:" : ":copy:") + entry.assetId())
                    .getBytes(StandardCharsets.UTF_8));
            commands.add(new AssetOperationCommand(commandId, entry.assetId(),
                    rewrite ? MigrationCommand.Operation.REWRITE_REFERENCES : MigrationCommand.Operation.COPY,
                    destination, encode(entry.rewrites()), List.of(), Optional.empty()));
        }
        return List.copyOf(commands);
    }

    /** Encodes rewrites as ordered {@code rewrite.NNNN.kind|from|to} command parameters. */
    public static Map<String, String> encode(List<MaterialAnalysis.Rewrite> rewrites) {
        Map<String, String> parameters = new java.util.TreeMap<>();
        for (int i = 0; i < rewrites.size(); i++) {
            String prefix = String.format("rewrite.%04d.", i);
            parameters.put(prefix + "kind", rewrites.get(i).kind().name());
            parameters.put(prefix + "from", rewrites.get(i).original());
            parameters.put(prefix + "to", rewrites.get(i).replacement());
        }
        return Map.copyOf(parameters);
    }

    /** Decodes parameters produced by {@link #encode}; rejects any other shape. */
    public static List<MaterialAnalysis.Rewrite> decode(Map<String, String> parameters) {
        List<MaterialAnalysis.Rewrite> rewrites = new ArrayList<>();
        for (int i = 0; parameters.containsKey(String.format("rewrite.%04d.kind", i)); i++) {
            String prefix = String.format("rewrite.%04d.", i);
            String from = parameters.get(prefix + "from"), to = parameters.get(prefix + "to");
            if (from == null || to == null) throw new IllegalArgumentException("Incomplete rewrite parameter");
            rewrites.add(new MaterialAnalysis.Rewrite(AssetReference.Kind.valueOf(parameters.get(prefix + "kind")), from, to));
        }
        if (!encode(rewrites).equals(parameters)) throw new IllegalArgumentException("Unexpected rewrite parameters");
        return List.copyOf(rewrites);
    }
}
