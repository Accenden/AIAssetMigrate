package io.github.aiassetmigrate.conversion;

import io.github.aiassetmigrate.domain.Asset;
import org.lwjgl.assimp.AIExportDataBlob;
import org.lwjgl.assimp.AIExportFormatDesc;
import org.lwjgl.assimp.AIFile;
import org.lwjgl.assimp.AIFileCloseProc;
import org.lwjgl.assimp.AIFileFlushProc;
import org.lwjgl.assimp.AIFileIO;
import org.lwjgl.assimp.AIFileOpenProc;
import org.lwjgl.assimp.AIFileReadProc;
import org.lwjgl.assimp.AIFileSeek;
import org.lwjgl.assimp.AIFileTellProc;
import org.lwjgl.assimp.AIFileWriteProc;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.assimp.Assimp.*;

/**
 * OBJ and FBX model conversion to self-contained GLB with the Assimp library bundled by LWJGL.
 * Assimp reads only through in-memory callbacks that serve the {@link ConversionInput}: a request
 * for any other file fails the conversion, so a model cannot pull arbitrary files into its output.
 * Every texture must end up embedded; an FBX that references external textures is rejected.
 * If the native library cannot be loaded the strategy truthfully supports nothing.
 * Conversions are serialized because Assimp's callback state and error string are process-wide.
 */
public final class AssimpModelConverter implements ConversionStrategy {
    public static final String FORMAT = "glb";
    /** Combined size limit for the model and all its dependencies. */
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    private static final Set<String> SOURCES = Set.of("obj", "fbx");
    private static final int FLAGS = aiProcess_Triangulate | aiProcess_JoinIdenticalVertices | aiProcess_ValidateDataStructure;
    /**
     * OBJ textures live in separate files, so they are embedded from the served dependencies. FBX textures are
     * either inside the file already or external; embedding would make Assimp look for them on disk, so FBX
     * is imported as is and any texture that is not inside the file is rejected.
     */
    private static final int OBJ_FLAGS = FLAGS | aiProcess_EmbedTextures;
    private static final int MAX_TEXTURE_TYPE = 30;
    /** Held for every Assimp call in this package: its callback state and error string are process-wide. */
    static final Object LOCK = new Object();
    private static final Availability AVAILABILITY = probe();

    private record Availability(boolean available, String version, String reason) {}

    /** Per-conversion callback state, only touched while {@link #LOCK} is held. */
    private static Map<String, byte[]> served = Map.of();
    private static final Set<String> unserved = new LinkedHashSet<>();
    private static final Map<Long, ByteBuffer> open = new HashMap<>();
    private static AIFileIO fileIO;

    private static Availability probe() {
        try {
            String version = aiGetVersionMajor() + "." + aiGetVersionMinor() + "." + aiGetVersionPatch();
            boolean glb = false;
            long count = aiGetExportFormatCount();
            for (long i = 0; i < count; i++) {
                AIExportFormatDesc description = aiGetExportFormatDescription(i);
                if (description != null) {
                    glb |= "glb2".equals(description.idString());
                    aiReleaseExportFormatDescription(description);
                }
            }
            if (!glb) return new Availability(false, version, "the bundled Assimp " + version + " has no glb2 exporter");
            return new Availability(true, version, "");
        } catch (Throwable failure) {
            // UnsatisfiedLinkError, ExceptionInInitializerError or similar: no usable native library on this platform.
            return new Availability(false, "", "the Assimp native library could not be loaded ("
                    + failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()) + ")");
        }
    }

    /** True when the native library loaded and can export GLB. */
    public static boolean available() { return AVAILABILITY.available(); }

    /** Why model conversion is unavailable, or empty when it is available. */
    public static String unavailableReason() { return AVAILABILITY.reason(); }

    /** Bundled Assimp version, for example {@code 6.0.2}, or empty when unavailable. */
    public static String assimpVersion() { return AVAILABILITY.version(); }

    @Override public String id() {
        return available() ? "assimp-lwjgl-" + AVAILABILITY.version() : "assimp-unavailable";
    }

    @Override public boolean supports(Asset asset, String targetFormat) {
        return available() && asset.type() == Asset.Type.MODEL
                && SOURCES.contains(String.valueOf(asset.metadata().get(Asset.FORMAT)).toLowerCase(Locale.ROOT))
                && FORMAT.equals(normalizeFormat(targetFormat));
    }

    @Override public String normalizeFormat(String format) {
        return format != null && format.toLowerCase(Locale.ROOT).equals(FORMAT) ? FORMAT : null;
    }

    @Override public void validateOptions(String targetFormat, Map<String, String> options) {
        if (normalizeFormat(targetFormat) == null) throw new IllegalArgumentException("Model target format must be glb");
        for (String key : options.keySet()) {
            if (!key.equals("format")) throw new IllegalArgumentException("Unknown conversion option for models: " + key);
        }
    }

    @Override public boolean needsDependencies() { return true; }

    /** Performs the full import and export, so a failing model is caught before any run is reserved. */
    @Override public void check(ConversionInput input) throws IOException {
        convert(input);
    }

    @Override public byte[] convert(ConversionInput input) throws IOException {
        if (!available()) throw new IOException("Model conversion is unavailable: " + unavailableReason());
        validateOptions(input.targetFormat(), input.options());
        String extension = extension(input.sourceName());
        if (!SOURCES.contains(extension)) throw new IOException("Only OBJ and FBX models can be converted to GLB");
        long total = input.source().length;
        for (byte[] bytes : input.dependencies().values()) total += bytes.length;
        if (total > MAX_INPUT_BYTES) throw new IOException("Model and dependencies exceed the 64 MiB conversion limit");
        Map<String, byte[]> files = new HashMap<>(input.dependencies());
        files.put(ConversionInput.key(input.sourceName()), input.source());
        synchronized (LOCK) {
            served = files;
            unserved.clear();
            AIScene scene = null;
            try {
                scene = aiImportFileEx(input.sourceName(), extension.equals("obj") ? OBJ_FLAGS : FLAGS, fileIO());
                if (!unserved.isEmpty()) {
                    throw new IOException("The model requested " + String.join(", ", unserved.stream().map(n -> "'" + n + "'").toList())
                            + ", which " + (unserved.size() == 1 ? "is" : "are") + " not among its reviewed dependencies");
                }
                if (scene == null) throw new IOException("Assimp could not import " + input.sourceName() + ": " + aiGetErrorString());
                if (scene.mNumMeshes() == 0) throw new IOException("The model contains no meshes");
                List<String> external = externalTextures(scene);
                if (!external.isEmpty()) {
                    throw new IOException("The model references textures that are not embedded: " + String.join(", ", external));
                }
                AIExportDataBlob blob = aiExportSceneToBlob(scene, "glb2", 0);
                if (blob == null) throw new IOException("Assimp could not export GLB: " + aiGetErrorString());
                try {
                    if (blob.next() != null) throw new IOException("GLB export produced more than one file");
                    ByteBuffer data = blob.data();
                    byte[] output = new byte[data.remaining()];
                    data.get(output);
                    if (output.length < 12 || output[0] != 'g' || output[1] != 'l' || output[2] != 'T' || output[3] != 'F') {
                        throw new IOException("Assimp output is not a GLB file");
                    }
                    return output;
                } finally {
                    aiReleaseExportBlob(blob);
                }
            } finally {
                if (scene != null) aiReleaseImport(scene);
                for (long handle : open.keySet()) AIFile.create(handle).free();
                open.clear();
                served = Map.of();
            }
        }
    }

    /** Material texture paths that do not resolve to a texture embedded in the scene. */
    private static List<String> externalTextures(AIScene scene) {
        List<String> external = new ArrayList<>();
        for (int m = 0; m < scene.mNumMaterials(); m++) {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(m));
            // PBR texture types are numbered above aiTextureType_UNKNOWN; counts for unused numbers are zero.
            for (int type = aiTextureType_DIFFUSE; type <= MAX_TEXTURE_TYPE; type++) {
                int count = aiGetMaterialTextureCount(material, type);
                for (int t = 0; t < count; t++) {
                    try (AIString path = AIString.calloc()) {
                        if (aiGetMaterialTexture(material, type, t, path, (int[]) null, null, null, null, null, null) != aiReturn_SUCCESS) {
                            external.add("an unreadable texture slot");
                            continue;
                        }
                        String text = path.dataString();
                        // Assimp reports some unresolvable FBX file references as an empty path.
                        if (text.isEmpty()) external.add("a texture with no usable file name");
                        else if (aiGetEmbeddedTexture(scene, text) == null) external.add("'" + text + "'");
                    }
                }
            }
        }
        return external.stream().distinct().toList();
    }

    private static AIFileIO fileIO() {
        if (fileIO != null) return fileIO;
        AIFileReadProc read = AIFileReadProc.create((file, buffer, size, count) -> {
            ByteBuffer data = open.get(file);
            if (data == null || size == 0) return 0;
            long wanted = Math.min(size * count, data.remaining());
            int n = (int) (wanted - wanted % size);
            MemoryUtil.memByteBuffer(buffer, n).put(data.slice(data.position(), n));
            data.position(data.position() + n);
            return n / size;
        });
        AIFileWriteProc write = AIFileWriteProc.create((file, buffer, size, count) -> 0);
        AIFileTellProc tell = AIFileTellProc.create(file -> open.containsKey(file) ? open.get(file).position() : 0);
        AIFileTellProc length = AIFileTellProc.create(file -> open.containsKey(file) ? open.get(file).limit() : 0);
        AIFileSeek seek = AIFileSeek.create((file, offset, origin) -> {
            ByteBuffer data = open.get(file);
            if (data == null) return aiReturn_FAILURE;
            long base = origin == aiOrigin_SET ? 0 : origin == aiOrigin_CUR ? data.position() : data.limit();
            long target = base + offset;
            if (target < 0 || target > data.limit()) return aiReturn_FAILURE;
            data.position((int) target);
            return aiReturn_SUCCESS;
        });
        AIFileFlushProc flush = AIFileFlushProc.create(file -> { });
        AIFileOpenProc openProc = AIFileOpenProc.create((io, fileName, mode) -> {
            String requested = MemoryUtil.memUTF8(fileName);
            String key;
            try { key = ConversionInput.key(requested); }
            catch (RuntimeException invalid) { unserved.add(requested); return 0L; }
            byte[] bytes = served.get(key);
            if (bytes == null || !MemoryUtil.memUTF8(mode).startsWith("r")) {
                unserved.add(requested);
                return 0L;
            }
            AIFile file = AIFile.calloc().ReadProc(read).WriteProc(write).TellProc(tell).FileSizeProc(length)
                    .SeekProc(seek).FlushProc(flush);
            open.put(file.address(), ByteBuffer.wrap(bytes).asReadOnlyBuffer());
            return file.address();
        });
        AIFileCloseProc close = AIFileCloseProc.create((io, file) -> {
            if (open.remove(file) != null) AIFile.create(file).free();
        });
        // Callbacks live for the whole process; they are created once and never freed.
        fileIO = AIFileIO.calloc().OpenProc(openProc).CloseProc(close);
        return fileIO;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** UTF-8 helper for tests and callers that build OBJ/MTL inputs from text. */
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
