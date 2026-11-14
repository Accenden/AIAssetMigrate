package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Coverage of an entry, not format validity. Empty path identifies the source root. EXCLUDED is a file the
 * user chose to leave out of the project: it is accounted for (complete coverage) but never read,
 * inventoried or migrated, and a reference to it is treated as skipped.
 */
public record PathOutcome(Path relativePath, Kind kind, Status status) {
    public enum Kind { FILE, DIRECTORY }
    public enum Status { INVENTORIED, TRAVERSED, SKIPPED, FAILED, UNVISITED, EXCLUDED }

    public PathOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        InventoryPath.requireRelative(relativePath, kind == Kind.DIRECTORY);
        if (((status == Status.INVENTORIED || status == Status.EXCLUDED) && kind != Kind.FILE)
                || (status == Status.TRAVERSED && kind != Kind.DIRECTORY)) {
            throw new IllegalArgumentException("Outcome status does not match entry kind");
        }
    }
}
