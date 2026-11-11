package io.github.aiassetmigrate.validation;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OutputValidatorTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), runId = UUID.randomUUID();
    @TempDir Path directory;

    private record Fixture(MigrationPlan plan, InventorySnapshot inventory, List<MigrationCommand> commands) {}

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** One command per entry: relative output path, source type and the bytes the source held. */
    private Fixture fixture(Path output, Map<String, Asset.Type> files, Map<String, byte[]> contents,
                            MigrationCommand.Operation operation) throws Exception {
        List<Asset> assets = new ArrayList<>();
        List<MigrationCommand> commands = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Asset asset = new Asset(UUID.randomUUID(), Path.of(entry.getKey()), entry.getValue(),
                    sha(contents.get(entry.getKey())), Map.of());
            assets.add(asset);
            commands.add(new AssetOperationCommand(UUID.randomUUID(), asset.id(), operation, Path.of(entry.getKey()),
                    operation == MigrationCommand.Operation.REWRITE_REFERENCES ? Map.of("r", "x") : Map.of(), List.of(), Optional.empty()));
        }
        var inventory = new InventorySnapshot(1, 1, new Project(project, directory.resolve("source"), 3, assets, TIME),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, TIME, List.of(), List.of());
        var plan = new MigrationPlan(planId, project, 1, 3, "unity", "6000.0", output, List.of(), commands, List.of(),
                PlanStatus.APPROVED, TIME);
        return new Fixture(plan, inventory, commands);
    }

    private MigrationRun run(Fixture fixture, RunStatus status, Map<UUID, StepStatus> steps, boolean completed,
                             Map<UUID, String> details) {
        return new MigrationRun(runId, project, planId, 1, fixture.plan().outputRoot(), status, steps, TIME,
                Optional.of(TIME), completed ? Optional.of(TIME) : Optional.empty(), Optional.empty(), details);
    }

    private static Map<UUID, StepStatus> all(Fixture fixture, StepStatus status) {
        Map<UUID, StepStatus> steps = new LinkedHashMap<>();
        fixture.commands().forEach(command -> steps.put(command.id(), status));
        return steps;
    }

    private static Set<String> codes(OutputValidator.Result result) {
        return result.findings().stream().map(Finding::code).collect(Collectors.toSet());
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void uncompletedRunChecksOutputsFoundButIsNeverPassed() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        byte[] a = bytes("v 0 0 0\n");
        Files.write(output.resolve("a.obj"), a);
        var fixture = fixture(output, new TreeMap<>(Map.of("a.obj", Asset.Type.MODEL, "b.obj", Asset.Type.MODEL)),
                Map.of("a.obj", a, "b.obj", bytes("v 1 1 1\n")), MigrationCommand.Operation.COPY);
        var result = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.INCOMPLETE,
                all(fixture, StepStatus.PENDING), false, Map.of()), fixture.inventory());
        assertEquals(Report.ValidationStatus.INCOMPLETE, result.status());
        assertTrue(codes(result).containsAll(Set.of("RUN_NOT_COMPLETED", "OUTPUT_VERIFIED", "OUTPUT_NOT_PRODUCED")), result.findings().toString());
        assertEquals(new OutputValidator.Counts(2, 1, 1, 0, 0, 1), result.counts());
        assertTrue(result.summary().contains("did not record completion"), result.summary());
    }

    @Test void partialRunReportsSkippedStepsAndCannotPass() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        byte[] a = bytes("v 0 0 0\n");
        Files.write(output.resolve("a.obj"), a);
        var fixture = fixture(output, new TreeMap<>(Map.of("a.obj", Asset.Type.MODEL, "b.obj", Asset.Type.MODEL)),
                Map.of("a.obj", a, "b.obj", bytes("v 1 1 1\n")), MigrationCommand.Operation.COPY);
        Map<UUID, StepStatus> steps = new LinkedHashMap<>();
        steps.put(fixture.commands().get(0).id(), StepStatus.SUCCESS);
        steps.put(fixture.commands().get(1).id(), StepStatus.FAILED);
        var result = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.PARTIAL, steps, true, Map.of()), fixture.inventory());
        assertEquals(Report.ValidationStatus.INCOMPLETE, result.status());
        var notProduced = result.findings().stream().filter(f -> f.code().equals("OUTPUT_NOT_PRODUCED")).toList();
        assertEquals(1, notProduced.size());
        assertTrue(notProduced.getFirst().message().contains("FAILED"), notProduced.toString());
    }

    @Test void runWithNoRecordedOutputsIsNotValidated() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        var fixture = fixture(output, Map.of("a.obj", Asset.Type.MODEL), Map.of("a.obj", bytes("v\n")), MigrationCommand.Operation.COPY);
        var result = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.FAILED,
                all(fixture, StepStatus.SKIPPED), true, Map.of()), fixture.inventory());
        assertEquals(Report.ValidationStatus.NOT_VALIDATED, result.status());
        assertTrue(result.summary().contains("nothing was validated"), result.summary());
    }

    @Test void transformedOutputWithoutARecordedHashIsUnknown() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        byte[] mtl = bytes("newmtl A\n");
        Files.write(output.resolve("a.mtl"), mtl);
        var fixture = fixture(output, Map.of("a.mtl", Asset.Type.MATERIAL), Map.of("a.mtl", bytes("source differs\n")),
                MigrationCommand.Operation.REWRITE_REFERENCES);
        UUID step = fixture.commands().getFirst().id();
        var unknown = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.SUCCESS, all(fixture, StepStatus.SUCCESS),
                true, Map.of(step, "Published; temporary file cleanup failed: denied")), fixture.inventory());
        assertEquals(Report.ValidationStatus.INCOMPLETE, unknown.status());
        assertTrue(codes(unknown).contains("OUTPUT_HASH_UNKNOWN"));

        var recorded = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.SUCCESS, all(fixture, StepStatus.SUCCESS),
                true, Map.of(step, "Published with 1 reference rewrite(s); output SHA-256 " + sha(mtl))), fixture.inventory());
        assertEquals(Report.ValidationStatus.PASSED, recorded.status(), recorded.findings().toString());
    }

    @Test void referencesMustMatchOutputCaseAndFilesOutsideTheRunAreNotTrusted() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        byte[] obj = bytes("mtllib Hero.mtl\nmtllib extra.mtl\n");
        byte[] mtl = bytes("newmtl A\n");
        Files.write(output.resolve("hero.obj"), obj);
        Files.write(output.resolve("hero.mtl"), mtl);
        Files.write(output.resolve("extra.mtl"), bytes("newmtl B\n"));
        var fixture = fixture(output, new TreeMap<>(Map.of("hero.obj", Asset.Type.MODEL, "hero.mtl", Asset.Type.MATERIAL)),
                Map.of("hero.obj", obj, "hero.mtl", mtl), MigrationCommand.Operation.COPY);
        var result = OutputValidator.validate(fixture.plan(), run(fixture, RunStatus.SUCCESS,
                all(fixture, StepStatus.SUCCESS), true, Map.of()), fixture.inventory());
        assertEquals(Report.ValidationStatus.FAILED, result.status());
        var unresolved = result.findings().stream().filter(f -> f.code().equals("OUTPUT_REFERENCE_UNRESOLVED")).toList();
        assertEquals(1, unresolved.size(), result.findings().toString());
        assertTrue(unresolved.getFirst().message().contains("'Hero.mtl'"), unresolved.toString());
        var outside = result.findings().stream().filter(f -> f.code().equals("OUTPUT_REFERENCE_OUTSIDE_RUN")).toList();
        assertEquals(1, outside.size(), result.findings().toString());
        assertEquals(Finding.Severity.UNKNOWN, outside.getFirst().severity());
    }

    @Test void runMustBelongToThePlanAndRevision() throws Exception {
        Path output = Files.createDirectory(directory.resolve("output"));
        var fixture = fixture(output, Map.of("a.obj", Asset.Type.MODEL), Map.of("a.obj", bytes("v\n")), MigrationCommand.Operation.COPY);
        var foreign = new MigrationRun(runId, project, UUID.randomUUID(), 1, output, RunStatus.SUCCESS,
                all(fixture, StepStatus.SUCCESS), TIME, Optional.of(TIME), Optional.of(TIME));
        assertThrows(IllegalArgumentException.class, () -> OutputValidator.validate(fixture.plan(), foreign, fixture.inventory()));
        var stale = new InventorySnapshot(1, 1, new Project(project, directory.resolve("source"), 4, List.of(), TIME),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, TIME, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> OutputValidator.validate(fixture.plan(),
                run(fixture, RunStatus.SUCCESS, all(fixture, StepStatus.SUCCESS), true, Map.of()), stale));
    }
}
