package io.github.aiassetmigrate.domain;

import java.nio.file.Path;
import java.util.Objects;

/** Coverage of an entry, not format validity. Empty path identifies the source root. */
public record PathOutcome(Path relativePath, Kind kind, Status status) {
    public enum Kind { FILE, DIRECTORY }
    public enum Status { INVENTORIED, TRAVERSED, SKIPPED, FAILED, UNVISITED }

    public PathOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        InventoryPath.requireRelative(relativePath, kind == Kind.DIRECTORY);
        if ((status == Status.INVENTORIED && kind != Kind.FILE)
                || (status == Status.TRAVERSED && kind != Kind.DIRECTORY)) {
            throw new IllegalArgumentException("Outcome status does not match entry kind");
        }
    }
}
