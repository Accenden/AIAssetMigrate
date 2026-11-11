package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end F02-F04 workflow: categorize, correct a broken reference, organize by category,
 * acknowledge an opaque FBX, then plan, approve and migrate with reference rewrites.
 */
class OrganizationWorkflowIntegrationTest {
    private static final byte[] FBX = "Kaydara FBX Binary  \0\u001a\0è\u001c\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final String OBJ = "mtllib Hero.mtl\nusemtl Skin\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
    private static final String MTL = "newmtl Skin\nmap_Kd textures/hero_diffuse.png\n";

    @TempDir Path directory;

    private static ReviewedChange accept(UUID asset, ReviewedChange.Kind kind, Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, ReviewedChange.Decision.ACCEPTED, values, Instant.now());
    }

    @Test void categorizeCorrectOrganizeAndMigrateWithRewrittenReferences() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.createDirectories(source.resolve("models"));
        Files.createDirectories(source.resolve("tex"));
        Files.createDirectories(source.resolve("props"));
        Files.writeString(source.resolve("models/Hero.obj"), OBJ);
        Files.writeString(source.resolve("models/Hero.mtl"), MTL);
        Files.write(source.resolve("tex/hero_diffuse.png"), new byte[] {1, 2, 3});
        Files.write(source.resolve("props/crate.fbx"), FBX);
        Path output = Files.createDirectory(directory.resolve("output"));

        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            assertTrue(snapshot.hasCompleteCoverage(), snapshot.findings().toString());
            UUID project = snapshot.project().id();
            long revision = snapshot.project().inventoryRevision();
            Map<String, UUID> ids = snapshot.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
            UUID obj = ids.get("models/Hero.obj"), mtl = ids.get("models/Hero.mtl");
            UUID png = ids.get("tex/hero_diffuse.png"), fbx = ids.get("props/crate.fbx");

            // F02: category suggestions use path words, never applied until reviewed.
            var categories = manager.analyze(project, AssetManager.AnalysisKind.CATEGORIES).suggestions().stream()
                    .collect(Collectors.toMap(AssetManager.Suggestion::assetId, AssetManager.Suggestion::proposedValue));
            assertEquals(Map.of(obj, "Characters", mtl, "Characters", png, "Characters", fbx, "Props"), categories);
            assertTrue(manager.loadReviews(project).isEmpty());

            // F03: the broken texture reference yields an uncertain correction candidate.
            var relationships = manager.analyze(project, AssetManager.AnalysisKind.RELATIONSHIPS);
            assertTrue(relationships.suggestions().stream().anyMatch(s -> s.assetId().equals(mtl)
                    && s.proposedValue().equals(png.toString()) && s.reference().equals(java.util.Optional.of("textures/hero_diffuse.png"))),
                    relationships.suggestions().toString());
            assertTrue(relationships.findings().stream().anyMatch(f -> f.code().equals("REFERENCE_CANDIDATE")));
            var blocked = manager.previewOrganization(project, revision);
            assertTrue(blocked.findings().stream().anyMatch(f -> f.code().equals("UNRESOLVED_REFERENCE")));
            assertTrue(blocked.findings().stream().anyMatch(f -> f.code().equals("INSPECTION_UNSUPPORTED")
                    && f.assetId().equals(java.util.Optional.of(fbx))));

            // Acknowledgments are only for assets whose references cannot be inspected.
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, revision,
                    List.of(accept(obj, ReviewedChange.Kind.ACKNOWLEDGMENT, Map.of("reason", "not needed")))));

            List<ReviewedChange> reviews = new ArrayList<>();
            categories.forEach((asset, category) -> reviews.add(accept(asset, ReviewedChange.Kind.CATEGORY, Map.of("category", category))));
            reviews.add(accept(mtl, ReviewedChange.Kind.MATERIAL_MAPPING,
                    Map.of("targetAssetId", png.toString(), "reference", "textures/hero_diffuse.png")));
            reviews.add(accept(fbx, ReviewedChange.Kind.ACKNOWLEDGMENT, Map.of("reason", "Embedded FBX dependencies checked manually")));
            manager.review(project, revision, reviews);

            // F04: by-category folders come from accepted categories; names use snake case.
            var organization = manager.analyzeOrganization(project,
                    new AssetManager.OrganizationRules(AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.BY_CATEGORY));
            assertTrue(organization.findings().isEmpty(), organization.findings().toString());
            List<ReviewedChange> layout = new ArrayList<>();
            for (var suggestion : organization.suggestions()) {
                boolean name = suggestion.kind() == AssetManager.AnalysisKind.NAMES;
                layout.add(accept(suggestion.assetId(), name ? ReviewedChange.Kind.NAME : ReviewedChange.Kind.FOLDER,
                        Map.of(name ? "name" : "folder", suggestion.proposedValue())));
            }
            manager.review(project, revision, layout);

            var preview = manager.previewOrganization(project, revision);
            assertFalse(preview.blocked(), preview.findings().toString());
            Map<UUID, String> destinations = preview.entries().stream()
                    .collect(Collectors.toMap(e -> e.assetId(), e -> e.destination().orElseThrow()));
            assertEquals(Map.of(obj, "Characters/hero.obj", mtl, "Characters/hero.mtl",
                    png, "Characters/hero_diffuse.png", fbx, "Props/crate.fbx"), destinations);

            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertFalse(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN), plan.findings().toString());
            Map<UUID, MigrationCommand.Operation> operations = plan.commands().stream()
                    .collect(Collectors.toMap(MigrationCommand::assetId, MigrationCommand::operation));
            assertEquals(Map.of(obj, MigrationCommand.Operation.REWRITE_REFERENCES, mtl, MigrationCommand.Operation.REWRITE_REFERENCES,
                    png, MigrationCommand.Operation.COPY, fbx, MigrationCommand.Operation.COPY), operations);

            manager.approve(plan.id(), plan.version());
            var run = manager.migrate(plan.id());
            assertEquals(RunStatus.SUCCESS, run.status(), run.toString());

            // Sources are untouched; output copies carry only the reviewed reference changes.
            assertEquals(OBJ, Files.readString(source.resolve("models/Hero.obj")));
            assertEquals(MTL, Files.readString(source.resolve("models/Hero.mtl")));
            assertEquals(OBJ.replace("mtllib Hero.mtl", "mtllib hero.mtl"), Files.readString(output.resolve("Characters/hero.obj")));
            assertEquals("newmtl Skin\nmap_Kd hero_diffuse.png\n", Files.readString(output.resolve("Characters/hero.mtl")));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output.resolve("Characters/hero_diffuse.png")));
            assertArrayEquals(FBX, Files.readAllBytes(output.resolve("Props/crate.fbx")));

            // Re-importing the output proves the rewritten references resolve in the new layout.
            var migrated = manager.importAssets(output).snapshot();
            var materials = manager.analyze(migrated.project().id(), AssetManager.AnalysisKind.MATERIALS);
            assertTrue(materials.findings().stream().noneMatch(f -> f.code().startsWith("REFERENCE_")
                    || f.code().startsWith("MATERIAL_MISSING") || f.code().startsWith("MATERIAL_UNVERIFIED")),
                    materials.findings().toString());
            assertTrue(materials.findings().stream().anyMatch(f -> f.code().equals("MATERIAL_REFERENCE")));
        }
    }

    @Test void acceptedAiCorrectionDrivesTheSameReviewedRewriteAsARuleSuggestion() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd skin.png\n");
        Files.write(source.resolve("hero_albedo.png"), new byte[] {4, 5});
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            Map<String, UUID> ids = snapshot.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
            UUID mtl = ids.get("hero.mtl"), png = ids.get("hero_albedo.png");
            // Rules find no filename match for skin.png; the model proposes hero_albedo.png.
            var rules = manager.analyze(project, AssetManager.AnalysisKind.MATERIALS);
            assertTrue(rules.suggestions().isEmpty(), rules.suggestions().toString());
            List<String> prompts = new ArrayList<>();
            io.github.aiassetmigrate.ai.LLMClient model = new io.github.aiassetmigrate.ai.LLMClient() {
                @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) throws AiException {
                    prompts.add(prompt);
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"corrections\":[{\"ownerId\":\""
                                + mtl + "\",\"reference\":\"skin.png\",\"targetAssetId\":\"" + png + "\"}]}");
                    } catch (Exception e) { throw new AiException(AiException.Reason.INVALID_OUTPUT, "bad"); }
                }
                @Override public String description() { return "fake model"; }
            };
            var assisted = manager.analyze(project, AssetManager.AnalysisKind.MATERIALS, model);
            var suggestion = assisted.suggestions().stream().filter(s -> s.source() == AssetManager.Source.AI).findFirst().orElseThrow();
            assertEquals(png.toString(), suggestion.proposedValue());
            assertFalse(prompts.getFirst().contains("newmtl"), "file contents are never sent");
            assertTrue(manager.previewOrganization(project, 1).blocked(), "an AI suggestion is not applied until reviewed");

            manager.review(project, 1, List.of(accept(mtl, ReviewedChange.Kind.MATERIAL_MAPPING,
                    Map.of("targetAssetId", suggestion.proposedValue(), "reference", suggestion.reference().orElseThrow()))));
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            manager.approve(plan.id(), plan.version());
            assertEquals(RunStatus.SUCCESS, manager.migrate(plan.id()).status());
            assertEquals("newmtl Skin\nmap_Kd hero_albedo.png\n", Files.readString(output.resolve("hero.mtl")));
        }
    }

    @Test void reviewedTextureConversionUsesTheStrategyAndRewritesItsReferences() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("crate.mtl"), "newmtl Wood\nmap_Kd crate.png\n");
        byte[] png = io.github.aiassetmigrate.conversion.ConversionStrategyTest.png(true);
        Files.write(source.resolve("crate.png"), png);
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            Map<String, UUID> ids = snapshot.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
            UUID mtl = ids.get("crate.mtl"), texture = ids.get("crate.png");
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 1,
                    List.of(accept(mtl, ReviewedChange.Kind.CONVERSION, Map.of("format", "jpg")))), "only textures convert");
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 1,
                    List.of(accept(texture, ReviewedChange.Kind.CONVERSION, Map.of("format", "fbx")))));

            // Transparency without an approved background blocks the plan before approval.
            manager.review(project, 1, List.of(accept(texture, ReviewedChange.Kind.CONVERSION, Map.of("format", "jpg"))));
            var blocked = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            assertTrue(blocked.findings().stream().anyMatch(f -> f.code().equals("CONVERSION_CHECK_FAILED")));
            assertThrows(Exception.class, () -> manager.approve(blocked.id(), blocked.version()));

            manager.review(project, 1, List.of(accept(texture, ReviewedChange.Kind.CONVERSION,
                    Map.of("format", "jpg", "background", "#FFFFFF"))));
            var preview = manager.previewOrganization(project, 1);
            assertFalse(preview.blocked(), preview.findings().toString());
            var textureEntry = preview.entries().stream().filter(e -> e.assetId().equals(texture)).findFirst().orElseThrow();
            assertEquals(java.util.Optional.of("crate.jpg"), textureEntry.destination());
            var plan = manager.createPlan(project, new AssetManager.TargetProfile("unity", "6000.0"), output);
            var convert = plan.commands().stream().filter(c -> c.assetId().equals(texture)).findFirst().orElseThrow();
            assertEquals(MigrationCommand.Operation.CONVERT_TEXTURE, convert.operation());
            assertEquals(java.util.Optional.of(io.github.aiassetmigrate.conversion.TextureConverter.ID), convert.converterRequirement());
            manager.approve(plan.id(), plan.version());
            var run = manager.migrate(plan.id());
            assertEquals(RunStatus.SUCCESS, run.status(), run.toString());

            byte[] converted = Files.readAllBytes(output.resolve("crate.jpg"));
            assertEquals((byte) 0xD8, converted[1], "output is JPEG");
            assertEquals("newmtl Wood\nmap_Kd crate.jpg\n", Files.readString(output.resolve("crate.mtl")));
            assertArrayEquals(png, Files.readAllBytes(source.resolve("crate.png")), "source preserved");
            assertFalse(Files.exists(output.resolve("crate.png")));
        }
    }

    @Test void stemGroupingSuggestsUnreferencedTexturesWithoutReferences() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("crate.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        Files.write(source.resolve("crate_albedo.png"), new byte[] {9});
        Files.write(source.resolve("crateful.png"), new byte[] {8});
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            Map<String, UUID> ids = snapshot.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
            var result = manager.analyze(snapshot.project().id(), AssetManager.AnalysisKind.RELATIONSHIPS);
            assertEquals(List.of(ids.get("crate_albedo.png").toString()), result.suggestions().stream()
                    .filter(s -> s.assetId().equals(ids.get("crate.obj"))).map(AssetManager.Suggestion::proposedValue).toList());
            assertTrue(result.suggestions().stream().allMatch(s -> s.reference().isEmpty()));
        }
    }

    @Test void destinationsThatCannotBeRewrittenRemainBlocking() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("a.obj"), "mtllib a.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        Files.writeString(source.resolve("a.mtl"), "newmtl x\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            UUID mtl = snapshot.project().assets().stream().filter(a -> a.relativePath().toString().endsWith(".mtl"))
                    .findFirst().orElseThrow().id();
            manager.review(project, 1, List.of(accept(mtl, ReviewedChange.Kind.FOLDER, Map.of("folder", "My Materials"))));
            assertTrue(manager.previewOrganization(project, 1).findings().stream()
                    .anyMatch(f -> f.code().equals("REFERENCE_REWRITE_UNSUPPORTED")));
        }
    }
}
