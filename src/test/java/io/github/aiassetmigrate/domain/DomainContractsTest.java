package io.github.aiassetmigrate.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DomainContractsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path directory;

    private AssetOperationCommand command() {
        return new AssetOperationCommand(ID, ID, MigrationCommand.Operation.COPY,
                Path.of("textures", "sample.png"), Map.of(), List.of(), Optional.empty());
    }

    private MigrationPlan plan(List<MigrationCommand> commands) {
        return new MigrationPlan(ID, ID, 1, 0, "unity", "6", directory.resolve("output"),
                List.of(), commands, List.of(), PlanStatus.DRAFT, TIME);
    }

    private List<Record> snapshots() {
        return List.of(
                new Asset(ID, Path.of("sample.png"), Asset.Type.TEXTURE, "a".repeat(64), Map.of()),
                new Project(ID, directory, 0, List.of(), TIME),
                new InventorySnapshot(1, 1, new Project(ID, directory, 1, List.of(), TIME),
                        List.of(), InventorySnapshot.ScanStatus.PARTIAL, TIME, List.of(), List.of()),
                new PathOutcome(Path.of("sample.png"), PathOutcome.Kind.FILE, PathOutcome.Status.SKIPPED),
                new AssetReference(ID, AssetReference.Kind.TEXTURE, "sample.png", Optional.empty(),
                        AssetReference.ResolutionStatus.UNRESOLVED),
                new Finding(ID, Optional.empty(), Optional.empty(), Finding.Severity.UNKNOWN, "unknown", "Not checked"),
                new ReviewedChange(ID, ID, ReviewedChange.Kind.CATEGORY,
                        ReviewedChange.Decision.ACCEPTED, Map.of("category", "texture"), TIME),
                command(), plan(List.of(command())),
                new MigrationRun(ID, ID, ID, 1, directory.resolve("output"), RunStatus.PENDING,
                        Map.of(ID, StepStatus.PENDING), TIME, Optional.empty(), Optional.empty()),
                new Report(ID, ID, ID, RunStatus.INCOMPLETE, Report.ValidationStatus.NOT_VALIDATED,
                        List.of(), "Not validated", TIME),
                new LogEvent(ID, ID, 1, TIME, LogEvent.Level.INFO, "Created", Optional.empty(),
                        Optional.empty(), Map.of()));
    }

    @Test
    void everyReferenceComponentRejectsNull() throws Exception {
        for (Record snapshot : snapshots()) {
            RecordComponent[] components = snapshot.getClass().getRecordComponents();
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            var constructor = snapshot.getClass().getDeclaredConstructor(types);
            Object[] arguments = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                arguments[i] = components[i].getAccessor().invoke(snapshot);
            }
            for (int i = 0; i < components.length; i++) {
                if (!types[i].isPrimitive()) {
                    Object[] invalid = arguments.clone();
                    invalid[i] = null;
                    var exception = assertThrows(InvocationTargetException.class,
                            () -> constructor.newInstance(invalid),
                            snapshot.getClass().getSimpleName() + "." + components[i].getName());
                    assertInstanceOf(NullPointerException.class, exception.getCause());
                }
            }
        }
    }

    @Test
    void collectionsAreSnapshotsAndUnmodifiable() {
        var metadata = new HashMap<String, String>();
        metadata.put("format", "png");
        var asset = new Asset(ID, Path.of("sample.png"), Asset.Type.TEXTURE, "a".repeat(64), metadata);
        var review = new ReviewedChange(ID, ID, ReviewedChange.Kind.CATEGORY,
                ReviewedChange.Decision.ACCEPTED, metadata, TIME);
        var dependencies = new ArrayList<UUID>();
        var operation = new AssetOperationCommand(ID, ID, MigrationCommand.Operation.COPY,
                Path.of("sample.png"), metadata, dependencies, Optional.empty());
        var event = new LogEvent(ID, ID, 1, TIME, LogEvent.Level.INFO, "event",
                Optional.empty(), Optional.empty(), metadata);
        metadata.clear();
        dependencies.add(ID);
        for (Map<String, String> values : List.of(asset.metadata(), review.values(), operation.parameters(), event.details())) {
            assertEquals(Map.of("format", "png"), values);
            assertThrows(UnsupportedOperationException.class, () -> values.put("new", "value"));
        }
        assertTrue(operation.dependencies().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> operation.dependencies().add(ID));

        var assets = new ArrayList<>(List.of(asset));
        var project = new Project(ID, directory, 1, assets, TIME);
        var commands = new ArrayList<MigrationCommand>(List.of(operation));
        var reviews = new ArrayList<>(List.of(review));
        var findings = new ArrayList<Finding>();
        var plan = new MigrationPlan(ID, ID, 1, 1, "unity", "6", directory.resolve("output"),
                reviews, commands, findings, PlanStatus.DRAFT, TIME);
        var report = new Report(ID, ID, ID, RunStatus.INCOMPLETE,
                Report.ValidationStatus.NOT_VALIDATED, findings, "summary", TIME);
        assets.clear();
        commands.clear();
        reviews.clear();
        findings.add(new Finding(ID, Optional.of(ID), Optional.empty(), Finding.Severity.WARNING, "test", "test"));
        assertEquals(List.of(asset), project.assets());
        assertEquals(List.of(operation), plan.commands());
        assertEquals(List.of(review), plan.reviewedChanges());
        assertTrue(plan.findings().isEmpty());
        assertTrue(report.findings().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> project.assets().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.commands().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.reviewedChanges().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.findings().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.findings().clear());

        var steps = new HashMap<>(Map.of(ID, StepStatus.PENDING));
        var run = new MigrationRun(ID, ID, ID, 1, directory, RunStatus.PENDING,
                steps, TIME, Optional.empty(), Optional.empty());
        steps.put(ID, StepStatus.SUCCESS);
        assertEquals(StepStatus.PENDING, run.stepStatuses().get(ID));
        assertThrows(UnsupportedOperationException.class, () -> run.stepStatuses().clear());
    }

    @Test
    void nullCollectionElementsAreRejected() {
        assertThrows(NullPointerException.class, () -> plan(Arrays.asList(command(), null)));
        var metadata = new HashMap<String, String>();
        metadata.put("format", null);
        assertThrows(NullPointerException.class,
                () -> new Asset(ID, Path.of("sample.png"), Asset.Type.TEXTURE, "a".repeat(64), metadata));
    }

    @Test
    void invalidNumericValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Project(ID, directory, -1, List.of(), TIME));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPlan(ID, ID, 0, 0,
                "unity", "6", directory, List.of(), List.of(), List.of(), PlanStatus.DRAFT, TIME));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPlan(ID, ID, 1, -1,
                "unity", "6", directory, List.of(), List.of(), List.of(), PlanStatus.DRAFT, TIME));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRun(ID, ID, ID, 0, directory,
                RunStatus.PENDING, Map.of(), TIME, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LogEvent(ID, ID, 0, TIME,
                LogEvent.Level.INFO, "event", Optional.empty(), Optional.empty(), Map.of()));
    }

    @Test
    void absoluteAssetAndCommandPathsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(ID, directory, Asset.Type.TEXTURE, "a".repeat(64), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssetOperationCommand(ID, ID,
                MigrationCommand.Operation.COPY, directory, Map.of(), List.of(), Optional.empty()));
    }

    @Test
    void constructingSnapshotsDoesNotAccessOrCreateOutput() throws Exception {
        assertEquals(12, snapshots().size());
        assertFalse(Files.exists(directory.resolve("output")));
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
        assertEquals(command(), command());
    }
}
