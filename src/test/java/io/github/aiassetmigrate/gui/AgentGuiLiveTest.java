package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.MigrationPlan;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live, opt-in check that the agent works through the real GUI: opens the application window, types "prepare this
 * project for godot 4.3" in the Agent drawer with the connected ChatGPT account, opens "Review N proposed decisions",
 * accepts all, and checks that the new plan in the Plan step has no blockers. Nothing is approved or migrated.
 * Run with: mvnw-local.cmd test -Dtest=AgentGuiLiveTest -Daam.gui.live=true (needs test-env\setup.cmd and 'auth connect').
 * Screenshots go to target/gui-live.
 */
@EnabledIfSystemProperty(named = "aam.gui.live", matches = "true")
class AgentGuiLiveTest {
    @TempDir Path directory;
    private final Path shots = Path.of("target", "gui-live");

    @Test void agentPrepareWorksThroughTheGui() throws Exception {
        Path messy = Path.of("test-env", "sources", "messy").toAbsolutePath();
        assertTrue(Files.isDirectory(messy), "run test-env\\setup.cmd first");
        Path source = directory.resolve("messy"), state = directory.resolve("state"), output = Files.createDirectories(directory.resolve("output"));
        copy(messy, source);
        UUID project;
        try (var manager = new DefaultAssetManager(Files.createDirectories(state))) {
            project = manager.importAssets(source).snapshot().project().id();
            manager.rescan(project, source, Set.of(Path.of("misc", "notes.txt")));
        }
        Files.createDirectories(shots);

        Preferences preferences = Preferences.userNodeForPackage(AssetMigrateApplication.class);
        Map<String, String> saved = new HashMap<>();
        for (String key : preferences.keys()) saved.put(key, preferences.get(key, null));
        try {
            try { Platform.startup(() -> { }); } catch (IllegalStateException alreadyStarted) { /* fine */ }
            AssetMigrateApplication app = new AssetMigrateApplication();
            // Application.launch registers the command-line parameters; do the same with none.
            com.sun.javafx.application.ParametersImpl.registerParameters(app, new com.sun.javafx.application.ParametersImpl(List.of()));
            Stage stage = fx(() -> { Stage s = new Stage(); app.start(s); return s; });
            pause(1500); // let the remembered project load before the fields are replaced
            fx(() -> {
                this.<TextField>field(app, "state").setText(state.toString());
                this.<TextField>field(app, "projectId").setText(project.toString());
                // Open the project as the Open button does, so the wizard's later steps unlock.
                var begin = AssetMigrateApplication.class.getDeclaredMethod("begin", boolean.class);
                begin.setAccessible(true);
                begin.invoke(app, true);
                return null;
            });
            waitFor(Duration.ofMinutes(1), () -> fxQuiet(() -> field(app, "currentSnapshot") != null));
            fx(() -> {
                this.<TextField>field(app, "outputRoot").setText(output.toString());
                this.<javafx.beans.property.ObjectProperty<AssetManager.TargetProfile>>field(app, "selectedTarget")
                        .set(new AssetManager.TargetProfile("godot", "4.3"));
                this.<ToggleButton>field(app, "agentTool").setSelected(true);
                TextField request = field(app, "agentRequest");
                request.setText("prepare this project for godot 4.3 into " + output);
                request.fireEvent(new ActionEvent());
                return null;
            });
            Button decisions = field(app, "agentDecisions");
            TextArea transcript = field(app, "agentTranscript");
            waitFor(Duration.ofMinutes(5), () -> fxQuiet(() -> transcript.getText().contains("Nothing was approved")
                    || transcript.getText().contains("failed")));
            shot(stage.getScene(), "1-agent-result.png");
            String text = fx(transcript::getText);
            System.out.println("=== Agent transcript ===\n" + text);
            assertTrue(text.contains("Prepared for godot 4.3"), text);
            assertFalse(text.contains("INFO "), "INFO notes are not shown in the transcript: " + text);
            assertTrue(text.strip().endsWith("Nothing was approved or migrated.")
                    || text.strip().contains("Review and approve it in the Plan step"), "the answer is at the end: " + text);
            assertTrue(fx(decisions::isVisible), "the drawer offers the proposed decisions");
            String button = fx(decisions::getText);
            System.out.println("Button: " + button);
            assertTrue(button.matches("Review \\d+ proposed decision\\(s\\)\\.\\.\\."), button);

            // Opening the dialog blocks the FX thread in a nested loop; drive it from a later pulse.
            Platform.runLater(decisions::fire);
            DialogPane dialog = waitForDialog();
            List<String> rows = fx(() -> lookupAll(dialog, CheckBox.class).stream().map(Labeled::getText).toList());
            System.out.println("=== Dialog rows ===");
            rows.forEach(row -> System.out.println("  [ ] " + row));
            assertFalse(rows.isEmpty());
            assertTrue(fx(() -> lookupAll(dialog, CheckBox.class).stream().noneMatch(CheckBox::isSelected)), "nothing is preselected");
            shot(dialog.getScene(), "2-review-dialog.png");
            fx(() -> { button(dialog, "Select all").fire(); return null; });
            assertTrue(fx(() -> lookupAll(dialog, CheckBox.class).stream().allMatch(CheckBox::isSelected)));
            shot(dialog.getScene(), "3-all-selected.png");
            fx(() -> { button(dialog, "Apply " + rows.size()).fire(); return null; });

            // Applying saves the decisions and creates a new plan in the Plan step.
            waitFor(Duration.ofMinutes(3), () -> fxQuiet(() -> field(app, "planContext") != null));
            pause(500);
            MigrationPlan plan = fx(() -> {
                Object context = field(app, "planContext");
                return (MigrationPlan) context.getClass().getMethod("plan").invoke(context);
            });
            Labeled status = field(app, "status");
            System.out.println("Status: " + fx(status::getText));
            long blocking = plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN).count();
            System.out.println("New plan " + plan.id() + ": " + plan.status() + ", " + plan.commands().size() + " commands, "
                    + blocking + " blocking");
            plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN)
                    .forEach(f -> System.out.println("  BLOCKER " + f.code() + " " + f.sourceRelativePath().orElse(null)));
            shot(stage.getScene(), "4-after-apply.png");
            assertEquals(io.github.aiassetmigrate.domain.PlanStatus.DRAFT, plan.status(), "applying never approves");
            assertFalse(fx(decisions::isVisible), "the applied bundle is no longer offered");
            assertEquals("PLAN", fx(() -> this.<Object>field(app, "currentStep").toString()), "the window shows the new plan");
            assertEquals(0, blocking, "the confirmed bundle clears every blocker");
            fx(() -> { stage.close(); return null; });
        } finally {
            preferences.clear();
            saved.forEach(preferences::put);
            preferences.flush();
        }
    }

    // ---------------------------------------------------------------- helpers

    private DialogPane waitForDialog() throws Exception {
        DialogPane[] found = new DialogPane[1];
        waitFor(Duration.ofSeconds(20), () -> fxQuiet(() -> {
            for (Window window : Window.getWindows()) {
                if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane) { found[0] = pane; return true; }
            }
            return false;
        }));
        return found[0];
    }

    private static Button button(DialogPane dialog, String prefix) {
        return lookupAll(dialog, Button.class).stream().filter(b -> b.getText() != null && b.getText().startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("no button " + prefix));
    }

    private static <T extends Node> List<T> lookupAll(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
        // A scroll pane's content is reached both directly and through its skin; count each node once.
        if (type.isInstance(node) && found.stream().noneMatch(seen -> seen == node)) found.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) collect(scroll.getContent(), type, found);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, found));
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Object app, String name) throws ReflectiveOperationException {
        Field field = AssetMigrateApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(app);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(work.call()); } catch (Throwable e) { result.completeExceptionally(e); }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static boolean fxQuiet(Callable<Boolean> check) {
        try { return fx(check); } catch (Exception e) { return false; }
    }

    private static void waitFor(Duration limit, BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) fail("timed out after " + limit);
            Thread.sleep(500);
        }
    }

    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }

    private void shot(Scene scene, String name) throws Exception {
        WritableImage image = fx(() -> scene.snapshot(null));
        PixelReader pixels = image.getPixelReader();
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, pixels.getArgb(x, y));
        ImageIO.write(out, "png", shots.resolve(name).toFile());
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
    }
}
