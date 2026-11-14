package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.conversion.FbxReader;
import io.github.aiassetmigrate.conversion.ModelFixtures;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.ReviewedChange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FBX models read with the built-in FBX reader: real FBX files need no acknowledgment, are validated instead of
 * reported as not checked, and a texture an FBX needs is kept (or put) where the FBX expects it.
 */
class FbxIntegrationTest {
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");

    @TempDir Path directory;

    @BeforeAll static void requireReader() {
        assumeTrue(FbxReader.available(), "the built-in FBX reader needs the Assimp natives");
    }

    private static Set<String> blockers(MigrationPlan plan) {
        return plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN)
                .map(Finding::code).collect(Collectors.toSet());
    }

    private static Map<String, UUID> ids(AssetManager manager, UUID project) {
        return manager.loadInventory(project).project().assets().stream()
                .collect(Collectors.toMap(a -> PortablePath.of(a.relativePath()), a -> a.id()));
    }

    private static ReviewedChange accept(UUID asset, ReviewedChange.Kind kind, Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, ReviewedChange.Decision.ACCEPTED, values, Instant.now());
    }

    private Report run(DefaultAssetManager manager, UUID project, Path output) {
        MigrationPlan plan = manager.createPlan(project, UNITY, output);
        assertEquals(Set.of(), blockers(plan), plan.findings().toString());
        manager.approve(plan.id(), plan.version());
        return manager.validate(manager.migrate(plan.id()).id());
    }

    private static String verified(Report report, String output) {
        return report.findings().stream().filter(f -> f.code().equals("OUTPUT_VERIFIED") && f.message().startsWith(output + ":"))
                .map(Finding::message).findFirst().orElseThrow(() -> new AssertionError(report.findings().toString()));
    }

    @Test void fbxWithAnEmbeddedTextureNeedsNoAcknowledgmentAndPassesValidation() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source/props"));
        Files.write(source.resolve("crate.fbx"), ModelFixtures.embeddedFbx());
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(directory.resolve("source")).snapshot().project().id();
            Report report = run(manager, id, output);
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            String passed = verified(report, "props/crate.fbx");
            assertTrue(passed.contains("FBX read with the built-in reader (1 mesh(es), 1 embedded image(s) decode)"), passed);
        }
    }

    @Test void anExternalTextureNextToTheFbxIsCheckedInTheOutput() throws Exception {
        Path scratch = directory.resolve("scratch");
        Path source = Files.createDirectories(directory.resolve("source/props"));
        Files.write(source.resolve("crate.fbx"), ModelFixtures.externalFbx(scratch));
        Files.copy(scratch.resolve("wood.png"), source.resolve("wood.png"));
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(directory.resolve("source")).snapshot().project().id();
            var relationships = manager.analyze(id, AssetManager.AnalysisKind.RELATIONSHIPS);
            assertTrue(relationships.suggestions().stream().anyMatch(s -> s.reference().equals(Optional.of("wood.png"))),
                    "the FBX's texture is now a known relationship");
            Report report = run(manager, id, output);
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertTrue(verified(report, "props/crate.fbx").contains("1 reference(s) resolve to outputs of this run"));
        }
    }

    @Test void aTextureTheLayoutWouldMoveAwayIsKeptWhereTheFbxExpectsIt() throws Exception {
        Path scratch = directory.resolve("scratch");
        Path source = Files.createDirectories(directory.resolve("source/props"));
        Files.write(source.resolve("crate.fbx"), ModelFixtures.externalFbx(scratch));
        Files.copy(scratch.resolve("wood.png"), source.resolve("wood.png"));
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(directory.resolve("source")).snapshot().project().id();
            Map<String, UUID> ids = ids(manager, id);
            // A folder rule moves the texture away from the FBX, which cannot be edited to follow it.
            manager.review(id, 1, List.of(accept(ids.get("props/wood.png"), ReviewedChange.Kind.FOLDER, Map.of("folder", "textures"))));
            MigrationPlan blocked = manager.createPlan(id, UNITY, output);
            assertEquals(Set.of("FBX_REFERENCE_NOT_REWRITABLE"), blockers(blocked), blocked.findings().toString());
            assertTrue(blocked.commands().stream().noneMatch(c -> c.operation() == io.github.aiassetmigrate.domain.MigrationCommand.Operation.REWRITE_REFERENCES),
                    "an FBX is never given a rewrite");

            var proposal = manager.proposeBlockerFixes(id, blocked, Optional.empty());
            assertEquals(List.of("Place props/wood.png at props/wood.png in the output, where props/crate.fbx looks for it "
                    + "(FBX files cannot be edited)"), proposal.fixes().stream().map(BlockerFixes.Fix::description).toList());
            manager.applyBlockerFixes(id, 1, proposal.fixes());
            Report report = run(manager, id, output);
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertTrue(Files.exists(output.resolve("props/wood.png")));
        }
    }

    @Test void aTextureTheFbxCannotFindIsPlacedWhereItLooksAndNoCorrectionIsSuggested() throws Exception {
        Path scratch = directory.resolve("scratch");
        Path source = Files.createDirectories(directory.resolve("source/props"));
        Files.write(source.resolve("crate.fbx"), ModelFixtures.externalFbx(scratch));
        Files.createDirectories(directory.resolve("source/textures"));
        Files.copy(scratch.resolve("wood.png"), directory.resolve("source/textures/wood.png"));
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(directory.resolve("source")).snapshot().project().id();
            Map<String, UUID> ids = ids(manager, id);
            assertFalse(manager.analyze(id, AssetManager.AnalysisKind.RELATIONSHIPS).suggestions().stream()
                    .anyMatch(s -> s.assetId().equals(ids.get("props/crate.fbx"))), "a correction could never be applied to an FBX");
            MigrationPlan blocked = manager.createPlan(id, UNITY, output);
            assertEquals(Set.of("UNRESOLVED_REFERENCE"), blockers(blocked));

            var proposal = manager.proposeBlockerFixes(id, blocked, Optional.empty());
            assertEquals(List.of("Place textures/wood.png at props/wood.png in the output, where props/crate.fbx looks for it "
                    + "(FBX files cannot be edited)"), proposal.fixes().stream().map(BlockerFixes.Fix::description).toList());
            manager.applyBlockerFixes(id, 1, proposal.fixes());
            Report report = run(manager, id, output);
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertTrue(Files.exists(output.resolve("props/wood.png")));
            assertTrue(Files.exists(directory.resolve("source/textures/wood.png")), "the source is untouched");
        }
    }

    @Test void anFbxTheReaderCannotReadIsAcknowledgedAndReportedAsNotChecked() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source"));
        Files.write(source.resolve("stub.fbx"), "Kaydara FBX Binary  \0\u001a\0è\u001c\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Path output = Files.createDirectory(directory.resolve("output"));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID id = manager.importAssets(source).snapshot().project().id();
            MigrationPlan blocked = manager.createPlan(id, UNITY, output);
            assertEquals(Set.of("INSPECTION_UNREADABLE"), blockers(blocked));
            var proposal = manager.proposeBlockerFixes(id, blocked, Optional.empty());
            BlockerFixes.Fix fix = proposal.fixes().getFirst();
            assertTrue(fix.recommended() && fix.description().startsWith("Include stub.fbx without checking it"), fix.description());
            manager.applyBlockerFixes(id, 1, List.of(fix));
            Report report = run(manager, id, output);
            assertEquals(Report.ValidationStatus.INCOMPLETE, report.validationStatus());
            assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("OUTPUT_FORMAT_UNCHECKED")
                    && f.message().contains("the built-in FBX reader could not read it")), report.findings().toString());
        }
    }
}
