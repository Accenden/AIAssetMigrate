package io.github.aiassetmigrate.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RescanCommandTest {
    @TempDir Path directory;

    @Test
    void rescansExistingProjectAndPreservesAssetIdentity() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path asset = Files.writeString(source.resolve("mesh.obj"), "v 0 0 0\n");
        Path state = directory.resolve("state");
        Path logs = directory.resolve("logs");
        StringWriter imported = new StringWriter();
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(imported));

        assertEquals(0, cli.execute("import", source.toString(), "--state-directory", state.toString(),
                "--log-directory", logs.toString()));
        UUID project = imported.toString().lines().filter(line -> line.startsWith("Project ID: "))
                .map(line -> UUID.fromString(line.substring("Project ID: ".length()))).findFirst().orElseThrow();
        String initialAsset = imported.toString().lines().filter(line -> line.contains("mesh.obj [MODEL]"))
                .findFirst().orElseThrow();

        Files.writeString(asset, "v 1 0 0\n");
        StringWriter rescanned = new StringWriter();
        StringWriter errors = new StringWriter();
        cli.setOut(new PrintWriter(rescanned)).setErr(new PrintWriter(errors));
        assertEquals(0, cli.execute("rescan", source.toString(), "--project", project.toString(),
                "--state-directory", state.toString(), "--log-directory", logs.toString()), errors.toString());
        String updatedAsset = rescanned.toString().lines().filter(line -> line.contains("mesh.obj [MODEL]"))
                .findFirst().orElseThrow();

        assertTrue(rescanned.toString().contains("Revision: 2"));
        assertTrue(updatedAsset.contains("ID: " + initialAsset.split("ID: ")[1].split(" SHA-256:")[0]));
        assertNotEquals(initialAsset.substring(initialAsset.indexOf("SHA-256: ")),
                updatedAsset.substring(updatedAsset.indexOf("SHA-256: ")));
        assertEquals("v 1 0 0\n", Files.readString(asset));
    }

    @Test
    void rejectsMissingProjectWithoutCreatingInventory() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        UUID project = UUID.randomUUID();
        var cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter()));
        assertNotEquals(0, cli.execute("rescan", source.toString(), "--project", project.toString(),
                "--state-directory", directory.resolve("state").toString(),
                "--log-directory", directory.resolve("logs").toString()));
        assertFalse(Files.exists(directory.resolve("state/projects").resolve(project.toString())
                .resolve("inventory-000001.json")));
        assertFalse(Files.exists(directory.resolve("logs")));
    }
}
