package io.github.aiassetmigrate.validation;

import io.github.aiassetmigrate.conversion.AssimpModelConverter;
import io.github.aiassetmigrate.conversion.ConversionInput;
import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.domain.Finding;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GlbInspectionTest {
    /** Assembles a GLB from a JSON document and an optional binary chunk, padding both to 4 bytes. */
    static byte[] glb(String json, byte[] bin) {
        byte[] text = pad(json.getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] data = bin == null ? null : pad(bin, (byte) 0);
        int length = 12 + 8 + text.length + (data == null ? 0 : 8 + data.length);
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GlbInspection.MAGIC).putInt(2).putInt(length);
        out.putInt(text.length).putInt(GlbInspection.JSON_CHUNK).put(text);
        if (data != null) out.putInt(data.length).putInt(GlbInspection.BIN_CHUNK).put(data);
        return out.array();
    }

    private static byte[] pad(byte[] bytes, byte fill) {
        byte[] padded = Arrays.copyOf(bytes, (bytes.length + 3) / 4 * 4);
        Arrays.fill(padded, bytes.length, padded.length, fill);
        return padded;
    }

    private static final String TRIANGLE = """
            {"asset":{"version":"2.0"},"buffers":[{"byteLength":36}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}""";

    private static List<String> codes(GlbInspection.Result result) {
        return result.problems().stream().map(GlbInspection.Problem::code).toList();
    }

    @Test void converterOutputPassesWithItsEmbeddedImageDecoded() throws Exception {
        assumeTrue(AssimpModelConverter.available(), AssimpModelConverter.unavailableReason());
        String obj = "mtllib a.mtl\nusemtl M\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3\n";
        byte[] output = new AssimpModelConverter().convert(new ConversionInput("a.obj", obj.getBytes(StandardCharsets.UTF_8),
                Map.of("a.mtl", "newmtl M\nmap_Kd a.png\n".getBytes(StandardCharsets.UTF_8), "a.png", ConversionStrategyTest.png(false)),
                "glb", Map.of()));
        var result = GlbInspection.inspect(output);
        assertEquals(List.of(), result.problems());
        assertTrue(result.summary().contains("1 embedded image(s) decode"), result.summary());
    }

    @Test void minimalHandBuiltGlbPasses() {
        var result = GlbInspection.inspect(glb(TRIANGLE, new byte[36]));
        assertEquals(List.of(), result.problems(), result.toString());
        assertTrue(result.summary().contains("1 mesh(es), no images"));
    }

    @Test void headerAndChunkDamageAreErrors() {
        byte[] valid = glb(TRIANGLE, new byte[36]);
        byte[] magic = valid.clone();
        magic[0] = 'X';
        assertEquals(List.of("OUTPUT_FORMAT_INVALID"), codes(GlbInspection.inspect(magic)));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 4);
        assertTrue(GlbInspection.inspect(truncated).problems().getFirst().message().contains("declares"));
        byte[] version = valid.clone();
        version[4] = 1;
        assertTrue(GlbInspection.inspect(version).problems().getFirst().message().contains("version 1"));
        assertEquals(List.of("OUTPUT_FORMAT_INVALID"), codes(GlbInspection.inspect("not a glb".getBytes(StandardCharsets.UTF_8))));
    }

    @Test void boundsExternalReferencesAndMeshesAreChecked() {
        assertTrue(GlbInspection.inspect(glb(TRIANGLE, new byte[20])).problems().stream()
                .anyMatch(p -> p.message().contains("larger than the binary chunk")));
        String overrun = TRIANGLE.replace("\"count\":3", "\"count\":4");
        assertTrue(GlbInspection.inspect(glb(overrun, new byte[36])).problems().stream()
                .anyMatch(p -> p.message().contains("accessor 0 reads past")));
        String external = TRIANGLE.replace("{\"byteLength\":36}", "{\"byteLength\":36,\"uri\":\"mesh.bin\"}");
        assertEquals("OUTPUT_REFERENCE_UNRESOLVED", codes(GlbInspection.inspect(glb(external, new byte[36]))).getFirst());
        String noMesh = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":36}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}]}""";
        assertTrue(GlbInspection.inspect(glb(noMesh, new byte[36])).problems().stream()
                .anyMatch(p -> p.message().contains("no meshes")), noMesh);
    }

    @Test void embeddedImagesMustDecode() throws Exception {
        byte[] png = ConversionStrategyTest.png(false);
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        bin.write(new byte[36]);
        bin.write(png);
        String json = TRIANGLE.replace("{\"byteLength\":36}", "{\"byteLength\":" + (36 + png.length) + "}")
                .replace("\"byteLength\":36}]", "\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":" + png.length + "}]")
                .replace("\"meshes\"", "\"images\":[{\"bufferView\":1,\"mimeType\":\"image/png\"}],\"meshes\"");
        assertEquals(List.of(), GlbInspection.inspect(glb(json, bin.toByteArray())).problems());

        byte[] corrupt = bin.toByteArray();
        Arrays.fill(corrupt, 36 + 8, corrupt.length, (byte) 0x55);
        var broken = GlbInspection.inspect(glb(json, corrupt));
        assertTrue(broken.problems().stream().anyMatch(p -> p.severity() == Finding.Severity.ERROR
                && p.message().startsWith("embedded image 0")), broken.toString());

        var jpegClaim = GlbInspection.inspect(glb(json.replace("image/png", "image/jpeg"), bin.toByteArray()));
        assertTrue(jpegClaim.problems().stream().anyMatch(p -> p.message().contains("not JPG")), jpegClaim.toString());
        var webp = GlbInspection.inspect(glb(json.replace("image/png", "image/webp"), bin.toByteArray()));
        assertEquals(Finding.Severity.UNKNOWN, webp.problems().getFirst().severity(), "unknown is not passed");
    }
}
