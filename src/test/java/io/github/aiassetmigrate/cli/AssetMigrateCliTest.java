package io.github.aiassetmigrate.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class AssetMigrateCliTest {
    @Test
    void helpAndDefaultInvocationExplainBootstrapScope() {
        for (String[] args : new String[][] { {}, { "--help" } }) {
            StringWriter output = new StringWriter();
            CommandLine cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(output));
            assertEquals(0, cli.execute(args));
            assertTrue(output.toString().contains("Usage: aiassetmigrate"));
            assertTrue(output.toString().replaceAll("\\s+", " ").contains("not implemented yet"));
        }
    }

    @Test
    void versionIsAvailableWithoutStartingJavafx() {
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new AssetMigrateCli()).setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("--version"));
        assertTrue(output.toString().contains("0.1.0-SNAPSHOT"));
    }

    @Test
    void unimplementedCommandsFailInsteadOfPretendingSuccess() {
        StringWriter errors = new StringWriter();
        CommandLine cli = new CommandLine(new AssetMigrateCli()).setErr(new PrintWriter(errors));
        assertEquals(2, cli.execute("migrate"));
        assertTrue(errors.toString().contains("migrate"));
    }
}
