package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FormatInspectionTest {
    @TempDir Path root;

    @Test
    void imagesAreHashOnlyWhileOtherFormatsRemainBoundedlyInspected() throws Exception {
        Files.write(root.resolve("image.png"), AssetScannerTest.pngHeader());
        Files.write(root.resolve("image.jpg"), AssetScannerTest.jpegHeader());
        Files.write(root.resolve("image.jpeg"), AssetScannerTest.jpegHeader());
        Files.writeString(root.resolve("model.obj"), "v 0 0 0\n");
        Files.writeString(root.resolve("material.mtl"), "newmtl test\n");
        Files.writeString(root.resolve("model.fbx"), "; FBX 7.4.0 project file\n");
        byte[] binary = "Kaydara FBX Binary  \0\u001a\0\u00e8\u001c\0\0".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(root.resolve("binary.fbx"), binary);
        var result = new AssetScanner().scan(root);
        Map<String, Asset> assets = result.assets().stream().collect(Collectors.toMap(a -> a.relativePath().toString(), a -> a));
        assertEquals(7, assets.size());
        assertEquals(7, result.findings().stream().filter(f -> f.code().equals("INSPECTION_UNVERIFIED")).count());
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
        for (String image : List.of("image.png", "image.jpg", "image.jpeg")) {
            assertEquals("NOT_PERFORMED", assets.get(image).metadata().get(Asset.INSPECTION_STATUS));
            assertEquals("SHA256_STREAM_ONLY", assets.get(image).metadata().get(Asset.INSPECTION_METHOD));
        }
        assertEquals("BASIC_CHECKED", assets.get("model.obj").metadata().get(Asset.INSPECTION_STATUS));
        assertEquals("FBX_BINARY_HEADER", assets.get("binary.fbx").metadata().get(Asset.INSPECTION_METHOD));
        assertEquals("FBX_ASCII_HEADER", assets.get("model.fbx").metadata().get(Asset.INSPECTION_METHOD));
        assertArrayEquals(binary, Files.readAllBytes(root.resolve("binary.fbx")));
        assertTrue(result.pathOutcomes().stream().filter(o -> o.kind() == PathOutcome.Kind.FILE)
                .allMatch(o -> o.status() == PathOutcome.Status.INVENTORIED));
    }

    @Test
    void arbitraryImagesAndUnknownFbxHeadersAreInventoriedWithoutClaimingValidity() throws Exception {
        byte[] badCrc = AssetScannerTest.pngHeader();
        badCrc[32] ^= 1;
        byte[] badDimensions = AssetScannerTest.pngHeader();
        Arrays.fill(badDimensions, 16, 20, (byte) 0);
        Files.write(root.resolve("crc.png"), badCrc);
        Files.write(root.resolve("dimensions.png"), badDimensions);
        Files.write(root.resolve("short.png"), Arrays.copyOf(AssetScannerTest.pngHeader(), 20));
        Files.write(root.resolve("bad.jpg"), new byte[]{(byte) 255, (byte) 216, 0, 1});
        Files.createFile(root.resolve("empty.jpeg"));
        Files.createFile(root.resolve("empty.fbx"));
        Files.write(root.resolve("short.fbx"), "Kaydara FBX Binary  \0\u001a\0".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(root.resolve("ok.obj"), "v 0 0 0\n");
        Files.writeString(root.resolve("unknown.xyz"), "data");
        var result = new AssetScanner().scan(root);
        assertEquals(8, result.assets().size());
        assertTrue(result.assets().stream().anyMatch(asset -> asset.relativePath().equals(Path.of("ok.obj"))));
        assertEquals(0, result.findings().stream().filter(f -> f.code().equals("CORRUPT_FORMAT")).count());
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("INSPECTION_UNKNOWN")).count());
        assertEquals(1, result.findings().stream().filter(f -> f.code().equals("UNSUPPORTED_EXTENSION")).count());
        assertEquals(1, result.pathOutcomes().stream().filter(o -> o.status() == PathOutcome.Status.SKIPPED).count());
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertArrayEquals(badCrc, Files.readAllBytes(root.resolve("crc.png")));
    }

    @Test
    void textBudgetsAreInconclusiveRatherThanCorrupt() throws Exception {
        Files.writeString(root.resolve("long.obj"), "abcdefghij\n");
        Files.writeString(root.resolve("many.mtl"), "a\nb\nc\nd\ne\n");
        var result = new AssetScanner(new ScanLimits(64, 100, 4, 8, 1)).scan(root);
        Map<String, Asset> assets = result.assets().stream().collect(Collectors.toMap(a -> a.relativePath().toString(), a -> a));
        assertEquals("TEXT_LINE_LIMIT", assets.get("long.obj").metadata().get(Asset.INSPECTION_METHOD));
        assertEquals("TEXT_PREFIX_LIMIT", assets.get("many.mtl").metadata().get(Asset.INSPECTION_METHOD));
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("INSPECTION_UNVERIFIED")).count());
        assertEquals(InventorySnapshot.ScanStatus.COMPLETE, result.scanStatus());
    }

    @Test
    void inspectionMetadataAndFindingsRoundTripInExistingSchema() throws Exception {
        Files.write(root.resolve("image.png"), AssetScannerTest.pngHeader());
        var result = new AssetScanner().scan(root);
        Instant now = Instant.now();
        var snapshot = new InventorySnapshot(1, 1, new Project(UUID.randomUUID(), root, 1, result.assets(), now),
                result.findings(), result.scanStatus(), now, result.pathOutcomes(), List.of());
        var codec = new InventoryJsonCodec();
        assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    }
}
