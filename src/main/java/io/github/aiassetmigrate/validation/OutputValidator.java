package io.github.aiassetmigrate.validation;

import io.github.aiassetmigrate.analysis.MaterialAnalysis;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationCommand;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.PortablePath;
import io.github.aiassetmigrate.domain.Project;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import io.github.aiassetmigrate.domain.StepStatus;
import io.github.aiassetmigrate.inventory.SafeStoragePaths;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F08 output validation. Read-only: checks each output a run recorded for existence, safe
 * path, readability, SHA-256 against what was published, format (PNG/JPEG decode, OBJ/MTL
 * text) and that OBJ/MTL references resolve inside the output root. FBX and other formats that
 * cannot be inspected are reported as UNKNOWN, never as passed. Outputs on disk are untrusted.
 */
public final class OutputValidator {
    /** Images larger than this many bytes are not decoded; their format check is UNKNOWN. */
    public static final int MAX_DECODE_BYTES = 64 * 1024 * 1024;
    public static final long MAX_DECODE_PIXELS = 64L * 1024 * 1024;
    private static final Pattern RECORDED_HASH = Pattern.compile("output SHA-256 ([0-9a-f]{64})");

    /** Counts are per planned output. {@code recorded} outputs are those the run says it produced. */
    public record Counts(int planned, int recorded, int passed, int failed, int unchecked, int notProduced) {}

    public record Result(Report.ValidationStatus status, List<Finding> findings, String summary, Counts counts) {
        public Result {
            Objects.requireNonNull(status, "status");
            findings = List.copyOf(findings);
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(counts, "counts");
        }
    }

    private OutputValidator() { }

    private static final class Output {
        final MigrationCommand command;
        final Asset asset;
        final String shown;
        final Path file;
        final List<Finding> findings = new ArrayList<>();
        final List<String> passed = new ArrayList<>();
        String actualHash;

        Output(MigrationCommand command, Asset asset, Path file) {
            this.command = command;
            this.asset = asset;
            this.shown = PortablePath.of(command.relativeOutputPath());
            this.file = file;
        }

        void add(Finding.Severity severity, String code, String message) {
            findings.add(finding(asset, severity, code, message));
        }

        boolean has(Finding.Severity severity) {
            return findings.stream().anyMatch(f -> f.severity() == severity);
        }
    }

    /**
     * @param plan the approved plan the run executed
     * @param run the run record; an uncompleted run's outputs are checked if present on disk
     * @param inventory the inventory revision the plan was built from
     */
    public static Result validate(MigrationPlan plan, MigrationRun run, InventorySnapshot inventory) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inventory, "inventory");
        if (!run.planId().equals(plan.id()) || !run.projectId().equals(plan.projectId())
                || run.planVersion() != plan.version() || !run.outputRoot().equals(plan.outputRoot())) {
            throw new IllegalArgumentException("Run does not belong to the plan");
        }
        if (!inventory.project().id().equals(plan.projectId())
                || inventory.project().inventoryRevision() != plan.inventoryRevision()) {
            throw new IllegalArgumentException("Inventory revision does not match the plan");
        }
        Map<UUID, Asset> assets = new HashMap<>();
        inventory.project().assets().forEach(asset -> assets.put(asset.id(), asset));
        Path root = run.outputRoot().toAbsolutePath().normalize();
        boolean completionRecorded = run.completedAt().isPresent();
        List<Finding> general = new ArrayList<>();
        List<Output> recorded = new ArrayList<>();
        int notProduced = 0, failed = 0;

        if (!completionRecorded) {
            general.add(finding(null, Finding.Severity.UNKNOWN, "RUN_NOT_COMPLETED",
                    "The run did not record completion; outputs found on disk were checked, but step results are unknown"));
        }
        for (MigrationCommand command : plan.commands()) {
            Asset asset = assets.get(command.assetId());
            if (asset == null) {
                general.add(finding(null, Finding.Severity.ERROR, "PLAN_ASSET_UNKNOWN",
                        "Planned asset " + command.assetId() + " is not in the plan's inventory revision"));
                failed++;
                continue;
            }
            Path file = root.resolve(command.relativeOutputPath()).normalize();
            Output output = new Output(command, asset, file);
            StepStatus step = run.stepStatuses().get(command.id());
            if (!file.startsWith(root) || file.equals(root)) {
                output.add(Finding.Severity.ERROR, "OUTPUT_UNSAFE_PATH", "Planned output " + output.shown + " is outside the output root");
                recorded.add(output);
                continue;
            }
            if (completionRecorded && step != StepStatus.SUCCESS) {
                notProduced++;
                general.add(finding(asset, Finding.Severity.WARNING, "OUTPUT_NOT_PRODUCED",
                        "Step " + (step == null ? "missing" : step) + "; the run recorded no output for " + output.shown));
                continue;
            }
            if (!completionRecorded && !present(file)) {
                notProduced++;
                general.add(finding(asset, Finding.Severity.WARNING, "OUTPUT_NOT_PRODUCED",
                        "The run did not record completion and " + output.shown + " was not found"));
                continue;
            }
            recorded.add(output);
            checkFile(output, run);
        }
        checkReferences(recorded, plan, root);

        List<Finding> findings = new ArrayList<>(general);
        int passed = 0, unchecked = 0;
        for (Output output : recorded) {
            findings.addAll(output.findings);
            if (output.has(Finding.Severity.ERROR)) failed++;
            else if (output.has(Finding.Severity.UNKNOWN)) unchecked++;
            else {
                passed++;
                findings.add(finding(output.asset, Finding.Severity.INFO, "OUTPUT_VERIFIED",
                        output.shown + ": " + String.join("; ", output.passed)));
            }
        }
        int planned = plan.commands().size();
        Counts counts = new Counts(planned, recorded.size(), passed, failed, unchecked, notProduced);
        Report.ValidationStatus status;
        if (recorded.isEmpty() && failed == 0) status = Report.ValidationStatus.NOT_VALIDATED;
        else if (findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR)) status = Report.ValidationStatus.FAILED;
        else if (findings.stream().anyMatch(f -> f.severity() == Finding.Severity.UNKNOWN)
                || notProduced > 0 || run.status() != RunStatus.SUCCESS) status = Report.ValidationStatus.INCOMPLETE;
        else status = Report.ValidationStatus.PASSED;
        return new Result(status, findings, summary(status, run, counts, completionRecorded), counts);
    }

    /** Deterministic one-paragraph summary; also the fallback when no AI summary is available. */
    static String summary(Report.ValidationStatus status, MigrationRun run, Counts counts, boolean completionRecorded) {
        StringBuilder text = new StringBuilder("Validation ").append(status).append(" for run ").append(run.id())
                .append(" (run status ").append(run.status()).append("). ");
        if (status == Report.ValidationStatus.NOT_VALIDATED) {
            text.append(counts.planned() == 0 ? "The plan had no outputs; nothing was validated."
                    : "The run recorded no outputs for its " + counts.planned() + " planned output(s); nothing was validated.");
            return text.toString();
        }
        text.append(counts.planned()).append(" planned output(s): ").append(counts.passed()).append(" passed, ")
                .append(counts.failed()).append(" failed, ").append(counts.unchecked()).append(" not fully checked, ")
                .append(counts.notProduced()).append(" not produced.");
        if (!completionRecorded) text.append(" The run did not record completion, so step results are unknown.");
        if (counts.unchecked() > 0) text.append(" Not fully checked is not passed: some formats (such as FBX) cannot be inspected.");
        return text.toString();
    }

    private static void checkFile(Output output, MigrationRun run) {
        try {
            SafeStoragePaths.validateExistingPath(output.file);
        } catch (IOException e) {
            output.add(Finding.Severity.ERROR, "OUTPUT_UNSAFE_PATH", "Output " + output.shown + " is a link or unsafe path");
            return;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(output.file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            output.add(Finding.Severity.ERROR, "OUTPUT_MISSING", "Recorded output " + output.shown + " does not exist");
            return;
        } catch (IOException e) {
            output.add(Finding.Severity.ERROR, "OUTPUT_UNREADABLE", "Cannot read attributes of " + output.shown + ": " + message(e));
            return;
        }
        if (!attributes.isRegularFile()) {
            output.add(Finding.Severity.ERROR, "OUTPUT_NOT_FILE", "Recorded output " + output.shown + " is not a regular file");
            return;
        }
        output.passed.add("exists");
        String extension = extension(output.shown);
        boolean image = extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg");
        boolean glb = extension.equals("glb");
        byte[] bytes = null;
        try {
            MessageDigest digest = digest();
            ByteArrayOutputStream kept = (image || glb) && attributes.size() <= MAX_DECODE_BYTES ? new ByteArrayOutputStream() : null;
            long total = 0;
            try (InputStream in = Files.newInputStream(output.file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) {
                    digest.update(buffer, 0, n);
                    total += n;
                    if (kept != null && total <= MAX_DECODE_BYTES) kept.write(buffer, 0, n);
                }
            }
            output.actualHash = HexFormat.of().formatHex(digest.digest());
            if (kept != null && total <= MAX_DECODE_BYTES) bytes = kept.toByteArray();
        } catch (IOException e) {
            output.add(Finding.Severity.ERROR, "OUTPUT_UNREADABLE", "Cannot read " + output.shown + ": " + message(e));
            return;
        }
        output.passed.add("readable");
        Optional<String> expected = expectedHash(output.command, output.asset, run);
        if (expected.isEmpty()) {
            output.add(Finding.Severity.UNKNOWN, "OUTPUT_HASH_UNKNOWN", "No published SHA-256 was recorded for "
                    + output.shown + "; its content cannot be compared with what the run wrote");
        } else if (!expected.get().equals(output.actualHash)) {
            output.add(Finding.Severity.ERROR, "OUTPUT_CHANGED", output.shown
                    + " differs from what the run published (SHA-256 mismatch); it was modified or replaced");
        } else {
            output.passed.add("SHA-256 matches what the run published");
        }
        if (image) checkImage(output, extension, bytes);
        else if (glb) checkGlb(output, bytes);
        else if (!text(output)) { // OBJ/MTL text and references are checked together in checkReferences.
            output.add(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", output.shown + ": "
                    + (extension.equals("fbx") ? "FBX contents cannot be inspected" : "no format check exists for ." + extension)
                    + "; only existence, readability and hash were checked");
        }
    }

    private static void checkImage(Output output, String extension, byte[] bytes) {
        if (bytes == null) {
            output.add(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", output.shown + " is too large to decode");
            return;
        }
        ImageCheck check = inspectImage(bytes, extension);
        if (check.severity() == null) output.passed.add(check.message());
        else output.add(check.severity(), check.severity() == Finding.Severity.ERROR ? "OUTPUT_FORMAT_INVALID"
                : "OUTPUT_FORMAT_UNCHECKED", output.shown + " " + check.message());
    }

    /** Result of decoding one image: a null severity means it passed and {@code message} says how. */
    record ImageCheck(Finding.Severity severity, String message) {}

    /** Decodes PNG or JPEG bytes with the JDK reader; also used for images embedded in GLB files. */
    static ImageCheck inspectImage(byte[] bytes, String extension) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) return new ImageCheck(Finding.Severity.ERROR, "is not a readable image");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean png = format.equals("png");
                boolean jpeg = format.equals("jpeg") || format.equals("jpg");
                if (extension.equals("png") ? !png : !jpeg) {
                    return new ImageCheck(Finding.Severity.ERROR, "contains " + format + " data, not " + extension.toUpperCase(Locale.ROOT));
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels <= 0) return new ImageCheck(Finding.Severity.ERROR, "has invalid dimensions");
                if (pixels > MAX_DECODE_PIXELS) {
                    return new ImageCheck(Finding.Severity.UNKNOWN, "(" + width + "x" + height
                            + ") exceeds the decode limit; only its header was checked");
                }
                reader.read(0);
                return new ImageCheck(null, (png ? "PNG" : "JPEG") + " decodes (" + width + "x" + height + ")");
            } finally {
                reader.dispose();
            }
        } catch (IIOException e) {
            if (String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("unsupported")) {
                return new ImageCheck(Finding.Severity.UNKNOWN, "uses an image variant the JDK reader does not support: " + message(e));
            }
            return new ImageCheck(Finding.Severity.ERROR, "could not be decoded: " + message(e));
        } catch (IOException | RuntimeException e) {
            return new ImageCheck(Finding.Severity.ERROR, "could not be decoded: " + message(e));
        }
    }

    private static void checkGlb(Output output, byte[] bytes) {
        if (bytes == null) {
            output.add(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED", output.shown + " is too large to inspect");
            return;
        }
        GlbInspection.Result result = GlbInspection.inspect(bytes);
        for (GlbInspection.Problem problem : result.problems()) {
            output.add(problem.severity(), problem.code(), output.shown + ": " + problem.message());
        }
        if (result.problems().isEmpty()) output.passed.add(result.summary());
    }

    /**
     * Parses produced OBJ/MTL outputs as an inventory of this run's outputs, so references must
     * resolve to files the run produced, at their output paths, with exact case.
     */
    private static void checkReferences(List<Output> recorded, MigrationPlan plan, Path root) {
        Map<UUID, Output> byAsset = new LinkedHashMap<>();
        List<Asset> produced = new ArrayList<>();
        for (Output output : recorded) {
            if (output.actualHash == null) continue;
            byAsset.put(output.asset.id(), output);
            produced.add(new Asset(output.asset.id(), output.command.relativeOutputPath(), output.asset.type(),
                    output.actualHash, Map.of(Asset.FORMAT, extension(output.shown))));
        }
        List<Output> inspectable = byAsset.values().stream().filter(OutputValidator::text).toList();
        if (inspectable.isEmpty()) return;
        Project project = new Project(plan.projectId(), root, 1, produced, Instant.EPOCH);
        InventorySnapshot outputs = new InventorySnapshot(InventorySnapshot.CURRENT_SCHEMA_VERSION,
                InventorySnapshot.CURRENT_INSPECTION_VERSION, project, List.of(), InventorySnapshot.ScanStatus.COMPLETE,
                Instant.EPOCH, List.of(), List.of());
        MaterialAnalysis.Result analysis = MaterialAnalysis.analyze(outputs, (asset, cap) -> {
            Path file = root.resolve(asset.relativePath());
            SafeStoragePaths.requireRegularFile(file);
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return in.readNBytes(cap + 1);
            }
        }, MaterialAnalysis.Limits.defaults());
        Map<UUID, MaterialAnalysis.Inspection> inspections = new HashMap<>();
        analysis.files().forEach(file -> inspections.put(file.assetId(), file.status()));
        Map<UUID, Integer> resolved = new HashMap<>();
        for (AssetReference reference : analysis.references()) {
            Output owner = byAsset.get(reference.ownerAssetId());
            if (owner == null) continue;
            if (reference.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED) {
                resolved.merge(owner.asset.id(), 1, Integer::sum);
                continue;
            }
            String kind = reference.kind() == AssetReference.Kind.MATERIAL_LIBRARY ? "Material library" : "Texture";
            Optional<Path> target = reference.normalizedTargetPath();
            if (reference.resolutionStatus() == AssetReference.ResolutionStatus.MISSING && target.isPresent()
                    && existsExactly(root, target.get())) {
                owner.add(Finding.Severity.UNKNOWN, "OUTPUT_REFERENCE_OUTSIDE_RUN", kind + " reference '" + reference.originalText()
                        + "' in " + owner.shown + " resolves to " + PortablePath.of(target.get())
                        + ", which this run did not produce; that file was not validated");
            } else {
                owner.add(Finding.Severity.ERROR, "OUTPUT_REFERENCE_UNRESOLVED", kind + " reference '" + reference.originalText()
                        + "' in " + owner.shown + " does not resolve inside the output (" + reference.resolutionStatus() + ")");
            }
        }
        for (Output output : inspectable) {
            MaterialAnalysis.Inspection inspection = inspections.getOrDefault(output.asset.id(), MaterialAnalysis.Inspection.UNAVAILABLE);
            switch (inspection) {
                case VERIFIED -> {
                    if (analysis.truncated()) {
                        output.add(Finding.Severity.UNKNOWN, "OUTPUT_REFERENCES_UNCHECKED",
                                "Reference checking exceeded its budget; references in " + output.shown + " were not fully checked");
                    } else if (!output.has(Finding.Severity.ERROR) && !output.has(Finding.Severity.UNKNOWN)) {
                        output.passed.add("valid UTF-8 text; " + resolved.getOrDefault(output.asset.id(), 0)
                                + " reference(s) resolve to outputs of this run");
                    }
                }
                case INVALID_TEXT -> output.add(Finding.Severity.ERROR, "OUTPUT_FORMAT_INVALID", output.shown + " is not valid UTF-8 text");
                case FINGERPRINT_MISMATCH -> output.add(Finding.Severity.ERROR, "OUTPUT_CHANGED", output.shown + " changed during validation");
                case BUDGET_EXCEEDED -> output.add(Finding.Severity.UNKNOWN, "OUTPUT_REFERENCES_UNCHECKED",
                        output.shown + " exceeds the reference inspection budget; its references were not checked");
                case UNAVAILABLE -> output.add(Finding.Severity.ERROR, "OUTPUT_UNREADABLE", output.shown + " could not be read for reference checks");
            }
        }
    }

    static Optional<String> expectedHash(MigrationCommand command, Asset asset, MigrationRun run) {
        if (command.operation() == MigrationCommand.Operation.COPY) return Optional.of(asset.fingerprint());
        String detail = run.stepDetails().get(command.id());
        if (detail == null) return Optional.empty();
        Matcher match = RECORDED_HASH.matcher(detail);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /** True only for a regular file at exactly this relative path (case included) under the root. */
    private static boolean existsExactly(Path root, Path relative) {
        try {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) return false;
            SafeStoragePaths.validateExistingPath(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            Path realRoot = root.toRealPath();
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            return real.startsWith(realRoot) && PortablePath.of(realRoot.relativize(real)).equals(PortablePath.of(relative));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** An OBJ model or MTL material at its output path, which the reference analysis parses. */
    private static boolean text(Output output) {
        String extension = extension(output.shown);
        return (output.asset.type() == Asset.Type.MODEL && extension.equals("obj"))
                || (output.asset.type() == Asset.Type.MATERIAL && extension.equals("mtl"));
    }

    private static boolean present(Path file) {
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static String extension(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static Finding finding(Asset asset, Finding.Severity severity, String code, String message) {
        return new Finding(UUID.randomUUID(), asset == null ? Optional.empty() : Optional.of(asset.id()),
                asset == null ? Optional.empty() : Optional.of(asset.relativePath()), severity, code, message);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
