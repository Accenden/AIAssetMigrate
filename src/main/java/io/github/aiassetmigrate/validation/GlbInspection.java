package io.github.aiassetmigrate.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.domain.Finding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Structural check of a binary glTF 2.0 (GLB) file, as F08 validates converted models. It checks the
 * header, chunk layout, JSON, buffer, bufferView and accessor bounds, and decodes every embedded image.
 * Self-contained output is required: an external {@code uri} is an error. It does not check that the
 * model looks right; a GLB that passes is well-formed, not visually verified.
 */
final class GlbInspection {
    static final int MAGIC = 0x46546C67;
    static final int JSON_CHUNK = 0x4E4F534A;
    static final int BIN_CHUNK = 0x004E4942;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Map<Integer, Integer> COMPONENT_BYTES = Map.of(5120, 1, 5121, 1, 5122, 2, 5123, 2, 5125, 4, 5126, 4);
    private static final Map<String, Integer> COMPONENTS = Map.of("SCALAR", 1, "VEC2", 2, "VEC3", 3, "VEC4", 4,
            "MAT2", 4, "MAT3", 9, "MAT4", 16);

    record Problem(Finding.Severity severity, String code, String message) {}

    record Result(List<Problem> problems, String summary) {}

    private GlbInspection() { }

    static Result inspect(byte[] glb) {
        List<Problem> problems = new ArrayList<>();
        try {
            return inspect(glb, problems);
        } catch (RuntimeException e) {
            problems.add(invalid("could not be parsed as GLB: " + e.getClass().getSimpleName()));
            return new Result(problems, "");
        }
    }

    private static Result inspect(byte[] glb, List<Problem> problems) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (glb.length < 20 || b.getInt(0) != MAGIC) return fail(problems, "is not a GLB file");
        if (b.getInt(4) != 2) return fail(problems, "is GLB version " + b.getInt(4) + ", not 2");
        long declared = Integer.toUnsignedLong(b.getInt(8));
        if (declared != glb.length) return fail(problems, "declares " + declared + " bytes but has " + glb.length);
        byte[] json = null, bin = null;
        int offset = 12;
        while (offset < glb.length) {
            if (offset + 8 > glb.length) return fail(problems, "has a truncated chunk header");
            long length = Integer.toUnsignedLong(b.getInt(offset));
            int type = b.getInt(offset + 4);
            if (offset + 8 + length > glb.length) return fail(problems, "has a chunk that runs past the end of the file");
            byte[] chunk = Arrays.copyOfRange(glb, offset + 8, offset + 8 + (int) length);
            if (json == null) {
                if (type != JSON_CHUNK) return fail(problems, "does not start with a JSON chunk");
                json = chunk;
            } else if (bin == null && type == BIN_CHUNK) {
                bin = chunk;
            }
            offset += 8 + (int) length;
        }
        if (json == null) return fail(problems, "has no JSON chunk");
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return fail(problems, "has invalid JSON: " + e.getClass().getSimpleName());
        }
        if (root == null || !root.isObject()) return fail(problems, "JSON chunk is not an object");
        if (!root.path("asset").path("version").asText("").startsWith("2.")) return fail(problems, "is not glTF 2.x");

        long[] buffers = sizes(root.path("buffers"), bin, problems);
        long[] views = views(root.path("bufferViews"), buffers, problems);
        accessors(root.path("accessors"), views, root.path("bufferViews"), problems);
        int meshes = root.path("meshes").size();
        if (meshes == 0) problems.add(invalid("contains no meshes"));
        int images = images(root.path("images"), root.path("bufferViews"), views, bin, problems);
        return new Result(problems, "GLB 2.0 structure valid (" + meshes + " mesh(es), "
                + (images == 0 ? "no images" : images + " embedded image(s) decode") + ")");
    }

    private static long[] sizes(JsonNode buffers, byte[] bin, List<Problem> problems) {
        long[] sizes = new long[buffers.size()];
        for (int i = 0; i < buffers.size(); i++) {
            JsonNode buffer = buffers.get(i);
            long length = buffer.path("byteLength").asLong(-1);
            sizes[i] = length;
            if (length < 0) problems.add(invalid("buffer " + i + " has no valid byteLength"));
            if (buffer.has("uri")) {
                problems.add(external("buffer " + i));
                sizes[i] = -1;
            } else if (i == 0 && (bin == null || length > bin.length)) {
                problems.add(invalid("buffer 0 is larger than the binary chunk"));
                sizes[i] = -1;
            } else if (i > 0) {
                problems.add(invalid("buffer " + i + " has no data (only buffer 0 may use the binary chunk)"));
                sizes[i] = -1;
            }
        }
        return sizes;
    }

    private static long[] views(JsonNode views, long[] buffers, List<Problem> problems) {
        long[] sizes = new long[views.size()];
        for (int i = 0; i < views.size(); i++) {
            JsonNode view = views.get(i);
            int buffer = view.path("buffer").asInt(-1);
            long start = view.path("byteOffset").asLong(0), length = view.path("byteLength").asLong(-1);
            sizes[i] = -1;
            if (buffer < 0 || buffer >= buffers.length || length < 0 || start < 0) {
                problems.add(invalid("bufferView " + i + " is malformed"));
            } else if (buffers[buffer] >= 0 && start + length > buffers[buffer]) {
                problems.add(invalid("bufferView " + i + " runs past the end of buffer " + buffer));
            } else if (buffers[buffer] >= 0) {
                sizes[i] = length;
            }
        }
        return sizes;
    }

    private static void accessors(JsonNode accessors, long[] views, JsonNode viewNodes, List<Problem> problems) {
        for (int i = 0; i < accessors.size(); i++) {
            JsonNode accessor = accessors.get(i);
            Integer component = COMPONENT_BYTES.get(accessor.path("componentType").asInt(-1));
            Integer count = COMPONENTS.get(accessor.path("type").asText(""));
            long elements = accessor.path("count").asLong(-1);
            if (component == null || count == null || elements < 1) {
                problems.add(invalid("accessor " + i + " is malformed"));
                continue;
            }
            if (!accessor.has("bufferView")) continue; // Zero-filled or sparse accessors carry no direct data.
            int view = accessor.path("bufferView").asInt(-1);
            if (view < 0 || view >= views.length) {
                problems.add(invalid("accessor " + i + " refers to a missing bufferView"));
                continue;
            }
            if (views[view] < 0) continue; // Already reported.
            long element = (long) component * count;
            long stride = viewNodes.get(view).path("byteStride").asLong(element);
            long end = accessor.path("byteOffset").asLong(0) + stride * (elements - 1) + element;
            if (end > views[view]) problems.add(invalid("accessor " + i + " reads past the end of bufferView " + view));
        }
    }

    private static int images(JsonNode images, JsonNode viewNodes, long[] views, byte[] bin, List<Problem> problems) {
        int decoded = 0;
        for (int i = 0; i < images.size(); i++) {
            JsonNode image = images.get(i);
            if (image.has("uri")) {
                problems.add(external("image " + i));
                continue;
            }
            int view = image.path("bufferView").asInt(-1);
            if (view < 0 || view >= views.length || views[view] < 0 || bin == null) {
                problems.add(invalid("image " + i + " has no readable data"));
                continue;
            }
            String mime = image.path("mimeType").asText("");
            String extension = mime.equals("image/png") ? "png" : mime.equals("image/jpeg") ? "jpg" : null;
            if (extension == null) {
                problems.add(new Problem(Finding.Severity.UNKNOWN, "OUTPUT_FORMAT_UNCHECKED",
                        "image " + i + " has MIME type '" + mime + "', which cannot be decoded here"));
                continue;
            }
            int start = (int) viewNodes.get(view).path("byteOffset").asLong(0);
            byte[] data = Arrays.copyOfRange(bin, start, start + (int) views[view]);
            OutputValidator.ImageCheck check = OutputValidator.inspectImage(data, extension);
            if (check.severity() == null) decoded++;
            else problems.add(new Problem(check.severity(), check.severity() == Finding.Severity.ERROR
                    ? "OUTPUT_FORMAT_INVALID" : "OUTPUT_FORMAT_UNCHECKED", "embedded image " + i + " " + check.message()));
        }
        return decoded;
    }

    private static Problem invalid(String message) {
        return new Problem(Finding.Severity.ERROR, "OUTPUT_FORMAT_INVALID", message);
    }

    private static Problem external(String what) {
        return new Problem(Finding.Severity.ERROR, "OUTPUT_REFERENCE_UNRESOLVED", what
                + " refers to an external file; converted models must be self-contained");
    }

    private static Result fail(List<Problem> problems, String message) {
        problems.add(invalid(message));
        return new Result(problems, "");
    }
}
