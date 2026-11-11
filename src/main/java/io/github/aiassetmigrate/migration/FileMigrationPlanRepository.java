package io.github.aiassetmigrate.migration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import io.github.aiassetmigrate.inventory.FileReviewedChangeRepository;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Immutable plan, approval and run records; locks coordinate cooperating project writers. */
public final class FileMigrationPlanRepository {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path state;
    private final FileAssetRepository inventories;
    private final FileReviewedChangeRepository reviews;
    private final ObjectMapper mapper;

    @JsonDeserialize(as = AssetOperationCommand.class)
    private interface CommandMixin { }

    public record StoredPlan(int schemaVersion, MigrationPlan plan, long reviewRevision) {
        public StoredPlan {
            Objects.requireNonNull(plan, "plan");
            if (schemaVersion != 1 || reviewRevision < 0) throw new IllegalArgumentException("Invalid stored plan");
        }
    }

    public record ExecutionResult(RunStatus status, Map<UUID, StepStatus> steps,
                                  Optional<String> message, Map<UUID, String> stepDetails) {
        public ExecutionResult(RunStatus status, Map<UUID, StepStatus> steps) {
            this(status, steps, Optional.empty(), Map.of());
        }
        public ExecutionResult {
            Objects.requireNonNull(status, "status");
            steps = Map.copyOf(steps);
            Objects.requireNonNull(message, "message");
            stepDetails = Map.copyOf(stepDetails);
            if (!steps.keySet().containsAll(stepDetails.keySet()))
                throw new IllegalArgumentException("Step details must refer to run steps");
        }
    }

    @FunctionalInterface
    public interface Execution {
        ExecutionResult run(MigrationPlan plan, InventorySnapshot inventory, MigrationRun reservation) throws IOException;
    }

    /** Must reject unsafe commands, blockers and any mismatch with the trusted reconstructed plan. */
    @FunctionalInterface
    public interface PlanValidation {
        void validate(MigrationPlan plan, InventorySnapshot inventory) throws IOException;
    }

    public FileMigrationPlanRepository(Path stateDirectory) throws IOException {
        SafeStoragePaths.validateExistingPath(stateDirectory);
        state = stateDirectory.toAbsolutePath().normalize();
        inventories = new FileAssetRepository(state);
        reviews = new FileReviewedChangeRepository(state);
        SimpleModule paths = new SimpleModule();
        paths.addSerializer(Path.class, new JsonSerializer<>() {
            @Override public void serialize(Path value, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeString(value.isAbsolute() ? value.toUri().toString() : value.toString().replace('\\', '/'));
            }
        });
        paths.addDeserializer(Path.class, new JsonDeserializer<>() {
            @Override public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) throw new IOException("Path must be a string");
                try {
                    String text = parser.getText();
                    return text.startsWith("file:") ? Path.of(java.net.URI.create(text)) : Path.of(text);
                } catch (RuntimeException ex) { throw new IOException("Invalid path", ex); }
            }
        });
        mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule())
                .registerModule(paths).addMixIn(MigrationCommand.class, CommandMixin.class)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    }

    /** Claims the supplied ID once globally; failed claims are deliberately never reused. */
    public MigrationPlan createDraft(MigrationPlan plan, long expectedReviewRevision) throws IOException {
        Objects.requireNonNull(plan, "plan");
        if (plan.status() != PlanStatus.DRAFT || plan.version() != 1 || plan.inventoryRevision() < 1 || expectedReviewRevision < 0)
            throw new IllegalArgumentException("Expected a version-one draft and valid revisions");
        InventorySnapshot inventory = currentInventory(plan.projectId());
        try (var ignored = inventories.beginWrite(plan.projectId(), inventory.project().sourceRoot())) {
            validateCurrent(new StoredPlan(1, plan, expectedReviewRevision));
            Path root = state.resolve("plans");
            SafeStoragePaths.validateExistingPath(root);
            try { Files.createDirectory(root); } catch (FileAlreadyExistsException ignoredExists) { }
            SafeStoragePaths.validateExistingPath(root);
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw new IOException("Plans root is not a directory");
            syncDirectory(state);
            Path directory = directory(plan.id());
            Files.createDirectory(directory);
            syncDirectory(root);
            writeNew(directory.resolve("draft.json"), new StoredPlan(1, plan, expectedReviewRevision));
            return plan;
        }
    }

    public StoredPlan load(UUID planId) throws IOException {
        StoredPlan stored = read(directory(planId).resolve("draft.json"), StoredPlan.class);
        if (!stored.plan().id().equals(planId) || stored.plan().status() != PlanStatus.DRAFT
                || stored.plan().version() != 1 || stored.plan().inventoryRevision() < 1)
            throw new IOException("Invalid plan identity or state");
        Path approval = directory(planId).resolve("approved.json");
        if (exists(approval)) {
            MigrationPlan approved = read(approval, MigrationPlan.class);
            if (!approved.equals(withStatus(stored.plan(), PlanStatus.APPROVED))) throw new IOException("Approval does not match draft");
            return new StoredPlan(1, approved, stored.reviewRevision());
        }
        return stored;
    }

    public MigrationPlan approve(UUID planId, int expectedVersion, PlanValidation validation) throws IOException {
        Objects.requireNonNull(validation, "validation");
        StoredPlan before = load(planId);
        InventorySnapshot inventory = currentInventory(before.plan().projectId());
        try (var ignored = inventories.beginWrite(before.plan().projectId(), inventory.project().sourceRoot())) {
            StoredPlan current = load(planId);
            requireVersion(current, expectedVersion, PlanStatus.DRAFT);
            validateCurrent(current);
            validation.validate(current.plan(), currentInventory(current.plan().projectId()));
            MigrationPlan approved = withStatus(current.plan(), PlanStatus.APPROVED);
            writeNew(directory(planId).resolve("approved.json"), approved);
            return approved;
        }
    }

    /** Validates before consuming approval and holds the project lock throughout execution. */
    public MigrationRun execute(UUID planId, int expectedVersion, PlanValidation validation, Execution execution) throws IOException {
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(execution, "execution");
        StoredPlan before = load(planId);
        InventorySnapshot inventory = currentInventory(before.plan().projectId());
        try (var ignored = inventories.beginWrite(before.plan().projectId(), inventory.project().sourceRoot())) {
            StoredPlan current = load(planId);
            requireVersion(current, expectedVersion, PlanStatus.APPROVED);
            validateCurrent(current);
            InventorySnapshot lockedInventory = currentInventory(current.plan().projectId());
            validation.validate(current.plan(), lockedInventory);
            MigrationRun run = startLocked(planId, expectedVersion);
            ExecutionResult result = Objects.requireNonNull(execution.run(current.plan(), lockedInventory, run), "execution result");
            return finishLocked(planId, run.id(), result);
        }
    }

    private MigrationRun startLocked(UUID planId, int expectedVersion) throws IOException {
        StoredPlan current = load(planId);
        requireVersion(current, expectedVersion, PlanStatus.APPROVED);
        validateCurrent(current);
        Path start = directory(planId).resolve("started.json");
        if (exists(start)) throw new IOException("Plan already consumed");
        Instant now = Instant.now();
        Map<UUID, StepStatus> steps = new LinkedHashMap<>();
        for (MigrationCommand command : current.plan().commands()) {
            if (steps.put(command.id(), StepStatus.PENDING) != null) throw new IOException("Duplicate command ID");
        }
        MigrationRun run = new MigrationRun(UUID.randomUUID(), current.plan().projectId(), planId, expectedVersion,
                current.plan().outputRoot(), RunStatus.RUNNING, steps, now, Optional.of(now), Optional.empty());
        writeNew(start, run);
        return run;
    }

    /** An uncompleted start is reported as INCOMPLETE, including while a worker may still be running. */
    public MigrationRun loadRun(UUID planId) throws IOException {
        StoredPlan plan = load(planId);
        MigrationRun started = read(directory(planId).resolve("started.json"), MigrationRun.class);
        validateStart(plan, started);
        Path completed = directory(planId).resolve("completed.json");
        if (exists(completed)) {
            MigrationRun result = read(completed, MigrationRun.class);
            validateRun(plan, result);
            if (!result.id().equals(started.id()) || result.startedAt().isEmpty() || result.completedAt().isEmpty()
                    || !result.startedAt().equals(started.startedAt()) || !result.createdAt().equals(started.createdAt())
                    || !terminal(result.status()) || !result.stepStatuses().keySet().equals(started.stepStatuses().keySet())
                    || (result.status() == RunStatus.SUCCESS && result.stepStatuses().values().stream().anyMatch(s -> s != StepStatus.SUCCESS)))
                throw new IOException("Invalid completed run");
            return result;
        }
        return new MigrationRun(started.id(), started.projectId(), started.planId(), started.planVersion(),
                started.outputRoot(), RunStatus.INCOMPLETE, started.stepStatuses(), started.createdAt(), started.startedAt(), Optional.empty());
    }

    /** A run together with the approved plan it executed. */
    public record FoundRun(StoredPlan plan, MigrationRun run) {
        public FoundRun {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(run, "run");
        }
    }

    /**
     * Finds a run by its ID across all plans. Plan directories that cannot be read are counted
     * and reported if the run is not found, never silently treated as "no such run".
     */
    public Optional<FoundRun> findRun(UUID runId) throws IOException {
        Objects.requireNonNull(runId, "runId");
        Path root = state.resolve("plans");
        SafeStoragePaths.validateExistingPath(root);
        if (!exists(root)) return Optional.empty();
        int unreadable = 0;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                UUID planId;
                try { planId = UUID.fromString(entry.getFileName().toString()); }
                catch (IllegalArgumentException notAPlan) { continue; }
                if (!planId.toString().equals(entry.getFileName().toString())) continue;
                if (!exists(entry.resolve("started.json"))) continue;
                try {
                    MigrationRun run = loadRun(planId);
                    if (run.id().equals(runId)) return Optional.of(new FoundRun(load(planId), run));
                } catch (IOException | RuntimeException e) {
                    unreadable++;
                }
            }
        }
        if (unreadable > 0) throw new IOException("Run " + runId + " was not found; " + unreadable + " run record(s) could not be read");
        return Optional.empty();
    }

    private MigrationRun finishLocked(UUID planId, UUID runId, ExecutionResult execution) throws IOException {
        RunStatus status = execution.status();
        Map<UUID, StepStatus> steps = execution.steps();
        if (!terminal(status)) throw new IllegalArgumentException("A terminal run status is required");
        StoredPlan plan = load(planId);
        MigrationRun start = read(directory(planId).resolve("started.json"), MigrationRun.class);
        validateStart(plan, start);
        if (!start.id().equals(runId) || !steps.keySet().equals(start.stepStatuses().keySet())) throw new IOException("Run identity or steps conflict");
        if (status == RunStatus.SUCCESS && steps.values().stream().anyMatch(s -> s != StepStatus.SUCCESS))
            throw new IOException("Successful run requires successful steps");
        MigrationRun result = new MigrationRun(runId, start.projectId(), planId, start.planVersion(), start.outputRoot(),
                status, steps, start.createdAt(), start.startedAt(), Optional.of(Instant.now()),
                execution.message(), execution.stepDetails());
        writeNew(directory(planId).resolve("completed.json"), result);
        return result;
    }

    private void validateRun(StoredPlan plan, MigrationRun run) throws IOException {
        if (plan.plan().status() != PlanStatus.APPROVED || !run.planId().equals(plan.plan().id())
                || !run.projectId().equals(plan.plan().projectId()) || run.planVersion() != plan.plan().version()
                || !run.outputRoot().equals(plan.plan().outputRoot())) throw new IOException("Run does not match approved plan");
    }

    private void validateStart(StoredPlan plan, MigrationRun run) throws IOException {
        validateRun(plan, run);
        Set<UUID> commands = new HashSet<>();
        for (MigrationCommand command : plan.plan().commands()) {
            if (!commands.add(command.id())) throw new IOException("Duplicate command ID");
        }
        if (run.status() != RunStatus.RUNNING || run.startedAt().isEmpty() || run.completedAt().isPresent()
                || !run.stepStatuses().keySet().equals(commands)
                || run.message().isPresent() || !run.stepDetails().isEmpty()
                || run.stepStatuses().values().stream().anyMatch(status -> status != StepStatus.PENDING))
            throw new IOException("Invalid run reservation");
    }

    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCESS || status == RunStatus.PARTIAL || status == RunStatus.FAILED
                || status == RunStatus.INCOMPLETE || status == RunStatus.CANCELLED;
    }

    private void validateCurrent(StoredPlan stored) throws IOException {
        MigrationPlan plan = stored.plan();
        InventorySnapshot inventory = currentInventory(plan.projectId());
        if (inventory.project().inventoryRevision() != plan.inventoryRevision()) throw new IOException("Inventory revision conflict");
        var review = reviews.loadLatest(plan.projectId());
        long revision = review.map(FileReviewedChangeRepository.ReviewSet::revision).orElse(0L);
        if (revision != stored.reviewRevision() || (review.isPresent() && review.get().inventoryRevision() != plan.inventoryRevision())
                || !plan.reviewedChanges().equals(review.map(FileReviewedChangeRepository.ReviewSet::changes).orElse(List.of())))
            throw new IOException("Reviewed changes revision conflict");
    }

    private InventorySnapshot currentInventory(UUID project) throws IOException {
        return inventories.loadLatest(project).orElseThrow(() -> new IOException("Inventory not found"));
    }

    private static void requireVersion(StoredPlan stored, int version, PlanStatus status) throws IOException {
        if (version < 1 || stored.plan().version() != version || stored.plan().status() != status)
            throw new IOException("Plan version or status conflict");
    }

    private static MigrationPlan withStatus(MigrationPlan p, PlanStatus status) {
        return new MigrationPlan(p.id(), p.projectId(), p.version(), p.inventoryRevision(), p.targetProfile(),
                p.targetProfileVersion(), p.outputRoot(), p.reviewedChanges(), p.commands(), p.findings(), status, p.createdAt());
    }

    private Path directory(UUID id) { return state.resolve("plans").resolve(Objects.requireNonNull(id, "planId").toString()); }

    private void writeNew(Path target, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) throw new IOException("Record exceeds byte limit");
        SafeStoragePaths.validateExistingPath(target.getParent());
        if (exists(target)) throw new IOException("Record already exists: " + target);
        Path temp = target.resolveSibling(".tmp-" + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                channel.write(buffer);
            }
            channel.force(true);
        }
        SafeStoragePaths.requireRegularFile(temp);
        SafeStoragePaths.validateExistingPath(target.getParent());
        if (exists(target)) throw new IOException("Record already exists: " + target);
        // Hard-link creation is atomic and fails if the name already exists, unlike ATOMIC_MOVE.
        Files.createLink(target, temp);
        syncDirectory(target.getParent());
        Files.delete(temp);
        syncDirectory(target.getParent());
    }

    private static void syncDirectory(Path directory) throws IOException {
        SafeStoragePaths.syncDirectory(directory);
    }

    private <T> T read(Path file, Class<T> type) throws IOException {
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() > MAX_BYTES) throw new IOException("Record exceeds byte limit");
        byte[] bytes = new byte[(int) before.size()];
        try (FileChannel channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Record changed while reading");
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Record grew while reading");
        }
        SafeStoragePaths.requireRegularFile(file);
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Record changed while reading");
        try {
            // Older run records predate diagnostics; all other record types retain strict creator checks.
            if (type == MigrationRun.class) {
                JsonNode node = mapper.readTree(bytes);
                if (node == null || !node.isObject()) throw new IOException("Invalid run record");
                for (String field : List.of("id", "projectId", "planId", "planVersion", "outputRoot", "status",
                        "stepStatuses", "createdAt", "startedAt", "completedAt")) {
                    if (!node.has(field)) throw new IOException("Missing run field: " + field);
                }
                return mapper.readerFor(type)
                        .without(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                        .without(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).readValue(bytes);
            }
            return mapper.readValue(bytes, type);
        }
        catch (RuntimeException ex) { throw new IOException("Invalid record: " + file, ex); }
    }

    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException ex) { return false; }
    }
}
