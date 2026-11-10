package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.analysis.CategoryAnalyzer;
import io.github.aiassetmigrate.analysis.MaterialAnalysis;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.analysis.OrganizationRulesAnalyzer;
import io.github.aiassetmigrate.compatibility.CompatibilityEngine;
import io.github.aiassetmigrate.migration.CopyPlanBuilder;
import io.github.aiassetmigrate.migration.CopyExecutor;
import io.github.aiassetmigrate.migration.FileMigrationPlanRepository;
import io.github.aiassetmigrate.inventory.AssetScanner;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import io.github.aiassetmigrate.inventory.FileReviewedChangeRepository;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;
import io.github.aiassetmigrate.logging.LogPublisher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
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

    /** Uses the F01 state layout, including logs under {@code <state-directory>/logs}. */
    public DefaultAssetManager(Path stateDirectory) throws IOException {
        this(stateDirectory, Objects.requireNonNull(stateDirectory, "stateDirectory").resolve("logs"));
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
        if (overlap(this.stateDirectory, this.logDirectory) && !usesManagedLogDirectory()) {
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
                        List<AssetReference> references = scan.references().stream().map(reference -> new AssetReference(
                                idMap.getOrDefault(reference.ownerAssetId(), reference.ownerAssetId()), reference.kind(),
                                reference.originalText(), reference.normalizedTargetPath(), reference.resolutionStatus())).toList();
                        long revision = previous.isPresent() ? Math.incrementExact(previous.get().project().inventoryRevision()) : 1;
                        Instant created = previous.map(value -> value.project().createdAt()).orElse(started);
                        Instant scannedAt = Instant.now();
                        if (scannedAt.isBefore(created)) scannedAt = created;
                        var project = new Project(id, scan.sourceRoot(), revision, assets, created);
                        var snapshot = new InventorySnapshot(InventorySnapshot.CURRENT_SCHEMA_VERSION,
                                InventorySnapshot.CURRENT_INSPECTION_VERSION, project, findings,
                                scan.scanStatus(), scannedAt, scan.pathOutcomes(), references);
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
        if (overlap(stateDirectory, logDirectory) && !usesManagedLogDirectory()) {
            throw new IOException("State and log directories must be separate");
        }
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
            // On providers without Unix link counts, the strict publisher verifies its
            // durable identity anchor when it opens the sidecar.
            if (Files.getFileStore(path).supportsFileAttributeView("unix")) {
                validateLogLinkCount(path, true, publisherOpen);
            }
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

    private boolean usesManagedLogDirectory() {
        return logDirectory.equals(stateDirectory.resolve("logs"));
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
    @Override public AnalysisResult analyze(UUID id, AnalysisKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind == AnalysisKind.CATEGORIES) {
            InventorySnapshot snapshot = loadInventory(id);
            return new CategoryAnalyzer().analyze(snapshot.project().assets(), snapshot.project().inventoryRevision());
        }
        if (kind == AnalysisKind.RELATIONSHIPS || kind == AnalysisKind.MATERIALS) {
            InventorySnapshot snapshot = loadInventory(id);
            var analysis = inspectReferences(snapshot);
            Map<Path, Asset> paths = new HashMap<>();
            Map<UUID, Asset> assets = new HashMap<>();
            snapshot.project().assets().forEach(asset -> { paths.put(asset.relativePath(), asset); assets.put(asset.id(), asset); });
            List<Suggestion> suggestions = new ArrayList<>();
            List<Finding> findings = new ArrayList<>();
            Set<UUID> inspected = new HashSet<>();
            for (var file : analysis.files()) {
                inspected.add(file.assetId());
                if (file.status() != MaterialAnalysis.Inspection.VERIFIED) {
                    Asset asset = assets.get(file.assetId());
                    findings.add(analysisFinding(asset, "INSPECTION_" + file.status(), "Reference inspection: " + file.status()));
                }
            }
            for (Asset asset : snapshot.project().assets()) {
                if ((asset.type() == Asset.Type.MODEL || asset.type() == Asset.Type.MATERIAL)
                        && !inspected.contains(asset.id())) {
                    findings.add(analysisFinding(asset, "INSPECTION_UNSUPPORTED",
                            "Reference inspection is not supported for this format"));
                }
            }
            if (!snapshot.hasCompleteCoverage()) {
                findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.WARNING,
                        "INCOMPLETE_INVENTORY", "Reference analysis cannot cover paths omitted by the inventory scan"));
            }
            for (AssetReference reference : analysis.references()) {
                Asset owner = assets.get(reference.ownerAssetId());
                Asset target = reference.normalizedTargetPath().map(paths::get).orElse(null);
                if (reference.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED && target != null) {
                    if (kind == AnalysisKind.RELATIONSHIPS
                            || (kind == AnalysisKind.MATERIALS && reference.kind() == AssetReference.Kind.TEXTURE)) {
                        suggestions.add(analysisSuggestion(owner.id(), kind, target.id().toString(), reference.originalText()));
                    }
                } else {
                    findings.add(analysisFinding(owner, "REFERENCE_" + reference.resolutionStatus(),
                            "Reference '" + reference.originalText() + "': " + reference.resolutionStatus()));
                    if (kind == AnalysisKind.RELATIONSHIPS || reference.kind() == AssetReference.Kind.TEXTURE) {
                        for (Asset candidate : correctionCandidates(snapshot.project().assets(), owner, reference)) {
                            suggestions.add(analysisSuggestion(owner.id(), kind, candidate.id().toString(), reference.originalText()));
                            findings.add(analysisFinding(owner, "REFERENCE_CANDIDATE", "Possible correction for '"
                                    + reference.originalText() + "': " + PortablePath.of(candidate.relativePath())
                                    + " (uncertain; review before accepting)"));
                        }
                    }
                }
            }
            if (kind == AnalysisKind.RELATIONSHIPS) {
                Set<UUID> referenced = new HashSet<>();
                for (AssetReference reference : analysis.references()) {
                    reference.normalizedTargetPath().map(paths::get).ifPresent(target -> referenced.add(target.id()));
                }
                for (Asset texture : snapshot.project().assets()) {
                    if (texture.type() != Asset.Type.TEXTURE || referenced.contains(texture.id())) continue;
                    for (Asset owner : stemOwners(snapshot.project().assets(), texture)) {
                        suggestions.add(analysisSuggestion(owner.id(), kind, texture.id().toString(), ""));
                        findings.add(analysisFinding(owner, "NAME_RELATIONSHIP_CANDIDATE", "Unreferenced texture "
                                + PortablePath.of(texture.relativePath()) + " shares this asset's name (uncertain grouping)"));
                    }
                }
            }
            if (kind == AnalysisKind.MATERIALS) {
                for (var mapping : analysis.mappings()) {
                    Asset owner = assets.get(mapping.modelId());
                    if (mapping.status() == MaterialAnalysis.MappingStatus.RESOLVED) {
                        suggestions.add(analysisSuggestion(owner.id(), kind, mapping.materialAssetId().orElseThrow().toString(), mapping.materialName()));
                        findings.add(analysisFinding(owner, "MATERIAL_REFERENCE", "Material '" + mapping.materialName() + "' maps to " + mapping.materialAssetId().orElseThrow()));
                    } else {
                        findings.add(analysisFinding(owner, "MATERIAL_" + mapping.status(), "Material '" + mapping.materialName() + "': " + mapping.status()));
                    }
                }
            }
            if (analysis.truncated()) findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(),
                    Finding.Severity.WARNING, "ANALYSIS_LIMIT", "Reference analysis exceeded its budget"));
            return new AnalysisResult(snapshot.project().inventoryRevision(), suggestions, findings);
        }
        if (kind == AnalysisKind.NAMES || kind == AnalysisKind.FOLDERS) {
            InventorySnapshot snapshot = loadInventory(id);
            List<Suggestion> suggestions = snapshot.project().assets().stream().map(asset -> {
                Path path = asset.relativePath();
                String value = kind == AnalysisKind.NAMES ? path.getFileName().toString()
                        : path.getParent() == null ? "" : path.getParent().toString().replace('\\', '/');
                return new Suggestion(UUID.nameUUIDFromBytes((kind + ":" + asset.id() + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)), asset.id(), kind, value);
            }).toList();
            return new AnalysisResult(snapshot.project().inventoryRevision(), suggestions, List.of());
        }
        throw new UnsupportedOperationException("Analysis not implemented: " + kind);
    }

    @Override public AnalysisResult analyzeOrganization(UUID id, OrganizationRules rules) {
        Objects.requireNonNull(rules, "rules");
        InventorySnapshot inventory = loadInventory(id);
        List<ReviewedChange> reviews = rules.folderStyle() == FolderStyle.BY_CATEGORY ? loadReviews(id) : List.of();
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed while analyzing; retry");
        return OrganizationRulesAnalyzer.analyze(inventory, rules, reviews);
    }

    private MaterialAnalysis.Result inspectReferences(InventorySnapshot snapshot) {
        return MaterialAnalysis.analyze(snapshot, (asset, cap) -> {
            Path file = snapshot.project().sourceRoot().resolve(asset.relativePath());
            SafeStoragePaths.requireRegularFile(file);
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() > cap) return new byte[cap + 1];
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(cap + 1);
            }
            SafeStoragePaths.requireRegularFile(file);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Source changed during analysis");
            return bytes;
        }, MaterialAnalysis.Limits.defaults());
    }

    @Override public OrganizationPreview.Result previewOrganization(UUID id, long expectedInventoryRevision) {
        if (expectedInventoryRevision < 1) throw new IllegalArgumentException("Inventory revision must be positive");
        InventorySnapshot inventory = loadInventory(id);
        if (inventory.project().inventoryRevision() != expectedInventoryRevision) {
            throw new IllegalStateException("Inventory revision changed; analyze again");
        }
        List<ReviewedChange> decisions = loadReviews(id);
        var references = inspectReferences(inventory);
        InventorySnapshot inspected = new InventorySnapshot(inventory.schemaVersion(), inventory.inspectionVersion(),
                inventory.project(), inventory.findings(), inventory.scanStatus(), inventory.scannedAt(),
                inventory.pathOutcomes(), references.references());
        var result = OrganizationPreview.preview(inspected, id, expectedInventoryRevision, decisions);
        List<OrganizationPreview.Finding> findings = new ArrayList<>(result.findings());
        Set<UUID> excluded = new HashSet<>();
        result.entries().stream().filter(OrganizationPreview.Entry::excluded).forEach(entry -> excluded.add(entry.assetId()));
        Set<UUID> acknowledged = new HashSet<>();
        decisions.stream().filter(change -> change.kind() == ReviewedChange.Kind.ACKNOWLEDGMENT
                && change.decision() == ReviewedChange.Decision.ACCEPTED).forEach(change -> acknowledged.add(change.assetId()));
        Set<UUID> inspectedIds = new HashSet<>();
        for (var file : references.files()) {
            inspectedIds.add(file.assetId());
            if (file.status() != MaterialAnalysis.Inspection.VERIFIED && !excluded.contains(file.assetId())) {
                findings.add(new OrganizationPreview.Finding("INSPECTION_" + file.status(), Optional.of(file.assetId()),
                        "Reference coverage is incomplete"));
            }
        }
        for (Asset asset : inventory.project().assets()) {
            if ((asset.type() == Asset.Type.MODEL || asset.type() == Asset.Type.MATERIAL)
                    && !inspectedIds.contains(asset.id()) && !excluded.contains(asset.id())
                    && !acknowledged.contains(asset.id())) {
                findings.add(new OrganizationPreview.Finding("INSPECTION_UNSUPPORTED", Optional.of(asset.id()),
                        "Reference inspection is not supported for this format; exclude the asset or accept an"
                        + " ACKNOWLEDGMENT that its dependencies are unknown"));
            }
        }
        if (references.truncated()) findings.add(new OrganizationPreview.Finding("ANALYSIS_LIMIT", Optional.empty(),
                "Reference analysis exceeded its budget"));
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed while previewing; analyze again");
        return new OrganizationPreview.Result(id, expectedInventoryRevision, result.entries(), findings);
    }

    private static Suggestion analysisSuggestion(UUID owner, AnalysisKind kind, String target, String reference) {
        UUID suggestionId = UUID.nameUUIDFromBytes((kind + ":" + owner + ":" + target + ":" + reference).getBytes(StandardCharsets.UTF_8));
        return new Suggestion(suggestionId, owner, kind, target,
                reference.isEmpty() ? Optional.empty() : Optional.of(reference));
    }

    /**
     * Assets of the type a broken reference expects whose filename matches the reference's
     * final segment, ignoring case. These are uncertain suggestions, never applied implicitly.
     */
    static List<Asset> correctionCandidates(List<Asset> assets, Asset owner, AssetReference reference) {
        if (reference.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED
                || !MaterialAnalysis.rewritableReference(reference.originalText())) return List.of();
        Asset.Type expected = reference.kind() == AssetReference.Kind.MATERIAL_LIBRARY ? Asset.Type.MATERIAL : Asset.Type.TEXTURE;
        String text = reference.originalText();
        String filename = text.substring(text.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (filename.isEmpty()) return List.of();
        return assets.stream().filter(asset -> asset.type() == expected && !asset.id().equals(owner.id())
                        && asset.relativePath().getFileName().toString().toLowerCase(java.util.Locale.ROOT).equals(filename))
                .sorted(java.util.Comparator.comparing((Asset asset) -> PortablePath.of(asset.relativePath())))
                .limit(5).toList();
    }

    /** Models and materials whose filename stem prefixes a texture's stem at a word boundary. */
    static List<Asset> stemOwners(List<Asset> assets, Asset texture) {
        String textureStem = stem(texture);
        return assets.stream().filter(asset -> asset.type() == Asset.Type.MODEL || asset.type() == Asset.Type.MATERIAL)
                .filter(asset -> {
                    String stem = stem(asset);
                    return stem.length() >= 3 && textureStem.startsWith(stem) && (textureStem.length() == stem.length()
                            || !Character.isLetterOrDigit(textureStem.charAt(stem.length())));
                })
                .sorted(java.util.Comparator.comparing((Asset asset) -> PortablePath.of(asset.relativePath())))
                .limit(5).toList();
    }

    private static String stem(Asset asset) {
        String name = asset.relativePath().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Finding analysisFinding(Asset asset, String code, String message) {
        return new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                Finding.Severity.WARNING, code, message);
    }
    @Override public List<ReviewedChange> loadReviews(UUID id) {
        InventorySnapshot inventory = loadInventory(id);
        try {
            List<ReviewedChange> result = new FileReviewedChangeRepository(stateDirectory).loadLatest(id)
                    .filter(set -> set.inventoryRevision() == inventory.project().inventoryRevision())
                    .map(FileReviewedChangeRepository.ReviewSet::changes).orElse(List.of());
            if (!loadInventory(id).equals(inventory)) {
                throw new IllegalStateException("Inventory changed while loading reviews; retry");
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load reviews for project " + id, e);
        }
    }

    @Override public List<ReviewedChange> review(UUID id, long expectedInventoryRevision, List<ReviewedChange> changes) {
        if (expectedInventoryRevision < 1) throw new IllegalArgumentException("Inventory revision must be positive");
        InventorySnapshot inventory = loadInventory(id);
        if (inventory.project().inventoryRevision() != expectedInventoryRevision) {
            throw new IllegalStateException("Inventory revision changed: reviewed " + expectedInventoryRevision
                    + ", current " + inventory.project().inventoryRevision() + "; analyze again");
        }
        List<ReviewedChange> incoming = List.copyOf(changes);
        if (incoming.isEmpty()) throw new IllegalArgumentException("At least one decision is required");
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        for (ReviewedChange change : incoming) {
            if (!assets.containsKey(change.assetId())) throw new IllegalArgumentException("Unknown reviewed asset");
            Map<String, String> values = change.values();
            switch (change.kind()) {
                case CATEGORY -> requireValue(values, "category");
                case RELATIONSHIP, MATERIAL_MAPPING -> {
                    String target = requireValue(values, "targetAssetId");
                    UUID targetId;
                    try { targetId = UUID.fromString(target); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid targetAssetId", e); }
                    if (!assets.containsKey(targetId) || targetId.equals(change.assetId())) {
                        throw new IllegalArgumentException("Target must be another asset in the inventory");
                    }
                    if (change.kind() == ReviewedChange.Kind.MATERIAL_MAPPING) {
                        requireValue(values, "reference");
                        Asset.Type ownerType = assets.get(change.assetId()).type();
                        Asset.Type targetType = assets.get(targetId).type();
                        if (!((ownerType == Asset.Type.MODEL && targetType == Asset.Type.MATERIAL)
                                || (ownerType == Asset.Type.MATERIAL && targetType == Asset.Type.TEXTURE))) {
                            throw new IllegalArgumentException("Material mappings require a model and material, or a material and texture");
                        }
                    } else if (values.containsKey("reference")) {
                        requireValue(values, "reference");
                    }
                }
                case EXCLUSION -> requireValue(values, "reason");
                case ACKNOWLEDGMENT -> {
                    requireValue(values, "reason");
                    if (MaterialAnalysis.inspectable(assets.get(change.assetId()))
                            || assets.get(change.assetId()).type() == Asset.Type.TEXTURE
                            || assets.get(change.assetId()).type() == Asset.Type.OTHER) {
                        throw new IllegalArgumentException(
                                "Acknowledgments apply only to models or materials whose references cannot be inspected");
                    }
                }
                case NAME -> {
                    String name = requireValue(values, "name");
                    if (change.decision() == ReviewedChange.Decision.ACCEPTED && !OrganizationPreview.validName(name)) {
                        throw new IllegalArgumentException("Accepted name must be a portable filename");
                    }
                }
                case FOLDER -> {
                    String folder = values.get("folder");
                    if (folder == null || folder.length() > 4096) {
                        throw new IllegalArgumentException("A folder value is required (maximum 4096 characters)");
                    }
                    if (change.decision() == ReviewedChange.Decision.ACCEPTED && !OrganizationPreview.validFolder(folder)) {
                        throw new IllegalArgumentException("Accepted folder must be a portable relative path");
                    }
                }
                default -> throw new UnsupportedOperationException("Review not implemented: " + change.kind());
            }
        }
        try {
            validatePaths(inventory.project().sourceRoot(), id);
            FileReviewedChangeRepository reviews = new FileReviewedChangeRepository(stateDirectory);
            var previous = reviews.loadLatest(id);
            List<ReviewedChange> merged = new ArrayList<>();
            previous.filter(set -> set.inventoryRevision() == inventory.project().inventoryRevision())
                    .ifPresent(set -> merged.addAll(set.changes()));
            for (ReviewedChange change : incoming) {
                merged.removeIf(old -> old.assetId().equals(change.assetId()) && old.kind() == change.kind()
                        && (change.kind() == ReviewedChange.Kind.CATEGORY || change.kind() == ReviewedChange.Kind.EXCLUSION
                            || change.kind() == ReviewedChange.Kind.ACKNOWLEDGMENT
                            || change.kind() == ReviewedChange.Kind.NAME || change.kind() == ReviewedChange.Kind.FOLDER
                            || (change.kind() == ReviewedChange.Kind.MATERIAL_MAPPING
                                && Objects.equals(old.values().get("reference"), change.values().get("reference")))
                            || (change.kind() == ReviewedChange.Kind.RELATIONSHIP
                                && (change.values().containsKey("reference")
                                    ? Objects.equals(old.values().get("reference"), change.values().get("reference"))
                                    : !old.values().containsKey("reference")
                                        && Objects.equals(old.values().get("targetAssetId"), change.values().get("targetAssetId"))))));
                merged.add(change);
            }
            long expected = previous.map(FileReviewedChangeRepository.ReviewSet::revision).orElse(0L);
            try (Publisher publisher = acquireWriter(id)) {
                publisher.publish(LogEvent.Level.INFO, "Saving reviewed changes");
                var saved = reviews.save(inventory, expected, merged);
                try {
                    publisher.publish(LogEvent.Level.INFO, "Review revision " + saved.revision() + " committed");
                } catch (IOException e) {
                    throw new IOException("Review revision " + saved.revision() + " was committed, but completion logging failed", e);
                }
                return saved.changes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save reviews for project " + id + ": " + e.getMessage(), e);
        }
    }

    private static String requireValue(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException("A nonblank " + key + " is required (maximum 4096 characters)");
        }
        return value;
    }
    @Override public List<TargetProfile> supportedTargets() {
        ensureOpen();
        return CompatibilityEngine.profiles();
    }

    @Override public List<Finding> checkCompatibility(UUID id, TargetProfile target) {
        return assessCompatibility(id, target).findings();
    }

    @Override public CompatibilityResult assessCompatibility(UUID id, TargetProfile target) {
        InventorySnapshot inventory = loadInventory(id);
        if (!CompatibilityEngine.profiles().contains(Objects.requireNonNull(target, "target"))) {
            throw new IllegalArgumentException("Unknown target profile or version: " + target);
        }
        var inspectedReferences = inspectReferences(inventory);
        InventorySnapshot inspected = new InventorySnapshot(inventory.schemaVersion(), inventory.inspectionVersion(),
                inventory.project(), inventory.findings(), inventory.scanStatus(), inventory.scannedAt(),
                inventory.pathOutcomes(), inspectedReferences.references());
        List<Finding> findings = new ArrayList<>(inventory.findings());
        findings.addAll(CompatibilityEngine.check(inspected, target));
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        Set<UUID> inspectedIds = new HashSet<>();
        for (var file : inspectedReferences.files()) {
            inspectedIds.add(file.assetId());
            if (file.status() != MaterialAnalysis.Inspection.VERIFIED) {
                findings.add(analysisFinding(assets.get(file.assetId()), "INSPECTION_" + file.status(),
                        "Reference inspection: " + file.status()));
            }
        }
        for (Asset asset : inventory.project().assets()) {
            if ((asset.type() == Asset.Type.MODEL || asset.type() == Asset.Type.MATERIAL)
                    && !inspectedIds.contains(asset.id())) {
                findings.add(analysisFinding(asset, "INSPECTION_UNSUPPORTED", "References were not inspected for this format"));
            }
        }
        for (var mapping : inspectedReferences.mappings()) {
            if (mapping.status() != MaterialAnalysis.MappingStatus.RESOLVED) {
                findings.add(analysisFinding(assets.get(mapping.modelId()), "MATERIAL_" + mapping.status(),
                        "Material '" + mapping.materialName() + "': " + mapping.status()));
            }
        }
        if (inspectedReferences.truncated()) findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(),
                Finding.Severity.WARNING, "ANALYSIS_LIMIT", "Reference analysis exceeded its budget"));
        findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.WARNING,
                "SOURCE_REVALIDATION_INCOMPLETE", "Only inspected OBJ and MTL source fingerprints were rechecked; other source files may have changed"));
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed during compatibility assessment; retry");
        return new CompatibilityResult(inventory.project().inventoryRevision(), target, findings);
    }
    @Override public MigrationPlan createPlan(UUID id, TargetProfile target, Path output) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(output, "output");
        if (!output.isAbsolute()) throw new IllegalArgumentException("Output root must be absolute");
        for (Path component : output) {
            if (component.toString().equals("..")) throw new IllegalArgumentException("Output root cannot contain parent traversal");
        }
        Path root = output.normalize();
        InventorySnapshot inventory = loadInventory(id);
        if (!supportedTargets().contains(target)) throw new IllegalArgumentException("Unknown target profile or version: " + target);
        try {
            validatePaths(inventory.project().sourceRoot(), id);
            SafeStoragePaths.validateSeparation(inventory.project().sourceRoot(), root);
            SafeStoragePaths.validateExistingPath(root);
            if (overlap(root, stateDirectory) || overlap(root, logDirectory)) {
                throw new IOException("Output must be separate from state and log directories");
            }
            var latestReviews = new FileReviewedChangeRepository(stateDirectory).loadLatest(id);
            long reviewRevision = latestReviews.map(FileReviewedChangeRepository.ReviewSet::revision).orElse(0L);
            List<ReviewedChange> decisions = latestReviews
                    .filter(set -> set.inventoryRevision() == inventory.project().inventoryRevision())
                    .map(FileReviewedChangeRepository.ReviewSet::changes).orElse(List.of());
            var preview = previewOrganization(id, inventory.project().inventoryRevision());
            var compatibility = assessCompatibility(id, target);
            if (!loadInventory(id).equals(inventory)) throw new IOException("Inventory changed during planning");
            var draft = CopyPlanBuilder.draft(inventory, decisions, preview, target, root, UUID.randomUUID(),
                    Instant.now(), compatibility.findings());
            return new FileMigrationPlanRepository(stateDirectory).createDraft(draft, reviewRevision);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create draft plan for project " + id, e);
        }
    }

    @Override public MigrationPlan loadPlan(UUID planId) {
        ensureOpen();
        try {
            return new FileMigrationPlanRepository(stateDirectory).load(planId).plan();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load plan " + planId, e);
        }
    }
    @Override public MigrationPlan approve(UUID id, int version) {
        ensureOpen();
        try {
            return new FileMigrationPlanRepository(stateDirectory).approve(id, version, this::validateCopyPlan);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot approve plan " + id + ": " + e.getMessage(), e);
        }
    }

    @Override public MigrationRun migrate(UUID id) {
        ensureOpen();
        try {
            var repository = new FileMigrationPlanRepository(stateDirectory);
            MigrationPlan plan = repository.load(id).plan();
            return repository.execute(id, plan.version(), this::validateCopyPlan, (approved, inventory, reservation) -> {
                var inspected = inspectedInventory(inventory);
                var result = new CopyExecutor().execute(inspected, approved, stateDirectory, logDirectory);
                Map<UUID, StepStatus> statuses = new java.util.LinkedHashMap<>();
                Map<UUID, String> details = new java.util.LinkedHashMap<>();
                for (var step : result.steps()) {
                    statuses.put(step.commandId(), switch (step.status()) {
                        case COMPLETED -> StepStatus.SUCCESS;
                        case FAILED -> StepStatus.FAILED;
                        case SKIPPED -> StepStatus.SKIPPED;
                    });
                    if (step.detail() != null && !step.detail().isBlank()) details.put(step.commandId(), step.detail());
                }
                long succeeded = statuses.values().stream().filter(status -> status == StepStatus.SUCCESS).count();
                RunStatus status = result.preflightPassed() && result.error().isEmpty() ? RunStatus.SUCCESS
                        : succeeded > 0 ? RunStatus.PARTIAL : RunStatus.FAILED;
                return new FileMigrationPlanRepository.ExecutionResult(status, statuses,
                        Optional.ofNullable(result.error()).filter(message -> !message.isBlank()), details);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot execute plan " + id + ": " + e.getMessage(), e);
        }
    }

    @Override public MigrationRun loadRun(UUID id) {
        ensureOpen();
        try {
            return new FileMigrationPlanRepository(stateDirectory).loadRun(id);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load run for plan " + id, e);
        }
    }

    private void validateCopyPlan(MigrationPlan plan, InventorySnapshot inventory) throws IOException {
        if (!plan.projectId().equals(inventory.project().id())
                || plan.inventoryRevision() != inventory.project().inventoryRevision()) throw new IOException("Plan inventory is stale");
        TargetProfile target = new TargetProfile(plan.targetProfile(), plan.targetProfileVersion());
        if (!supportedTargets().contains(target)) throw new IOException("Plan target is not supported");
        validatePaths(inventory.project().sourceRoot(), plan.projectId());
        Path output = plan.outputRoot();
        if (!output.isAbsolute()) throw new IOException("Output must be absolute");
        SafeStoragePaths.validateSeparation(inventory.project().sourceRoot(), output);
        SafeStoragePaths.validateExistingPath(output);
        if (overlap(output, stateDirectory) || overlap(output, logDirectory)) throw new IOException("Output overlaps state or logs");
        if (plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN)) {
            throw new IOException("Plan has blocking findings");
        }
        var preview = previewOrganization(plan.projectId(), plan.inventoryRevision());
        if (preview.blocked()) throw new IOException("Current organization preview has blocking findings");
        var compatibility = assessCompatibility(plan.projectId(), target);
        if (compatibility.inventoryRevision() != plan.inventoryRevision()
                || compatibility.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN)) {
            throw new IOException("Current compatibility assessment has blocking findings");
        }
        if (!CopyPlanBuilder.commands(inventory, preview, plan.id()).equals(plan.commands())) {
            throw new IOException("Plan commands do not match the reviewed organization");
        }
        if (plan.status() == PlanStatus.APPROVED) {
            new CopyExecutor().validatePreflight(inspectedInventory(inventory), plan, stateDirectory, logDirectory);
        }
    }

    private InventorySnapshot inspectedInventory(InventorySnapshot inventory) throws IOException {
        var analysis = inspectReferences(inventory);
        if (analysis.truncated() || analysis.files().stream().anyMatch(f -> f.status() != MaterialAnalysis.Inspection.VERIFIED)) {
            throw new IOException("Reference inspection is incomplete or source files changed");
        }
        return new InventorySnapshot(inventory.schemaVersion(), inventory.inspectionVersion(), inventory.project(),
                inventory.findings(), inventory.scanStatus(), inventory.scannedAt(), inventory.pathOutcomes(), analysis.references());
    }
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
