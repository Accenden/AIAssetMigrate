package io.github.aiassetmigrate.inventory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Report;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/**
 * Append-only validation reports under {@code <state>/projects/<project>/reports}. A report is
 * written once and never replaced; writers hold the inventory repository's project lock.
 */
public final class FileReportRepository {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    /** History refuses to load more than this many reports rather than silently truncating. */
    public static final int MAX_REPORTS = 10_000;
    private static final Pattern NAME = Pattern.compile("report-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");
    private final Path stateDirectory;
    private final ObjectMapper mapper;

    public FileReportRepository(Path stateDirectory) throws IOException {
        SafeStoragePaths.validateExistingPath(stateDirectory);
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        SimpleModule paths = new SimpleModule();
        paths.addSerializer(Path.class, new JsonSerializer<>() {
            @Override public void serialize(Path value, JsonGenerator out, SerializerProvider provider) throws IOException {
                if (value.isAbsolute()) throw new IOException("Reports store relative paths only");
                out.writeString(PortablePath.of(value));
            }
        });
        paths.addDeserializer(Path.class, new JsonDeserializer<>() {
            @Override public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) throw new IOException("Path must be a string");
                try {
                    Path path = Path.of(parser.getText());
                    if (path.isAbsolute()) throw new IOException("Report paths must be relative");
                    return path;
                } catch (RuntimeException e) { throw new IOException("Invalid path", e); }
            }
        });
        mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()).registerModule(paths)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    /** Writes a new report; fails if one with the same ID exists. The caller holds the project lock. */
    public Report save(Report report) throws IOException {
        Objects.requireNonNull(report, "report");
        byte[] bytes = mapper.writeValueAsBytes(report);
        if (bytes.length > MAX_BYTES) throw new IOException("Report exceeds byte limit");
        Path directory = directory(report.projectId());
        SafeStoragePaths.createDirectories(directory);
        Path target = directory.resolve(name(report.id()));
        if (exists(target)) throw new IOException("Report already exists: " + report.id());
        Path temporary = directory.resolve(".report-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    checkInterrupted();
                    channel.write(buffer);
                }
                channel.force(true);
            }
            SafeStoragePaths.requireRegularFile(temporary);
            SafeStoragePaths.validateExistingPath(directory);
            // Hard-link creation fails if the name exists, so a saved report is never replaced.
            Files.createLink(target, temporary);
            SafeStoragePaths.syncDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
        SafeStoragePaths.syncDirectory(directory);
        return report;
    }

    /** All saved reports for the project, oldest first. An empty list means no validation history. */
    public List<Report> list(UUID projectId) throws IOException {
        Path directory = directory(projectId);
        SafeStoragePaths.validateExistingPath(directory);
        if (!exists(directory)) return List.of();
        List<Report> reports = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                checkInterrupted();
                String filename = entry.getFileName().toString();
                if (filename.startsWith(".report-")) continue;
                var match = NAME.matcher(filename);
                if (!match.matches()) throw new IOException("Unexpected file in report history: " + filename);
                if (reports.size() >= MAX_REPORTS) throw new IOException("Report history exceeds " + MAX_REPORTS + " reports");
                Report report = read(entry);
                if (!report.projectId().equals(projectId) || !report.id().toString().equals(match.group(1))) {
                    throw new IOException("Report identity does not match its location: " + filename);
                }
                reports.add(report);
            }
        }
        reports.sort(Comparator.comparing(Report::createdAt).thenComparing(Report::id));
        return List.copyOf(reports);
    }

    private Report read(Path file) throws IOException {
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() > MAX_BYTES) throw new IOException("Report exceeds byte limit: " + file.getFileName());
        byte[] bytes = new byte[(int) before.size()];
        try (FileChannel channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Report changed while reading");
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Report grew while reading");
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Report changed while reading");
        try {
            return mapper.readValue(bytes, Report.class);
        } catch (RuntimeException | IOException e) {
            throw new IOException("Invalid report: " + file.getFileName(), e);
        }
    }

    private Path directory(UUID projectId) {
        return stateDirectory.resolve("projects").resolve(Objects.requireNonNull(projectId, "projectId").toString()).resolve("reports");
    }

    private static String name(UUID id) { return "report-" + id + ".json"; }

    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException e) { return false; }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Report operation interrupted");
    }
}
