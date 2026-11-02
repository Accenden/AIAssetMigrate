package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.inventory.AssetScanner;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;
import io.github.aiassetmigrate.logging.LogPublisher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** File-backed F01 facade. Watched publishers belong to this manager until it closes. */
public final class DefaultAssetManager implements AssetManager, AutoCloseable {
    private static final long MAX_EXISTING_LOG_BYTES = 64L * 1024 * 1024;
    private final Path stateDirectory;
    private final Path logDirectory;
    private final FileAssetRepository repository;
    private final AssetScanner scanner;
    private final PublisherFactory publisherFactory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, ManagedPublisher> publishers = new HashMap<>();

    private static final class ManagedPublisher {
        final Publisher publisher;
        int writers;
        boolean watched;

        ManagedPublisher(Publisher publisher) { this.publisher = publisher; }
    }

    @FunctionalInterface
    interface PublisherFactory {
        Publisher open(Path directory, UUID projectId) throws IOException;
    }

    interface Publisher extends AutoCloseable {
        void publish(LogEvent.Level level, String message) throws IOException;
        default LogSubscription subscribe(long afterSequence, LogObserver observer) throws IOException {
            throw new UnsupportedOperationException("Publisher does not support subscriptions");
        }
        @Override void close() throws IOException;
    }

    public DefaultAssetManager(Path stateDirectory, Path logDirectory) throws IOException {
        this(stateDirectory, logDirectory, new AssetScanner(), (directory, id) -> {
            LogPublisher publisher = new LogPublisher(directory, id, true);
            return new Publisher() {
                public void publish(LogEvent.Level level, String message) throws IOException {
                    publisher.publish(level, message);
                }
                public LogSubscription subscribe(long afterSequence, LogObserver observer) throws IOException {
                    return publisher.subscribe(afterSequence, observer, observer::onError);
                }
                public void close() throws IOException { publisher.close(); }
            };
        });
    }

    DefaultAssetManager(Path stateDirectory, Path logDirectory, AssetScanner scanner,
                        PublisherFactory publisherFactory) throws IOException {
        SafeStoragePaths.validateExistingPath(stateDirectory);
        SafeStoragePaths.validateExistingPath(logDirectory);
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        this.logDirectory = logDirectory.toAbsolutePath().normalize();
        if (overlap(this.stateDirectory, this.logDirectory)) {
            throw new IOException("State and log directories must be separate");
        }
        this.repository = new FileAssetRepository(stateDirectory);
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.publisherFactory = Objects.requireNonNull(publisherFactory, "publisherFactory");
    }

    @Override
    public ImportResult importAssets(Path sourceRoot) {
        return scanAndSave(UUID.randomUUID(), sourceRoot, false);
    }

    @Override
    public ImportResult rescan(UUID projectId, Path sourceRoot) {
        return scanAndSave(Objects.requireNonNull(projectId, "projectId"), sourceRoot, true);
    }

    private ImportResult scanAndSave(UUID id, Path sourceRoot, boolean rescan) {
        ensureOpen();
        Long committed = null;
        try {
            validatePaths(sourceRoot, id);
            try (var writer = repository.beginWrite(id, sourceRoot)) {
                Optional<InventorySnapshot> previous = repository.loadLatest(id);
                if (rescan && previous.isEmpty()) throw new IOException("Project not found: " + id);
                if (!rescan && previous.isPresent()) throw new IOException("Project already exists: " + id);
                if (previous.isPresent() && !previous.get().project().sourceRoot().equals(sourceRoot.toAbsolutePath().normalize())) {
                    throw new IOException("Source root does not match the project");
                }
                validatePaths(sourceRoot, id);
                try (Publisher publisher = acquireWriter(id)) {
                    try {
                        publisher.publish(LogEvent.Level.INFO, "Scan started");
                        Instant started = Instant.now();
                        AssetScanner.ScanResult scan = scanner.scan(sourceRoot);
                        publisher.publish(LogEvent.Level.INFO, "Scan completed: " + scan.assets().size()
                                + " assets, " + scan.findings().size() + " findings, status " + scan.scanStatus());
                        Map<Path, UUID> oldIds = new HashMap<>();
                        Set<Path> unresolved = new HashSet<>();
                        scan.assets().forEach(asset -> unresolved.add(asset.relativePath()));
                        InventorySnapshot history = previous.orElse(null);
                        while (history != null && !unresolved.isEmpty()) {
                            Map<Path, UUID> historicalIds = new HashMap<>();
                            history.project().assets().forEach(asset -> historicalIds.put(asset.relativePath(), asset.id()));
                            Map<Path, PathOutcome> outcomes = new HashMap<>();
                            history.pathOutcomes().forEach(outcome -> outcomes.put(outcome.relativePath(), outcome));
                            // Directory statuses also include failures in descendants; require local evidence.
                            Set<Path> incompleteDirectories = new HashSet<>();
                            history.findings().forEach(finding -> {
                                if (Set.of("DIRECTORY_READ_FAILED", "DEPTH_LIMIT", "ENTRY_LIMIT",
                                        "CASE_COLLISION").contains(finding.code())
                                        || (finding.code().equals("UNSAFE_PATH") && finding.message().equals(
                                            "Entry cannot be safely classified or accessed"))) {
                                    finding.sourceRelativePath().ifPresent(incompleteDirectories::add);
                                }
                            });
                            for (var iterator = unresolved.iterator(); iterator.hasNext();) {
                                Path path = iterator.next();
                                UUID historicalId = historicalIds.get(path);
                                if (historicalId != null) {
                                    oldIds.put(path, historicalId);
                                    iterator.remove();
                                    continue;
                                }
                                Path current = path;
                                PathOutcome outcome = null;
                                while (current != null) {
                                    outcome = outcomes.get(current);
                                    if (outcome != null) break;
                                    current = current.getParent();
                                }
                                if (outcome == null) {
                                    current = Path.of("");
                                    outcome = outcomes.get(current);
                                }
                                boolean incomplete = outcome != null
                                        && (outcome.status() == PathOutcome.Status.FAILED
                                            || outcome.status() == PathOutcome.Status.UNVISITED)
                                        && (current.equals(path) || (outcome.kind() == PathOutcome.Kind.DIRECTORY
                                            && incompleteDirectories.contains(current)));
                                if (!incomplete) iterator.remove();
                            }
                            long revision = history.project().inventoryRevision();
                            history = revision > 1 && !unresolved.isEmpty()
                                    ? repository.loadRevision(id, revision - 1) : null;
                        }
                        Map<UUID, UUID> idMap = new HashMap<>();
                        List<Asset> assets = scan.assets().stream().map(asset -> {
                            UUID stable = oldIds.getOrDefault(asset.relativePath(), asset.id());
                            idMap.put(asset.id(), stable);
                            return new Asset(stable, asset.relativePath(), asset.type(), asset.fingerprint(), asset.metadata());
                        }).toList();
                        List<Finding> findings = scan.findings().stream().map(finding -> new Finding(
                                finding.id(), finding.assetId().map(assetId -> idMap.getOrDefault(assetId, assetId)),
                                finding.sourceRelativePath(), finding.severity(), finding.code(), finding.message())).toList();
                        long revision = previous.isPresent() ? Math.incrementExact(previous.get().project().inventoryRevision()) : 1;
                        Instant created = previous.map(value -> value.project().createdAt()).orElse(started);
                        Instant scannedAt = Instant.now();
                        if (scannedAt.isBefore(created)) scannedAt = created;
                        var project = new Project(id, scan.sourceRoot(), revision, assets, created);
                        var snapshot = new InventorySnapshot(InventorySnapshot.CURRENT_SCHEMA_VERSION,
                                InventorySnapshot.CURRENT_INSPECTION_VERSION, project, findings,
                                scan.scanStatus(), scannedAt, scan.pathOutcomes(), List.of());
                        publisher.publish(LogEvent.Level.INFO, "Saving inventory revision " + revision);
                        validatePaths(sourceRoot, id, true);
                        writer.saveNextRevision(snapshot);
                        committed = revision;
                        publisher.publish(LogEvent.Level.INFO, "Inventory revision " + revision + " committed");
                        return new ImportResult(snapshot);
                    } catch (IOException | RuntimeException failure) {
                        try {
                            publisher.publish(LogEvent.Level.ERROR, "Import failed" +
                                    (committed == null ? " before commit" : " after committing revision " + committed));
                        } catch (IOException | RuntimeException logFailure) {
                            failure.addSuppressed(logFailure);
                        }
                        throw failure;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw new ImportException("Import failed for project " + id
                    + (committed == null ? "" : " after committing revision " + committed), failure,
                    Optional.of(id), Optional.ofNullable(committed));
        }
    }

    private void validatePaths(Path sourceRoot, UUID id) throws IOException {
        validatePaths(sourceRoot, id, false);
    }

    private void validatePaths(Path sourceRoot, UUID id, boolean publisherOpen) throws IOException {
        SafeStoragePaths.validateSeparation(sourceRoot, stateDirectory);
        SafeStoragePaths.validateSeparation(sourceRoot, logDirectory);
        SafeStoragePaths.validateExistingPath(stateDirectory);
        SafeStoragePaths.validateExistingPath(logDirectory);
        if (overlap(stateDirectory, logDirectory)) throw new IOException("State and log directories must be separate");
        validateLogFile(LogPublisher.logFile(logDirectory, id), publisherOpen);
        validateLogFile(logDirectory.resolve(id + ".lock"), publisherOpen);
        validateLogFile(logDirectory.resolve(id + ".gate"), publisherOpen);
        validateLogFile(logDirectory.resolve(id + ".commit"), publisherOpen);
    }

    private static void validateLogFile(Path path, boolean publisherOpen) throws IOException {
        SafeStoragePaths.validateExistingPath(path);
        try {
            SafeStoragePaths.requireRegularFile(path);
            if (Files.size(path) > MAX_EXISTING_LOG_BYTES) throw new IOException("Log file is too large: " + path);
            validateLogLinkCount(path, Files.getFileStore(path).supportsFileAttributeView("unix"), publisherOpen);
        } catch (NoSuchFileException missing) {
            // The publisher creates missing files after all paths have been validated.
        }
    }

    static void validateLogLinkCount(Path path, boolean unixAvailable, boolean publisherOpen) throws IOException {
        if (!unixAvailable) {
            // The publisher must safely create its files before these pre-commit checks.
            if (!publisherOpen) throw new IOException("Cannot safely reuse log file without link count: " + path);
            return;
        }
        Object count;
        try {
            count = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            throw new IOException("Cannot verify log file link count: " + path, e);
        }
        if (!(count instanceof Number number) || number.longValue() != 1) {
            throw new IOException("Log file must have exactly one link: " + path);
        }
    }

    private static boolean overlap(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private synchronized Publisher acquireWriter(UUID id) throws IOException {
        ensureOpen();
        ManagedPublisher entry = publishers.get(id);
        if (entry == null) {
            entry = new ManagedPublisher(publisherFactory.open(logDirectory, id));
            publishers.put(id, entry);
        }
        entry.writers++;
        ManagedPublisher owned = entry;
        return new Publisher() {
            @Override public void publish(LogEvent.Level level, String message) throws IOException {
                owned.publisher.publish(level, message);
            }
            @Override public void close() throws IOException { releaseWriter(id, owned); }
        };
    }

    private synchronized void releaseWriter(UUID id, ManagedPublisher entry) throws IOException {
        if (--entry.writers == 0 && (!entry.watched || closed.get())) {
            publishers.remove(id, entry);
            entry.publisher.close();
        }
    }

    @Override public InventorySnapshot loadInventory(UUID projectId) {
        ensureOpen();
        try {
            return repository.loadLatest(projectId).orElseThrow(() -> new IOException("Project not found: " + projectId));
        } catch (IOException e) { throw new ImportException("Cannot load project " + projectId, e); }
    }

    @Override public InventorySnapshot loadInventory(UUID projectId, long revision) {
        ensureOpen();
        try { return repository.loadRevision(projectId, revision); }
        catch (IOException e) { throw new ImportException("Cannot load project " + projectId + " revision " + revision, e); }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Asset manager is closed");
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
        for (var iterator = publishers.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next().getValue();
            if (entry.writers != 0) continue;
            iterator.remove();
            try { entry.publisher.close(); }
            catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw new UncheckedIOException("Cannot close project publisher", failure);
    }
    @Override public AnalysisResult analyze(UUID id, AnalysisKind kind) { throw new UnsupportedOperationException(); }
    @Override public List<ReviewedChange> review(UUID id, List<ReviewedChange> changes) { throw new UnsupportedOperationException(); }
    @Override public List<Finding> checkCompatibility(UUID id, TargetProfile target) { throw new UnsupportedOperationException(); }
    @Override public MigrationPlan createPlan(UUID id, TargetProfile target, Path output) { throw new UnsupportedOperationException(); }
    @Override public MigrationPlan approve(UUID id, int version) { throw new UnsupportedOperationException(); }
    @Override public MigrationRun migrate(UUID id) { throw new UnsupportedOperationException(); }
    @Override public Report validate(UUID id) { throw new UnsupportedOperationException(); }
    @Override public List<Report> history(UUID id) { throw new UnsupportedOperationException(); }
    @Override public CommandResult command(UUID id, String request) { throw new UnsupportedOperationException(); }
    @Override public synchronized LogSubscription watch(UUID id, long sequence, LogObserver observer) {
        ensureOpen();
        Objects.requireNonNull(id, "projectId");
        Objects.requireNonNull(observer, "observer");
        if (sequence < 0) throw new IllegalArgumentException("afterSequence must be nonnegative");
        ManagedPublisher entry = publishers.get(id);
        boolean created = false;
        try {
            if (entry == null) {
                var snapshot = repository.loadLatest(id)
                        .orElseThrow(() -> new IOException("Project not found: " + id));
                validatePaths(snapshot.project().sourceRoot(), id);
                SafeStoragePaths.requireRegularFile(LogPublisher.logFile(logDirectory, id));
                entry = new ManagedPublisher(publisherFactory.open(logDirectory, id));
                publishers.put(id, entry);
                created = true;
            }
            LogSubscription subscription = entry.publisher.subscribe(sequence, observer);
            entry.watched = true;
            return subscription;
        } catch (IOException | RuntimeException failure) {
            if (created) {
                publishers.remove(id);
                try { entry.publisher.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            if (failure instanceof IOException io) throw new UncheckedIOException("Cannot watch project " + id, io);
            throw (RuntimeException) failure;
        }
    }
}
