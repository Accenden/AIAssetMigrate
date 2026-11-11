package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ConversionStrategyTest {
    private final TextureConverter textures = new TextureConverter();

    public static byte[] png(boolean transparent) throws IOException {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) image.setRGB(x, y, transparent && x == 0 ? 0x00000000 : 0xFF3366CC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static Asset asset(String name, Asset.Type type, String format) {
        return new Asset(UUID.randomUUID(), Path.of(name), type, "0".repeat(64), Map.of(Asset.FORMAT, format));
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test void convertsOpaquePngToJpegAndBackPreservingDimensions() throws Exception {
        byte[] jpg = textures.convert(png(false), "jpg", Map.of("quality", "0.8"));
        assertEquals((byte) 0xFF, jpg[0]);
        assertEquals((byte) 0xD8, jpg[1], "JPEG SOI marker");
        assertEquals(4, read(jpg).getWidth());
        byte[] back = textures.convert(jpg, "png", Map.of());
        assertEquals((byte) 0x89, back[0]);
        assertEquals(3, read(back).getHeight());
    }

    @Test void transparencyNeedsAnApprovedBackgroundForJpeg() throws Exception {
        byte[] transparent = png(true);
        var error = assertThrows(IOException.class, () -> textures.check(transparent, "jpg", Map.of()));
        assertTrue(error.getMessage().contains("background"));
        assertDoesNotThrow(() -> textures.convert(transparent, "jpg", Map.of("background", "#FFFFFF")));
        // A fully transparent image avoids JPEG chroma subsampling blending neighbouring colours.
        BufferedImage clear = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(clear, "png", bytes);
        BufferedImage flattened = read(textures.convert(bytes.toByteArray(), "jpg", Map.of("background", "#FFFFFF", "quality", "1.0")));
        int pixel = flattened.getRGB(8, 8) & 0xFFFFFF;
        assertTrue(((pixel >> 16) & 0xFF) > 240 && (pixel & 0xFF) > 240, "transparent pixel uses the background");
        assertDoesNotThrow(() -> textures.check(transparent, "png", Map.of()));
    }

    @Test void rejectsInvalidOptionsAndNonImages() {
        assertThrows(IllegalArgumentException.class, () -> TextureConverter.options("jpg", Map.of("background", "white")));
        assertThrows(IllegalArgumentException.class, () -> TextureConverter.options("jpg", Map.of("quality", "2")));
        assertThrows(IllegalArgumentException.class, () -> TextureConverter.options("png", Map.of("quality", "0.5")));
        assertThrows(IllegalArgumentException.class, () -> TextureConverter.options("jpg", Map.of("exec", "rm")));
        assertThrows(IllegalArgumentException.class, () -> TextureConverter.options("gif", Map.of()));
        assertThrows(IOException.class, () -> textures.check("not an image".getBytes(), "jpg", Map.of()));
    }

    @Test void strategiesAreSelectedBySupportAndModelsAreHonestlyUnsupported() {
        Asset png = asset("a.png", Asset.Type.TEXTURE, "png");
        Asset jpeg = asset("a.jpeg", Asset.Type.TEXTURE, "jpeg");
        Asset obj = asset("a.obj", Asset.Type.MODEL, "obj");
        assertTrue(textures.supports(png, "jpg"));
        assertTrue(textures.supports(jpeg, "png"));
        assertFalse(textures.supports(png, "png"), "no-op conversions are not offered");
        assertFalse(textures.supports(jpeg, "jpg"), "jpeg and jpg are the same format");
        assertFalse(textures.supports(obj, "fbx"));
        assertEquals(TextureConverter.ID, Converters.find(png, "jpg").orElseThrow().id());
        assertTrue(Converters.find(obj, "fbx").isEmpty());
        ModelConverter models = new ModelConverter();
        assertFalse(models.supports(obj, "fbx"));
        assertThrows(IOException.class, () -> models.convert(new byte[0], "fbx", Map.of()));
        assertEquals(models, Converters.byId(ModelConverter.ID).map(ModelConverter.class::cast).map(m -> models).orElseThrow());
    }
}
