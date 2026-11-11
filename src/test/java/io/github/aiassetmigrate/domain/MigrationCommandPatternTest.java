package io.github.aiassetmigrate.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MigrationCommandPatternTest {
    @Test void eachCommandInvokesItsOwnReceiverActionWithoutTheInvokerInspectingIt() throws Exception {
        List<String> calls = new ArrayList<>();
        MigrationCommand.Receiver receiver = new MigrationCommand.Receiver() {
            @Override public String copy(MigrationCommand c) { calls.add("copy:" + c.relativeOutputPath()); return "copied"; }
            @Override public String rewriteReferences(MigrationCommand c) { calls.add("rewrite:" + c.relativeOutputPath()); return "rewritten"; }
            @Override public String convertTexture(MigrationCommand c) { calls.add("convert:" + c.relativeOutputPath()); return "converted"; }
            @Override public String convertModel(MigrationCommand c) { calls.add("model:" + c.relativeOutputPath()); return "model"; }
        };
        List<MigrationCommand> plan = List.of(
                command(MigrationCommand.Operation.COPY, "a.png"),
                command(MigrationCommand.Operation.REWRITE_REFERENCES, "a.mtl"),
                command(MigrationCommand.Operation.CONVERT_TEXTURE, "b.jpg"),
                command(MigrationCommand.Operation.CONVERT_MODEL, "c.glb"));
        List<String> details = new ArrayList<>();
        for (MigrationCommand command : plan) details.add(command.execute(receiver));
        assertEquals(List.of("copy:a.png", "rewrite:a.mtl", "convert:b.jpg", "model:c.glb"), calls);
        assertEquals(List.of("copied", "rewritten", "converted", "model"), details);
        assertThrows(NullPointerException.class, () -> plan.getFirst().execute(null));
    }

    private static MigrationCommand command(MigrationCommand.Operation operation, String path) {
        return new AssetOperationCommand(UUID.randomUUID(), UUID.randomUUID(), operation, Path.of(path), Map.of(), List.of(), Optional.empty());
    }
}
