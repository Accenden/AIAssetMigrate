package io.github.aiassetmigrate.migration;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CopyExecutorTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2024-01-01T00:00:00Z");

    private record Fixture(InventorySnapshot snapshot, MigrationPlan plan, Path source, Path output, Path state, Path log) {}

    private Fixture fixture() throws Exception {
        Path base = temp.toRealPath(), source = Files.createDirectory(base.resolve("source"));
        Path output = Files.createDirectory(base.resolve("output"));
        Path state = base.resolve("state"), log = base.resolve("log");
        List<Asset> assets = new ArrayList<>();
        List<MigrationCommand> commands = new ArrayList<>();
        for (String name : List.of("a.txt", "b.txt")) {
            byte[] content = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(source.resolve(name), content);
            UUID asset = UUID.randomUUID();
            assets.add(new Asset(asset, Path.of(name), Asset.Type.OTHER,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), Map.of()));
            commands.add(new AssetOperationCommand(UUID.randomUUID(), asset, MigrationCommand.Operation.COPY,
                    Path.of(name), Map.of(), List.of(), Optional.empty()));
        }
        Project project = new Project(UUID.randomUUID(), source, 1, assets, now);
        InventorySnapshot snapshot = new InventorySnapshot(1, 1, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE, now, List.of(), List.of());
        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), project.id(), 1, 1, "test", "1", output,
                List.of(), commands, List.of(), PlanStatus.APPROVED, now);
        return new Fixture(snapshot, plan, source, output, state, log);
    }

    private MigrationPlan withCommands(Fixture f, List<MigrationCommand> commands) {
        var p = f.plan;
        return new MigrationPlan(p.id(), p.projectId(), p.version(), p.inventoryRevision(), p.targetProfile(),
                p.targetProfileVersion(), p.outputRoot(), p.reviewedChanges(), commands, p.findings(), p.status(), p.createdAt());
    }

    private CopyExecutor.Result run(Fixture f, MigrationPlan plan) {
        return new CopyExecutor().execute(f.snapshot, plan, f.state, f.log);
    }

    @Test void createsMissingReviewedDestinationFoldersOnlyDuringExecution() throws Exception {
        Fixture f = fixture();
        MigrationCommand a = f.plan.commands().getFirst(), b = f.plan.commands().get(1);
        var folder = new ReviewedChange(UUID.randomUUID(), a.assetId(), ReviewedChange.Kind.FOLDER,
                ReviewedChange.Decision.ACCEPTED, Map.of("folder", "deep/er"), now);
        var p = f.plan;
        var plan = new MigrationPlan(p.id(), p.projectId(), p.version(), p.inventoryRevision(), p.targetProfile(),
                p.targetProfileVersion(), p.outputRoot(), List.of(folder), List.of(new AssetOperationCommand(a.id(), a.assetId(),
                        MigrationCommand.Operation.COPY, Path.of("deep", "er", "a.txt"), Map.of(), List.of(), Optional.empty()), b),
                p.findings(), p.status(), p.createdAt());
        new CopyExecutor().validatePreflight(f.snapshot, plan, f.state, f.log);
        assertFalse(Files.exists(f.output.resolve("deep")));
        var result = run(f, plan);
        assertTrue(result.preflightPassed(), result.error());
        assertEquals("a.txt", Files.readString(f.output.resolve("deep/er/a.txt")));
        assertEquals("b.txt", Files.readString(f.output.resolve("b.txt")));
    }

    @Test void rewriteCommandsMustMatchTheReviewedPreview() throws Exception {
        Fixture f = fixture();
        MigrationCommand original = f.plan.commands().getFirst();
        var rewrite = new AssetOperationCommand(original.id(), original.assetId(), MigrationCommand.Operation.REWRITE_REFERENCES,
                original.relativeOutputPath(), CopyPlanBuilder.encode(List.of(new io.github.aiassetmigrate.analysis.MaterialAnalysis.Rewrite(
                        AssetReference.Kind.TEXTURE, "a.png", "b.png"))), List.of(), Optional.empty());
        var result = run(f, withCommands(f, List.of(rewrite, f.plan.commands().get(1))));
        assertFalse(result.preflightPassed());
        assertTrue(result.error().contains("rewrites differ"), result.error());
        var forged = new AssetOperationCommand(original.id(), original.assetId(), MigrationCommand.Operation.REWRITE_REFERENCES,
                original.relativeOutputPath(), Map.of("rewrite.0000.kind", "TEXTURE"), List.of(), Optional.empty());
        assertFalse(run(f, withCommands(f, List.of(forged))).preflightPassed());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
    }

    @Test void copiesAndPreservesSources() throws Exception {
        Fixture f = fixture();
        var result = run(f, f.plan);
        assertTrue(result.preflightPassed(), result.error());
        assertEquals(List.of(CopyExecutor.Status.COMPLETED, CopyExecutor.Status.COMPLETED), result.steps().stream().map(CopyExecutor.Step::status).toList());
        assertEquals("a.txt", Files.readString(f.output.resolve("a.txt")));
        assertEquals("a.txt", Files.readString(f.source.resolve("a.txt")));
    }

    @Test void preflightCollisionPreventsEveryWrite() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.output.resolve("b.txt"), "existing");
        var result = run(f, f.plan);
        assertFalse(result.preflightPassed());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
        assertEquals("existing", Files.readString(f.output.resolve("b.txt")));
    }

    @Test void sourceMutationBeforePublishFailsAndSkipsLaterSteps() throws Exception {
        Fixture f = fixture();
        var executor = new CopyExecutor((i, stage) -> {
            if (i == 0 && stage.equals("before-publish")) Files.writeString(f.source.resolve("a.txt"), "changed");
        });
        var result = executor.execute(f.snapshot, f.plan, f.state, f.log);
        assertTrue(result.preflightPassed());
        assertEquals(List.of(CopyExecutor.Status.FAILED, CopyExecutor.Status.SKIPPED), result.steps().stream().map(CopyExecutor.Step::status).toList());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
        try (var stream = Files.list(f.output)) { assertEquals(0, stream.count()); }
    }

    @Test void publishedEarlierStepIsNotRolledBackOnFailure() throws Exception {
        Fixture f = fixture();
        var executor = new CopyExecutor((i, stage) -> { if (i == 1 && stage.equals("before-step")) throw new IOException("injected"); });
        var result = executor.execute(f.snapshot, f.plan, f.state, f.log);
        assertEquals(List.of(CopyExecutor.Status.COMPLETED, CopyExecutor.Status.FAILED), result.steps().stream().map(CopyExecutor.Step::status).toList());
        assertEquals("a.txt", Files.readString(f.output.resolve("a.txt")));
        assertFalse(Files.exists(f.output.resolve("b.txt")));
    }

    @Test void lateCollisionDoesNotOverwrite() throws Exception {
        Fixture f = fixture();
        var executor = new CopyExecutor((i, stage) -> { if (i == 0 && stage.equals("before-publish")) Files.writeString(f.output.resolve("a.txt"), "racer"); });
        var result = executor.execute(f.snapshot, f.plan, f.state, f.log);
        assertEquals(CopyExecutor.Status.FAILED, result.steps().getFirst().status());
        assertEquals("racer", Files.readString(f.output.resolve("a.txt")));
    }

    @Test void parentReplacementIsDetectedBeforePublication() throws Exception {
        Fixture f = fixture();
        var executor = new CopyExecutor((i, stage) -> {
            if (i == 0 && stage.equals("before-publish")) {
                Files.move(f.output, temp.resolve("old-output"));
                Files.createDirectory(f.output);
            }
        });
        var result = executor.execute(f.snapshot, f.plan, f.state, f.log);
        assertEquals(CopyExecutor.Status.FAILED, result.steps().getFirst().status());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
    }

    @Test void sourceMutationBeforeExecutionFailsWholePreflight() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.source.resolve("b.txt"), "changed");
        var result = run(f, f.plan);
        assertFalse(result.preflightPassed());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
    }

    @Test void rejectsHardLinksAndSymlinks() throws Exception {
        Fixture f = fixture();
        if (Files.getFileStore(f.source).supportsFileAttributeView("unix")) {
            try { Files.createLink(temp.resolve("alias"), f.source.resolve("a.txt")); }
            catch (UnsupportedOperationException | IOException e) { assumeTrue(false, "hard links unavailable"); }
            assertFalse(run(f, f.plan).preflightPassed());
            Files.delete(temp.resolve("alias"));
        }
        Files.delete(f.source.resolve("a.txt"));
        io.github.aiassetmigrate.TestLinks.symlinkOrSkip(f.source.resolve("a.txt"), f.source.resolve("b.txt"));
        assertFalse(run(f, f.plan).preflightPassed());
    }

    @Test void rejectsTraversalUnsupportedOperationsAndOverlap() throws Exception {
        Fixture f = fixture();
        MigrationCommand original = f.plan.commands().getFirst();
        var traversal = new AssetOperationCommand(original.id(), original.assetId(), MigrationCommand.Operation.COPY,
                Path.of("..", "output2", "a.txt"), Map.of(), List.of(), Optional.empty());
        assertFalse(run(f, withCommands(f, List.of(traversal))).preflightPassed());
        var conversion = new AssetOperationCommand(original.id(), original.assetId(), MigrationCommand.Operation.CONVERT_TEXTURE,
                Path.of("a.txt"), Map.of(), List.of(), Optional.empty());
        assertFalse(run(f, withCommands(f, List.of(conversion))).preflightPassed());
        assertFalse(new CopyExecutor().execute(f.snapshot, f.plan, f.output.resolve("state"), f.log).preflightPassed());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
    }

    @Test void rejectsStaleRevisionAndSymlinkOutputRoot() throws Exception {
        Fixture f = fixture();
        var p = f.plan;
        var stale = new MigrationPlan(p.id(), p.projectId(), p.version(), p.inventoryRevision() + 1,
                p.targetProfile(), p.targetProfileVersion(), p.outputRoot(), p.reviewedChanges(),
                p.commands(), p.findings(), p.status(), p.createdAt());
        assertFalse(run(f, stale).preflightPassed());
        Path alias = temp.resolve("output-alias");
        io.github.aiassetmigrate.TestLinks.symlinkOrSkip(alias, f.output);
        var aliased = new MigrationPlan(p.id(), p.projectId(), p.version(), p.inventoryRevision(),
                p.targetProfile(), p.targetProfileVersion(), alias.toAbsolutePath(), p.reviewedChanges(),
                p.commands(), p.findings(), p.status(), p.createdAt());
        assertFalse(run(f, aliased).preflightPassed());
        assertFalse(Files.exists(f.output.resolve("a.txt")));
    }
}
