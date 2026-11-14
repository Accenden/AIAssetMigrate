package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Leaving skipped files out of a project: coverage, carry-forward, planning, and references that still block. */
class ExclusionIntegrationTest {
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    @TempDir Path directory;

    private static boolean blocks(io.github.aiassetmigrate.domain.MigrationPlan plan, String code) {
        return plan.findings().stream().anyMatch(f -> f.code().equals(code)
                && (f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN));
    }

    @Test void leavingAStrayFileOutUnblocksThePlanWithoutTouchingSources() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd hero.png\n");
        Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
        Files.createDirectory(source.resolve("misc"));
        Files.writeString(source.resolve("misc/notes.txt"), "a stray file");
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            InventorySnapshot partial = manager.importAssets(source).snapshot();
            UUID project = partial.project().id();
            assertEquals(InventorySnapshot.ScanStatus.PARTIAL, partial.scanStatus());
            assertEquals(Set.of(Path.of("misc", "notes.txt")),
                    Set.copyOf(partial.excludableFiles().stream().map(PathOutcome::relativePath).toList()));
            assertTrue(blocks(manager.createPlan(project, UNITY, output), "PARTIAL_INVENTORY"), "a skipped file blocks planning");

            InventorySnapshot complete = manager.rescan(project, source, Set.of(Path.of("misc", "notes.txt"))).snapshot();
            assertEquals(InventorySnapshot.ScanStatus.COMPLETE, complete.scanStatus());
            assertTrue(complete.hasCompleteCoverage());
            assertEquals(Set.of(Path.of("misc", "notes.txt")), complete.excludedFiles());
            assertTrue(complete.excludableFiles().isEmpty());
            assertTrue(complete.findings().stream().anyMatch(f -> f.code().equals("EXCLUDED_BY_USER")
                    && f.sourceRelativePath().equals(Optional.of(Path.of("misc", "notes.txt")))));
            assertEquals(3, complete.project().assets().size(), "a left-out file is not an asset");

            InventorySnapshot kept = manager.rescan(project, source).snapshot();
            assertEquals(Set.of(Path.of("misc", "notes.txt")), kept.excludedFiles(), "a plain rescan keeps exclusions");
            assertEquals(InventorySnapshot.ScanStatus.COMPLETE, kept.scanStatus());

            var plan = manager.createPlan(project, UNITY, output);
            assertFalse(blocks(plan, "PARTIAL_INVENTORY"), plan.findings().toString());
            assertFalse(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN), plan.findings().toString());
            manager.approve(plan.id(), plan.version());
            var outcome = manager.migrateAndValidate(plan.id(), Optional.empty());
            assertEquals(RunStatus.SUCCESS, outcome.run().status(), outcome.toString());
            assertFalse(Files.exists(output.resolve("misc/notes.txt")), "left-out files are not migrated");
            assertEquals("a stray file", Files.readString(source.resolve("misc/notes.txt")), "sources are never changed");

            InventorySnapshot included = manager.rescan(project, source, Set.of()).snapshot();
            assertEquals(InventorySnapshot.ScanStatus.PARTIAL, included.scanStatus(), "including it again restores the gap");
            assertTrue(included.excludedFiles().isEmpty());
        }
    }

    @Test void leavingOutAFileThatIsReferencedStillBlocksThePlan() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd hero.png\n");
        Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            InventorySnapshot snapshot = manager.rescan(project, source, Set.of(Path.of("hero.png"))).snapshot();
            assertEquals(InventorySnapshot.ScanStatus.COMPLETE, snapshot.scanStatus());
            assertTrue(snapshot.references().stream().anyMatch(r -> r.originalText().equals("hero.png")
                    && r.resolutionStatus() == io.github.aiassetmigrate.domain.AssetReference.ResolutionStatus.SKIPPED),
                    "a reference to a left-out file is skipped, not resolved or missing: " + snapshot.references());
            var plan = manager.createPlan(project, UNITY, output);
            assertTrue(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                    && f.code().equals("UNRESOLVED_REFERENCE")), "the material's texture is gone, so the plan is blocked: " + plan.findings());
        }
    }

    @Test void exclusionsMustBeRelativeFilePaths() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("a.obj"), "v 0 0 0\n");
        try (var manager = new DefaultAssetManager(directory.resolve("state"))) {
            UUID project = manager.importAssets(source).snapshot().project().id();
            assertThrows(IllegalArgumentException.class,
                    () -> manager.rescan(project, source, Set.of(io.github.aiassetmigrate.TestPaths.absolute("a.obj"))));
            assertThrows(IllegalArgumentException.class, () -> manager.rescan(project, source, Set.of(Path.of("..", "a.obj"))));
            assertThrows(IllegalArgumentException.class, () -> new PathOutcome(Path.of("dir"), PathOutcome.Kind.DIRECTORY,
                    PathOutcome.Status.EXCLUDED), "only files can be left out");
            assertEquals(1, manager.loadInventory(project).project().inventoryRevision(), "a rejected request scans nothing");
        }
    }
}
