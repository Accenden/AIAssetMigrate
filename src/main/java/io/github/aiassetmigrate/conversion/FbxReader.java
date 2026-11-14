package io.github.aiassetmigrate.conversion;

import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Reads FBX models with the Assimp library that is bundled for FBX/OBJ to GLB conversion, so an FBX can be
 * checked instead of only fingerprinted. It reads from memory only and never opens another file:
 * <ul>
 *   <li>Assimp imports the scene: an FBX it cannot import, or one without meshes, is reported as unreadable;</li>
 *   <li>images embedded in the file are returned so they can be decoded;</li>
 *   <li>texture files the model refers to are read from the FBX node tree ({@link FbxNodes}) as well as from
 *       Assimp, because Assimp reports some of them as an empty path.</li>
 * </ul>
 * FBX is binary and is never rewritten: a referenced texture must be where the file says it is.
 */
public final class FbxReader {
    private FbxReader() { }

    /** Size limit for reading one FBX (the same as for converting one). */
    public static final int MAX_BYTES = AssimpModelConverter.MAX_INPUT_BYTES;
    private static final int MAX_TEXTURE_TYPE = 30;

    /** An image stored inside the FBX: its format hint (for example {@code png}) and file bytes. */
    public record EmbeddedImage(String format, byte[] data) {}

    /**
     * What was read. {@code textureFiles} are the external texture paths as recorded (backslashes become
     * slashes), relative ones preferred; {@code rawEmbedded} counts embedded images stored as raw pixels.
     */
    public record Result(int meshes, int materials, List<String> textureFiles, List<EmbeddedImage> embeddedImages,
                         int rawEmbedded) {
        public Result {
            textureFiles = List.copyOf(textureFiles);
            embeddedImages = List.copyOf(embeddedImages);
        }
    }

    /** True when the bundled Assimp library loaded on this platform. */
    public static boolean available() { return AssimpModelConverter.available(); }

    /** Reads one FBX file; throws with Assimp's or the scanner's reason when it cannot be read. */
    public static Result read(byte[] fbx) throws IOException {
        if (!available()) throw new IOException("The built-in FBX reader is unavailable: " + AssimpModelConverter.unavailableReason());
        if (fbx.length > MAX_BYTES) throw new IOException("FBX file exceeds the 64 MiB reading limit");
        List<FbxNodes.TextureFile> recorded;
        try {
            recorded = FbxNodes.textureFiles(fbx);
        } catch (IOException | RuntimeException e) {
            throw new IOException("FBX structure could not be read: " + e.getMessage(), e);
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(Math.max(1, fbx.length)).put(fbx).flip();
        try {
            synchronized (AssimpModelConverter.LOCK) {
                AIScene scene = aiImportFileFromMemory(buffer, aiProcess_ValidateDataStructure, "fbx");
                if (scene == null) throw new IOException("Assimp could not read this FBX: " + aiGetErrorString());
                try {
                    if (scene.mNumMeshes() == 0) throw new IOException("The FBX contains no meshes");
                    List<EmbeddedImage> images = new ArrayList<>();
                    int raw = 0;
                    for (int i = 0; i < scene.mNumTextures(); i++) {
                        AITexture texture = AITexture.create(scene.mTextures().get(i));
                        if (texture.mHeight() != 0) { raw++; continue; }
                        ByteBuffer data = texture.pcDataCompressed();
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        images.add(new EmbeddedImage(texture.achFormatHintString().toLowerCase(Locale.ROOT), bytes));
                    }
                    return new Result(scene.mNumMeshes(), scene.mNumMaterials(), textureFiles(scene, recorded), images, raw);
                } finally {
                    aiReleaseImport(scene);
                }
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    /**
     * External texture paths: those recorded in the file that are not embedded, plus any path only Assimp
     * reports, unless a recorded one already names the same file. Keyed case-insensitively by file name so
     * one texture recorded with both an absolute and a relative name is listed once, by its relative name.
     */
    private static List<String> textureFiles(AIScene scene, List<FbxNodes.TextureFile> recorded) {
        Map<String, String> files = new LinkedHashMap<>();
        java.util.Set<String> embedded = new java.util.HashSet<>();
        for (FbxNodes.TextureFile file : recorded) {
            if (file.embedded()) {
                embedded.add(fileName(file.relative()));
                embedded.add(fileName(file.recorded()));
            }
        }
        for (FbxNodes.TextureFile file : recorded) {
            String path = normalize(file.path());
            String name = fileName(path);
            if (file.embedded() || embedded.contains(name) || aiGetEmbeddedTexture(scene, file.path()) != null) continue;
            String known = files.get(name);
            if (known == null || (absolute(known) && !absolute(path))) files.put(name, path);
        }
        for (int m = 0; m < scene.mNumMaterials(); m++) {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(m));
            for (int type = aiTextureType_DIFFUSE; type <= MAX_TEXTURE_TYPE; type++) {
                for (int t = 0; t < aiGetMaterialTextureCount(material, type); t++) {
                    try (AIString path = AIString.calloc()) {
                        if (aiGetMaterialTexture(material, type, t, path, (int[]) null, null, null, null, null, null) != aiReturn_SUCCESS) continue;
                        String text = path.dataString();
                        if (text.isEmpty() || text.startsWith("*") || aiGetEmbeddedTexture(scene, text) != null) continue;
                        String normalized = normalize(text);
                        if (!embedded.contains(fileName(normalized))) files.putIfAbsent(fileName(normalized), normalized);
                    }
                }
            }
        }
        return List.copyOf(files.values());
    }

    private static String normalize(String path) { return path.replace('\\', '/'); }

    private static String fileName(String path) {
        String text = normalize(path);
        return text.substring(text.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean absolute(String path) {
        return path.startsWith("/") || (path.length() > 1 && path.charAt(1) == ':');
    }
}
