package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PlanCommandsTest {
    private final UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), asset = UUID.randomUUID(), command = UUID.randomUUID();
    private final Instant time = Instant.parse("2025-01-01T00:00:00Z");

    private MigrationPlan plan() {
        return new MigrationPlan(planId, project, 4, 7, "engine\nname", "v1", Path.of("output"),
                List.of(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.NAME,
                        ReviewedChange.Decision.ACCEPTED, Map.of("key", "value\nline"), time)),
                List.of(new AssetOperationCommand(command, asset, MigrationCommand.Operation.COPY,
                        Path.of("dest\nfile"), Map.of("source", "a\\b"), List.of(), Optional.empty())),
                List.of(new Finding(UUID.randomUUID(), Optional.of(asset), Optional.of(Path.of("source")),
                        Finding.Severity.ERROR, "BLOCK", "bad\u001bmessage")), PlanStatus.DRAFT, time);
    }

    private InventorySnapshot inventory(UUID projectId, long revision, List<Asset> assets) {
        return new InventorySnapshot(1, 1, new Project(projectId, Path.of("/source"), revision, assets, time),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, time, List.of(), List.of());
    }

    private Asset sourceAsset(UUID id) {
        return new Asset(id, Path.of("source\n\"\u001bfile"), Asset.Type.OTHER, "0".repeat(64), Map.of());
    }

    private InventorySnapshot checkedInventory(Object[] args) {
        assertArrayEquals(new Object[]{project, 7L}, args);
        return inventory(project, 7, List.of(sourceAsset(asset)));
    }

    @Test void planCreationDisplaysExactFieldsWithoutFurtherActions() {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter output = new StringWriter();
        var cli = new CommandLine(new PlanCommands.Plan((state, logs) -> {
            assertEquals(Path.of("s"), state); assertEquals(Path.of("l"), logs);
            return manager((name, args) -> {
                if (name.equals("close")) { closed.set(true); return null; }
                if (name.equals("loadInventory")) return checkedInventory(args);
                assertEquals("createPlan", name);
                assertEquals(project, args[0]);
                assertEquals(new AssetManager.TargetProfile("engine", "1"), args[1]);
                assertEquals(Path.of("out"), args[2]);
                return plan();
            });
        })).setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("--project", project.toString(), "--target", "engine", "--target-version", "1",
                "--output", "out", "--state-directory", "s", "--log-directory", "l"));
        assertTrue(closed.get());
        String text = output.toString();
        for (String expected : List.of(planId.toString(), project.toString(), "Version: 4", "Inventory revision: 7",
                "engine\\u000aname", "Status: DRAFT", "value\\u000aline", command.toString(), asset.toString(),
                "dest\\u000afile", "Source relative path: \"source\\u000a\\\"\\u001bfile\"",
                "a\\\\b", "Severity: ERROR", "BLOCK", "bad\\u001bmessage")) {
            assertTrue(text.contains(expected), expected);
        }
    }

    @Test void showAndApproveCallOnlyTheirExactOperations() {
        for (boolean approve : List.of(false, true)) {
            var factory = (ImportCommand.ManagerFactory) (state, logs) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                if (name.equals("loadInventory")) return checkedInventory(args);
                assertEquals(approve ? "approve" : "loadPlan", name);
                assertEquals(planId, args[0]);
                if (approve) assertEquals(4, args[1]);
                return plan();
            });
            StringWriter output = new StringWriter();
            var cli = new CommandLine(approve ? new PlanCommands.Approve(factory) : new PlanCommands.Show(factory))
                    .setOut(new PrintWriter(output));
            String[] args = approve
                    ? new String[]{"--plan", planId.toString(), "--version", "4", "--state-directory", "s", "--log-directory", "l"}
                    : new String[]{"--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"};
            assertEquals(0, cli.execute(args));
            assertTrue(output.toString().contains("Source relative path: \"source\\u000a\\\"\\u001bfile\""));
        }
    }

    @Test void mismatchedOrMissingInventoryNeverDisplaysMisleadingPlan() {
        for (InventorySnapshot snapshot : List.of(
                inventory(UUID.randomUUID(), 7, List.of(sourceAsset(asset))),
                inventory(project, 8, List.of(sourceAsset(asset))),
                inventory(project, 7, List.of()),
                inventory(project, 7, List.of(sourceAsset(asset), sourceAsset(asset))))) {
            StringWriter output = new StringWriter(), error = new StringWriter();
            var cli = new CommandLine(new PlanCommands.Show((state, logs) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                if (name.equals("loadPlan")) return plan();
                assertEquals("loadInventory", name);
                assertArrayEquals(new Object[]{project, 7L}, args);
                return snapshot;
            }))).setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
            assertEquals(1, cli.execute("--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"));
            assertTrue(output.toString().isEmpty());
            assertTrue(error.toString().contains("Cannot display plan sources"));
        }
    }

    @Test void failureToLoadInventoryAfterMutationReportsReturnedPlan() {
        for (boolean approve : List.of(false, true)) {
            StringWriter error = new StringWriter();
            var factory = (ImportCommand.ManagerFactory) (state, logs) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                if (name.equals("loadInventory")) throw new IllegalStateException("unavailable\nrevision");
                assertEquals(approve ? "approve" : "createPlan", name);
                return plan();
            });
            var cli = new CommandLine(approve ? new PlanCommands.Approve(factory) : new PlanCommands.Plan(factory))
                    .setErr(new PrintWriter(error)).setOut(new PrintWriter(new StringWriter()));
            String[] args = approve
                    ? new String[]{"--plan", planId.toString(), "--version", "4", "--state-directory", "s", "--log-directory", "l"}
                    : new String[]{"--project", project.toString(), "--target", "engine", "--target-version", "1", "--output", "out", "--state-directory", "s", "--log-directory", "l"};
            assertEquals(1, cli.execute(args));
            assertTrue(error.toString().contains(planId.toString()));
            assertTrue(error.toString().contains("version 4 with status DRAFT; saved state may have changed"));
            assertTrue(error.toString().contains("unavailable\\u000arevision"));
        }
    }

    @Test void migrationReportsActualStatusAndNonSuccessExit() {
        for (RunStatus status : RunStatus.values()) {
            StringWriter output = new StringWriter();
            var cli = new CommandLine(new PlanCommands.Migrate((state, logs) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                assertEquals("migrate", name); assertEquals(planId, args[0]);
                return new MigrationRun(UUID.randomUUID(), project, planId, 4, Path.of("out"), status,
                        Map.of(command, StepStatus.FAILED), time, Optional.empty(), Optional.empty());
            }))).setOut(new PrintWriter(output));
            assertEquals(status == RunStatus.SUCCESS ? 0 : 1,
                    cli.execute("--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"));
            assertTrue(output.toString().contains("Status: " + status));
            assertTrue(output.toString().contains(command + " Status: FAILED"));
        }
    }

    @Test void migrateAndShowRunDisplayDiagnostics() {
        for (boolean show : List.of(false, true)) {
            StringWriter output = new StringWriter();
            var factory = (ImportCommand.ManagerFactory) (state, logs) -> manager((name, args) -> {
                if (name.equals("close")) return null;
                assertEquals(show ? "loadRun" : "migrate", name);
                return new MigrationRun(UUID.randomUUID(), project, planId, 4, Path.of("out"), RunStatus.PARTIAL,
                        Map.of(command, StepStatus.FAILED), time, Optional.of(time), Optional.of(time),
                        Optional.of("preflight failed"), Map.of(command, "cleanup \"warning\""));
            });
            var cli = new CommandLine(show ? new PlanCommands.ShowRun(factory) : new PlanCommands.Migrate(factory))
                    .setOut(new PrintWriter(output));
            assertEquals(show ? 0 : 1, cli.execute("--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"));
            assertTrue(output.toString().contains("Message: \"preflight failed\""));
            assertTrue(output.toString().contains("Detail: \"cleanup \\\"warning\\\"\""));
        }
    }

    @Test void invalidArgumentsAreRejectedBeforeManagerCreation() {
        var factory = (ImportCommand.ManagerFactory) (state, logs) -> { throw new AssertionError("manager created"); };
        for (String version : List.of("0", "-1", "bad", "99999999999999")) {
            var cli = new CommandLine(new PlanCommands.Approve(factory)).setErr(new PrintWriter(new StringWriter()));
            assertEquals(2, cli.execute("--plan", planId.toString(), "--version", version, "--state-directory", "s", "--log-directory", "l"));
        }
        var show = new CommandLine(new PlanCommands.Show(factory)).setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, show.execute("--plan", "not-a-uuid", "--state-directory", "s", "--log-directory", "l"));
        var plan = new CommandLine(new PlanCommands.Plan(factory)).setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, plan.execute("--project", project.toString(), "--target", " ", "--target-version", "1", "--output", "out", "--state-directory", "s", "--log-directory", "l"));
        var migrate = new CommandLine(new PlanCommands.Migrate(factory)).setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, migrate.execute("--plan", planId.toString()));
    }

    @Test void reportsEscapedFailureAndRegistersCommands() {
        StringWriter error = new StringWriter();
        var cli = new CommandLine(new PlanCommands.Show((state, logs) -> { throw new IllegalStateException("bad\npath"); }))
                .setErr(new PrintWriter(error));
        assertEquals(1, cli.execute("--plan", planId.toString(), "--state-directory", "s", "--log-directory", "l"));
        assertTrue(error.toString().contains("bad\\u000apath"));
        var root = new CommandLine(new AssetMigrateCli());
        for (String name : List.of("plan", "show-plan", "approve", "migrate")) assertTrue(root.getSubcommands().containsKey(name));
    }

    private interface Handler { Object call(String name, Object[] args); }
    private static AssetManager manager(Handler handler) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class},
                (proxy, method, args) -> handler.call(method.getName(), args));
    }
}
