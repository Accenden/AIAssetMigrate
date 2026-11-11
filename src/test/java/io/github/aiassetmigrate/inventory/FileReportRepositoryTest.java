package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileReportRepositoryTest {
    @TempDir Path state;
    private final UUID project = UUID.randomUUID();

    private Report report(Instant at, Optional<String> ai) {
        return new Report(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(), RunStatus.PARTIAL,
                Report.ValidationStatus.INCOMPLETE, List.of(
                        new Finding(UUID.randomUUID(), Optional.of(UUID.randomUUID()), Optional.of(Path.of("models", "hero.obj")),
                                Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", "not checked"),
                        new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.WARNING, "RUN", "run")),
                "summary", ai, at);
    }

    @Test void reportsRoundTripOldestFirstAndAreNeverReplaced() throws Exception {
        var repository = new FileReportRepository(state);
        assertEquals(List.of(), repository.list(project), "no history is an empty list");
        assertFalse(Files.exists(state.resolve("projects")), "reads create no state");
        Report later = report(Instant.parse("2026-02-01T00:00:00Z"), Optional.of("advisory"));
        Report earlier = report(Instant.parse("2026-01-01T00:00:00Z"), Optional.empty());
        repository.save(later);
        repository.save(earlier);
        assertEquals(List.of(earlier, later), repository.list(project));
        assertEquals(List.of(earlier, later), new FileReportRepository(state).list(project));
        assertThrows(IOException.class, () -> repository.save(later));
        assertEquals(List.of(), repository.list(UUID.randomUUID()));
    }

    @Test void unexpectedOrTamperedFilesAreErrorsNotSilentlyIgnored() throws Exception {
        var repository = new FileReportRepository(state);
        Report saved = repository.save(report(Instant.now(), Optional.empty()));
        Path directory = state.resolve("projects").resolve(project.toString()).resolve("reports");
        Path file = directory.resolve("report-" + saved.id() + ".json");

        Files.writeString(directory.resolve("notes.txt"), "x");
        assertThrows(IOException.class, () -> repository.list(project));
        Files.delete(directory.resolve("notes.txt"));

        String json = Files.readString(file);
        Files.delete(file);
        Path moved = directory.resolve("report-" + UUID.randomUUID() + ".json");
        Files.writeString(moved, json);
        assertThrows(IOException.class, () -> repository.list(project), "identity must match the filename");
        Files.writeString(moved, "{\"id\":");
        assertThrows(IOException.class, () -> repository.list(project));
    }
}
