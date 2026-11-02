package io.github.aiassetmigrate.inventory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Bounded evidence only: none of these checks validates complete asset contents. */
final class FormatInspection {
    private static final int MAX_PREFIX = 64 * 1024;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] FBX = "Kaydara FBX Binary  \0\u001a\0".getBytes(StandardCharsets.US_ASCII);
    private final String extension;
    private final long size;
    private final ScanLimits limits;
    private final byte[] prefix;
    private int length;

    FormatInspection(String extension, long size, ScanLimits limits) {
        this.extension = extension;
        this.size = size;
        this.limits = limits;
        boolean text = extension.equals("obj") || extension.equals("mtl");
        int cap = text ? (int) Math.min(MAX_PREFIX, limits.maxParsedTextBytes()) : MAX_PREFIX;
        this.prefix = new byte[(int) Math.min(size, cap)];
    }

    void accept(byte[] bytes, int count) {
        int copied = Math.min(count, prefix.length - length);
        System.arraycopy(bytes, 0, prefix, length, copied);
        length += copied;
    }

    Result finish() {
        return switch (extension) {
            case "png" -> png();
            case "jpg", "jpeg" -> jpeg();
            case "fbx" -> fbx();
            default -> text();
        };
    }

    private Result png() {
        // A PNG needs IHDR, at least one IDAT, and IEND, each with chunk framing.
        if (size < 57 || !startsWith(PNG)) return corrupt("PNG signature or mandatory chunks are missing");
        if (u32(8) != 13 || !Arrays.equals(Arrays.copyOfRange(prefix, 12, 16),
                new byte[]{'I', 'H', 'D', 'R'})) return corrupt("PNG has an invalid IHDR chunk");
        long width = u32(16), height = u32(20);
        int depth = prefix[24] & 255, color = prefix[25] & 255;
        boolean validDepth = switch (color) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> false;
        };
        CRC32 crc = new CRC32();
        crc.update(prefix, 12, 17);
        if (width == 0 || width > Integer.MAX_VALUE || height == 0 || height > Integer.MAX_VALUE
                || !validDepth || prefix[26] != 0 || prefix[27] != 0
                || (prefix[28] != 0 && prefix[28] != 1) || crc.getValue() != u32(29)) {
            return corrupt("PNG IHDR fields or checksum are invalid");
        }
        return unverified("PNG_IHDR", "PNG IHDR checked; image data and remaining chunks are unverified");
    }

    private Result jpeg() {
        if (size < 8 || (prefix[0] & 255) != 0xff || (prefix[1] & 255) != 0xd8
                || (prefix[2] & 255) != 0xff || (prefix[3] & 255) == 0) {
            return corrupt("JPEG start marker or following marker is missing");
        }
        return unverified("JPEG_START_MARKER", "JPEG start marker checked; image data is unverified");
    }

    private Result fbx() {
        if (size == 0) return corrupt("Empty FBX cannot contain an FBX document");
        if (startsWith(FBX)) {
            if (size < FBX.length + 4) return corrupt("Binary FBX version field is truncated");
            return unverified("FBX_BINARY_HEADER", "Binary FBX header checked; document is unverified");
        }
        return unverified("FBX_INCONCLUSIVE", "FBX format could not be verified by bounded inspection");
    }

    private Result text() {
        int lineBytes = 0;
        for (int i = 0; i < length; i++) {
            if (prefix[i] == '\n' || prefix[i] == '\r') lineBytes = 0;
            else if (++lineBytes > limits.maxTextLineBytes()) {
                return unverified("TEXT_LINE_LIMIT", "Text line exceeds inspection budget; document is unverified");
            }
        }
        if (size > length) return unverified("TEXT_PREFIX_LIMIT", "Text inspection budget reached; document is unverified");
        return unverified("TEXT_PREFIX", "Text sampled without complete format validation");
    }

    private boolean startsWith(byte[] signature) {
        if (length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (prefix[i] != signature[i]) return false;
        return true;
    }

    private long u32(int offset) {
        return ((long) (prefix[offset] & 255) << 24) | ((long) (prefix[offset + 1] & 255) << 16)
                | ((long) (prefix[offset + 2] & 255) << 8) | (prefix[offset + 3] & 255L);
    }

    private static Result corrupt(String message) { return new Result(true, "", message); }
    private static Result unverified(String method, String message) { return new Result(false, method, message); }
    record Result(boolean corrupt, String method, String message) {}
}
