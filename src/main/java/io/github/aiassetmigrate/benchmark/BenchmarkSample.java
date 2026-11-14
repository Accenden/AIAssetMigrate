package io.github.aiassetmigrate.benchmark;

import io.github.aiassetmigrate.application.AssetManager.TargetProfile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A messy sample project with known right answers, for measuring what the AI adds to the rules (AI plan, step 1).
 * Each family exercises something the rules cannot settle alone: vaguely named files whose material names the object,
 * a broken reference with two equally named candidates, a texture that belongs to a model only by what it depicts,
 * a damaged image and a transparent texture. Written fresh into an empty folder each run; sources are never changed.
 */
public final class BenchmarkSample {
    private BenchmarkSample() {}

    /** A broken reference and the file it should point to (relative paths). */
    public record Correction(String owner, String reference, String target) {}

    /** An unreferenced texture that belongs to a model. */
    public record Grouping(String owner, String texture) {}

    /** How a proposed plan step is judged: expected (it should be proposed), acceptable (harmless either way) or wrong. */
    public enum Verdict { EXPECTED, ACCEPTABLE, WRONG }

    /** Acceptable categories per file; a texture or material may take its family's category or its type category. */
    public static final Map<String, Set<String>> CATEGORIES = categories();

    public static final List<Correction> CORRECTIONS = List.of(
            new Correction("enemies/goblin.mtl", "skin.png", "enemies/textures/skin.png"),
            new Correction("weapons/sword.mtl", "sword_tex.png", "textures/sword_tex.png"));

    public static final List<Grouping> GROUPINGS = List.of(
            new Grouping("environment/rock.obj", "environment/rock_albedo.jpg"),
            new Grouping("environment/tree_oak.obj", "environment/T_Bark_D.png"));

    /** Groupings that are true but not required: a texture with its own family (it is also a broken reference's target). */
    public static final Set<Grouping> ACCEPTABLE_GROUPINGS = Set.of(
            new Grouping("weapons/sword.obj", "textures/sword_tex.png"), new Grouping("weapons/sword.mtl", "textures/sword_tex.png"),
            new Grouping("enemies/goblin.obj", "enemies/textures/skin.png"), new Grouping("enemies/goblin.mtl", "enemies/textures/skin.png"));

    /** Files whose output name has a right answer, and the pattern it must match (case-insensitive). */
    public static final Map<String, Pattern> NAMES = names();

    private static final Set<String> MODELS = Set.of("import/mdl_0001.obj", "enemies/goblin.obj", "npc/villager/villager.obj",
            "weapons/sword.obj", "misc/model_final_final.obj", "props/barrel.fbx", "environment/rock.obj", "environment/tree_oak.obj");
    private static final Set<String> OPAQUE_PNGS = Set.of("import/IMG_0042.PNG", "enemies/textures/skin.png",
            "npc/villager/skin.png", "textures/sword_tex.png", "environment/T_Bark_D.png");

    private static Map<String, Set<String>> categories() {
        Map<String, Set<String>> key = new LinkedHashMap<>();
        key.put("import/mdl_0001.obj", Set.of("Characters"));
        key.put("import/knight.mtl", Set.of("Characters", "Materials"));
        key.put("import/IMG_0042.PNG", Set.of("Characters", "Textures"));
        key.put("enemies/goblin.obj", Set.of("Characters"));
        key.put("enemies/goblin.mtl", Set.of("Characters", "Materials"));
        key.put("enemies/textures/skin.png", Set.of("Characters", "Textures"));
        key.put("npc/villager/villager.obj", Set.of("Characters"));
        key.put("npc/villager/villager.mtl", Set.of("Characters", "Materials"));
        key.put("npc/villager/skin.png", Set.of("Characters", "Textures"));
        key.put("weapons/sword.obj", Set.of("Weapons"));
        key.put("weapons/sword.mtl", Set.of("Weapons", "Materials"));
        key.put("textures/sword_tex.png", Set.of("Weapons", "Textures"));
        key.put("misc/model_final_final.obj", Set.of("Props"));
        key.put("misc/chest.mtl", Set.of("Props", "Materials"));
        key.put("misc/wood_planks.jpg", Set.of("Props", "Textures"));
        key.put("props/barrel.fbx", Set.of("Props"));
        key.put("environment/rock.obj", Set.of("Environment"));
        key.put("environment/rock_albedo.jpg", Set.of("Environment", "Textures"));
        key.put("environment/tree_oak.obj", Set.of("Environment"));
        key.put("environment/T_Bark_D.png", Set.of("Environment", "Textures"));
        key.put("environment/grass_alpha.png", Set.of("Environment", "Textures"));
        key.put("ui/MainMenu Button.png", Set.of("UI"));
        key.put("ui/IMG_0101.png", Set.of("UI"));
        key.put("misc/broken.png", Set.of("Textures"));
        return Map.copyOf(key);
    }

    private static Map<String, Pattern> names() {
        Map<String, Pattern> key = new LinkedHashMap<>();
        key.put("import/mdl_0001.obj", Pattern.compile(".*knight.*\\.obj", Pattern.CASE_INSENSITIVE));
        key.put("import/IMG_0042.PNG", Pattern.compile(".*knight.*\\.png", Pattern.CASE_INSENSITIVE));
        key.put("misc/model_final_final.obj", Pattern.compile(".*chest.*\\.obj", Pattern.CASE_INSENSITIVE));
        key.put("ui/MainMenu Button.png", Pattern.compile("main_?menu_button\\.png", Pattern.CASE_INSENSITIVE));
        key.put("environment/T_Bark_D.png", Pattern.compile(".*bark.*\\.png", Pattern.CASE_INSENSITIVE));
        return Map.copyOf(key);
    }

    /** Plan steps every target should get: leave out the damaged image and fix both broken references. */
    public static Set<String> expectedSteps() {
        return Set.of(exclude("misc/broken.png"), fix("enemies/goblin.mtl", "skin.png", "enemies/textures/skin.png"),
                fix("weapons/sword.mtl", "sword_tex.png", "textures/sword_tex.png"));
    }

    public static String exclude(String path) { return "EXCLUDE " + path; }
    public static String convert(String path, String format) { return "CONVERT " + path + " " + format; }
    public static String fix(String owner, String reference, String target) { return "FIX " + owner + " '" + reference + "' -> " + target; }

    /**
     * Judges one plan step for a target. Converting models to GLB suits Godot (its recommended format) but adds the
     * glTFast dependency in Unity; converting an opaque PNG to JPG is harmless; anything else not expected is wrong
     * (for example flattening the transparent grass texture, or leaving out a usable file).
     */
    public static Verdict judge(TargetProfile target, String step) {
        if (expectedSteps().contains(step)) return Verdict.EXPECTED;
        for (String model : MODELS) {
            if (step.equals(convert(model, "glb"))) return target.id().equals("godot") ? Verdict.ACCEPTABLE : Verdict.WRONG;
        }
        for (String png : OPAQUE_PNGS) {
            if (step.equals(convert(png, "jpg"))) return Verdict.ACCEPTABLE;
        }
        return Verdict.WRONG;
    }

    /** Writes the sample into {@code root}, which must be empty or missing. */
    public static void write(Path root) throws IOException {
        Files.createDirectories(root);
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) throw new IOException("The benchmark folder must be empty: " + root);
        }
        // Knight: the model and texture are named vaguely; only the material names the object.
        text(root, "import/mdl_0001.obj", obj("Knight", "knight.mtl", "Armor"));
        text(root, "import/knight.mtl", "newmtl Armor\nKd 0.7 0.7 0.75\nmap_Kd IMG_0042.PNG\n");
        image(root, "import/IMG_0042.PNG", "png", 64, false, new Color(150, 150, 165));
        // Goblin: skin.png is not next to the material, and two files have that name.
        text(root, "enemies/goblin.obj", obj("Goblin", "goblin.mtl", "Skin"));
        text(root, "enemies/goblin.mtl", "newmtl Skin\nKd 0.4 0.6 0.3\nmap_Kd skin.png\n");
        image(root, "enemies/textures/skin.png", "png", 64, false, new Color(90, 140, 60));
        text(root, "npc/villager/villager.obj", obj("Villager", "villager.mtl", "Skin"));
        text(root, "npc/villager/villager.mtl", "newmtl Skin\nKd 0.9 0.75 0.6\nmap_Kd skin.png\n");
        image(root, "npc/villager/skin.png", "png", 64, false, new Color(230, 190, 150));
        // Sword: one file of the expected name exists elsewhere, so the rules can fix it.
        text(root, "weapons/sword.obj", obj("Sword", "sword.mtl", "Steel"));
        text(root, "weapons/sword.mtl", "newmtl Steel\nKd 0.8 0.8 0.85\nmap_Kd sword_tex.png\n");
        image(root, "textures/sword_tex.png", "png", 64, false, new Color(200, 200, 210));
        // Chest: a meaningless model name in a meaningless folder.
        text(root, "misc/model_final_final.obj", obj("Chest", "chest.mtl", "Wood"));
        text(root, "misc/chest.mtl", "newmtl Wood\nKd 0.6 0.4 0.2\nmap_Kd wood_planks.jpg\n");
        image(root, "misc/wood_planks.jpg", "jpg", 64, false, new Color(140, 95, 50));
        // A real binary FBX with its texture embedded.
        try (InputStream fbx = BenchmarkSample.class.getResourceAsStream("barrel.fbx")) {
            if (fbx == null) throw new IOException("The benchmark FBX resource is missing");
            Files.createDirectories(root.resolve("props"));
            Files.write(root.resolve("props/barrel.fbx"), fbx.readAllBytes());
        }
        // Environment: one texture shares its model's name, one only depicts it (bark for a tree).
        text(root, "environment/rock.obj", obj("Rock", null, null));
        image(root, "environment/rock_albedo.jpg", "jpg", 64, false, new Color(120, 120, 115));
        text(root, "environment/tree_oak.obj", obj("TreeOak", null, null));
        image(root, "environment/T_Bark_D.png", "png", 64, false, new Color(100, 70, 40));
        image(root, "environment/grass_alpha.png", "png", 64, true, new Color(60, 170, 60));
        // UI: transparent icons, one with a camera-style name.
        image(root, "ui/MainMenu Button.png", "png", 32, true, new Color(70, 70, 80));
        image(root, "ui/IMG_0101.png", "png", 32, true, new Color(200, 60, 60));
        // A truncated PNG: inventoried by fingerprint, but it cannot be decoded.
        byte[] png = encode("png", 32, false, Color.GRAY);
        Files.createDirectories(root.resolve("misc"));
        Files.write(root.resolve("misc/broken.png"), Arrays.copyOf(png, 40));
    }

    /** The number of files {@link #write} creates. */
    public static int fileCount() { return CATEGORIES.size(); }

    private static String obj(String name, String library, String material) {
        StringBuilder text = new StringBuilder("# ").append(name).append('\n');
        if (library != null) text.append("mtllib ").append(library).append('\n');
        text.append("o ").append(name).append('\n');
        text.append("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 0 1\n");
        if (material != null) text.append("usemtl ").append(material).append('\n');
        text.append("f 1/1/1 2/2/1 3/3/1 4/4/1\n");
        return text.toString();
    }

    private static void text(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void image(Path root, String path, String format, int size, boolean transparent, Color color) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, encode(format, size, transparent, color));
    }

    private static byte[] encode(String format, int size, boolean transparent, Color color) {
        BufferedImage image = new BufferedImage(size, size, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            if (!transparent) {
                graphics.setColor(color.darker());
                graphics.fillRect(0, 0, size, size);
            }
            graphics.setColor(color);
            for (int y = 0; y < size; y += 8) {
                for (int x = (y / 8) % 2 * 8; x < size; x += 16) graphics.fillRect(x, y, 8, 8);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format.equals("jpg") ? "jpeg" : format, out)) throw new IOException("No " + format + " writer");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
