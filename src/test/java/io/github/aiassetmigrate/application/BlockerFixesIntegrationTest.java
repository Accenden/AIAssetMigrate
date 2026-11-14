package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BlockerFixesIntegrationTest {
    private static final byte[] FBX = "Kaydara FBX Binary  \0\u001a\0è\u001c\0\0".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir Path directory;

    private static void png(Path file, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, transparent ? 0x00FFFFFF : 0xFF336699);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static Set<String> blockers(MigrationPlan plan) {
        return plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN)
                .map(Finding::code).collect(Collectors.toSet());
    }

    private static ReviewedChange accepted(UUID asset, ReviewedChange.Kind kind, Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, ReviewedChange.Decision.ACCEPTED, values, Instant.now());
    }

    @Test void theProposedFixesClearEveryBlockerWithoutTouchingTheSourceAndKeepEarlierDecisions() throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source.resolve("weapons"));
        Files.writeString(source.resolve("weapons/sword.obj"), "mtllib sword.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl steel\nf 1 2 3\n");
        Files.writeString(source.resolve("weapons/sword.mtl"), "newmtl steel\nmap_Kd sword_tex.png\n");
        png(source.resolve("textures/sword_tex.png"), false);
        Files.createDirectories(source.resolve("props"));
        Files.write(source.resolve("props/crate.fbx"), FBX);
        Files.createDirectories(source.resolve("misc"));
        Files.writeString(source.resolve("misc/notes.txt"), "not an asset");
        png(source.resolve("ui/a.png"), false);
        png(source.resolve("ui/b.png"), false);
        png(source.resolve("environment/grass.png"), true);
        // A truncated PNG: import only fingerprints images, so this used to fail only in validation after the run.
        // As in test-env: the first 40 bytes of a valid PNG (signature and header, no image data).
        png(directory.resolve("whole.png"), false);
        Files.write(source.resolve("misc/broken.png"), java.util.Arrays.copyOf(Files.readAllBytes(directory.resolve("whole.png")), 40));
        Path output = Files.createDirectory(directory.resolve("output"));

        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var imported = manager.importAssets(source).snapshot();
            UUID id = imported.project().id();
            Map<String, UUID> ids = imported.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), a -> a.id()));
            // Two files renamed to the same name, and a conversion that cannot keep transparency.
            manager.review(id, 1, List.of(
                    accepted(ids.get("ui/a.png"), ReviewedChange.Kind.NAME, Map.of("name", "icon.png")),
                    accepted(ids.get("ui/b.png"), ReviewedChange.Kind.NAME, Map.of("name", "icon.png")),
                    accepted(ids.get("environment/grass.png"), ReviewedChange.Kind.CONVERSION, Map.of("format", "jpg")),
                    accepted(ids.get("textures/sword_tex.png"), ReviewedChange.Kind.CATEGORY, Map.of("category", "Weapons"))));
            var target = manager.supportedTargets().getFirst();
            MigrationPlan blocked = manager.createPlan(id, target, output);
            assertEquals(Set.of("PARTIAL_INVENTORY", "UNRESOLVED_REFERENCE", "INSPECTION_UNSUPPORTED", "DESTINATION_CONFLICT",
                    "CONVERSION_CHECK_FAILED", "SOURCE_IMAGE_INVALID"), blockers(blocked));
            assertTrue(blocked.findings().stream().anyMatch(f -> f.code().equals("SOURCE_IMAGE_INVALID")
                    && f.sourceRelativePath().map(PortablePath::of).equals(Optional.of("misc/broken.png"))
                    && f.message().endsWith("it would fail validation")), "the damaged image is caught before the run");

            var proposal = manager.proposeBlockerFixes(id, blocked, Optional.empty());
            assertTrue(proposal.unfixable().isEmpty(), proposal.unfixable().toString());
            assertTrue(proposal.fixes().stream().allMatch(BlockerFixes.Fix::recommended), proposal.fixes().toString());
            assertEquals(Set.of("PARTIAL_INVENTORY", "UNRESOLVED_REFERENCE", "INSPECTION_UNSUPPORTED", "DESTINATION_CONFLICT",
                    "CONVERSION_CHECK_FAILED", "SOURCE_IMAGE_INVALID"),
                    proposal.fixes().stream().map(BlockerFixes.Fix::code).collect(Collectors.toSet()));
            assertEquals(1, proposal.fixes().stream().filter(f -> f.code().equals("DESTINATION_CONFLICT")).count(),
                    "one rename settles both files of the clash");
            assertTrue(proposal.fixes().stream().anyMatch(f -> f.description().equals("Rename ui/b.png to icon_2.png (it would be "
                    + "written to the same place as ui/a.png)")), proposal.fixes().toString());
            assertEquals(1, manager.loadInventory(id).project().inventoryRevision(), "proposing changes nothing");

            var applied = manager.applyBlockerFixes(id, proposal.inventoryRevision(), proposal.fixes());
            assertTrue(applied.rescanned());
            assertEquals(2, applied.inventoryRevision());
            assertEquals(0, applied.decisionsDropped());
            MigrationPlan fixed = manager.createPlan(id, target, output);
            assertEquals(Set.of(), blockers(fixed), fixed.findings().toString());
            assertTrue(manager.loadReviews(id).stream().anyMatch(c -> c.kind() == ReviewedChange.Kind.CATEGORY
                    && "Weapons".equals(c.values().get("category"))), "an unrelated earlier decision survives the rescan");
            Set<String> outputs = fixed.commands().stream().map(c -> PortablePath.of(c.relativeOutputPath())).collect(Collectors.toSet());
            assertTrue(outputs.containsAll(Set.of("ui/icon.png", "ui/icon_2.png", "environment/grass.jpg", "props/crate.fbx")),
                    "the transparent texture is still converted, on white: " + outputs);
            assertTrue(manager.loadReviews(id).stream().anyMatch(c -> c.kind() == ReviewedChange.Kind.CONVERSION
                    && c.decision() == ReviewedChange.Decision.ACCEPTED && "#FFFFFF".equals(c.values().get("background"))));
            assertFalse(outputs.contains("misc/notes.txt"));
            assertFalse(outputs.contains("misc/broken.png"), "the damaged image was left out");
            assertTrue(Files.exists(source.resolve("misc/broken.png")));
            assertTrue(Files.exists(source.resolve("misc/notes.txt")), "the source folder is untouched");
            assertEquals("newmtl steel\nmap_Kd sword_tex.png\n", Files.readString(source.resolve("weapons/sword.mtl")));
        }
    }

    @Test void anAiChoiceSettlesATieAndWithoutOneTheFileIsOnlyOfferedToBeLeftOut() throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source.resolve("weapons"));
        Files.writeString(source.resolve("weapons/sword.mtl"), "newmtl steel\nmap_Kd tex.png\n");
        png(source.resolve("a/tex.png"), false);
        png(source.resolve("b/tex.png"), false);
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID id = snapshot.project().id();
            MigrationPlan plan = manager.createPlan(id, manager.supportedTargets().getFirst(), output);
            assertEquals(Set.of("UNRESOLVED_REFERENCE"), blockers(plan));

            var rulesOnly = manager.proposeBlockerFixes(id, plan, Optional.empty());
            assertEquals(1, rulesOnly.fixes().size());
            BlockerFixes.Fix tie = rulesOnly.fixes().getFirst();
            assertFalse(tie.recommended(), "two equally named files: nothing is preselected");
            assertEquals(ReviewedChange.Kind.EXCLUSION, tie.changes().getFirst().kind());
            assertTrue(tie.description().contains("2 files match"), tie.description());

            UUID owner = tie.assetId().orElseThrow();
            UUID chosen = snapshot.project().assets().stream().filter(a -> PortablePath.of(a.relativePath()).equals("b/tex.png"))
                    .findFirst().orElseThrow().id();
            var analysis = manager.analyze(id, AssetManager.AnalysisKind.RELATIONSHIPS);
            List<AssetManager.Suggestion> withAi = new java.util.ArrayList<>(analysis.suggestions());
            withAi.add(new AssetManager.Suggestion(UUID.randomUUID(), owner, AssetManager.AnalysisKind.RELATIONSHIPS,
                    chosen.toString(), Optional.of("tex.png"), AssetManager.Source.AI, Optional.of("same folder family")));
            var aided = BlockerFixes.propose(plan, manager.loadInventory(id), manager.loadReviews(id),
                    manager.previewOrganization(id, 1), withAi, Instant.now());
            BlockerFixes.Fix fix = aided.fixes().getFirst();
            assertTrue(fix.recommended());
            assertEquals(BlockerFixes.Source.AI, fix.source());
            assertEquals(Map.of("targetAssetId", chosen.toString(), "reference", "tex.png"), fix.changes().getFirst().values());

            manager.applyBlockerFixes(id, 1, List.of(fix));
            assertEquals(Set.of(), blockers(manager.createPlan(id, manager.supportedTargets().getFirst(), output)));
        }
    }
}
