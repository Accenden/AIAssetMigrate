package io.github.aiassetmigrate.conversion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The built-in FBX reader: Assimp for the scene, the node scan for texture file names. */
class FbxReaderTest {
    @BeforeAll static void requireNatives() {
        assumeTrue(FbxReader.available(), "Assimp natives unavailable: " + AssimpModelConverter.unavailableReason());
    }

    @Test void anEmbeddedTextureIsReturnedForDecodingAndIsNotAFileReference() throws Exception {
        var read = FbxReader.read(ModelFixtures.embeddedFbx());
        assertEquals(1, read.meshes());
        assertEquals(List.of(), read.textureFiles());
        assertEquals(1, read.embeddedImages().size());
        assertEquals("png", read.embeddedImages().getFirst().format());
        assertTrue(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(read.embeddedImages().getFirst().data())) != null,
                "the embedded bytes are the image file");
    }

    @Test void anExternalTextureIsFoundEvenThoughAssimpReportsAnEmptyPath(@TempDir Path directory) throws Exception {
        var read = FbxReader.read(ModelFixtures.externalFbx(directory));
        assertEquals(List.of("wood.png"), read.textureFiles());
        assertTrue(read.embeddedImages().isEmpty());
    }

    @Test void damagedOrForeignFilesAreReportedNotCrashedOn() throws Exception {
        byte[] stub = "Kaydara FBX Binary  \0\u001a\0\u00e8\u001c\0\0".getBytes(StandardCharsets.ISO_8859_1);
        var stubError = assertThrows(IOException.class, () -> FbxReader.read(stub));
        assertTrue(stubError.getMessage().contains("could not read"), stubError.getMessage());
        byte[] whole = ModelFixtures.embeddedFbx();
        assertThrows(IOException.class, () -> FbxReader.read(Arrays.copyOf(whole, whole.length / 2)), "truncated");
        byte[] junk = new byte[4096];
        new java.util.Random(11).nextBytes(junk);
        assertThrows(IOException.class, () -> FbxReader.read(junk));
        byte[] hostile = Arrays.copyOf(whole, whole.length);
        hostile[27] = (byte) 0xFF; hostile[28] = (byte) 0xFF; hostile[29] = (byte) 0xFF; hostile[30] = (byte) 0x7F;
        assertThrows(IOException.class, () -> FbxReader.read(hostile), "a node claiming to run past the file");
    }

    @Test void theNodeScanPrefersRelativeNamesAndReadsAscii(@TempDir Path directory) throws Exception {
        var binary = FbxNodes.textureFiles(ModelFixtures.externalFbx(directory));
        assertEquals(List.of("wood.png"), binary.stream().map(FbxNodes.TextureFile::path).distinct().toList());
        String ascii = "; FBX 7.4.0 project file\nObjects:  {\n Texture: 1, \"Texture::wood\", \"\" {\n"
                + "  FileName: \"C:\\art\\wood.png\"\n  RelativeFilename: \"tex\\wood.png\"\n }\n}\n";
        var files = FbxNodes.textureFiles(ascii.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("tex\\wood.png"), files.stream().map(FbxNodes.TextureFile::path).toList(),
                "the relative name wins over the absolute one");
    }
}
