package io.github.aiassetmigrate.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Command pattern: an immutable, reviewable migration operation that knows which receiver
 * action performs it. A {@link MigrationPlan} stores commands in order; the migration
 * executor is the invoker and supplies the {@link Receiver} that performs the file work.
 * Holding a command grants no authority: the invoker must verify the approved plan first.
 */
public sealed interface MigrationCommand permits AssetOperationCommand {
    enum Operation { COPY, CONVERT_TEXTURE, REWRITE_REFERENCES }

    UUID id();
    UUID assetId();
    Operation operation();
    Path relativeOutputPath();
    Map<String, String> parameters();
    List<UUID> dependencies();
    Optional<String> converterRequirement();

    /** Performs this command through the receiver and returns a step detail for the run record. */
    String execute(Receiver receiver) throws IOException;

    /** Command receiver: performs the file operation for one already-validated command. */
    interface Receiver {
        String copy(MigrationCommand command) throws IOException;
        String rewriteReferences(MigrationCommand command) throws IOException;
        String convertTexture(MigrationCommand command) throws IOException;
    }
}
