package io.github.aiassetmigrate.conversion;

import org.lwjgl.assimp.AIExportDataBlob;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/** Model test files built at test time with the bundled Assimp; requires {@link AssimpModelConverter#available()}. */
public final class ModelFixtures {
    public static final String OBJ = "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\nv 1 0 0\nv 0 1 0\nv 1 1 0\n"
            + "vt 0 0\nvt 1 0\nvt 0 1\nvt 1 1\nvn 0 0 1\nf 1/1/1 2/2/1 4/4/1 3/3/1\n";
    public static final String MTL = "newmtl Skin\nKd 0.8 0.2 0.2\nmap_Kd tex/hero.png\n";

    private ModelFixtures() { }

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** hero.obj with hero.mtl and tex/hero.png as dependencies. */
    public static ConversionInput objInput() throws IOException {
        return new ConversionInput("hero.obj", utf8(OBJ), Map.of("hero.mtl", utf8(MTL),
                "tex/hero.png", ConversionStrategyTest.png(false)), "glb", Map.of("format", "glb"));
    }

    /** Exports a scene as binary FBX through Assimp itself. */
    static byte[] exportFbx(AIScene scene) {
        AIExportDataBlob blob = aiExportSceneToBlob(scene, "fbx", 0);
        if (blob == null) throw new IllegalStateException("FBX export failed: " + aiGetErrorString());
        try {
            ByteBuffer out = blob.data();
            byte[] bytes = new byte[out.remaining()];
            out.get(bytes);
            return bytes;
        } finally {
            aiReleaseExportBlob(blob);
        }
    }

    /** A binary FBX whose texture is embedded, built from the converter's own GLB output. */
    public static byte[] embeddedFbx() throws IOException {
        byte[] glb = new AssimpModelConverter().convert(objInput());
        ByteBuffer data = MemoryUtil.memAlloc(glb.length).put(glb).flip();
        try {
            AIScene scene = aiImportFileFromMemory(data, aiProcess_ValidateDataStructure, "glb");
            if (scene == null) throw new IllegalStateException("GLB import failed: " + aiGetErrorString());
            try { return exportFbx(scene); } finally { aiReleaseImport(scene); }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** A binary FBX that references wood.png as an external file; scratch files are written to {@code scratch}. */
    public static byte[] externalFbx(Path scratch) throws IOException {
        Files.createDirectories(scratch);
        Files.writeString(scratch.resolve("crate.obj"),
                "mtllib crate.mtl\nusemtl Wood\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3\n");
        Files.writeString(scratch.resolve("crate.mtl"), "newmtl Wood\nmap_Kd wood.png\n");
        Files.write(scratch.resolve("wood.png"), ConversionStrategyTest.png(false));
        AIScene scene = aiImportFile(scratch.resolve("crate.obj").toString(), aiProcess_ValidateDataStructure);
        if (scene == null) throw new IllegalStateException("OBJ import failed: " + aiGetErrorString());
        try { return exportFbx(scene); } finally { aiReleaseImport(scene); }
    }
}
