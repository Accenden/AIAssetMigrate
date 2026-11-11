package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.conversion.AssimpModelConverter;
import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** F07 model conversion through the real facade: review, plan, approve, migrate. */
class ModelConversionIntegrationTest {
    private static final AssetManager.TargetProfile GODOT = new AssetManager.TargetProfile("godot", "4.3");
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    @TempDir Path directory;
    Path source, output;
    Map<String, UUID> ids;

    @BeforeAll static void requireNatives() {
        assumeTrue(AssimpModelConverter.available(), "Assimp natives unavailable: " + AssimpModelConverter.unavailableReason());
    }

    private static ReviewedChange accept(UUID asset, ReviewedChange.Kind kind, Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, ReviewedChange.Decision.ACCEPTED, values, Instant.now());
    }

    /** Imports hero.obj -> hero.mtl -> hero.png and returns the project; asset IDs are kept in {@link #ids}. */
    private UUID importChain(DefaultAssetManager manager) throws Exception {
        source = Files.createDirectory(directory.resolve("source"));
        output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\nv 1 0 0\nv 0 1 0\nv 1 1 0\n"
                + "vt 0 0\nvt 1 0\nvt 0 1\nvt 1 1\nvn 0 0 1\nf 1/1/1 2/2/1 4/4/1 3/3/1\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nKd 0.8 0.2 0.2\nmap_Kd hero.png\n");
        Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
        var snapshot = manager.importAssets(source).snapshot();
        ids = snapshot.project().assets().stream().collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
        return snapshot.project().id();
    }

    private static String glbJson(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46546C67, b.getInt(0));
        assertEquals(glb.length, b.getInt(8));
        return new String(glb, 20, b.getInt(12), StandardCharsets.UTF_8);
    }

    private static Set<String> codes(MigrationPlan plan) {
        return plan.findings().stream().map(Finding::code).collect(Collectors.toSet());
    }

    private MigrationRun approveAndMigrate(DefaultAssetManager manager, MigrationPlan plan) {
        assertFalse(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                || f.severity() == Finding.Severity.UNKNOWN), plan.findings().toString());
        manager.approve(plan.id(), plan.version());
        MigrationRun run = manager.migrate(plan.id());
        assertEquals(RunStatus.SUCCESS, run.status(), run.toString());
        return run;
    }

    @Test void reviewedObjBecomesAnEmbeddedGlbAndSourcesStayUnchanged() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);
            String originalObj = Files.readString(source.resolve("hero.obj"));
            manager.review(project, 1, List.of(accept(ids.get("hero.obj"), ReviewedChange.Kind.CONVERSION, Map.of("format", "GLB"))));
            var preview = manager.previewOrganization(project, 1);
            assertFalse(preview.blocked(), preview.findings().toString());
            var plan = manager.createPlan(project, GODOT, output);
            var convert = plan.commands().stream().filter(c -> c.assetId().equals(ids.get("hero.obj"))).findFirst().orElseThrow();
            assertEquals(MigrationCommand.Operation.CONVERT_MODEL, convert.operation());
            assertEquals("hero.glb", PortablePath.of(convert.relativeOutputPath()));
            assertEquals(java.util.Optional.of("assimp-lwjgl-" + AssimpModelConverter.assimpVersion()), convert.converterRequirement());
            assertTrue(codes(plan).contains("MODEL_CONVERSION_FIDELITY"));
            assertFalse(codes(plan).contains("GLB_REQUIRES_GLTFAST"), "Godot imports GLB natively");

            MigrationRun run = approveAndMigrate(manager, plan);
            assertTrue(run.stepDetails().get(convert.id()).contains("output SHA-256"), run.stepDetails().toString());
            String json = glbJson(Files.readAllBytes(output.resolve("hero.glb")));
            assertTrue(json.contains("\"images\"") && !json.contains("\"uri\""), json);
            assertTrue(Files.exists(output.resolve("hero.mtl")) && Files.exists(output.resolve("hero.png")),
                    "the MTL and texture are still copied next to the GLB");
            assertFalse(Files.exists(output.resolve("hero.obj")));
            assertEquals(originalObj, Files.readString(source.resolve("hero.obj")), "sources are never modified");

            // F08: the converted model is validated, not left as "not fully checked".
            Report report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("OUTPUT_VERIFIED")
                    && f.message().startsWith("hero.glb:") && f.message().contains("GLB 2.0 structure valid")
                    && f.message().contains("1 embedded image(s) decode")), report.findings().toString());
            Files.write(output.resolve("hero.glb"), "tampered".getBytes(StandardCharsets.UTF_8));
            Report tampered = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.FAILED, tampered.validationStatus());
        }
    }

    @Test void reviewedMovesAndTextureConversionsReachTheConvertedModel() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);
            manager.review(project, 1, List.of(
                    accept(ids.get("hero.obj"), ReviewedChange.Kind.CONVERSION, Map.of("format", "glb")),
                    accept(ids.get("hero.png"), ReviewedChange.Kind.FOLDER, Map.of("folder", "textures")),
                    accept(ids.get("hero.png"), ReviewedChange.Kind.CONVERSION, Map.of("format", "jpg"))));
            var plan = manager.createPlan(project, UNITY, output);
            assertTrue(codes(plan).contains("GLB_REQUIRES_GLTFAST"), "Unity needs glTFast for GLB");
            approveAndMigrate(manager, plan);
            assertEquals("newmtl Skin\nKd 0.8 0.2 0.2\nmap_Kd textures/hero.jpg\n", Files.readString(output.resolve("hero.mtl")));
            assertTrue(Files.exists(output.resolve("textures/hero.jpg")));
            String json = glbJson(Files.readAllBytes(output.resolve("hero.glb")));
            assertTrue(json.contains("\"mimeType\":\"image/jpeg\""), "the converted JPEG is the embedded texture: " + json);
        }
    }

    @Test void acknowledgedFbxConvertsAndExternalTexturesBlockApproval() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            source = Files.createDirectory(directory.resolve("source"));
            output = Files.createDirectory(directory.resolve("output"));
            Files.write(source.resolve("crate.fbx"), io.github.aiassetmigrate.conversion.ModelFixtures.embeddedFbx());
            Files.write(source.resolve("barrel.fbx"), io.github.aiassetmigrate.conversion.ModelFixtures.externalFbx(directory.resolve("scratch")));
            var snapshot = manager.importAssets(source).snapshot();
            assertTrue(snapshot.hasCompleteCoverage(), snapshot.findings().toString());
            UUID project = snapshot.project().id();
            Map<String, UUID> ids = snapshot.project().assets().stream()
                    .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), Asset::id));
            UUID crate = ids.get("crate.fbx"), barrel = ids.get("barrel.fbx");
            manager.review(project, 1, List.of(
                    accept(crate, ReviewedChange.Kind.ACKNOWLEDGMENT, Map.of("reason", "embedded textures checked")),
                    accept(crate, ReviewedChange.Kind.CONVERSION, Map.of("format", "glb")),
                    accept(barrel, ReviewedChange.Kind.ACKNOWLEDGMENT, Map.of("reason", "checked")),
                    accept(barrel, ReviewedChange.Kind.CONVERSION, Map.of("format", "glb"))));

            var blocked = manager.createPlan(project, GODOT, output);
            var failure = blocked.findings().stream().filter(f -> f.code().equals("CONVERSION_CHECK_FAILED")).toList();
            assertEquals(1, failure.size(), blocked.findings().toString());
            assertEquals(java.util.Optional.of(barrel), failure.getFirst().assetId());
            assertTrue(failure.getFirst().message().contains("not embedded"), failure.toString());
            assertThrows(Exception.class, () -> manager.approve(blocked.id(), blocked.version()));

            manager.review(project, 1, List.of(accept(barrel, ReviewedChange.Kind.EXCLUSION, Map.of("reason", "needs its texture"))));
            MigrationRun run = approveAndMigrate(manager, manager.createPlan(project, GODOT, output));
            assertFalse(glbJson(Files.readAllBytes(output.resolve("crate.glb"))).contains("\"uri\""));
            assertFalse(Files.exists(output.resolve("barrel.glb")));
            Report report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
        }
    }

    @Test void layoutAndUnsupportedConversionsAreBlockedBeforeApproval() throws Exception {
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = importChain(manager);
            UUID obj = ids.get("hero.obj"), mtl = ids.get("hero.mtl");
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 1,
                    List.of(accept(obj, ReviewedChange.Kind.CONVERSION, Map.of("format", "fbx")))), "no FBX writer");
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 1,
                    List.of(accept(obj, ReviewedChange.Kind.CONVERSION, Map.of("format", "glb", "quality", "1")))), "models take no options");
            assertThrows(IllegalArgumentException.class, () -> manager.review(project, 1,
                    List.of(accept(mtl, ReviewedChange.Kind.CONVERSION, Map.of("format", "glb")))), "materials are not models");

            manager.review(project, 1, List.of(accept(obj, ReviewedChange.Kind.CONVERSION, Map.of("format", "glb")),
                    accept(mtl, ReviewedChange.Kind.FOLDER, Map.of("folder", "materials"))));
            var preview = manager.previewOrganization(project, 1);
            assertTrue(preview.findings().stream().anyMatch(f -> f.code().equals("MODEL_CONVERSION_LAYOUT")), preview.findings().toString());
            var plan = manager.createPlan(project, GODOT, output);
            assertThrows(Exception.class, () -> manager.approve(plan.id(), plan.version()));
            try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        }
    }
}
