package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AssetScannerTest {
    @TempDir Path directory;

    @Test
    void emptyRootHasCompleteCoverageWithoutCreatingFiles() throws IOException {
        var result = new AssetScanner().scan(directory);
        assertEquals(directory.toRealPath(), result.sourceRoot());
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        assertTrue(result.assets().isEmpty());
        assertTrue(result.findings().isEmpty());
        assertEquals(List.of(new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.TRAVERSED)),
                result.pathOutcomes());
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void classifiesMixedCaseExtensionsAndIncludesHiddenAndUnicodePaths() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("spaces \u96ea"));
        Files.writeString(nested.resolve("model.OBJ"), "v 0 0 0\n");
        Files.writeString(nested.resolve("material.MtL"), "newmtl name\n");
        Files.write(nested.resolve("model.FbX"), new byte[]{0, 1, 2});
        Files.write(directory.resolve("image.PNG"), new byte[]{3, 4, 5});
        Files.write(directory.resolve("image.JpG"), new byte[]{6, 7, 8});
        Files.write(directory.resolve("image.JPEG"), new byte[]{9, 10, 11});
        Path hidden = Files.write(directory.resolve(".hidden.png"), new byte[]{12});
        if (Files.getFileStore(directory).supportsFileAttributeView("dos")) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }
        var result = new AssetScanner().scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        assertEquals(7, result.assets().size());
        Map<String, Asset> assets = result.assets().stream()
                .collect(Collectors.toMap(asset -> portable(asset.relativePath()), asset -> asset));
        assertEquals(Asset.Type.MODEL, assets.get("spaces \u96ea/model.OBJ").type());
        assertEquals(Asset.Type.MODEL, assets.get("spaces \u96ea/model.FbX").type());
        assertEquals(Asset.Type.MATERIAL, assets.get("spaces \u96ea/material.MtL").type());
        for (String name : List.of("image.PNG", "image.JpG", "image.JPEG", ".hidden.png")) {
            assertEquals(Asset.Type.TEXTURE, assets.get(name).type());
        }
        for (Asset asset : result.assets()) {
            Path file = directory.resolve(asset.relativePath());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
                    asset.fingerprint());
            assertEquals(Long.toString(Files.size(file)), asset.metadata().get(Asset.SIZE_BYTES));
            assertEquals(Files.getLastModifiedTime(file).toInstant().toString(), asset.metadata().get(Asset.MODIFIED_AT));
            assertEquals(Asset.InspectionStatus.NOT_PERFORMED.name(), asset.metadata().get(Asset.INSPECTION_STATUS));
        }
        assertEquals("obj", assets.get("spaces \u96ea/model.OBJ").metadata().get(Asset.FORMAT));
        assertEquals("jpeg", assets.get("image.JPEG").metadata().get(Asset.FORMAT));
        assertSorted(result.assets().stream().map(asset -> portable(asset.relativePath())).toList());
        assertSorted(result.pathOutcomes().stream().map(outcome -> portable(outcome.relativePath())).toList());
    }

    @Test
    void arbitraryImageBytesAreHashedWithoutDecodingOrValidationClaims() throws Exception {
        byte[] bytes = {(byte) 0xff, (byte) 0xfe, 0, 7};
        for (String extension : List.of("png", "jpg", "jpeg")) {
            Files.write(directory.resolve("not-an-image." + extension), bytes);
        }
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var result = new AssetScanner().scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        assertEquals(3, result.assets().size());
        assertTrue(result.findings().isEmpty());
        result.assets().forEach(asset -> {
            assertEquals(expected, asset.fingerprint());
            assertEquals("NOT_PERFORMED", asset.metadata().get(Asset.INSPECTION_STATUS));
            assertFalse(asset.metadata().containsKey("width"));
            assertFalse(asset.metadata().containsKey("height"));
        });
        for (String extension : List.of("png", "jpg", "jpeg")) {
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("not-an-image." + extension)));
        }
    }

    @Test
    void emptyFileUsesStandardSha256WithoutClaimingFormatValidity() throws IOException {
        Files.createFile(directory.resolve("empty.obj"));
        var result = new AssetScanner().scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        assertEquals(1, result.assets().size());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.assets().getFirst().fingerprint());
        assertEquals("NOT_PERFORMED", result.assets().getFirst().metadata().get(Asset.INSPECTION_STATUS));
    }

    @Test
    void unsupportedFilesProduceSortedWarningsAndRetainHealthyAssets() throws IOException {
        Files.writeString(directory.resolve("z.txt"), "unsupported");
        Files.writeString(directory.resolve("a.bin"), "unsupported");
        Files.writeString(directory.resolve("healthy.obj"), "not parsed in step 3");
        var result = new AssetScanner().scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertEquals(List.of(Path.of("healthy.obj")), result.assets().stream().map(Asset::relativePath).toList());
        assertEquals(2, result.findings().size());
        assertEquals(List.of("a.bin", "z.txt"), result.findings().stream()
                .map(finding -> portable(finding.sourceRelativePath().orElseThrow())).toList());
        assertTrue(result.findings().stream().allMatch(finding -> finding.assetId().isEmpty()));
        assertEquals(2, result.pathOutcomes().stream().filter(outcome -> outcome.status() == PathOutcome.Status.SKIPPED).count());
    }

    @Test
    void doesNotExcludeHiddenOrCommonBuildFolderNames() throws IOException {
        for (String folder : List.of(".hidden", "node_modules", "target")) {
            Files.writeString(Files.createDirectory(directory.resolve(folder)).resolve("asset.obj"), "content");
        }
        var result = new AssetScanner().scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        assertEquals(List.of(".hidden/asset.obj", "node_modules/asset.obj", "target/asset.obj"),
                result.assets().stream().map(asset -> portable(asset.relativePath())).toList());
    }

    @Test
    void hashesLargeFileInChunksAndLeavesSourceBytesAndTimesUnchanged() throws Exception {
        Path source = directory.resolve("large.fbx");
        byte[] block = new byte[64 * 1024];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) i;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileChannel channel = FileChannel.open(source, CREATE_NEW, WRITE)) {
            for (int i = 0; i < 128; i++) {
                ByteBuffer buffer = ByteBuffer.wrap(block);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                digest.update(block);
            }
        }
        var before = Files.getLastModifiedTime(source);
        String expected = HexFormat.of().formatHex(digest.digest());
        var result = new AssetScanner().scan(directory);
        assertEquals(1, result.assets().size());
        assertEquals(expected, result.assets().getFirst().fingerprint());
        assertEquals(8L * 1024 * 1024, Files.size(source));
        assertEquals(before, Files.getLastModifiedTime(source));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(source), entries.toList());
        }
    }

    @Test
    void depthLimitMarksUnvisitedCoverageAndRetainsTopLevelFiles() throws IOException {
        Files.writeString(directory.resolve("top.obj"), "top");
        Path deep = Files.createDirectories(directory.resolve("folder/deeper"));
        Files.writeString(deep.resolve("deep.obj"), "deep");
        var limits = new ScanLimits(1, 100, 1024, 1024, 10);
        var result = new AssetScanner(limits).scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertEquals(List.of(Path.of("top.obj")), result.assets().stream().map(Asset::relativePath).toList());
        assertTrue(result.pathOutcomes().stream().anyMatch(outcome -> outcome.kind() == PathOutcome.Kind.DIRECTORY
                && outcome.status() == PathOutcome.Status.UNVISITED));
        assertFalse(result.findings().isEmpty());
    }

    @Test
    void entryLimitBoundsInventoryAndMarksIncompleteDirectoryCoverage() throws IOException {
        for (int i = 0; i < 10; i++) {
            Files.writeString(directory.resolve(i + ".obj"), "content");
        }
        var result = new AssetScanner(new ScanLimits(64, 2, 1024, 1024, 10)).scan(directory);
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertTrue(result.assets().size() <= 2);
        assertTrue(result.pathOutcomes().contains(new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY,
                PathOutcome.Status.UNVISITED)));
        assertFalse(result.findings().isEmpty());
    }

    @Test
    void rejectsMissingAndRegularFileRootsWithoutCreatingAnything() throws IOException {
        Path missing = directory.resolve("missing");
        assertThrows(IOException.class, () -> new AssetScanner().scan(missing));
        assertFalse(Files.exists(missing));
        Path file = Files.writeString(directory.resolve("file.obj"), "unchanged");
        assertThrows(IOException.class, () -> new AssetScanner().scan(file));
        assertEquals("unchanged", Files.readString(file));
    }

    @Test
    void interruptedScanDoesNotReturnSuccessAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> new AssetScanner().scan(directory));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesLimitsAndExposesDocumentedDefaults() {
        assertEquals(new ScanLimits(64, 100_000, 1024 * 1024, 64L * 1024 * 1024, 100_000), ScanLimits.DEFAULTS);
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(-1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScanLimits(1, 1, 1, 1, 0));
        assertThrows(NullPointerException.class, () -> new AssetScanner(null));
        assertDoesNotThrow(() -> new ScanLimits(0, 1, 1, 1, 1));
    }

    @Test
    void scanResultsDefensivelyCopyCollectionsAndScannerCanBeReused() throws IOException {
        Files.writeString(directory.resolve("test.obj"), "data");
        var scanner = new AssetScanner();
        var first = scanner.scan(directory);
        var assets = new ArrayList<>(first.assets());
        var findings = new ArrayList<>(first.findings());
        var outcomes = new ArrayList<>(first.pathOutcomes());
        var copy = new AssetScanner.ScanResult(first.sourceRoot(), assets, findings, first.scanStatus(), outcomes);
        assets.clear();
        findings.clear();
        outcomes.clear();
        assertEquals(first, copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.assets().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.findings().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.pathOutcomes().clear());
        assertThrows(NullPointerException.class, () -> new AssetScanner.ScanResult(null,
                first.assets(), first.findings(), first.scanStatus(), first.pathOutcomes()));
        assertThrows(NullPointerException.class, () -> new AssetScanner.ScanResult(first.sourceRoot(),
                null, first.findings(), first.scanStatus(), first.pathOutcomes()));
        assertThrows(NullPointerException.class, () -> new AssetScanner.ScanResult(first.sourceRoot(),
                first.assets(), null, first.scanStatus(), first.pathOutcomes()));
        assertThrows(NullPointerException.class, () -> new AssetScanner.ScanResult(first.sourceRoot(),
                first.assets(), first.findings(), null, first.pathOutcomes()));
        assertThrows(NullPointerException.class, () -> new AssetScanner.ScanResult(first.sourceRoot(),
                first.assets(), first.findings(), first.scanStatus(), null));
        Files.delete(directory.resolve("test.obj"));
        assertTrue(scanner.scan(directory).assets().isEmpty());
        assertEquals(1, first.assets().size());
    }

    @Test
    void rejectsSymbolicLinkRootAndSkipsLinkedFilesAndDirectories() throws IOException {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("outside.obj"), "do not follow");
        Path alias = directory.resolve("root-alias");
        try {
            Files.createSymbolicLink(alias, root);
            Files.createSymbolicLink(root.resolve("directory-link"), outside);
            Files.createSymbolicLink(root.resolve("file-link.obj"), outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic-link creation unavailable: " + unavailable);
        }
        assertThrows(IOException.class, () -> new AssetScanner().scan(alias));
        var result = new AssetScanner().scan(root);
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertTrue(result.assets().isEmpty());
        assertFalse(result.findings().isEmpty());
        assertEquals("do not follow", Files.readString(outsideFile));
    }

    @Test
    void rejectsWindowsJunctionRootAndSkipsJunctionSubtree() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
                "Junction test requires Windows");
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("outside.obj"), "do not follow");
        Path junction = root.resolve("junction");
        Process process;
        try {
            process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "Windows junction creation unavailable: " + unavailable);
            return;
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            assumeTrue(finished && process.exitValue() == 0, "Windows junction creation unavailable: " + output);
            assertThrows(IOException.class, () -> new AssetScanner().scan(junction));
            var result = new AssetScanner().scan(root);
            assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
            assertTrue(result.assets().isEmpty());
            assertFalse(result.findings().isEmpty());
            assertEquals("do not follow", Files.readString(outsideFile));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static void assertSorted(List<String> values) {
        assertEquals(values.stream().sorted().toList(), values);
    }
}
