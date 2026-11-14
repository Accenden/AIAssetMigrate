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
import io.github.aiassetmigrate.inventory.FileReportRepository;
import io.github.aiassetmigrate.inventory.FileReviewedChangeRepository;
import io.github.aiassetmigrate.validation.OutputValidator;
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
        return scanAndSave(UUID.randomUUID(), sourceRoot, false, Optional.empty());
    }

    @Override
    public ImportResult rescan(UUID projectId, Path sourceRoot) {
        return scanAndSave(Objects.requireNonNull(projectId, "projectId"), sourceRoot, true, Optional.empty());
    }

    @Override
    public ImportResult rescan(UUID projectId, Path sourceRoot, Set<Path> excludedFiles) {
        Objects.requireNonNull(excludedFiles, "excludedFiles");
        // Constructing an outcome validates each entry as a relative file path before anything is scanned.
        excludedFiles.forEach(path -> new PathOutcome(path, PathOutcome.Kind.FILE, PathOutcome.Status.EXCLUDED));
        return scanAndSave(Objects.requireNonNull(projectId, "projectId"), sourceRoot, true, Optional.of(Set.copyOf(excludedFiles)));
    }

    /** {@code exclusions}: the files to leave out; empty keeps the previous revision's (none for a new project). */
    private ImportResult scanAndSave(UUID id, Path sourceRoot, boolean rescan, Optional<Set<Path>> exclusions) {
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
                        Set<Path> excluded = exclusions.orElseGet(() -> previous.map(InventorySnapshot::excludedFiles).orElse(Set.of()));
                        if (!excluded.isEmpty()) {
                            publisher.publish(LogEvent.Level.INFO, "Leaving out " + excluded.size() + " file(s) chosen by the user");
                        }
                        AssetScanner.ScanResult scan = scanner.scan(sourceRoot, excluded);
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
                // Material mappings are material-to-texture (or model-to-material); an FBX model's textures are
                // relationships only. An FBX cannot be rewritten, so no correction is suggested for it: the Plan
                // step's Fix blockers offers to place the texture where the FBX expects it instead.
                boolean mapping = kind == AnalysisKind.MATERIALS && reference.kind() == AssetReference.Kind.TEXTURE
                        && owner.type() == Asset.Type.MATERIAL;
                if (reference.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED && target != null) {
                    if (kind == AnalysisKind.RELATIONSHIPS || mapping) {
                        suggestions.add(analysisSuggestion(owner.id(), kind, target.id().toString(), reference.originalText()));
                    }
                } else {
                    findings.add(analysisFinding(owner, "REFERENCE_" + reference.resolutionStatus(),
                            "Reference '" + reference.originalText() + "': " + reference.resolutionStatus()));
                    if (!MaterialAnalysis.fbx(owner) && (kind == AnalysisKind.RELATIONSHIPS || mapping)) {
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
            // Default rules: snake_case names; folders from accepted categories, otherwise one per type.
            // Only real changes are suggested; other styles are available through analyzeOrganization.
            boolean names = kind == AnalysisKind.NAMES;
            InventorySnapshot snapshot = loadInventory(id);
            List<ReviewedChange> reviews = names ? List.of() : loadReviews(id);
            if (!loadInventory(id).equals(snapshot)) throw new IllegalStateException("Inventory changed while analyzing; retry");
            AnalysisResult rules = OrganizationRulesAnalyzer.analyze(snapshot, names
                    ? new OrganizationRules(NameStyle.SNAKE_CASE, FolderStyle.PRESERVE)
                    : new OrganizationRules(NameStyle.PRESERVE, FolderStyle.BY_CATEGORY), reviews);
            List<Finding> findings = new ArrayList<>(rules.findings());
            findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.INFO,
                    names ? "NAME_RULE" : "FOLDER_RULE", (names
                    ? "Names are suggested in snake_case (lower case, words joined by _, extension unchanged)"
                    : "Folders are suggested from accepted categories, otherwise one folder per file type")
                    + (rules.suggestions().isEmpty() ? "; every file already follows it." : "; only changes are listed.")
                    + " Other styles: Rename and folder rules (CLI: analyze-organization)."));
            return new AnalysisResult(rules.inventoryRevision(), rules.suggestions(), findings);
        }
        throw new UnsupportedOperationException("Analysis not implemented: " + kind);
    }

    @Override public AnalysisResult analyze(UUID id, AnalysisKind kind, io.github.aiassetmigrate.ai.LLMClient assistant) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(assistant, "assistant");
        InventorySnapshot inventory = loadInventory(id);
        AnalysisResult base = analyze(id, kind);
        if (base.inventoryRevision() != inventory.project().inventoryRevision()) {
            throw new IllegalStateException("Inventory changed while analyzing; retry");
        }
        // Every kind gets the inspected references, so the model sees which files belong together (step 4 of the
        // AI plan); categories and organization also get measured traits such as transparency.
        var references = inspectReferences(inventory);
        InventorySnapshot inspected = new InventorySnapshot(inventory.schemaVersion(), inventory.inspectionVersion(),
                inventory.project(), inventory.findings(), inventory.scanStatus(), inventory.scannedAt(),
                inventory.pathOutcomes(), references.references());
        Map<UUID, Set<String>> traits = kind == AnalysisKind.RELATIONSHIPS || kind == AnalysisKind.MATERIALS
                ? Map.of() : traits(inventory);
        Map<UUID, String> categories = new HashMap<>();
        if (kind == AnalysisKind.NAMES || kind == AnalysisKind.FOLDERS) {
            for (ReviewedChange review : loadReviews(id)) {
                if (review.kind() == ReviewedChange.Kind.CATEGORY && review.decision() == ReviewedChange.Decision.ACCEPTED
                        && review.values().containsKey("category")) categories.put(review.assetId(), review.values().get("category"));
            }
        }
        AnalysisResult result = new io.github.aiassetmigrate.ai.AIEngine(assistant).assist(kind, inspected, base, categories, traits);
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed during AI analysis; retry");
        return result;
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
        var preview = preview(inventory, references, decisions);
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed while previewing; analyze again");
        return preview;
    }

    /** The organization preview for these decisions, with the inspection findings that also block a plan. */
    private static OrganizationPreview.Result preview(InventorySnapshot inventory, MaterialAnalysis.Result references,
                                                      List<ReviewedChange> decisions) {
        UUID id = inventory.project().id();
        long expectedInventoryRevision = inventory.project().inventoryRevision();
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
        Map<UUID, Asset> byId = new HashMap<>();
        inventory.project().assets().forEach(asset -> byId.put(asset.id(), asset));
        for (var file : references.files()) {
            inspectedIds.add(file.assetId());
            // An acknowledgment accepts an FBX the built-in reader could not read, as it always did for FBX.
            boolean acknowledgedFbx = acknowledged.contains(file.assetId()) && MaterialAnalysis.fbx(byId.get(file.assetId()));
            if (file.status() != MaterialAnalysis.Inspection.VERIFIED && !excluded.contains(file.assetId()) && !acknowledgedFbx) {
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
        for (ReviewedChange change : incoming) validateChange(assets, change);
        try {
            validatePaths(inventory.project().sourceRoot(), id);
            FileReviewedChangeRepository reviews = new FileReviewedChangeRepository(stateDirectory);
            var previous = reviews.loadLatest(id);
            List<ReviewedChange> merged = merge(previous.filter(set -> set.inventoryRevision() == inventory.project().inventoryRevision())
                    .map(FileReviewedChangeRepository.ReviewSet::changes).orElse(List.of()), incoming);
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

    /** Checks one decision against the inventory as {@link #review} does; throws IllegalArgumentException with the reason. */
    private static void validateChange(Map<UUID, Asset> assets, ReviewedChange change) {
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
                case CONVERSION -> {
                    String format = requireValue(values, "format");
                    Asset asset = assets.get(change.assetId());
                    String target = io.github.aiassetmigrate.conversion.Converters.normalizeFormat(format);
                    if (change.decision() == ReviewedChange.Decision.ACCEPTED) {
                        var strategy = target == null ? Optional.<io.github.aiassetmigrate.conversion.ConversionStrategy>empty()
                                : io.github.aiassetmigrate.conversion.Converters.find(asset, target);
                        if (strategy.isEmpty()) {
                            String reason = asset.type() == Asset.Type.MODEL && !io.github.aiassetmigrate.conversion.AssimpModelConverter.available()
                                    ? " (model conversion is unavailable: " + io.github.aiassetmigrate.conversion.AssimpModelConverter.unavailableReason() + ")" : "";
                            throw new IllegalArgumentException("No installed converter supports converting this asset to " + format + reason);
                        }
                        strategy.get().validateOptions(target, values);
                    }
                }
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

    /** Adds decisions to a saved set: a new decision replaces the one in the same slot (for example one category per file). */
    static List<ReviewedChange> merge(List<ReviewedChange> existing, List<ReviewedChange> incoming) {
            List<ReviewedChange> merged = new ArrayList<>(existing);
            for (ReviewedChange change : incoming) {
                merged.removeIf(old -> old.assetId().equals(change.assetId()) && old.kind() == change.kind()
                        && (change.kind() == ReviewedChange.Kind.CATEGORY || change.kind() == ReviewedChange.Kind.EXCLUSION
                            || change.kind() == ReviewedChange.Kind.ACKNOWLEDGMENT || change.kind() == ReviewedChange.Kind.CONVERSION
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
            return merged;
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

    @Override public CompatibilityResult assessCompatibility(UUID id, TargetProfile target,
                                                             io.github.aiassetmigrate.ai.LLMClient assistant) {
        Objects.requireNonNull(assistant, "assistant");
        CompatibilityResult base = assessCompatibility(id, target);
        List<Finding> findings = new ArrayList<>(base.findings());
        findings.addAll(new io.github.aiassetmigrate.ai.AIEngine(assistant).explainCompatibility(target, base.findings()));
        return new CompatibilityResult(base.inventoryRevision(), target, findings);
    }
    @Override public MigrationPlan createPlan(UUID id, TargetProfile target, Path output) {
        return createPlan(id, target, output, Optional.empty());
    }

    @Override public MigrationPlan createPlan(UUID id, TargetProfile target, Path output,
                                              io.github.aiassetmigrate.ai.LLMClient assistant) {
        Objects.requireNonNull(assistant, "assistant");
        return createPlan(id, target, output, Optional.of(assistant));
    }

    private MigrationPlan createPlan(UUID id, TargetProfile target, Path output,
                                     Optional<io.github.aiassetmigrate.ai.LLMClient> assistant) {
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
            PlanState state = planState(inventory, target);
            var preview = state.preview();
            List<Finding> planFindings = new ArrayList<>(state.checks());
            UUID planId = UUID.randomUUID();
            if (assistant.isPresent()) {
                // AI suggestions are checked by the controller and recorded as findings; commands stay deterministic.
                planFindings.addAll(suggestionFindings(suggest(state, target, assistant.get())));
            }
            if (!loadInventory(id).equals(inventory)) throw new IOException("Inventory changed during planning");
            var draft = CopyPlanBuilder.draft(inventory, decisions, preview, target, root, planId,
                    Instant.now(), planFindings);
            return new FileMigrationPlanRepository(stateDirectory).createDraft(draft, reviewRevision);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create draft plan for project " + id, e);
        }
    }

    /**
     * Runs each reviewed conversion's strategy check on exactly the input execution will use
     * (fingerprint-verified sources, reviewed rewrites and texture conversions applied); failures block
     * approval. Converted models also get a standing fidelity warning, and a GLB warning for Unity.
     */
    private static List<Finding> conversionChecks(InventorySnapshot inventory, OrganizationPreview.Result preview,
                                                  TargetProfile target) {
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        List<Finding> findings = new ArrayList<>();
        Path root = inventory.project().sourceRoot();
        io.github.aiassetmigrate.migration.ConversionInputs.SourceBytes sources = asset -> verifiedSource(root, asset);
        for (var entry : preview.entries()) {
            if (entry.conversion().isEmpty() || entry.excluded()) continue;
            Asset asset = assets.get(entry.assetId());
            try {
                var strategy = io.github.aiassetmigrate.migration.ConversionInputs.strategy(asset, entry);
                strategy.check(io.github.aiassetmigrate.migration.ConversionInputs.build(inventory, preview, asset.id(), sources));
            } catch (IOException | RuntimeException e) {
                findings.add(new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                        Finding.Severity.ERROR, "CONVERSION_CHECK_FAILED", e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            if (asset.type() == Asset.Type.MODEL) {
                findings.add(new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                        Finding.Severity.WARNING, "MODEL_CONVERSION_FIDELITY", "Converting to GLB with Assimp "
                        + io.github.aiassetmigrate.conversion.AssimpModelConverter.assimpVersion() + " approximates materials (for"
                        + " example MTL colours become PBR values); validation checks the file, not how it looks in the engine"));
                if (target.id().equals("unity")) {
                    findings.add(new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                            Finding.Severity.WARNING, "GLB_REQUIRES_GLTFAST", "Unity imports GLB only with the glTFast package"
                            + " (com.unity.cloud.gltfast); the original model format imports natively"));
                }
            }
        }
        return findings;
    }

    /**
     * Import only fingerprints images, so a damaged PNG or JPEG would otherwise surface only when validation fails
     * after the run. Each image copied unchanged is decoded here with validation's own check (converted images
     * are decoded by their conversion check); one that would fail blocks approval as SOURCE_IMAGE_INVALID.
     * Sources that cannot be read within the limit are left to the run's own preflight.
     */
    private static List<Finding> imageChecks(InventorySnapshot inventory, OrganizationPreview.Result preview) {
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        List<Finding> findings = new ArrayList<>();
        Path root = inventory.project().sourceRoot();
        for (var entry : preview.entries()) {
            Asset asset = assets.get(entry.assetId());
            if (asset == null || entry.excluded() || entry.destination().isEmpty() || !entry.conversion().isEmpty()
                    || asset.type() != Asset.Type.TEXTURE) continue;
            String name = asset.relativePath().getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("png", "jpg", "jpeg").contains(extension)) continue;
            byte[] bytes;
            try { bytes = verifiedSource(root, asset); }
            catch (IOException unreadable) { continue; }
            io.github.aiassetmigrate.validation.OutputValidator.imageFailure(bytes, extension).ifPresent(problem ->
                    findings.add(new Finding(UUID.randomUUID(), Optional.of(asset.id()), Optional.of(asset.relativePath()),
                            Finding.Severity.ERROR, "SOURCE_IMAGE_INVALID", PortablePath.of(asset.relativePath()) + " " + problem
                            + "; copied as it is, it would fail validation")));
        }
        return findings;
    }

    /** Reads an inventoried source file within the conversion limit and checks it against its fingerprint. */
    private static byte[] verifiedSource(Path root, Asset asset) throws IOException {
        Path file = root.resolve(asset.relativePath());
        SafeStoragePaths.requireRegularFile(file);
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(64 * 1024 * 1024 + 1);
        }
        if (bytes.length > 64 * 1024 * 1024) throw new IOException(asset.relativePath() + " is too large to convert");
        try {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(asset.fingerprint())) throw new IOException("Source changed since the inventory scan: " + asset.relativePath());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        return bytes;
    }

    /**
     * Everything a plan is checked against, computed once: the inspected references, saved decisions, the organization
     * preview and the plan checks (compatibility, conversion checks, image decoding).
     */
    private record PlanState(InventorySnapshot inventory, MaterialAnalysis.Result references, List<ReviewedChange> saved,
                             OrganizationPreview.Result preview, List<Finding> checks) {}

    private PlanState planState(InventorySnapshot inventory, TargetProfile target) {
        UUID id = inventory.project().id();
        var references = inspectReferences(inventory);
        List<ReviewedChange> saved = loadReviews(id);
        var preview = preview(inventory, references, saved);
        List<Finding> checks = new ArrayList<>(assessCompatibility(id, target).findings());
        checks.addAll(conversionChecks(inventory, preview, target));
        checks.addAll(imageChecks(inventory, preview));
        return new PlanState(inventory, references, saved, preview, checks);
    }

    @Override public PlanSuggestions suggestPlanSteps(UUID id, TargetProfile target, io.github.aiassetmigrate.ai.LLMClient assistant) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(assistant, "assistant");
        InventorySnapshot inventory = loadInventory(id);
        if (!supportedTargets().contains(target)) throw new IllegalArgumentException("Unknown target profile or version: " + target);
        PlanSuggestions suggestions = suggest(planState(inventory, target), target, assistant);
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed while preparing suggestions; retry");
        return suggestions;
    }

    @Override public BlockerFixes.Proposal checkDecisions(UUID id, long inventoryRevision, Optional<TargetProfile> target,
                                                          List<BlockerFixes.Fix> candidates) {
        Objects.requireNonNull(target, "target");
        List<BlockerFixes.Fix> chosen = List.copyOf(candidates);
        InventorySnapshot inventory = loadInventory(id);
        if (inventory.project().inventoryRevision() != inventoryRevision) {
            throw new IllegalStateException("The project was rescanned; check the decisions again");
        }
        if (target.isPresent() && !supportedTargets().contains(target.get())) {
            throw new IllegalArgumentException("Unknown target profile or version: " + target.get());
        }
        PlanState state;
        if (target.isPresent()) {
            state = planState(inventory, target.get());
        } else {
            var references = inspectReferences(inventory);
            List<ReviewedChange> saved = loadReviews(id);
            var preview = preview(inventory, references, saved);
            List<Finding> checks = new ArrayList<>(imageChecks(inventory, preview));
            state = new PlanState(inventory, references, saved, preview, checks);
        }
        var checked = check(state, target, chosen);
        if (!loadInventory(id).equals(inventory)) throw new IllegalStateException("Inventory changed while checking decisions; retry");
        return checked;
    }

    private PlanSuggestions suggest(PlanState state, TargetProfile target, io.github.aiassetmigrate.ai.LLMClient assistant) {
        InventorySnapshot inventory = state.inventory();
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        Map<UUID, String> outputs = new HashMap<>();
        Map<UUID, Map<String, String>> conversions = new HashMap<>();
        for (var entry : state.preview().entries()) {
            if (!entry.excluded()) entry.destination().ifPresent(destination -> outputs.put(entry.assetId(), destination));
            if (!entry.conversion().isEmpty()) conversions.put(entry.assetId(), entry.conversion());
        }
        Map<UUID, Set<String>> issues = new HashMap<>();
        for (Finding finding : state.checks()) {
            // Every asset carries CONTENT_UNVERIFIED; it says nothing about one file.
            if (finding.severity() == Finding.Severity.INFO || finding.code().equals("CONTENT_UNVERIFIED")) continue;
            finding.assetId().ifPresent(asset -> issues.computeIfAbsent(asset, ignored -> new java.util.TreeSet<>()).add(finding.code()));
        }
        for (var finding : state.preview().findings()) {
            finding.assetId().ifPresent(asset -> issues.computeIfAbsent(asset, ignored -> new java.util.TreeSet<>()).add(finding.code()));
        }
        var context = new io.github.aiassetmigrate.ai.AIEngine.StepContext(target, inventory.project().assets(), outputs,
                conversions, issues, traits(inventory), state.references().references(), CompatibilityEngine.notes(target));
        var proposals = new io.github.aiassetmigrate.ai.AIEngine(assistant).proposeSteps(context);
        List<BlockerFixes.Fix> candidates = proposals.proposals().stream().map(proposal -> toFix(proposal, assets)).toList();
        BlockerFixes.Proposal checked = check(state, Optional.of(target), candidates);
        List<Finding> findings = new ArrayList<>(proposals.findings());
        if (!candidates.isEmpty()) {
            findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.INFO, "AI_PLAN_CHECKED",
                    checked.fixes().size() + " of " + candidates.size() + " AI suggestion(s) passed the program's checks"
                            + (checked.unfixable().isEmpty() ? "" : "; " + checked.unfixable().size() + " were dropped")));
        }
        return new PlanSuggestions(target, checked, findings);
    }

    private static BlockerFixes.Fix toFix(io.github.aiassetmigrate.ai.AIEngine.StepProposal proposal, Map<UUID, Asset> assets) {
        UUID asset = proposal.assetId();
        String path = PortablePath.of(assets.get(asset).relativePath());
        String why = proposal.reason().map(reason -> " (AI: " + reason + ")").orElse(" (suggested by AI)");
        Instant now = Instant.now();
        return switch (proposal.kind()) {
            case CONVERT -> {
                Map<String, String> values = new java.util.LinkedHashMap<>();
                String format = io.github.aiassetmigrate.conversion.Converters.normalizeFormat(proposal.values().get("format"));
                values.put("format", format == null ? proposal.values().get("format") : format);
                if (proposal.values().containsKey("background")) values.put("background", proposal.values().get("background"));
                yield new BlockerFixes.Fix("AI_CONVERSION", Optional.of(asset), "Convert " + path + " to " + values.get("format")
                        + (values.containsKey("background") ? " on a " + values.get("background") + " background" : "") + why,
                        List.of(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.CONVERSION,
                                ReviewedChange.Decision.ACCEPTED, values, now)), Set.of(), BlockerFixes.Source.AI, false);
            }
            case EXCLUDE -> new BlockerFixes.Fix("AI_EXCLUSION", Optional.of(asset), "Leave " + path + " out of the migration" + why,
                    List.of(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED,
                            Map.of("reason", "Left out on an AI suggestion" + proposal.reason().map(r -> ": " + r).orElse("")), now)),
                    Set.of(), BlockerFixes.Source.AI, false);
            case FIX_REFERENCE -> {
                UUID target = UUID.fromString(proposal.values().get("targetAssetId"));
                String reference = proposal.values().get("reference");
                yield new BlockerFixes.Fix("AI_REFERENCE_FIX", Optional.of(asset), "Point '" + reference + "' in " + path + " to "
                        + PortablePath.of(assets.get(target).relativePath()) + why,
                        List.of(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.RELATIONSHIP, ReviewedChange.Decision.ACCEPTED,
                                Map.of("targetAssetId", target.toString(), "reference", reference), now)), Set.of(), BlockerFixes.Source.AI, false);
            }
        };
    }

    /**
     * The program's checks for proposed decisions. A fix is dropped, with the reason, when a decision breaks a review
     * rule, leaves out a file that nothing blocks, would make the organization preview block (a destination clash, a
     * reference to a file left out), does not resolve the reference it corrects, or fails the converter's own check
     * on the exact input the run would use. Fixes that change nothing are left out silently, and of two fixes for the
     * same decision only the first is kept. Conversion warnings (for example GLB needing glTFast in Unity) are added
     * to the description so the user sees them before accepting.
     *
     * <p>Each fix is checked on top of the fixes that passed before it, so a conversion that works only once a
     * reference fix in the same list is applied passes, and its description names the fix it needs. Fix blockers'
     * own fixes that leave files out of the scan are passed through unchanged.
     */
    private BlockerFixes.Proposal check(PlanState state, Optional<TargetProfile> target, List<BlockerFixes.Fix> candidates) {
        InventorySnapshot inventory = state.inventory();
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        Set<String> currentPreview = new HashSet<>();
        state.preview().findings().forEach(finding -> currentPreview.add(previewKey(finding)));
        Map<UUID, Set<String>> blockers = new HashMap<>();
        for (Finding finding : state.checks()) {
            if (finding.severity() == Finding.Severity.ERROR || finding.severity() == Finding.Severity.UNKNOWN) {
                finding.assetId().ifPresent(asset -> blockers.computeIfAbsent(asset, ignored -> new HashSet<>()).add(finding.code()));
            }
        }
        state.preview().findings().forEach(finding -> finding.assetId()
                .ifPresent(asset -> blockers.computeIfAbsent(asset, ignored -> new HashSet<>()).add(finding.code())));
        Set<String> broken = new HashSet<>();
        for (AssetReference reference : state.references().references()) {
            if (reference.resolutionStatus() != AssetReference.ResolutionStatus.RESOLVED) {
                broken.add(reference.ownerAssetId() + "\u0000" + reference.originalText());
            }
        }
        Set<String> currentConversionErrors = new HashSet<>();
        if (target.isPresent()) {
            for (Finding finding : state.checks()) {
                if (finding.code().equals("CONVERSION_CHECK_FAILED")) currentConversionErrors.add(finding.assetId() + "|" + finding.message());
            }
        }
        // Simulates the preview and conversion checks for {@code effective} on top of {@code base}; returns the problem, if any.
        record Outcome(Optional<String> problem, List<String> notes) {}
        java.util.function.BiFunction<List<ReviewedChange>, List<ReviewedChange>, Outcome> simulate = (base, effective) -> {
            List<ReviewedChange> merged = merge(merge(state.saved(), base), effective);
            OrganizationPreview.Result simulated = preview(inventory, state.references(), merged);
            Optional<OrganizationPreview.Finding> blocker = simulated.findings().stream()
                    .filter(finding -> !currentPreview.contains(previewKey(finding))).findFirst();
            if (blocker.isPresent()) {
                return new Outcome(Optional.of("it would block the plan (" + blocker.get().assetId().map(assets::get)
                        .map(asset -> PortablePath.of(asset.relativePath()) + ": ").orElse("") + blocker.get().message() + ")"), List.of());
            }
            for (ReviewedChange change : effective) {
                String reference = change.values().get("reference");
                if (reference != null && simulated.findings().stream().anyMatch(finding -> finding.code().equals("UNRESOLVED_REFERENCE")
                        && finding.assetId().equals(Optional.of(change.assetId()))
                        && finding.message().equals(OrganizationPreview.UNRESOLVED_PREFIX + reference))) {
                    return new Outcome(Optional.of("the reference would still not resolve"), List.of());
                }
            }
            List<String> notes = new ArrayList<>();
            if (target.isPresent() && effective.stream().anyMatch(change -> change.kind() == ReviewedChange.Kind.CONVERSION)) {
                Set<UUID> converted = new HashSet<>();
                effective.stream().filter(change -> change.kind() == ReviewedChange.Kind.CONVERSION).forEach(change -> converted.add(change.assetId()));
                for (Finding finding : conversionChecks(inventory, simulated, target.get())) {
                    if (finding.severity() == Finding.Severity.ERROR
                            && !currentConversionErrors.contains(finding.assetId() + "|" + finding.message())) {
                        return new Outcome(Optional.of("the converter's check fails: " + finding.message()), List.of());
                    }
                    if (finding.severity() == Finding.Severity.WARNING && finding.assetId().filter(converted::contains).isPresent()
                            && !finding.code().equals("MODEL_CONVERSION_FIDELITY")) {
                        notes.add(finding.message());
                    }
                }
            }
            return new Outcome(Optional.empty(), notes);
        };
        List<BlockerFixes.Fix> passed = new ArrayList<>();
        List<List<ReviewedChange>> passedChanges = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Map<String, Map<String, String>> taken = new HashMap<>();
        Set<Path> leftOut = new HashSet<>();
        // Conversions last: they are the fixes most likely to depend on another one (a reference fix) in the list.
        List<BlockerFixes.Fix> ordered = candidates.stream().sorted(java.util.Comparator.comparingInt(fix -> fix.changes().stream()
                .anyMatch(change -> change.kind() == ReviewedChange.Kind.CONVERSION) ? 1 : 0)).toList();
        for (BlockerFixes.Fix fix : ordered) {
            String label = fix.description();
            if (!fix.leaveOut().isEmpty()) {
                if (fix.source() != BlockerFixes.Source.RULES || !fix.changes().isEmpty()) {
                    dropped.add(label + ": leaving files out of the scan is only offered by Fix blockers");
                } else if (leftOut.addAll(fix.leaveOut())) {
                    passed.add(fix); // Fix blockers' own rescan fix; applying it rescans and carries decisions over.
                    passedChanges.add(List.of());
                }
                continue;
            }
            String problem = null;
            List<ReviewedChange> effective = new ArrayList<>();
            for (ReviewedChange change : fix.changes()) {
                try {
                    validateChange(assets, change);
                } catch (IllegalArgumentException | UnsupportedOperationException invalid) {
                    problem = invalid.getMessage();
                    break;
                }
                if (state.saved().stream().anyMatch(saved -> saved.assetId().equals(change.assetId()) && saved.kind() == change.kind()
                        && saved.decision() == change.decision() && saved.values().equals(change.values()))) continue;
                String reference = change.values().get("reference");
                if ((change.kind() == ReviewedChange.Kind.RELATIONSHIP || change.kind() == ReviewedChange.Kind.MATERIAL_MAPPING)
                        && reference != null && !broken.contains(change.assetId() + "\u0000" + reference)) continue;
                if (change.kind() == ReviewedChange.Kind.EXCLUSION && change.decision() == ReviewedChange.Decision.ACCEPTED
                        && blockers.getOrDefault(change.assetId(), Set.of()).isEmpty()) {
                    problem = "nothing blocks " + PortablePath.of(assets.get(change.assetId()).relativePath())
                            + ", so leaving it out would lose a usable file";
                    break;
                }
                effective.add(change);
            }
            if (problem != null) {
                dropped.add(label + ": " + problem);
                continue;
            }
            if (effective.isEmpty()) continue; // nothing would change
            Set<String> slots = new HashSet<>();
            effective.forEach(change -> slots.add(slot(change)));
            // The same decision from two sources is offered once (a reason may differ); a different one for the slot is dropped.
            if (effective.stream().allMatch(change -> withoutReason(change.values()).equals(
                    Optional.ofNullable(taken.get(slot(change))).map(DefaultAssetManager::withoutReason).orElse(null)))) continue;
            if (slots.stream().anyMatch(taken::containsKey)) {
                dropped.add(label + ": another suggestion already decides this for the same file");
                continue;
            }
            Outcome alone = simulate.apply(List.of(), effective);
            Outcome outcome = alone;
            String needs = null;
            if (alone.problem().isPresent() && !passedChanges.isEmpty()) {
                // It may only work together with fixes that passed before it: find the one it needs, else all of them.
                List<ReviewedChange> before = passedChanges.stream().flatMap(List::stream).toList();
                Outcome together = simulate.apply(before, effective);
                if (together.problem().isEmpty()) {
                    outcome = together;
                    needs = "the decisions listed before it";
                    for (int i = 0; i < passedChanges.size(); i++) {
                        if (passedChanges.get(i).isEmpty()) continue;
                        Outcome withOne = simulate.apply(passedChanges.get(i), effective);
                        if (withOne.problem().isEmpty()) {
                            needs = "\"" + passed.get(i).description() + "\"";
                            outcome = withOne;
                            break;
                        }
                    }
                }
            }
            if (outcome.problem().isPresent()) {
                dropped.add(label + ": " + outcome.problem().get());
                continue;
            }
            effective.forEach(change -> taken.put(slot(change), change.values()));
            // A fix checked before (for example plan suggestions re-checked in the agent's bundle) already carries its notes.
            boolean annotated = label.contains(". Works only together with ") || label.contains(". Note: ");
            String description = annotated ? label : label + (needs == null ? "" : ". Works only together with " + needs)
                    + (outcome.notes().isEmpty() ? "" : ". Note: " + String.join(" ", outcome.notes()));
            passed.add(description.equals(label) ? fix : new BlockerFixes.Fix(fix.code(), fix.assetId(), description,
                    fix.changes(), fix.leaveOut(), fix.source(), fix.recommended()));
            passedChanges.add(effective);
        }
        return new BlockerFixes.Proposal(inventory.project().id(), inventory.project().inventoryRevision(), passed, dropped);
    }

    private static Map<String, String> withoutReason(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        copy.remove("reason");
        return copy;
    }

    private static String previewKey(OrganizationPreview.Finding finding) {
        return finding.code() + "|" + finding.assetId().map(UUID::toString).orElse("") + "|" + finding.message();
    }

    /** The decision a change occupies, as {@link #merge} replaces them. */
    private static String slot(ReviewedChange change) {
        String detail = switch (change.kind()) {
            case RELATIONSHIP -> change.values().containsKey("reference") ? "ref:" + change.values().get("reference")
                    : "target:" + change.values().get("targetAssetId");
            case MATERIAL_MAPPING -> "ref:" + change.values().get("reference");
            default -> "";
        };
        return change.assetId() + "|" + change.kind() + "|" + detail;
    }

    /** AI plan suggestions as non-blocking plan findings, each saying how to apply it. */
    private static List<Finding> suggestionFindings(PlanSuggestions suggestions) {
        List<Finding> findings = new ArrayList<>(suggestions.findings());
        for (BlockerFixes.Fix fix : suggestions.proposal().fixes()) {
            String review = fix.changes().stream().map(change -> "review --asset " + change.assetId() + " --kind " + change.kind()
                    + " --decision " + change.decision() + new java.util.TreeMap<>(change.values()).entrySet().stream()
                    .map(e -> " --value " + e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining()))
                    .collect(java.util.stream.Collectors.joining("; "));
            findings.add(new Finding(UUID.randomUUID(), fix.assetId(), Optional.empty(), Finding.Severity.INFO, "AI_PLAN_PROPOSAL",
                    "AI suggests: " + fix.description() + ". Not applied: accept it under AI suggestions in the Plan step, or save it ("
                            + review + ") and create a new plan"));
        }
        for (String line : suggestions.proposal().unfixable()) {
            findings.add(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(), Finding.Severity.INFO,
                    "AI_PLAN_PROPOSAL_DROPPED", "Dropped by the program's checks: " + line));
        }
        return findings;
    }

    /**
     * Facts measured from the files for AI context, never their contents: whether each PNG or JPEG texture has
     * transparency. Textures that cannot be read or decoded get no trait (their plan findings say why).
     */
    private static Map<UUID, Set<String>> traits(InventorySnapshot inventory) {
        Map<UUID, Set<String>> traits = new HashMap<>();
        Path root = inventory.project().sourceRoot();
        int read = 0;
        for (Asset asset : inventory.project().assets()) {
            if (asset.type() != Asset.Type.TEXTURE || read >= io.github.aiassetmigrate.ai.AIEngine.MAX_ASSETS) continue;
            String format = asset.metadata().getOrDefault(Asset.FORMAT, "");
            if (!Set.of("png", "jpg", "jpeg").contains(format)) continue;
            read++;
            byte[] bytes;
            try { bytes = verifiedSource(root, asset); }
            catch (IOException unreadable) { continue; }
            io.github.aiassetmigrate.conversion.TextureConverter.transparency(bytes)
                    .ifPresent(transparent -> traits.put(asset.id(), Set.of(transparent ? "transparent" : "opaque")));
        }
        return traits;
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
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        // An FBX the reader could not read (its fingerprint matched) or too large to read was acknowledged or
        // excluded before approval, as uninspected FBX always were; a changed source still stops the run.
        java.util.function.Predicate<MaterialAnalysis.FileInspection> acceptable = f -> f.status() == MaterialAnalysis.Inspection.VERIFIED
                || (MaterialAnalysis.fbx(assets.get(f.assetId())) && (f.status() == MaterialAnalysis.Inspection.UNREADABLE
                        || f.status() == MaterialAnalysis.Inspection.BUDGET_EXCEEDED));
        if (analysis.truncated() || !analysis.files().stream().allMatch(acceptable)) {
            throw new IOException("Reference inspection is incomplete or source files changed");
        }
        return new InventorySnapshot(inventory.schemaVersion(), inventory.inspectionVersion(), inventory.project(),
                inventory.findings(), inventory.scanStatus(), inventory.scannedAt(), inventory.pathOutcomes(), analysis.references());
    }
    @Override public Report validate(UUID runId) {
        return validateRun(runId, Optional.empty());
    }

    @Override public Report validate(UUID runId, io.github.aiassetmigrate.ai.LLMClient assistant) {
        Objects.requireNonNull(assistant, "assistant");
        return validateRun(runId, Optional.of(assistant));
    }

    /**
     * Checks outputs under the project lock, so no migration can run meanwhile; the optional AI
     * summary is requested after the lock is released and cannot alter the computed result.
     */
    private Report validateRun(UUID runId, Optional<io.github.aiassetmigrate.ai.LLMClient> assistant) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId");
        try {
            var plans = new FileMigrationPlanRepository(stateDirectory);
            var found = plans.findRun(runId).orElseThrow(() -> new IOException("Run not found: " + runId));
            MigrationPlan plan = found.plan().plan();
            UUID project = plan.projectId();
            Path sourceRoot = repository.loadLatest(project)
                    .orElseThrow(() -> new IOException("Project not found: " + project)).project().sourceRoot();
            InventorySnapshot inventory = repository.loadRevision(project, plan.inventoryRevision());
            validatePaths(sourceRoot, project);
            MigrationRun run;
            OutputValidator.Result result;
            try (var ignored = repository.beginWrite(project, sourceRoot)) {
                run = plans.loadRun(plan.id());
                if (!run.id().equals(runId)) throw new IOException("Run record changed during validation");
                result = OutputValidator.validate(plan, run, inventory);
            }
            List<Finding> findings = new ArrayList<>(result.findings());
            Optional<String> aiSummary = Optional.empty();
            if (assistant.isPresent()) {
                var summary = new io.github.aiassetmigrate.ai.AIEngine(assistant.get())
                        .summarizeValidation(result.status(), run.status(), result.counts(), result.findings());
                findings.addAll(summary.findings());
                aiSummary = summary.summary();
            }
            Report report = new Report(UUID.randomUUID(), project, plan.id(), run.id(), run.status(), result.status(),
                    findings, result.summary(), aiSummary, Instant.now());
            validatePaths(sourceRoot, project);
            try (Publisher publisher = acquireWriter(project)) {
                publisher.publish(LogEvent.Level.INFO, "Saving validation report " + report.id() + " for run " + runId);
                try (var ignored = repository.beginWrite(project, sourceRoot)) {
                    new FileReportRepository(stateDirectory).save(report);
                }
                try {
                    publisher.publish(report.validationStatus() == Report.ValidationStatus.FAILED ? LogEvent.Level.ERROR
                            : report.validationStatus() == Report.ValidationStatus.PASSED ? LogEvent.Level.INFO : LogEvent.Level.WARNING,
                            "Validation report " + report.id() + " saved: " + report.validationStatus());
                } catch (IOException e) {
                    throw new IOException("Validation report " + report.id() + " was saved, but completion logging failed", e);
                }
            }
            return report;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot validate run " + runId + ": " + e.getMessage(), e);
        }
    }

    @Override public List<Report> history(UUID id) {
        ensureOpen();
        Objects.requireNonNull(id, "projectId");
        InventorySnapshot snapshot = loadInventory(id);
        try {
            validatePaths(snapshot.project().sourceRoot(), id);
            return new FileReportRepository(stateDirectory).list(id);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load validation history for project " + id + ": " + e.getMessage(), e);
        }
    }
    @Override public CommandResult command(UUID id, String request) {
        ensureOpen();
        requireRequest(request);
        loadInventory(id);
        return new CommandResult("Natural-language commands need a connected AI account ('auth connect'); no action was taken.",
                Optional.empty(), List.of(new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(),
                        Finding.Severity.WARNING, "AI_UNAVAILABLE", "No model was supplied for this request")));
    }

    @Override public CommandResult command(UUID id, String request, io.github.aiassetmigrate.ai.LLMClient assistant) {
        ensureOpen();
        Objects.requireNonNull(assistant, "assistant");
        requireRequest(request);
        InventorySnapshot inventory = loadInventory(id);
        Map<String, Integer> types = new HashMap<>();
        inventory.project().assets().forEach(asset -> types.merge(asset.type().name(), 1, Integer::sum));
        var context = new io.github.aiassetmigrate.ai.AIEngine.CommandContext(id, inventory.project().inventoryRevision(),
                types, supportedTargets());
        var interpretation = new io.github.aiassetmigrate.ai.AIEngine(assistant).interpret(request, context);
        if (interpretation.intent().isEmpty()) {
            return new CommandResult("The request could not be interpreted; no action was taken. Rephrase it or use the explicit commands.",
                    Optional.empty(), interpretation.findings());
        }
        return new CommandDispatcher(this, assistant, request).dispatch(id, interpretation.intent().get(), interpretation.findings());
    }

    private static void requireRequest(String request) {
        Objects.requireNonNull(request, "request");
        if (request.isBlank() || request.length() > io.github.aiassetmigrate.ai.AIEngine.MAX_REQUEST_LENGTH) {
            throw new IllegalArgumentException("Request must be 1 to " + io.github.aiassetmigrate.ai.AIEngine.MAX_REQUEST_LENGTH + " characters");
        }
    }
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
