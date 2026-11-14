package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PNG and JPEG texture conversion with the JDK's ImageIO. Options: {@code background}
 * ({@code #RRGGBB}, required to flatten transparency into JPEG) and {@code quality}
 * (JPEG, 0.1-1.0, default 0.9). Images larger than {@link #MAX_PIXELS} are rejected
 * before decoding.
 */
public final class TextureConverter implements ConversionStrategy {
    public static final String ID = "texture-imageio";
    public static final long MAX_PIXELS = 64L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("png", "jpg");
    /** The check failure for a transparent texture converted to JPEG without a background colour. */
    public static final String NEEDS_BACKGROUND = "Texture has transparency; JPEG conversion needs an approved background colour (background=#RRGGBB)";

    @Override public String id() { return ID; }

    /** Normalizes {@code jpeg} to {@code jpg}; returns null for unsupported formats. */
    public static String normalize(String format) {
        if (format == null) return null;
        String value = format.toLowerCase(Locale.ROOT);
        if (value.equals("jpeg")) value = "jpg";
        return FORMATS.contains(value) ? value : null;
    }

    @Override public boolean supports(Asset asset, String targetFormat) {
        String source = normalize(asset.metadata().get(Asset.FORMAT));
        String target = normalize(targetFormat);
        return asset.type() == Asset.Type.TEXTURE && source != null && target != null && !source.equals(target);
    }

    @Override public String normalizeFormat(String format) { return normalize(format); }

    @Override public void validateOptions(String targetFormat, Map<String, String> options) { options(targetFormat, options); }

    @Override public void check(ConversionInput input) throws IOException {
        check(input.source(), input.targetFormat(), input.options());
    }

    @Override public byte[] convert(ConversionInput input) throws IOException {
        return convert(input.source(), input.targetFormat(), input.options());
    }

    @Override public void check(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        options(targetFormat, options);
        BufferedImage image = decode(source);
        if (normalize(targetFormat).equals("jpg") && transparent(image) && !options.containsKey("background")) {
            throw new IOException(NEEDS_BACKGROUND);
        }
    }

    @Override public byte[] convert(byte[] source, String targetFormat, Map<String, String> options) throws IOException {
        check(source, targetFormat, options);
        BufferedImage image = decode(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (normalize(targetFormat).equals("png")) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG writer is available");
            return out.toByteArray();
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.decode(options.getOrDefault("background", "#000000")));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer is available");
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(Float.parseFloat(options.getOrDefault("quality", "0.9")));
            writer.write(null, new IIOImage(rgb, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Validates conversion options; throws IllegalArgumentException with a user-facing message. */
    public static void options(String targetFormat, Map<String, String> options) {
        if (normalize(targetFormat) == null) throw new IllegalArgumentException("Target format must be png or jpg");
        for (var entry : options.entrySet()) {
            switch (entry.getKey()) {
                case "format" -> { }
                case "background" -> {
                    if (!entry.getValue().matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("background must be #RRGGBB");
                }
                case "quality" -> {
                    float quality;
                    try { quality = Float.parseFloat(entry.getValue()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("quality must be a number from 0.1 to 1.0"); }
                    if (!(quality >= 0.1f && quality <= 1.0f)) throw new IllegalArgumentException("quality must be from 0.1 to 1.0");
                    if (!normalize(targetFormat).equals("jpg")) throw new IllegalArgumentException("quality applies only to jpg");
                }
                default -> throw new IllegalArgumentException("Unknown conversion option: " + entry.getKey());
            }
        }
    }

    /**
     * Whether a PNG or JPEG has any pixel that is not fully opaque; empty when it cannot be decoded within the
     * conversion limits. Used to tell the AI which textures cannot become JPG without a background colour.
     */
    public static java.util.Optional<Boolean> transparency(byte[] source) {
        try {
            return java.util.Optional.of(transparent(decode(source)));
        } catch (IOException | RuntimeException undecodable) {
            return java.util.Optional.empty();
        }
    }

    private static BufferedImage decode(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new IOException("Texture is not a decodable PNG or JPEG image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) throw new IOException("Texture dimensions exceed the conversion limit");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Texture could not be decoded", e);
        }
    }

    private static boolean transparent(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) return true;
            }
        }
        return false;
    }
}
