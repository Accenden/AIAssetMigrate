package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.assimp.AIExportDataBlob;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.assimp.Assimp.*;

/** F07 model conversion through the bundled Assimp; skipped only where no native library exists. */
class AssimpModelConverterTest {
    private final AssimpModelConverter converter = new AssimpModelConverter();

    @BeforeAll static void requireNatives() {
        assumeTrue(AssimpModelConverter.available(), "Assimp natives unavailable: " + AssimpModelConverter.unavailableReason());
    }

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static ConversionInput objInput() throws IOException { return ModelFixtures.objInput(); }

    static String glbJson(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x46546C67, b.getInt(0), "glTF magic");
        assertEquals(2, b.getInt(4), "glTF version");
        assertEquals(glb.length, b.getInt(8), "declared length");
        assertEquals(0x4E4F534A, b.getInt(16), "first chunk is JSON");
        return new String(glb, 20, b.getInt(12), StandardCharsets.UTF_8);
    }

    @Test void objWithMaterialAndTextureBecomesASelfContainedGlb() throws Exception {
        byte[] glb = converter.convert(objInput());
        String json = glbJson(glb);
        assertTrue(json.contains("\"images\"") && json.contains("\"bufferView\""), json);
        assertFalse(json.contains("\"uri\""), "textures are embedded, not referenced");
        assertArrayEquals(glb, converter.convert(objInput()), "conversion is deterministic");
        assertDoesNotThrow(() -> converter.check(objInput()));
    }

    @Test void fbxWithEmbeddedTextureBecomesGlb() throws Exception {
        byte[] fbx = ModelFixtures.embeddedFbx();
        assertEquals("Kaydara FBX Binary", new String(fbx, 0, 18, StandardCharsets.ISO_8859_1));
        byte[] glb = converter.convert(new ConversionInput("crate.fbx", fbx, Map.of(), "glb", Map.of()));
        assertFalse(glbJson(glb).contains("\"uri\""));
    }

    @Test void filesOutsideTheReviewedDependenciesAreNeverRead() throws Exception {
        var missingMtl = new ConversionInput("hero.obj", utf8(ModelFixtures.OBJ), Map.of("tex/hero.png", ConversionStrategyTest.png(false)),
                "glb", Map.of());
        var error = assertThrows(IOException.class, () -> converter.convert(missingMtl));
        assertTrue(error.getMessage().contains("'hero.mtl'") && error.getMessage().contains("not among its reviewed dependencies"),
                error.getMessage());

        String hostile = "newmtl Skin\nmap_Kd tex/hero.png\nmap_Ks " + Path.of("C:", "Windows", "win.ini").toAbsolutePath() + "\n";
        var absolute = new ConversionInput("hero.obj", utf8(ModelFixtures.OBJ), Map.of("hero.mtl", utf8(hostile),
                "tex/hero.png", ConversionStrategyTest.png(false)), "glb", Map.of());
        var refused = assertThrows(IOException.class, () -> converter.convert(absolute));
        assertTrue(refused.getMessage().contains("win.ini"), refused.getMessage());
    }

    @Test void fbxThatReferencesExternalTexturesIsRejected(@TempDir Path directory) throws Exception {
        byte[] fbx = ModelFixtures.externalFbx(directory);
        var error = assertThrows(IOException.class, () -> converter.convert(new ConversionInput("crate.fbx", fbx, Map.of(), "glb", Map.of())));
        assertTrue(error.getMessage().contains("not embedded"), error.getMessage());
    }

    @Test void corruptInputFailsWithAMessage() {
        byte[] junk = new byte[4096];
        new java.util.Random(7).nextBytes(junk);
        for (String name : new String[] {"junk.obj", "junk.fbx"}) {
            var error = assertThrows(IOException.class, () -> converter.convert(new ConversionInput(name, junk, Map.of(), "glb", Map.of())));
            assertNotNull(error.getMessage());
        }
        assertThrows(IOException.class, () -> converter.convert(new ConversionInput("model.dae", junk, Map.of(), "glb", Map.of())));
    }

    @Test void supportFormatsOptionsAndLimits() throws Exception {
        Asset obj = new Asset(UUID.randomUUID(), Path.of("a.obj"), Asset.Type.MODEL, "0".repeat(64), Map.of(Asset.FORMAT, "obj"));
        Asset fbx = new Asset(UUID.randomUUID(), Path.of("a.fbx"), Asset.Type.MODEL, "0".repeat(64), Map.of(Asset.FORMAT, "fbx"));
        Asset png = new Asset(UUID.randomUUID(), Path.of("a.png"), Asset.Type.TEXTURE, "0".repeat(64), Map.of(Asset.FORMAT, "png"));
        assertTrue(converter.supports(obj, "glb") && converter.supports(fbx, "GLB"));
        assertFalse(converter.supports(obj, "fbx") || converter.supports(png, "glb"));
        assertTrue(converter.needsDependencies());
        assertTrue(converter.id().startsWith("assimp-lwjgl-"));
        assertThrows(IllegalArgumentException.class, () -> converter.validateOptions("glb", Map.of("format", "glb", "quality", "1")));
        assertThrows(IllegalArgumentException.class, () -> converter.validateOptions("gltf", Map.of()));
        byte[] large = new byte[AssimpModelConverter.MAX_INPUT_BYTES + 1];
        var error = assertThrows(IOException.class, () -> converter.convert(new ConversionInput("big.obj", large, Map.of(), "glb", Map.of())));
        assertTrue(error.getMessage().contains("64 MiB"));
    }
}
