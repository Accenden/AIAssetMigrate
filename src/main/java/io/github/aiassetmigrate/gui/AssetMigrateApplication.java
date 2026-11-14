package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.BlockerFixes;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.MigrationCommand;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.PlanStatus;
import io.github.aiassetmigrate.domain.Report;
import io.github.aiassetmigrate.domain.RunStatus;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.prefs.Preferences;

/**
 * JavaFX client. The core workflow is a five-step wizard (Project, Review, Target, Plan, Migrate)
 * with a stepper header and a fixed Back/Continue footer; the agent, live logs and AI account are
 * tools in a side drawer. Rarely needed controls are collapsed (progressive disclosure). Every
 * operation runs on the {@link ImportRunner} worker through the {@link AssetManager} facade.
 */
public final class AssetMigrateApplication extends Application {
    /** Only changes are suggested, so the defaults pick styles that change something; nothing is applied without Accept. */
    static final AssetManager.OrganizationRules DEFAULT_ORGANIZATION_RULES = new AssetManager.OrganizationRules(
            AssetManager.NameStyle.SNAKE_CASE, AssetManager.FolderStyle.BY_TYPE);
    private static final String PREF_WORKSPACE = "workspace";
    private static final String PREF_PROJECT = "project";
    private static final int MAX_BLOCKERS_SHOWN = 8;

    private final ImportRunner runner = new ImportRunner(Platform::runLater);
    private final Preferences preferences = Preferences.userNodeForPackage(AssetMigrateApplication.class);

    // Wizard frame
    private WizardFlow.Step currentStep = WizardFlow.Step.PROJECT;
    private boolean reviewVisited;
    private final Map<WizardFlow.Step, Node> stepViews = new EnumMap<>(WizardFlow.Step.class);
    private final Map<WizardFlow.Step, Button> stepButtons = new EnumMap<>(WizardFlow.Step.class);
    private final Map<WizardFlow.Step, Label> stepIndexes = new EnumMap<>(WizardFlow.Step.class);
    private final StackPane stepHost = new StackPane();
    private final HBox stepper = new HBox(6);
    private final Button back = new Button("Back");
    private final Button next = new Button("Continue");
    private final Label projectChip = new Label("No project");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label status = new Label("Start by importing a folder of game assets.");

    // Step 1: project
    private final ToggleButton importMode = new ToggleButton("Import a folder");
    private final ToggleButton openMode = new ToggleButton("Open a saved project");
    private final TextField source = new TextField();
    private final TextField state = new TextField();
    private final TextField projectId = new TextField();
    private final TextField revision = new TextField();
    private final HBox summaryBadges = new HBox(6);
    private final VBox loadedBox = new VBox(10);
    private final TableView<Asset> inventory = new TableView<>();
    private final TextArea findings = new TextArea();
    private final TitledPane findingsPane = collapsible("Scan findings", findings);
    /** Explains a partial scan and offers to leave skipped files out, or to include left-out files again. */
    private final VBox coverageBox = new VBox(8);
    private static final int MAX_SKIPPED_SHOWN = 6;
    private InventorySnapshot currentSnapshot;
    private final Map<UUID, String> assetPaths = new HashMap<>();

    // Step 2: review
    private final CheckBox useAi = new CheckBox("Use AI suggestions");
    private final ToggleGroup analysisKinds = new ToggleGroup();
    private final TableView<AssetManager.Suggestion> suggestions = new TableView<>();
    private final Label analysisRevision = new Label("No check run yet.");
    private final TextArea analysisFindings = new TextArea();
    private final TitledPane analysisFindingsPane = collapsible("Check findings", analysisFindings);
    private final TextArea savedReviews = new TextArea();
    private final TextArea organizationPreview = new TextArea();
    private final TextField reviewAsset = new TextField();
    private final ComboBox<ReviewedChange.Kind> reviewKind = new ComboBox<>();
    private final TextField reviewValue = new TextField();
    private final TextField reviewReference = new TextField();
    private final ComboBox<ReviewedChange.Decision> decision = new ComboBox<>();
    private final ComboBox<AssetManager.NameStyle> nameStyle = new ComboBox<>();
    private final ComboBox<AssetManager.FolderStyle> folderStyle = new ComboBox<>();
    private TitledPane manualReviewPane;
    private AnalysisContext analysisContext;
    /** Plain-language summary of the last AI-assisted check, shown below the suggestions; null when hidden. */
    private final VBox aiSummary = new VBox(10);
    private ReviewSummary currentSummary;
    /** Suggestions decided in this check, by id, with the label shown in the summary ("Accepted", "Set aside", ...). */
    private final Map<UUID, String> decidedSuggestions = new HashMap<>();
    /** What Accept and Reject would do for the selected suggestion. */
    private final Label decisionHint = new Label();
    static final String NO_SELECTION_HINT = "Select a suggestion to see exactly what Accept and Reject would do. "
            + "Accept uses the change when the plan is created; Reject keeps the file as it is.";

    // Step 3: target
    private final ObjectProperty<AssetManager.TargetProfile> selectedTarget = new SimpleObjectProperty<>();
    private final ToggleGroup targetGroup = new ToggleGroup();
    private final FlowPane targetCards = new FlowPane(10, 10);
    private final CheckBox compatibilityAi = new CheckBox("Explain issues with AI");
    private final HBox compatibilityBadges = new HBox(6);
    private final TextArea compatibilityResults = new TextArea();
    /** One card per issue type, with the AI explanation when requested; the raw list is collapsed below. */
    private final VBox compatibilityIssues = new VBox(10);
    private final TitledPane compatibilityRawPane = collapsible("All findings", compatibilityResults);
    private final CompatibilityGeneration compatibilityGeneration = new CompatibilityGeneration();

    // Step 4: plan
    private final TextField outputRoot = new TextField();
    private final CheckBox planAi = new CheckBox("Ask AI for suggestions");
    /** The AI's checked suggestions for the displayed plan (conversions, files to leave out, reference fixes). */
    private final VBox planSuggestionsCard = new VBox(8);
    private final Button createPlanButton = styled(new Button("Create plan"), "primary");
    private final Button approvePlanButton = new Button("Approve this plan");
    private final Button fixBlockersButton = new Button("Fix blockers...");
    private final HBox planBadges = new HBox(6);
    private final Label planGuidance = new Label();
    private final TableView<MigrationCommand> planTable = new TableView<>();
    private final TextArea planDetails = new TextArea();
    private final CompatibilityGeneration planGeneration = new CompatibilityGeneration();
    private PlanContext planContext;

    // Step 5: migrate
    private final Label migrateReady = new Label();
    private final Button runButton = styled(new Button("Run migration"), "primary");
    private final CheckBox validationAi = new CheckBox("Add an AI summary to the report");
    private final HBox migrateBadges = new HBox(6);
    /** After a run that did not pass: what to do about each problem, from {@link io.github.aiassetmigrate.validation.ValidationNextSteps}. */
    private final VBox migrateGuidance = new VBox(8);
    /** The report's AI summary in its own card (or why there is none); hidden when no AI summary was asked for. */
    private final VBox reportAiSummary = new VBox(8);
    /** The report's findings, problems first, one row per file; the full text stays in the collapsible log. */
    private final VBox reportFindings = new VBox(8);
    private TitledPane runLogPane;
    private final TextField validationRun = new TextField();
    private final TextArea validationResults = new TextArea();
    private AssetManager.MigrationOutcome lastOutcome;

    // Tools drawer
    private final ToggleGroup tools = new ToggleGroup();
    private final ToggleButton agentTool = new ToggleButton("Agent");
    private final ToggleButton logsTool = new ToggleButton("Live logs");
    private final ToggleButton aiTool = new ToggleButton("AI: checking...");
    private final VBox drawer = new VBox(12);
    private final Label drawerTitle = new Label();
    private final StackPane drawerContent = new StackPane();
    private final Label aiStatus = new Label("AI account: checking...");
    private final ComboBox<io.github.aiassetmigrate.ai.SiwcInference.Model> aiModel = new ComboBox<>();
    private final Button refreshModels = new Button("Refresh");
    /** True while the model list is replaced in code, so only user choices are saved. */
    private boolean updatingModels;
    private final VBox agentPane = new VBox(10);
    private final TextField agentRequest = new TextField();
    private final TextArea agentTranscript = new TextArea();
    /** Opens the last agent result's proposed decisions; hidden when it had none. */
    private final Button agentDecisions = new Button();
    private final VBox logPane = new VBox(10);
    private final VBox aiPane = new VBox(10);
    private final TextField logAfter = new TextField("0");
    private final TextArea logView = new TextArea();
    private final Label logStatus = new Label("Not following.");
    private io.github.aiassetmigrate.logging.LogFollower logFollower;

    record PlanRequest(UUID project, Path state, Path logs, Path output, AssetManager.TargetProfile target) {
        PlanContext create(AssetManager manager) {
            MigrationPlan plan = manager.createPlan(project, target, output);
            if (!project.equals(plan.projectId()) || !target.id().equals(plan.targetProfile())
                    || !target.version().equals(plan.targetProfileVersion()) || !output.equals(plan.outputRoot())
                    || plan.status() != PlanStatus.DRAFT) {
                throw new IllegalStateException("Created plan does not match the requested draft context.");
            }
            InventorySnapshot inventory = manager.loadInventory(plan.projectId(), plan.inventoryRevision());
            return PlanContext.fromInventory(this, plan, inventory);
        }

        /**
         * AI-assisted draft (F05/F06): the rule-based draft plus the AI's suggestions, each already checked by the
         * program. Suggestions change nothing until the user accepts them; a suggestion failure keeps the plan.
         */
        PlanResult create(AssetManager manager, io.github.aiassetmigrate.ai.LLMClient assistant) {
            PlanContext context = create(manager);
            try {
                AssetManager.PlanSuggestions suggestions = manager.suggestPlanSteps(project, target, assistant);
                if (suggestions.proposal().inventoryRevision() != context.plan().inventoryRevision()) {
                    return new PlanResult(context, Optional.empty(), Optional.of("The project changed while the AI was asked; create the plan again."));
                }
                return new PlanResult(context, Optional.of(suggestions), Optional.empty());
            } catch (RuntimeException failure) {
                String text = failure.getMessage();
                return new PlanResult(context, Optional.empty(), Optional.of(text == null || text.isBlank()
                        ? failure.getClass().getSimpleName() : text));
            }
        }
    }

    /** A created plan and, when AI was asked, its checked suggestions or why there are none. */
    record PlanResult(PlanContext context, Optional<AssetManager.PlanSuggestions> suggestions, Optional<String> suggestionFailure) {}

    record PlanContext(PlanRequest request, MigrationPlan plan, Map<UUID, Path> sources, List<String> sourceErrors) {
        PlanContext {
            sources = Map.copyOf(sources);
            sourceErrors = List.copyOf(sourceErrors);
        }

        static PlanContext fromInventory(PlanRequest request, MigrationPlan plan, InventorySnapshot inventory) {
            Map<UUID, Path> sources = new HashMap<>();
            List<String> errors = new ArrayList<>();
            if (!plan.projectId().equals(inventory.project().id())
                    || plan.inventoryRevision() != inventory.project().inventoryRevision()) {
                errors.add("Inventory project or revision does not match the plan; source paths cannot be verified.");
            } else {
                for (Asset asset : inventory.project().assets()) {
                    if (sources.putIfAbsent(asset.id(), asset.relativePath()) != null) {
                        errors.add("Duplicate inventory asset ID: " + asset.id());
                    }
                }
            }
            for (var command : plan.commands()) {
                if (!sources.containsKey(command.assetId())) errors.add("Unknown source asset ID: " + command.assetId());
            }
            for (var change : plan.reviewedChanges()) {
                if (!sources.containsKey(change.assetId())) errors.add("Unknown reviewed asset ID: " + change.assetId());
            }
            return new PlanContext(request, plan, sources, errors);
        }

        boolean sourcesVerified() {
            return sourceErrors.isEmpty() && plan.commands().stream().allMatch(command -> sources.containsKey(command.assetId()))
                    && plan.reviewedChanges().stream().allMatch(change -> sources.containsKey(change.assetId()));
        }

        MigrationPlan approve(AssetManager manager) {
            if (plan.status() != PlanStatus.DRAFT) throw new IllegalStateException("Display a draft before approval.");
            if (!sourcesVerified()) throw new IllegalStateException("Source paths could not be verified; approval is blocked.");
            MigrationPlan approved = manager.approve(plan.id(), plan.version());
            if (!sameExceptStatus(plan, approved) || approved.status() != PlanStatus.APPROVED) {
                throw new IllegalStateException("Approval returned a different plan; reload and review it.");
            }
            return approved;
        }

        /** Migrates the exact approved plan, then validates the run and saves a report (UC07). */
        AssetManager.MigrationOutcome migrate(AssetManager manager,
                                              Optional<io.github.aiassetmigrate.ai.LLMClient> assistant) {
            if (plan.status() != PlanStatus.APPROVED) throw new IllegalStateException("Explicitly approve the displayed plan first.");
            if (!sourcesVerified()) {
                throw new IllegalStateException("Source paths could not be verified; migration is blocked.");
            }
            if (!plan.equals(manager.loadPlan(plan.id()))) {
                throw new IllegalStateException("Stored plan changed; create and review a new draft.");
            }
            AssetManager.MigrationOutcome outcome = manager.migrateAndValidate(plan.id(), assistant);
            if (!outcome.run().planId().equals(plan.id())) throw new IllegalStateException("Migration returned a run for a different plan.");
            return outcome;
        }
    }

    private static boolean sameExceptStatus(MigrationPlan a, MigrationPlan b) {
        return a.id().equals(b.id()) && a.projectId().equals(b.projectId()) && a.version() == b.version()
                && a.inventoryRevision() == b.inventoryRevision() && a.targetProfile().equals(b.targetProfile())
                && a.targetProfileVersion().equals(b.targetProfileVersion()) && a.outputRoot().equals(b.outputRoot())
                && a.reviewedChanges().equals(b.reviewedChanges()) && a.commands().equals(b.commands())
                && a.findings().equals(b.findings()) && a.createdAt().equals(b.createdAt());
    }

    static final class CompatibilityGeneration {
        private long value;
        long current() { return value; }
        void invalidate() { value++; }
        boolean isCurrent(long captured) { return value == captured; }
    }

    record CompatibilityRequest(UUID project, AssetManager.TargetProfile target) {
        AssetManager.CompatibilityResult assess(AssetManager manager) {
            return checked(manager.assessCompatibility(project, target));
        }

        AssetManager.CompatibilityResult assess(AssetManager manager, io.github.aiassetmigrate.ai.LLMClient assistant) {
            return checked(manager.assessCompatibility(project, target, assistant));
        }

        private AssetManager.CompatibilityResult checked(AssetManager.CompatibilityResult result) {
            if (!target.equals(result.target())) throw new IllegalStateException("Compatibility result target does not match the requested target.");
            return result;
        }
    }

    record AnalysisContext(UUID project, Path state, Path logs, AssetManager.AnalysisResult result) {
        String revisionText() {
            return "Analysis inventory revision: " + result.inventoryRevision();
        }

        List<ReviewedChange> review(AssetManager manager, ReviewedChange change) {
            return review(manager, List.of(change));
        }

        /** Saves the decisions together in one review revision: all of them or none. */
        List<ReviewedChange> review(AssetManager manager, List<ReviewedChange> changes) {
            return manager.review(project, result.inventoryRevision(), changes);
        }

        List<ReviewedChange> loadReviews(AssetManager manager) {
            checkRevision(manager);
            List<ReviewedChange> reviews = manager.loadReviews(project);
            checkRevision(manager);
            return reviews;
        }

        OrganizationPreview.Result preview(AssetManager manager) {
            OrganizationPreview.Result preview = manager.previewOrganization(project, result.inventoryRevision());
            if (!project.equals(preview.projectId()) || result.inventoryRevision() != preview.inventoryRevision()) {
                throw new IllegalStateException("Preview does not match the analyzed project and revision.");
            }
            return preview;
        }

        private void checkRevision(AssetManager manager) {
            if (manager.loadInventory(project).project().inventoryRevision() != result.inventoryRevision()) {
                throw new IllegalStateException("Inventory revision changed; run analysis again before loading reviews.");
            }
        }
    }

    /** The dark shadcn-style theme, loaded over modena; see dark-theme.css. */
    static String stylesheet() {
        var url = AssetMigrateApplication.class.getResource("dark-theme.css");
        if (url == null) throw new IllegalStateException("dark-theme.css is missing from the application resources");
        return url.toExternalForm();
    }

    // ---------------------------------------------------------------- layout

    @Override
    public void start(Stage stage) {
        state.setText(preference(PREF_WORKSPACE, Path.of(System.getProperty("user.home"), "AIAssetMigrate").toString()));
        String lastProject = preference(PREF_PROJECT, "");
        projectId.setText(lastProject);

        stepViews.put(WizardFlow.Step.PROJECT, buildProjectStep(stage));
        stepViews.put(WizardFlow.Step.REVIEW, buildReviewStep());
        stepViews.put(WizardFlow.Step.TARGET, buildTargetStep());
        stepViews.put(WizardFlow.Step.PLAN, buildPlanStep(stage));
        stepViews.put(WizardFlow.Step.MIGRATE, buildMigrateStep());
        buildTools();
        installInvalidation();
        (lastProject.isBlank() ? importMode : openMode).setSelected(true);

        BorderPane root = new BorderPane();
        root.setTop(new VBox(buildHeader(), buildStepper()));
        root.setCenter(stepHost);
        root.setRight(drawer);
        root.setBottom(buildFooter());
        stepHost.getStyleClass().add("step-host");

        Scene scene = new Scene(root, 1120, 780);
        scene.getStylesheets().add(stylesheet());
        stage.setTitle("AIAssetMigrate");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setOnCloseRequest(event -> {
            if (runner.isBusy()) {
                event.consume();
                status.setText("Wait for the current operation to finish before closing.");
            } else {
                stopLogs();
            }
        });
        showStep(WizardFlow.Step.PROJECT);
        refreshAiStatus();
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(() -> {
                System.out.println("JavaFX import window launched successfully.");
                stage.close();
                Platform.exit();
            });
        }
    }

    private Node buildHeader() {
        Label brand = new Label("AIAssetMigrate");
        brand.getStyleClass().add("brand");
        projectChip.getStyleClass().add("badge");
        for (ToggleButton tool : List.of(agentTool, logsTool, aiTool)) {
            tool.setToggleGroup(tools);
            tool.getStyleClass().add("tool-toggle");
        }
        agentTool.setTooltip(new Tooltip("Ask for an action in plain language"));
        logsTool.setTooltip(new Tooltip("Watch this project's activity as it happens"));
        aiTool.setTooltip(new Tooltip("Connect or disconnect your ChatGPT account (optional)"));
        tools.selectedToggleProperty().addListener((o, previous, selected) -> showTool(selected));
        HBox header = new HBox(10, brand, projectChip, spacer(), agentTool, logsTool, aiTool);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private Node buildStepper() {
        for (WizardFlow.Step step : WizardFlow.Step.values()) {
            Label index = new Label(Integer.toString(step.number()));
            index.getStyleClass().add("step-index");
            Label title = new Label(step.title);
            title.getStyleClass().add("step-label");
            Button button = new Button();
            button.setGraphic(new HBox(8, index, title));
            ((HBox) button.getGraphic()).setAlignment(Pos.CENTER_LEFT);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().setAll("step");
            button.setOnAction(event -> showStep(step));
            stepButtons.put(step, button);
            stepIndexes.put(step, index);
            if (step.ordinal() > 0) {
                Region connector = new Region();
                connector.getStyleClass().add("step-connector");
                HBox.setHgrow(connector, Priority.ALWAYS);
                stepper.getChildren().add(connector);
            }
            stepper.getChildren().add(button);
        }
        stepper.setAlignment(Pos.CENTER_LEFT);
        stepper.getStyleClass().add("stepper");
        return stepper;
    }

    private Node buildFooter() {
        progress.setMaxSize(18, 18);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        status.setWrapText(true);
        status.getStyleClass().add("status");
        back.setOnAction(event -> currentStep.previous().ifPresent(this::showStep));
        next.setOnAction(event -> currentStep.next().ifPresent(this::showStep));
        // A long status wraps; the navigation buttons never shrink to "...".
        back.setMinWidth(Region.USE_PREF_SIZE);
        next.setMinWidth(Region.USE_PREF_SIZE);
        HBox footer = new HBox(10, progress, status, spacer(), back, next);
        HBox.setHgrow(status, Priority.SOMETIMES);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("app-footer");
        return footer;
    }

    private Node buildProjectStep(Stage stage) {
        ToggleGroup modes = new ToggleGroup();
        importMode.setToggleGroup(modes);
        openMode.setToggleGroup(modes);
        keepOneSelected(modes);

        source.setPromptText("Folder that contains your models, materials and textures");
        Button importButton = styled(new Button("Import"), "primary");
        importButton.setOnAction(event -> begin(false));
        VBox importForm = new VBox(6, fieldLabel("Asset folder"),
                row(source, browseButton(stage, source, "Select the asset folder"), importButton));

        projectId.setPromptText("Project ID (shown after an import; the last one is remembered)");
        revision.setPromptText("Latest");
        revision.setPrefColumnCount(6);
        Button openButton = styled(new Button("Open"), "primary");
        openButton.setOnAction(event -> begin(true));
        Label revisionLabel = new Label("Revision");
        revisionLabel.getStyleClass().add("muted");
        VBox openForm = new VBox(6, fieldLabel("Project ID"), row(projectId, revisionLabel, revision, openButton));
        showWhen(importForm, importMode);
        showWhen(openForm, openMode);

        state.setPromptText("Folder where projects, plans, reports and logs are saved");
        TitledPane workspace = collapsible("Workspace folder", new VBox(6,
                row(state, browseButton(stage, state, "Select the workspace folder")),
                muted("Projects, plans, reports and logs are saved here, with logs in its logs folder. Keep it outside "
                        + "your asset folder. The same workspace is used next time.")));

        Button rescan = new Button("Rescan folder");
        rescan.setTooltip(new Tooltip("Scan the asset folder again after you change files in it"));
        rescan.setOnAction(event -> begin(false, true));
        HBox loadedHeader = new HBox(8, summaryBadges, spacer(), rescan);
        loadedHeader.setAlignment(Pos.CENTER_LEFT);
        summaryBadges.setAlignment(Pos.CENTER_LEFT);

        addColumn("File", asset -> io.github.aiassetmigrate.domain.PortablePath.of(asset.relativePath()), 320, true);
        addColumn("Type", asset -> label(asset.type().toString()), 90, true);
        addColumn("Format", asset -> asset.metadata().getOrDefault(Asset.FORMAT, ""), 70, true);
        addColumn("Size", asset -> humanBytes(asset.metadata().getOrDefault(Asset.SIZE_BYTES, "")), 80, true);
        addColumn("Inspection", asset -> label(asset.metadata().getOrDefault(Asset.INSPECTION_STATUS, "")), 130, true);
        addColumn("Asset ID", asset -> asset.id().toString(), 270, false);
        addColumn("Modified", asset -> asset.metadata().getOrDefault(Asset.MODIFIED_AT, ""), 180, false);
        addColumn("SHA-256", Asset::fingerprint, 300, false);
        inventory.setTableMenuButtonVisible(true);
        inventory.setPlaceholder(new Label("No files in this project."));
        inventory.setPrefHeight(320);
        readOnly(findings, "Scan findings and skipped files appear here.");
        coverageBox.getStyleClass().add("callout");
        coverageBox.setVisible(false);
        coverageBox.managedProperty().bind(coverageBox.visibleProperty());
        loadedBox.getChildren().addAll(loadedHeader, coverageBox, inventory, findingsPane);
        VBox.setVgrow(inventory, Priority.ALWAYS);
        VBox.setVgrow(loadedBox, Priority.ALWAYS);

        VBox view = stepView("Choose your assets",
                "Import a folder of models, materials and textures, or reopen a project you imported before. "
                        + "Your files are only read, never changed.",
                segmented(importMode, openMode), importForm, openForm, workspace, loadedBox);
        return scrollable(view);
    }

    private Node buildReviewStep() {
        List<ToggleButton> kinds = new ArrayList<>();
        Map<AssetManager.AnalysisKind, String> tips = Map.of(
                AssetManager.AnalysisKind.CATEGORIES, "Suggest a category such as Characters or Props for each file",
                AssetManager.AnalysisKind.RELATIONSHIPS, "Find which files belong together and fix broken references",
                AssetManager.AnalysisKind.MATERIALS, "Check material and texture links",
                AssetManager.AnalysisKind.NAMES, "Suggest cleaner file names",
                AssetManager.AnalysisKind.FOLDERS, "Suggest an output folder for each file");
        for (AssetManager.AnalysisKind kind : AssetManager.AnalysisKind.values()) {
            ToggleButton button = new ToggleButton(label(kind.toString()));
            button.setToggleGroup(analysisKinds);
            button.setTooltip(new Tooltip(tips.get(kind)));
            button.setOnAction(event -> {
                button.setSelected(true);
                analyze(kind);
            });
            kinds.add(button);
        }
        keepOneSelected(analysisKinds);
        useAi.setTooltip(new Tooltip("Adds AI suggestions next to the rule-based ones. Sends relative paths, types and "
                + "reference text to OpenAI, never file contents. Connect an account with the AI button above."));
        HBox checks = new HBox(14, segmented(kinds.toArray(ToggleButton[]::new)), useAi);
        checks.setAlignment(Pos.CENTER_LEFT);

        suggestionColumn("File", suggestion -> assetPaths.getOrDefault(suggestion.assetId(), suggestion.assetId().toString()), 280, true);
        suggestionColumn("Check", suggestion -> label(suggestion.kind().toString()), 110, true);
        suggestionColumn("Suggestion", this::displayValue, 300, true);
        suggestionColumn("Reference", suggestion -> suggestion.reference().orElse(""), 140, true);
        suggestionColumn("Source", suggestion -> suggestion.source() == AssetManager.Source.AI ? "AI" : "Rules", 70, true);
        suggestionColumn("Asset ID", suggestion -> suggestion.assetId().toString(), 260, false);
        suggestions.setTableMenuButtonVisible(true);
        suggestions.setPlaceholder(new Label("Pick a check above to see its suggestions."));
        suggestions.setPrefHeight(300);
        suggestions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            renderSummary();
            decisionHint.setText(selected == null ? NO_SELECTION_HINT : decisionHint(selected, assetPaths));
            if (selected == null) return;
            reviewAsset.setText(selected.assetId().toString());
            reviewKind.setValue(suggestionKind(selected.kind()));
            reviewValue.setText(selected.proposedValue());
            reviewReference.setText(selected.reference().orElse(""));
            decision.setValue(null);
        });

        Button accept = styled(new Button("Accept"), "primary");
        Button reject = new Button("Reject");
        Button edit = new Button("Edit...");
        accept.setTooltip(new Tooltip("Use this change when the plan is created. Nothing is written until you run the plan."));
        reject.setTooltip(new Tooltip("Keep the file as it is; this suggestion is not used."));
        edit.setTooltip(new Tooltip("Change the suggested value before saving it."));
        accept.setOnAction(event -> decide(ReviewedChange.Decision.ACCEPTED));
        reject.setOnAction(event -> decide(ReviewedChange.Decision.REJECTED));
        for (Button button : List.of(accept, reject, edit)) {
            button.disableProperty().bind(suggestions.getSelectionModel().selectedItemProperty().isNull());
        }
        Button acceptAll = new Button("Accept all");
        acceptAll.setTooltip(new Tooltip("Accept every suggestion in the table at once, after a confirmation. Where the "
                + "rules and the AI disagree, the AI's suggestion is used."));
        acceptAll.setOnAction(event -> acceptAll());
        acceptAll.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(suggestions.getItems()));
        suggestions.getItems().addListener(
                (javafx.collections.ListChangeListener<AssetManager.Suggestion>) change -> refreshSuggestionCount());
        analysisRevision.getStyleClass().add("muted");
        HBox decisionBar = new HBox(8, analysisRevision, spacer(), acceptAll, accept, reject, edit);
        decisionBar.setAlignment(Pos.CENTER_LEFT);
        decisionHint.setText(NO_SELECTION_HINT);
        decisionHint.setWrapText(true);
        decisionHint.getStyleClass().addAll("callout", "decision-hint");

        aiSummary.getStyleClass().addAll("callout", "ai-summary");
        aiSummary.setVisible(false);
        aiSummary.managedProperty().bind(aiSummary.visibleProperty());

        readOnly(analysisFindings, "Findings from the last check appear here.");
        analysisFindings.setPrefRowCount(6);

        reviewAsset.setPromptText("Asset ID (filled in when you select a suggestion)");
        reviewKind.getItems().setAll(ReviewedChange.Kind.CATEGORY, ReviewedChange.Kind.RELATIONSHIP,
                ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Kind.EXCLUSION,
                ReviewedChange.Kind.NAME, ReviewedChange.Kind.FOLDER, ReviewedChange.Kind.ACKNOWLEDGMENT,
                ReviewedChange.Kind.CONVERSION);
        reviewKind.setPromptText("Kind");
        reviewValue.setPromptText("Category, target asset ID, reason, file name, folder, or a conversion such as: jpg background=#FFFFFF, glb");
        reviewReference.setPromptText("Original reference (required for material mapping)");
        decision.getItems().setAll(ReviewedChange.Decision.ACCEPTED, ReviewedChange.Decision.REJECTED);
        decision.setPromptText("Decision");
        Button saveManual = styled(new Button("Save decision"), "primary");
        saveManual.setOnAction(event -> review());
        Button clearManual = new Button("Clear");
        clearManual.setOnAction(event -> clearReview());
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.addRow(0, fieldLabel("Asset ID"), reviewAsset);
        form.addRow(1, fieldLabel("Kind"), reviewKind);
        form.addRow(2, fieldLabel("Value"), reviewValue);
        form.addRow(3, fieldLabel("Reference"), reviewReference);
        form.addRow(4, fieldLabel("Decision"), new HBox(8, decision, saveManual, clearManual));
        GridPane.setHgrow(reviewAsset, Priority.ALWAYS);
        GridPane.setHgrow(reviewValue, Priority.ALWAYS);
        GridPane.setHgrow(reviewReference, Priority.ALWAYS);
        manualReviewPane = collapsible("Edit or add a decision", new VBox(8, muted(
                "Use this to change a suggestion before saving it, or to add one yourself: an EXCLUSION leaves a file out, "
                        + "an ACKNOWLEDGMENT lets through a model that cannot be read, a CONVERSION changes a format (for "
                        + "example glb or jpg)."), form));
        edit.setOnAction(event -> manualReviewPane.setExpanded(true));

        nameStyle.getItems().setAll(AssetManager.NameStyle.values());
        nameStyle.setValue(DEFAULT_ORGANIZATION_RULES.nameStyle());
        folderStyle.getItems().setAll(AssetManager.FolderStyle.values());
        folderStyle.setValue(DEFAULT_ORGANIZATION_RULES.folderStyle());
        Button generateOrganization = new Button("Suggest with these rules");
        generateOrganization.setOnAction(event -> {
            var rules = new AssetManager.OrganizationRules(nameStyle.getValue(), folderStyle.getValue());
            analyze(organizationAnalysis(rules), false);
        });
        HBox rules = new HBox(8, fieldLabel("Names"), nameStyle, fieldLabel("Folders"), folderStyle, generateOrganization);
        rules.setAlignment(Pos.CENTER_LEFT);
        TitledPane rulesPane = collapsible("Rename and folder rules", new VBox(8, rules,
                muted("Suggests new names and folders from a rule, for example snake_case names in folders by type.")));

        Button loadReviews = new Button("Show saved decisions");
        loadReviews.setOnAction(event -> loadReviews());
        readOnly(savedReviews, "Decisions saved for this inventory revision appear here.");
        savedReviews.setPrefRowCount(6);
        TitledPane savedPane = collapsible("Saved decisions", new VBox(8, loadReviews, savedReviews));

        Button preview = new Button("Preview output layout");
        preview.setOnAction(event -> previewOrganization());
        readOnly(organizationPreview, "Where each file would go, and anything that would block a plan. Nothing is written.");
        organizationPreview.setPrefRowCount(10);
        TitledPane previewPane = collapsible("Output layout preview", new VBox(8, preview, organizationPreview));

        VBox view = stepView("Review suggestions",
                "Optional. Run a check, then Accept a suggestion to use it in the plan, or Reject it to keep the file "
                        + "as it is. Undecided suggestions are ignored, and nothing is copied or changed yet.",
                checks, suggestions, decisionBar, decisionHint, aiSummary, analysisFindingsPane, manualReviewPane, rulesPane, savedPane, previewPane);
        VBox.setVgrow(suggestions, Priority.ALWAYS);
        return scrollable(view);
    }

    private Node buildTargetStep() {
        targetCards.getChildren().setAll(muted("Loading supported engines..."));
        targetGroup.selectedToggleProperty().addListener((o, previous, selected) -> {
            // Clicking the chosen card again keeps it chosen; only reloading the cards clears the group.
            if (selected == null && previous != null && targetGroup.getToggles().contains(previous)) {
                previous.setSelected(true);
                return;
            }
            selectedTarget.set(selected == null ? null : (AssetManager.TargetProfile) selected.getUserData());
            refreshFlow();
        });
        compatibilityAi.setTooltip(new Tooltip("Adds a plain-language explanation per issue. Sends issue codes and "
                + "relative paths, never file contents. Findings are kept if AI fails."));
        Button assess = styled(new Button("Check compatibility"), "primary");
        assess.setOnAction(event -> assessCompatibility());
        assess.disableProperty().bind(selectedTarget.isNull());
        HBox actions = new HBox(14, assess, compatibilityAi);
        actions.setAlignment(Pos.CENTER_LEFT);
        compatibilityBadges.setAlignment(Pos.CENTER_LEFT);
        readOnly(compatibilityResults, "Run the check to list likely problems for the selected engine.");
        compatibilityResults.setPrefRowCount(14);
        compatibilityIssues.getStyleClass().add("callout");
        compatibilityIssues.setVisible(false);
        compatibilityIssues.managedProperty().bind(compatibilityIssues.visibleProperty());
        VBox view = stepView("Choose a target engine",
                "Pick the engine these assets are going to, then check them against it. The check lists likely "
                        + "problems before anything is copied; it is not a full validation.",
                fieldLabel("Engine"), targetCards, actions, compatibilityBadges, compatibilityIssues, compatibilityRawPane);
        return scrollable(view);
    }

    private Node buildPlanStep(Stage stage) {
        outputRoot.setPromptText("An existing, preferably empty folder outside the asset folder");
        planAi.setTooltip(new Tooltip("The AI suggests conversions, files to leave out and reference fixes for this engine. "
                + "Each is checked by the program first; you accept the ones you want and a new plan is created. "
                + "Sends relative paths, types, formats, transparency and finding codes, never file contents."));
        planSuggestionsCard.getStyleClass().addAll("callout", "ai-summary");
        planSuggestionsCard.managedProperty().bind(planSuggestionsCard.visibleProperty());
        planSuggestionsCard.setVisible(false);
        createPlanButton.setOnAction(event -> createPlan());
        approvePlanButton.setOnAction(event -> approvePlan());
        fixBlockersButton.setTooltip(new Tooltip("Lists a fix for each blocker for you to confirm, then creates a new plan. "
                + "Fixes are decisions you could make by hand; your asset folder is never changed."));
        fixBlockersButton.setOnAction(event -> fixBlockers(false));
        fixBlockersButton.managedProperty().bind(fixBlockersButton.visibleProperty());
        fixBlockersButton.setVisible(false);
        HBox actions = new HBox(10, createPlanButton, fixBlockersButton, approvePlanButton, planAi);
        actions.setAlignment(Pos.CENTER_LEFT);
        planBadges.setAlignment(Pos.CENTER_LEFT);
        planGuidance.setWrapText(true);
        planGuidance.getStyleClass().add("callout");
        planGuidance.setVisible(false);
        planGuidance.managedProperty().bind(planGuidance.visibleProperty());

        planColumn("Source file", command -> planContext == null ? "" : Optional.ofNullable(planContext.sources().get(command.assetId()))
                .map(io.github.aiassetmigrate.domain.PortablePath::of).orElse("<unknown>"), 300);
        planColumn("Action", command -> label(command.operation().toString()), 150);
        planColumn("Output file", command -> io.github.aiassetmigrate.domain.PortablePath.of(command.relativeOutputPath()), 300);
        planTable.setPlaceholder(new Label("Create a plan to see every file it will write."));
        planTable.setPrefHeight(260);
        readOnly(planDetails, "No plan yet.");
        planDetails.setWrapText(false);
        planDetails.setPrefRowCount(12);
        TitledPane details = collapsible("Full plan details (every command, decision and finding)", planDetails);

        VBox view = stepView("Create the migration plan",
                "The plan lists every file that will be written to the output folder. Check it, then approve this exact "
                        + "version. Nothing is written until you run it in the next step.",
                fieldLabel("Output folder"), row(outputRoot, browseButton(stage, outputRoot, "Select the output folder")),
                actions, planBadges, planGuidance, planSuggestionsCard, planTable, details);
        VBox.setVgrow(planTable, Priority.ALWAYS);
        return scrollable(view);
    }

    private Node buildMigrateStep() {
        migrateReady.setWrapText(true);
        migrateReady.getStyleClass().add("callout");
        runButton.setOnAction(event -> migratePlan());
        validationAi.setTooltip(new Tooltip("Adds an advisory summary. Sends statuses, finding codes and relative paths; "
                + "the pass/fail status is always computed without AI."));
        HBox actions = new HBox(14, runButton, validationAi);
        actions.setAlignment(Pos.CENTER_LEFT);
        migrateBadges.setAlignment(Pos.CENTER_LEFT);
        migrateGuidance.getStyleClass().addAll("callout", "callout-warning");
        migrateGuidance.managedProperty().bind(migrateGuidance.visibleProperty());
        migrateGuidance.setVisible(false);
        for (VBox section : List.of(reportAiSummary, reportFindings)) {
            section.getStyleClass().add("callout");
            section.managedProperty().bind(section.visibleProperty());
            section.setVisible(false);
        }
        readOnly(validationResults, "The run result and its validation report appear here.");
        validationResults.setPrefRowCount(16);
        runLogPane = collapsible("Full run log and report (every step and check)", validationResults);

        validationRun.setPromptText("Run ID (filled in after a migration)");
        Button validate = new Button("Validate again");
        validate.setTooltip(new Tooltip("Re-check the outputs of this run, for example after moving or editing them"));
        validate.setOnAction(event -> validateRun());
        Button history = new Button("Show report history");
        history.setOnAction(event -> loadHistory());
        TitledPane historyPane = collapsible("Re-validate or view earlier reports", new VBox(8,
                row(validationRun, validate, history),
                muted("Validation checks each output exists, is unchanged, decodes (PNG/JPEG/GLB) and that its references "
                        + "resolve. FBX models are read with the built-in FBX reader. Checks that cannot be done are reported "
                        + "as not checked, never as passed.")));

        VBox view = stepView("Run the migration",
                "Copies the approved plan into the output folder, then checks every output file and saves a report. "
                        + "Your source files are never changed.",
                migrateReady, actions, migrateBadges, reportAiSummary, migrateGuidance, reportFindings, runLogPane, historyPane);
        return scrollable(view);
    }

    private void buildTools() {
        Label agentNote = muted("Ask in plain language, for example \"prepare this project for godot 4.3\", \"check unity "
                + "6000.0 compatibility\" or \"create a plan for godot 4.3 into C:\\out\". To prepare a project the agent runs "
                + "the read-only checks it needs and proposes decisions for you to confirm. It never approves, migrates or "
                + "saves anything by itself. Needs a connected AI account.");
        styled(agentDecisions, "primary");
        agentDecisions.managedProperty().bind(agentDecisions.visibleProperty());
        agentDecisions.setVisible(false);
        agentRequest.setPromptText("What should the agent do?");
        Button send = styled(new Button("Send"), "primary");
        send.setOnAction(event -> sendAgentRequest());
        agentRequest.setOnAction(event -> sendAgentRequest());
        readOnly(agentTranscript, "Requests and results appear here.");
        agentPane.getChildren().addAll(agentNote, row(agentRequest, send), agentDecisions, agentTranscript);
        VBox.setVgrow(agentTranscript, Priority.ALWAYS);

        Label logNote = muted("Replays this project's saved events after a sequence number, then follows new ones. "
                + "Closing the viewer never stops work.");
        logAfter.setPrefColumnCount(6);
        Button follow = styled(new Button("Follow"), "primary");
        Button stop = new Button("Stop");
        Button clear = new Button("Clear");
        follow.setOnAction(event -> followLogs());
        stop.setOnAction(event -> stopLogs());
        clear.setOnAction(event -> logView.clear());
        readOnly(logView, "Events appear here.");
        logView.setWrapText(false);
        logStatus.setWrapText(true);
        logStatus.getStyleClass().add("muted");
        HBox logControls = new HBox(8, muted("After"), logAfter, follow, stop, clear);
        logControls.setAlignment(Pos.CENTER_LEFT);
        logPane.getChildren().addAll(logNote, logControls, logStatus, logView);
        VBox.setVgrow(logView, Priority.ALWAYS);

        aiStatus.setWrapText(true);
        Button connectAi = styled(new Button("Connect account"), "primary");
        connectAi.setOnAction(event -> connectAi());
        Button disconnectAi = styled(new Button("Disconnect"), "destructive");
        disconnectAi.setOnAction(event -> disconnectAi());
        aiModel.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(io.github.aiassetmigrate.ai.SiwcInference.Model model) {
                return model == null ? "" : modelLabel(model);
            }
            @Override public io.github.aiassetmigrate.ai.SiwcInference.Model fromString(String text) { return null; }
        });
        aiModel.setPromptText("Connect to list models");
        aiModel.setMaxWidth(Double.MAX_VALUE);
        aiModel.setDisable(true);
        aiModel.setTooltip(new Tooltip("The ChatGPT model used by every AI feature"));
        HBox.setHgrow(aiModel, Priority.ALWAYS);
        aiModel.valueProperty().addListener((o, previous, chosen) -> {
            if (!updatingModels && chosen != null && !chosen.equals(previous)) selectAiModel(chosen);
        });
        refreshModels.setTooltip(new Tooltip("Fetch the models this account can use again"));
        refreshModels.setOnAction(event -> refreshAiStatus());
        refreshModels.setDisable(true);
        aiPane.getChildren().addAll(aiStatus, new HBox(8, connectAi, disconnectAi),
                muted("Model"), row(aiModel, refreshModels),
                muted("AI is optional. It adds suggestions, explanations and summaries; it never approves a plan or runs a "
                        + "migration. Requests send relative paths, types and finding codes, never file contents. "
                        + "Sign-in uses your ChatGPT account in the browser."));

        Button close = new Button("Close");
        close.setOnAction(event -> tools.selectToggle(null));
        drawerTitle.getStyleClass().add("h3");
        HBox drawerHeader = new HBox(8, drawerTitle, spacer(), close);
        drawerHeader.setAlignment(Pos.CENTER_LEFT);
        drawer.getChildren().addAll(drawerHeader, drawerContent);
        VBox.setVgrow(drawerContent, Priority.ALWAYS);
        drawer.getStyleClass().add("drawer");
        drawer.setPrefWidth(380);
        drawer.setVisible(false);
        drawer.managedProperty().bind(drawer.visibleProperty());
    }

    /** Clears results that no longer match the inputs they were computed from. */
    private void installInvalidation() {
        projectId.textProperty().addListener((o, a, b) -> {
            clearDisplayedResults();
            clearCompatibility();
            clearPlan();
            stopLogs();
            lastOutcome = null;
            refreshFlow();
        });
        state.textProperty().addListener((o, a, b) -> {
            clearDisplayedResults();
            clearCompatibility();
            clearPlan();
            stopLogs();
            refreshFlow();
        });
        for (TextField field : List.of(outputRoot, source, revision)) field.textProperty().addListener((o, a, b) -> clearPlan());
        selectedTarget.addListener((o, a, b) -> {
            clearCompatibility();
            clearPlan();
        });
    }

    // ---------------------------------------------------------------- navigation

    private void showStep(WizardFlow.Step step) {
        if (!progressState().unlocked(step)) return;
        currentStep = step;
        if (step == WizardFlow.Step.REVIEW) reviewVisited = true;
        stepHost.getChildren().setAll(stepViews.get(step));
        if (step == WizardFlow.Step.TARGET && targetGroup.getToggles().isEmpty() && !runner.isBusy()) loadCompatibilityTargets();
        refreshFlow();
    }

    private WizardFlow.Progress progressState() {
        return new WizardFlow.Progress(inventoryLoaded(), reviewVisited, selectedTarget.get() != null,
                planContext != null && planContext.plan().status() == PlanStatus.APPROVED, lastOutcome != null);
    }

    private boolean inventoryLoaded() {
        return currentSnapshot != null && currentSnapshot.project().id().toString().equals(projectId.getText().trim());
    }

    /** Re-derives the stepper, footer and step-local controls from the current state. */
    private void refreshFlow() {
        if (stepButtons.isEmpty()) return;
        WizardFlow.Progress flow = progressState();
        boolean busy = runner.isBusy();
        for (WizardFlow.Step step : WizardFlow.Step.values()) {
            Button button = stepButtons.get(step);
            boolean done = flow.complete(step) && step != currentStep;
            stepIndexes.get(step).setText(done ? "\u2713" : Integer.toString(step.number()));
            button.getStyleClass().removeAll("step-current", "step-complete");
            if (step == currentStep) button.getStyleClass().add("step-current");
            else if (done) button.getStyleClass().add("step-complete");
            button.setDisable(busy || !flow.unlocked(step));
        }
        back.setDisable(busy || currentStep.previous().isEmpty());
        boolean last = currentStep.next().isEmpty();
        next.setVisible(!last);
        next.setManaged(!last);
        next.setDisable(busy || !flow.canContinue(currentStep));
        next.setText(currentStep.next().map(step -> "Continue to " + step.title).orElse("Continue"));
        next.getStyleClass().remove("primary");
        if (flow.complete(currentStep) && flow.canContinue(currentStep)) next.getStyleClass().add("primary");

        projectChip.setText(inventoryLoaded() ? "Project " + shortId(currentSnapshot.project().id())
                + " \u00b7 revision " + currentSnapshot.project().inventoryRevision() : "No project");
        loadedBox.setVisible(inventoryLoaded());
        loadedBox.setManaged(inventoryLoaded());

        boolean draft = planContext != null && planContext.plan().status() == PlanStatus.DRAFT;
        boolean approved = planContext != null && planContext.plan().status() == PlanStatus.APPROVED;
        approvePlanButton.setDisable(!draft || !planContext.sourcesVerified() || blocking(planContext.plan()) > 0);
        boolean blocked = draft && blocking(planContext.plan()) > 0;
        fixBlockersButton.setVisible(blocked);
        approvePlanButton.getStyleClass().remove("primary");
        createPlanButton.getStyleClass().remove("primary");
        fixBlockersButton.getStyleClass().remove("primary");
        if (blocked) fixBlockersButton.getStyleClass().add("primary");
        else if (draft) approvePlanButton.getStyleClass().add("primary");
        else createPlanButton.getStyleClass().add("primary");
        createPlanButton.setText(planContext == null ? "Create plan" : "Create a new plan");
        runButton.setDisable(!approved);
        if (approved) {
            MigrationPlan plan = planContext.plan();
            migrateReady.setText("Ready: plan " + shortId(plan.id()) + " (version " + plan.version() + ") will write "
                    + plan.commands().size() + " file(s) for " + targetName(plan.targetProfile()) + " " + plan.targetProfileVersion()
                    + " into " + plan.outputRoot() + ".");
        } else if (lastOutcome != null) {
            migrateReady.setText("The approved plan has been run; each approval runs once. To migrate again, create and "
                    + "approve a new plan.");
        } else {
            migrateReady.setText("Approve a plan in the Plan step first.");
        }
    }

    private void showTool(javafx.scene.control.Toggle selected) {
        if (selected == null) {
            drawer.setVisible(false);
            return;
        }
        drawerTitle.setText(selected == agentTool ? "Agent" : selected == logsTool ? "Live logs" : "AI account");
        drawerContent.getChildren().setAll(selected == agentTool ? agentPane : selected == logsTool ? logPane : aiPane);
        drawer.setVisible(true);
    }

    private void busy(boolean value) {
        stepHost.setDisable(value);
        agentPane.setDisable(value);
        progress.setVisible(value);
        refreshFlow(); // disables the stepper and Back/Continue while the runner is busy
    }

    // ---------------------------------------------------------------- step 1: project

    private void begin(boolean load) {
        begin(load, false);
    }

    private void begin(boolean load, boolean rescan) {
        begin(load, rescan, Optional.empty());
    }

    /** {@code exclusions}: for a rescan, exactly the files to leave out; empty keeps the current ones. */
    private void begin(boolean load, boolean rescan, Optional<java.util.Set<Path>> exclusions) {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            Path sourcePath = load ? null : requiredPath(source, "Asset folder");
            UUID id = load || rescan ? projectUuid() : null;
            Long requestedRevision = null;
            if (load && !revision.getText().isBlank()) {
                requestedRevision = Long.valueOf(revision.getText().trim());
                if (requestedRevision < 1) throw new IllegalArgumentException("Revision must be positive.");
            }
            Long selectedRevision = requestedRevision;
            boolean started = runner.submit(statePath, logPath,
                    manager -> load ? (selectedRevision == null ? manager.loadInventory(id)
                            : manager.loadInventory(id, selectedRevision))
                            : rescan ? (exclusions.isPresent() ? manager.rescan(id, sourcePath, exclusions.get())
                                    : manager.rescan(id, sourcePath)).snapshot()
                            : manager.importAssets(sourcePath).snapshot(), this::completed);
            if (started) {
                clearCompatibility();
                clearPlan();
                currentSnapshot = null;
                lastOutcome = null;
                summaryBadges.getChildren().clear();
                inventory.getItems().clear();
                findings.clear();
                clearAnalysis();
                busy(true);
                status.setText(load ? "Opening project..." : rescan ? "Rescanning the asset folder..." : "Importing assets...");
            }
        } catch (NumberFormatException failure) {
            status.setText("Revision must be a whole number.");
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void completed(ImportRunner.Outcome outcome) {
        if (outcome.snapshot() != null) showSnapshot(outcome.snapshot());
        busy(false);
        Throwable failure = outcome.failure();
        if (failure == null) {
            savePreference(PREF_WORKSPACE, state.getText().trim());
            savePreference(PREF_PROJECT, projectId.getText().trim());
            InventorySnapshot snapshot = outcome.snapshot();
            status.setText(snapshot.scanStatus() == InventorySnapshot.ScanStatus.COMPLETE
                    ? "Loaded " + snapshot.project().assets().size() + " file(s). Continue to review suggestions, or skip ahead to choose a target."
                    : snapshot.excludableFiles().isEmpty()
                    ? "Some folders could not be read, so the scan is partial and a plan cannot be created. See Scan findings."
                    : "Some files were skipped, so a plan cannot be created yet. Leave them out (above), or fix them and Rescan.");
        } else {
            status.setText(failureText(failure, outcome.snapshot()));
            if (failure instanceof ImportException importFailure && importFailure.committedRevision().isPresent()) {
                projectId.setText(importFailure.projectId().map(UUID::toString).orElse(""));
                importFailure.committedRevision().ifPresent(value -> revision.setText(value.toString()));
            }
        }
    }

    private void showSnapshot(InventorySnapshot snapshot) {
        var project = snapshot.project();
        projectId.setText(project.id().toString());
        revision.setText(Long.toString(project.inventoryRevision()));
        source.setText(project.sourceRoot().toString());
        currentSnapshot = snapshot;
        assetPaths.clear();
        project.assets().forEach(asset -> assetPaths.put(asset.id(), io.github.aiassetmigrate.domain.PortablePath.of(asset.relativePath())));
        long errors = snapshot.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR).count();
        boolean complete = snapshot.scanStatus() == InventorySnapshot.ScanStatus.COMPLETE;
        summaryBadges.getChildren().setAll(
                badge(project.assets().size() + " files", ""),
                badge(complete ? "Scan complete" : "Scan partial: files skipped", complete ? "badge-success" : "badge-warning"),
                badge(errors > 0 ? errors + " errors" : snapshot.findings().size() + " notes", errors > 0 ? "badge-danger" : ""),
                badge("Revision " + project.inventoryRevision(), ""));
        if (!snapshot.excludedFiles().isEmpty()) summaryBadges.getChildren().add(badge(snapshot.excludedFiles().size() + " left out", ""));
        inventory.getItems().setAll(project.assets());
        findings.setText(formatFindings(snapshot));
        findingsPane.setText("Scan findings (" + snapshot.findings().size() + ")");
        renderCoverage(snapshot);
    }

    /**
     * Explains a partial scan and offers the fix: leave the skipped files out (recorded in the inventory, never
     * read or migrated, sources unchanged) or include previously left-out files again. Folder-level gaps cannot
     * be left out here; they need the folder fixed and a rescan.
     */
    private void renderCoverage(InventorySnapshot snapshot) {
        coverageBox.getChildren().clear();
        List<String> skipped = skippedFileLines(snapshot);
        boolean folderGaps = snapshot.pathOutcomes().stream().anyMatch(o -> o.kind() == PathOutcome.Kind.DIRECTORY
                && o.status() != PathOutcome.Status.TRAVERSED);
        if (!skipped.isEmpty()) {
            Label title = new Label(skipped.size() + " file(s) were skipped, so a plan cannot be created yet");
            title.getStyleClass().add("h3");
            VBox list = new VBox(2);
            skipped.stream().limit(MAX_SKIPPED_SHOWN).forEach(line -> list.getChildren().add(new Label("• " + line)));
            if (skipped.size() > MAX_SKIPPED_SHOWN) list.getChildren().add(muted("...and " + (skipped.size() - MAX_SKIPPED_SHOWN) + " more; see Scan findings."));
            Button leaveOut = styled(new Button("Leave these files out"), "primary");
            leaveOut.setOnAction(event -> leaveSkippedFilesOut(snapshot));
            coverageBox.getChildren().addAll(title, list, muted("Leaving them out means they are not part of this project: they "
                    + "are not read or copied, and your source folder is not changed. Or fix them (for example remove a stray file) "
                    + "and Rescan."), leaveOut);
        }
        if (folderGaps) {
            Label gap = new Label("Some folders could not be fully read (see Scan findings). Folders cannot be left out here: "
                    + "fix their access or move them, then Rescan.");
            gap.setWrapText(true);
            gap.getStyleClass().add("summary-issue");
            coverageBox.getChildren().add(gap);
        }
        if (!snapshot.excludedFiles().isEmpty()) {
            List<String> names = snapshot.excludedFiles().stream().map(io.github.aiassetmigrate.domain.PortablePath::of).toList();
            Button includeAgain = new Button("Include them again");
            includeAgain.setOnAction(event -> includeExcludedFiles(snapshot));
            HBox row = new HBox(10, muted(names.size() + " file(s) left out by you: " + String.join(", ", names.subList(0,
                    Math.min(names.size(), MAX_SKIPPED_SHOWN))) + (names.size() > MAX_SKIPPED_SHOWN ? ", ..." : "")), spacer(), includeAgain);
            row.setAlignment(Pos.CENTER_LEFT);
            coverageBox.getChildren().add(row);
        }
        coverageBox.setVisible(!coverageBox.getChildren().isEmpty());
        coverageBox.getStyleClass().remove("callout-warning");
        if (!skipped.isEmpty() || folderGaps) coverageBox.getStyleClass().add("callout-warning");
    }

    private void leaveSkippedFilesOut(InventorySnapshot snapshot) {
        List<String> lines = skippedFileLines(snapshot);
        if (lines.isEmpty()) return;
        if (!confirm("Leave files out", "Leave " + lines.size() + " file(s) out of this project?",
                String.join("\n", lines.stream().limit(15).map(line -> "• " + line).toList())
                        + (lines.size() > 15 ? "\n...and " + (lines.size() - 15) + " more" : "")
                        + "\n\nThey will not be read or copied, and your source folder is not changed. If a model or material "
                        + "refers to one of them, the plan will still say so. You can include them again at any time.",
                "Leave " + lines.size() + " out")) {
            status.setText("Nothing was changed.");
            return;
        }
        java.util.Set<Path> excluded = new java.util.LinkedHashSet<>(snapshot.excludedFiles());
        snapshot.excludableFiles().forEach(outcome -> excluded.add(outcome.relativePath()));
        begin(false, true, Optional.of(excluded));
    }

    private void includeExcludedFiles(InventorySnapshot snapshot) {
        if (!confirm("Include files again", "Include " + snapshot.excludedFiles().size() + " file(s) again?",
                "They are scanned again. If they are still unsupported or unreadable, the scan becomes partial again "
                        + "and plans are blocked until they are left out or fixed.", "Include them")) {
            return;
        }
        begin(false, true, Optional.of(java.util.Set.of()));
    }

    /** "misc/notes.txt (unsupported file type)" for each skipped or unreadable file, in plain words. */
    static List<String> skippedFileLines(InventorySnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        for (PathOutcome outcome : snapshot.excludableFiles()) {
            String reason = snapshot.findings().stream()
                    .filter(f -> f.sourceRelativePath().filter(outcome.relativePath()::equals).isPresent())
                    .map(f -> switch (f.code()) {
                        case "UNSUPPORTED_EXTENSION" -> "unsupported file type";
                        case "FILE_SIZE_LIMIT" -> "too large to scan";
                        case "CORRUPT_FORMAT" -> "damaged or not a valid file";
                        case "FILE_READ_FAILED", "UNSTABLE_FILE" -> "could not be read";
                        case "UNSAFE_PATH" -> "a link or unsafe path";
                        default -> null;
                    }).filter(java.util.Objects::nonNull).findFirst()
                    .orElse(outcome.status() == PathOutcome.Status.FAILED ? "could not be read" : "skipped");
            lines.add(io.github.aiassetmigrate.domain.PortablePath.of(outcome.relativePath()) + " (" + reason + ")");
        }
        return lines;
    }

    // ---------------------------------------------------------------- step 2: review

    private void analyze(AssetManager.AnalysisKind kind) {
        boolean ai = useAi.isSelected();
        analyze(ai ? assistedAnalysis(kind) : (manager, id) -> manager.analyze(id, kind), ai);
    }

    /** Runs on the worker thread: loads the saved account there, never on the JavaFX thread. */
    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> assistedAnalysis(AssetManager.AnalysisKind kind) {
        return (manager, id) -> manager.analyze(id, kind, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable());
    }

    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> organizationAnalysis(AssetManager.OrganizationRules rules) {
        return (manager, id) -> manager.analyzeOrganization(id, rules);
    }

    /** {@code summarize}: show the plain-language summary box, used when the check asked the AI. */
    private void analyze(BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> operation, boolean summarize) {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID id = projectUuid();
            if (runner.submitValue(statePath, logPath, manager -> checkRun(manager, id, operation.apply(manager, id)), result -> {
                Map<UUID, String> decided = Map.of();
                if (result.value() != null) {
                    AssetManager.AnalysisResult value = result.value().result();
                    analysisContext = new AnalysisContext(id, statePath, logPath, value);
                    // Suggestions already settled by a saved decision stay hidden, so switching checks
                    // does not bring back what was accepted or rejected earlier.
                    decided = DecisionSlots.alreadyDecided(value.suggestions(), result.value().saved());
                    Set<UUID> hidden = decided.keySet();
                    suggestions.getItems().setAll(value.suggestions().stream().filter(s -> !hidden.contains(s.id())).toList());
                    refreshSuggestionCount(); // also when the check found nothing and the list did not change
                    analysisFindings.setText(formatAnalysisFindings(value.findings()));
                    analysisFindingsPane.setText("Check findings (" + value.findings().size() + ")");
                    decidedSuggestions.putAll(decided);
                    if (summarize) {
                        currentSummary = ReviewSummary.of(value, assetPaths);
                        renderSummary();
                    }
                }
                busy(false);
                status.setText(result.failure() == null ? (decided.isEmpty()
                        ? "Select a suggestion, then Accept or Reject it. Undecided suggestions are simply ignored."
                        : decided.size() + " suggestion(s) you already decided are hidden (see Saved decisions).")
                        : failureText(result.failure(), null) + (result.value() != null ? "\nAnalysis results were obtained, but cleanup failed." : ""));
            })) {
                clearAnalysis();
                busy(true);
                status.setText("Running the check on the latest inventory...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    /** A check's result and the decisions already saved for its inventory revision. */
    record CheckRun(AssetManager.AnalysisResult result, List<ReviewedChange> saved) {
        CheckRun {
            saved = List.copyOf(saved);
        }
    }

    /** Runs on the worker thread. Decisions saved for another revision do not apply, so none are returned then. */
    static CheckRun checkRun(AssetManager manager, UUID id, AssetManager.AnalysisResult result) {
        boolean sameRevision = manager.loadInventory(id).project().inventoryRevision() == result.inventoryRevision();
        return new CheckRun(result, sameRevision ? manager.loadReviews(id) : List.of());
    }

    /** One-click decision on the selected suggestion, using its proposed value unchanged. */
    private void decide(ReviewedChange.Decision choice) {
        AssetManager.Suggestion selected = suggestions.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a suggestion first.");
            return;
        }
        try {
            ReviewedChange change = makeReview(selected, choice, selected.proposedValue(), selected.reference().orElse(""));
            saveReview(change, () -> {
                suggestions.getItems().remove(selected);
                decidedSuggestions.put(selected.id(), label(choice.toString()));
                renderSummary();
            });
        } catch (IllegalArgumentException failure) {
            status.setText("This suggestion needs editing first (" + failure.getMessage() + "). Use Edit...");
            manualReviewPane.setExpanded(true);
        }
    }

    /** Keeps the count and the empty-table message true as a check runs and its suggestions are decided. */
    private void refreshSuggestionCount() {
        AnalysisContext context = analysisContext;
        if (context == null) {
            suggestions.setPlaceholder(new Label("Pick a check above to see its suggestions."));
            analysisRevision.setText("No check run yet.");
            return;
        }
        int total = context.result().suggestions().size(), left = suggestions.getItems().size();
        suggestions.setPlaceholder(new Label(total == 0 ? "This check has no suggestions. Run another check, or continue."
                : "Every suggestion from this check has been decided. Run another check, or continue."));
        analysisRevision.setText((left == total ? total + " suggestion(s)" : left + " of " + total + " suggestion(s) left to decide")
                + " for inventory revision " + context.result().inventoryRevision());
    }

    /** A themed confirmation dialog; true only when the user picks {@code confirmLabel}. */
    private boolean confirm(String title, String header, String body, String confirmLabel) {
        javafx.scene.control.ButtonType confirm = new javafx.scene.control.ButtonType(confirmLabel,
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                body, confirm, javafx.scene.control.ButtonType.CANCEL);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().add(stylesheet());
        dialog.getDialogPane().setMinWidth(520);
        if (stepHost.getScene() != null) dialog.initOwner(stepHost.getScene().getWindow());
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }

    /** Accepts every suggestion in the table after an explicit confirmation; see {@link AcceptAll} for disagreements and ties. */
    private void acceptAll() {
        AcceptAll batch = AcceptAll.of(List.copyOf(suggestions.getItems()));
        if (batch.changes().isEmpty()) {
            status.setText(!batch.conflicting().isEmpty()
                    ? "Nothing was accepted: each remaining file has several equally likely suggestions. Pick one each."
                    : !batch.invalid().isEmpty() ? "Nothing was accepted: the remaining suggestions need editing first (use Edit...)."
                    : "Nothing to accept.");
            return;
        }
        if (!confirm("Accept all suggestions", "Accept " + batch.changes().size() + " suggestion(s)?",
                acceptAllMessage(batch), "Accept " + batch.changes().size())) {
            status.setText("Nothing was accepted.");
            return;
        }
        saveReviews(batch.changes(), () -> {
            suggestions.getItems().removeAll(batch.covered());
            suggestions.getItems().removeAll(batch.setAside());
            batch.covered().forEach(s -> decidedSuggestions.put(s.id(), "Accepted"));
            batch.setAside().forEach(s -> decidedSuggestions.put(s.id(), "Set aside for the AI's suggestion"));
            renderSummary();
        }, "Accepted " + batch.changes().size() + " suggestion(s)."
                + (batch.aiPreferredFiles() > 0 ? " Used the AI's suggestion for " + batch.aiPreferredFiles()
                + " file(s) where the rules suggested something else." : "")
                + (batch.conflictingFiles() > 0 ? " " + batch.conflictingFiles()
                + " file(s) with several equally likely suggestions are still in the table: pick one for each." : "")
                + (batch.invalid().isEmpty() ? "" : " " + batch.invalid().size() + " need editing first (use Edit...)."));
    }

    /** The confirmation text: what will be saved, what is left, and that nothing is written yet. */
    static String acceptAllMessage(AcceptAll batch) {
        StringBuilder text = new StringBuilder("Every suggestion in the table will be saved as accepted and used when you "
                + "create the plan.");
        if (batch.fromAi() > 0) text.append(" ").append(batch.fromAi()).append(" of them come from AI; check the table if unsure.");
        if (batch.aiPreferredFiles() > 0) {
            text.append("\n\nFor ").append(batch.aiPreferredFiles()).append(" file(s) the rules and the AI suggest different ")
                    .append("values; the AI's suggestion is accepted and the rule's (").append(batch.setAside().size())
                    .append(") is set aside.");
        }
        if (batch.conflictingFiles() > 0) {
            text.append("\n\n").append(batch.conflictingFiles()).append(" file(s) have several equally likely suggestions (")
                    .append(batch.conflicting().size()).append(" in all), for example two files with the same name that ")
                    .append("could fix one broken reference. They are left in the table for you to pick one.");
        }
        if (!batch.invalid().isEmpty()) {
            text.append("\n\n").append(batch.invalid().size()).append(" suggestion(s) cannot be saved as they are and are left "
                    + "for Edit...");
        }
        return text.append("\n\nNothing is written until you approve and run a plan, and you can change any decision later.")
                .toString();
    }

    private void review() {
        try {
            ReviewedChange change = makeReview(reviewAsset.getText(), reviewKind.getValue(), decision.getValue(),
                    reviewValue.getText(), reviewReference.getText());
            saveReview(change, this::clearReview);
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void saveReview(ReviewedChange change, Runnable onSaved) {
        String file = assetPaths.getOrDefault(change.assetId(), change.assetId().toString());
        saveReviews(List.of(change), onSaved, label(change.decision().toString()) + ": " + label(change.kind().toString())
                + " for " + file + ". Saved.");
    }

    /** Saves the decisions in one review revision; {@code onSaved} runs only if they were saved. */
    private void saveReviews(List<ReviewedChange> changes, Runnable onSaved, String savedText) {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID id = projectUuid();
            AnalysisContext context = requireAnalysisContext(id, statePath, logPath);
            if (runner.submitValue(statePath, logPath, manager -> context.review(manager, changes), result -> {
                if (result.value() != null) onSaved.run();
                busy(false);
                status.setText(result.failure() == null ? savedText
                        : failureText(result.failure(), null) + (result.value() != null
                        ? "\nThe review call returned, but cleanup failed; it may have been saved."
                        : changes.size() > 1 ? "\nThe decisions are saved together or not at all; check Saved decisions "
                        + "before retrying, or accept them one at a time to find the one that fails."
                        : "\nThe review may have been saved; verify before retrying."));
            })) {
                clearDisplayedResults();
                clearCompatibility();
                clearPlan();
                busy(true);
                status.setText(changes.size() == 1 ? "Saving decision..." : "Saving " + changes.size() + " decisions...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void previewOrganization() {
        organizationPreview.clear();
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            AnalysisContext context = requireAnalysisContext(projectUuid(), statePath, logPath);
            if (runner.submitValue(statePath, logPath, context::preview, result -> {
                if (result.value() != null) organizationPreview.setText(formatPreview(result.value()));
                busy(false);
                status.setText(result.failure() == null ? (result.value().blocked()
                        ? "The layout has blocking issues; they are listed at the end of the preview." : "The layout has no blocking issues.")
                        : failureText(result.failure(), null) + (result.value() != null ? "\nPreview was obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Preparing the layout preview...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private AnalysisContext requireAnalysisContext(UUID id, Path statePath, Path logPath) {
        AnalysisContext context = analysisContext;
        if (context == null || !id.equals(context.project()) || !statePath.equals(context.state()) || !logPath.equals(context.logs())) {
            throw new IllegalArgumentException("Run a check first (Categories, Relationships, ...); decisions are tied to it.");
        }
        return context;
    }

    private void loadReviews() {
        savedReviews.clear();
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            AnalysisContext context = requireAnalysisContext(projectUuid(), statePath, logPath);
            if (runner.submitValue(statePath, logPath, context::loadReviews, result -> {
                if (result.value() != null) savedReviews.setText(formatReviews(result.value()));
                busy(false);
                status.setText(result.failure() == null ? "Saved decisions loaded for revision " + context.result().inventoryRevision() + "."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nReviews were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Loading saved decisions...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void clearAnalysis() {
        analysisContext = null;
        suggestions.getItems().clear();
        refreshSuggestionCount();
        analysisFindings.clear();
        analysisFindingsPane.setText("Check findings");
        currentSummary = null;
        decidedSuggestions.clear();
        renderSummary();
        clearDisplayedResults();
        clearReview();
    }

    private void clearDisplayedResults() {
        savedReviews.clear();
        organizationPreview.clear();
    }

    private void clearReview() {
        suggestions.getSelectionModel().clearSelection();
        reviewAsset.clear();
        reviewKind.setValue(null);
        reviewValue.clear();
        reviewReference.clear();
        decision.setValue(null);
    }

    /**
     * Rebuilds the summary box. With a suggestion selected in the table it shows only that file's entry;
     * otherwise every file. Decided suggestions show their decision and can no longer be clicked.
     */
    private void renderSummary() {
        ReviewSummary summary = currentSummary;
        aiSummary.getChildren().clear();
        aiSummary.setVisible(summary != null);
        if (summary == null) return;
        Label title = new Label("AI review summary");
        title.getStyleClass().add("h3");
        AssetManager.Suggestion selected = suggestions.getSelectionModel().getSelectedItem();
        Optional<ReviewSummary.Item> focused = selected == null ? Optional.empty() : summary.itemFor(selected.assetId());
        if (focused.isPresent()) {
            Hyperlink showAll = new Hyperlink("Show all " + summary.items().size() + " files");
            showAll.getStyleClass().add("summary-suggestion");
            showAll.setOnAction(event -> suggestions.getSelectionModel().clearSelection());
            HBox header = new HBox(8, title, spacer(), showAll);
            header.setAlignment(Pos.CENTER_LEFT);
            aiSummary.getChildren().addAll(header, summaryItem(focused.get(), selected));
            return;
        }
        Label overview = new Label(summary.overview());
        overview.setWrapText(true);
        aiSummary.getChildren().addAll(title, overview);
        for (String note : summary.notes()) aiSummary.getChildren().add(muted(note));
        for (ReviewSummary.Item item : summary.shown()) aiSummary.getChildren().add(summaryItem(item, null));
        if (summary.hiddenItems() > 0) {
            aiSummary.getChildren().add(muted("...and " + summary.hiddenItems() + " more file(s); see the table above."));
        }
    }

    /** {@code selected} (nullable) is the table's selected suggestion, highlighted in the entry. */
    private Node summaryItem(ReviewSummary.Item item, AssetManager.Suggestion selected) {
        VBox box = new VBox(4);
        box.getStyleClass().add("summary-item");
        Label file = new Label(item.file());
        file.getStyleClass().add("summary-file");
        file.setWrapText(true);
        box.getChildren().add(file);
        for (String issue : item.issues()) {
            Label line = new Label("⚠  " + issue);
            line.getStyleClass().add("summary-issue");
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        for (ReviewSummary.Line line : item.suggestions()) {
            Label source = new Label(line.ai() ? "AI" : "Rule");
            source.getStyleClass().addAll("badge", line.ai() ? "badge-info" : "badge-neutral");
            source.setMinWidth(Region.USE_PREF_SIZE);
            Hyperlink link = new Hyperlink(line.text());
            link.getStyleClass().add("summary-suggestion");
            link.setWrapText(true);
            link.setMaxWidth(Double.MAX_VALUE);
            String done = decidedSuggestions.get(line.suggestion().id());
            if (done != null) {
                link.setText(line.text() + "  (" + done + ")");
                link.setDisable(true);
            } else {
                link.setTooltip(new Tooltip("Select this suggestion in the table"));
                link.setOnAction(event -> selectSuggestion(line.suggestion()));
            }
            HBox row = new HBox(8, source, link);
            row.setAlignment(Pos.CENTER_LEFT);
            if (line.suggestion().equals(selected)) row.getStyleClass().add("summary-selected");
            HBox.setHgrow(link, Priority.ALWAYS);
            box.getChildren().add(row);
            line.reason().ifPresent(reason -> {
                Label why = muted("Why: " + reason);
                why.getStyleClass().add("summary-reason");
                box.getChildren().add(why);
            });
        }
        item.nextStep().ifPresent(step -> box.getChildren().add(muted("Next: " + step)));
        return box;
    }

    private void selectSuggestion(AssetManager.Suggestion suggestion) {
        int index = suggestions.getItems().indexOf(suggestion);
        if (index < 0) {
            status.setText("That suggestion is no longer in the table; run the check again.");
            return;
        }
        suggestions.getSelectionModel().select(index);
        suggestions.scrollTo(index);
        suggestions.requestFocus();
        status.setText("Selected. Accept or Reject it, or use Edit... to change it first.");
    }

    /**
     * What Accept and Reject would each do for this suggestion, in terms of the file's output, followed by
     * the two rules that hold for every decision: nothing is written yet, and a later decision replaces it.
     */
    static String decisionHint(AssetManager.Suggestion suggestion, Map<UUID, String> paths) {
        String file = paths.getOrDefault(suggestion.assetId(), suggestion.assetId().toString());
        String value = suggestion.proposedValue();
        String accept;
        String reject;
        switch (suggestion.kind()) {
            case CATEGORIES -> {
                boolean none = value.equals(io.github.aiassetmigrate.analysis.CategoryAnalyzer.UNCATEGORIZED);
                accept = none ? file + " is recorded as Uncategorized."
                        : file + " is recorded under " + value + ", which is used when folders are sorted by category. "
                        + "The file itself does not change.";
                reject = "the category " + value + " is not used.";
            }
            case NAMES -> {
                accept = "the output copy of " + file + " is named " + value + ". OBJ and MTL files that refer to it "
                        + "are updated in the output to match.";
                reject = "it keeps its current name.";
            }
            case FOLDERS -> {
                accept = "the output copy of " + file + (value.isEmpty() ? " goes in the output folder's root."
                        : " goes in the folder " + value + ".") + " OBJ and MTL files that refer to it are updated in the "
                        + "output to match.";
                reject = "it stays in its current folder.";
            }
            case RELATIONSHIPS, MATERIALS -> {
                String target;
                try {
                    target = paths.getOrDefault(UUID.fromString(value), value);
                } catch (IllegalArgumentException notAnId) {
                    target = value;
                }
                if (suggestion.reference().isPresent()) {
                    String reference = "'" + suggestion.reference().get() + "'";
                    accept = "the plan uses " + target + " for " + reference + " in " + file + ". If " + reference
                            + " is a broken file path, the output copy is corrected to point there.";
                    reject = reference + " is left as written; if it is broken, the plan stays blocked until you fix "
                            + "or exclude it.";
                } else {
                    accept = "it is recorded that " + file + " belongs with " + target + ". The output does not change.";
                    reject = "the grouping is not recorded.";
                }
            }
            default -> throw new IllegalStateException("Unknown analysis kind: " + suggestion.kind());
        }
        return "Accept: " + accept + "\nReject: " + reject
                + "\nEither way nothing is written until you approve and run a plan, and a later decision for this "
                + "file replaces this one.";
    }

    /** Relationship and material suggestions propose a target asset ID; show its path instead. */
    private String displayValue(AssetManager.Suggestion suggestion) {
        try {
            String path = assetPaths.get(UUID.fromString(suggestion.proposedValue()));
            if (path != null) return path;
        } catch (IllegalArgumentException notAnId) {
            // a category, name or folder
        }
        return suggestion.proposedValue();
    }

    static ReviewedChange.Kind suggestionKind(AssetManager.AnalysisKind kind) {
        return switch (kind) {
            case CATEGORIES -> ReviewedChange.Kind.CATEGORY;
            case RELATIONSHIPS -> ReviewedChange.Kind.RELATIONSHIP;
            case MATERIALS -> ReviewedChange.Kind.MATERIAL_MAPPING;
            case NAMES -> ReviewedChange.Kind.NAME;
            case FOLDERS -> ReviewedChange.Kind.FOLDER;
        };
    }

    static ReviewedChange makeReview(AssetManager.Suggestion suggestion, ReviewedChange.Decision decision,
                                     String value, String reference) {
        return makeReview(suggestion.assetId().toString(), suggestionKind(suggestion.kind()), decision, value, reference);
    }

    static ReviewedChange makeReview(String asset, ReviewedChange.Kind kind, ReviewedChange.Decision decision,
                                     String value, String reference) {
        UUID assetId = UUID.fromString(asset.trim());
        if (kind == null) throw new IllegalArgumentException("Choose a review kind.");
        if (decision == null) throw new IllegalArgumentException("Choose ACCEPTED or REJECTED.");
        String edited = value.trim();
        if (edited.isEmpty() && kind != ReviewedChange.Kind.FOLDER) throw new IllegalArgumentException("Review value is required.");
        Map<String, String> values;
        switch (kind) {
            case CATEGORY -> values = Map.of("category", edited);
            case EXCLUSION, ACKNOWLEDGMENT -> values = Map.of("reason", edited);
            case CONVERSION -> {
                // "jpg" or "jpg background=#FFFFFF quality=0.9"
                String[] parts = edited.split("\\s+");
                Map<String, String> conversion = new java.util.LinkedHashMap<>();
                conversion.put("format", parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    int equals = parts[i].indexOf('=');
                    if (equals <= 0) throw new IllegalArgumentException("Conversion options use KEY=VALUE, for example background=#FFFFFF");
                    conversion.put(parts[i].substring(0, equals), parts[i].substring(equals + 1));
                }
                values = Map.copyOf(conversion);
            }
            case NAME -> values = Map.of("name", edited);
            case FOLDER -> values = Map.of("folder", edited);
            case RELATIONSHIP, MATERIAL_MAPPING -> {
                String target = UUID.fromString(edited).toString();
                if (kind == ReviewedChange.Kind.MATERIAL_MAPPING) {
                    if (reference.isBlank()) throw new IllegalArgumentException("Original reference is required for material mapping.");
                    values = Map.of("targetAssetId", target, "reference", reference);
                } else {
                    values = reference.isBlank() ? Map.of("targetAssetId", target)
                            : Map.of("targetAssetId", target, "reference", reference);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported review kind: " + kind);
        }
        return new ReviewedChange(UUID.randomUUID(), assetId, kind, decision, values, Instant.now());
    }

    // ---------------------------------------------------------------- step 3: target

    private void loadCompatibilityTargets() {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            long generation = compatibilityGeneration.current();
            if (runner.submitValue(statePath, logPath, AssetManager::supportedTargets, result -> {
                if (compatibilityGeneration.isCurrent(generation) && result.value() != null) showTargets(result.value());
                busy(false);
                status.setText(result.failure() == null ? "Pick an engine, then check compatibility."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nTargets were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Loading supported engines...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void showTargets(List<AssetManager.TargetProfile> targets) {
        AssetManager.TargetProfile previous = selectedTarget.get();
        targetGroup.getToggles().clear();
        List<Node> cards = new ArrayList<>();
        for (AssetManager.TargetProfile target : targets) {
            Label name = new Label(targetName(target.id()));
            name.getStyleClass().add("choice-title");
            Label version = new Label("Version " + target.version()); // no wrapping: it would be measured at zero width
            version.getStyleClass().add("muted");
            ToggleButton card = new ToggleButton();
            card.setGraphic(new VBox(2, name, version));
            card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            card.getStyleClass().add("choice-card");
            card.setUserData(target);
            card.setToggleGroup(targetGroup);
            if (target.equals(previous)) card.setSelected(true);
            cards.add(card);
        }
        targetCards.getChildren().setAll(cards.isEmpty() ? List.of(muted("No supported engines.")) : cards);
    }

    private void assessCompatibility() {
        clearCompatibility();
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            AssetManager.TargetProfile target = selectedTarget.get();
            if (target == null) throw new IllegalArgumentException("Choose an engine.");
            var request = new CompatibilityRequest(projectUuid(), target);
            long generation = compatibilityGeneration.current();
            boolean ai = compatibilityAi.isSelected();
            if (runner.submitValue(statePath, logPath, manager -> ai
                    ? request.assess(manager, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable())
                    : request.assess(manager), result -> {
                boolean current = compatibilityGeneration.isCurrent(generation);
                if (current && result.value() != null) {
                    compatibilityResults.setText(formatCompatibility(result.value()));
                    compatibilityRawPane.setText("All findings (" + result.value().findings().size() + ")");
                    compatibilityBadges.getChildren().setAll(severityBadges(result.value().findings()));
                    renderCompatibility(CompatibilitySummary.of(result.value().findings()), ai);
                }
                busy(false);
                if (!current) {
                    status.setText("Inputs changed; compatibility results discarded.");
                    return;
                }
                status.setText(result.failure() == null ? (count(result.value().findings(), Finding.Severity.ERROR) > 0
                        ? "Errors found: they will block the plan. Fix them (often in Review), or continue to see them in the plan."
                        : "No errors. Continue to create the plan.")
                        : failureText(result.failure(), null) + (result.value() != null ? "\nCompatibility results were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Checking compatibility with " + targetName(target.id()) + " " + target.version() + "...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void clearCompatibility() {
        compatibilityGeneration.invalidate();
        compatibilityResults.clear();
        compatibilityRawPane.setText("All findings");
        compatibilityBadges.getChildren().clear();
        compatibilityIssues.getChildren().clear();
        compatibilityIssues.setVisible(false);
    }

    /** "Issues to look at": one card per issue type; the AI explanation replaces the rule's wording when present. */
    private void renderCompatibility(CompatibilitySummary summary, boolean aiRequested) {
        Label title = new Label("Issues to look at");
        title.getStyleClass().add("h3");
        String intro = summary.issues().isEmpty() ? "No errors or warnings for this engine."
                : summary.issues().size() + " kind(s) of issue. Errors block the plan; warnings and unchecked items do not,"
                + " but are worth a look." + (aiRequested && summary.explained() > 0
                ? " AI explanations are advisory; the severities come from the rules." : "");
        compatibilityIssues.getChildren().setAll(title, muted(intro));
        for (String note : summary.notes()) {
            Label line = muted(note);
            line.getStyleClass().add("summary-issue");
            compatibilityIssues.getChildren().add(line);
        }
        for (CompatibilitySummary.Issue issue : summary.issues()) {
            Label severity = badge(issue.severity() == Finding.Severity.UNKNOWN ? "Not checked" : label(issue.severity().toString()),
                    issue.severity() == Finding.Severity.ERROR ? "badge-danger" : "badge-warning");
            Label name = new Label(label(issue.code()));
            name.getStyleClass().add("summary-file");
            HBox header = new HBox(8, severity, name, muted(issue.count() == 1 ? "1 finding" : issue.count() + " findings"));
            header.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(4, header);
            card.getStyleClass().add("summary-item");
            if (issue.aiExplanation().isPresent()) {
                Label source = badge("AI", "badge-info");
                source.setMinWidth(Region.USE_PREF_SIZE);
                Label text = new Label(issue.aiExplanation().get());
                text.setWrapText(true);
                HBox explanation = new HBox(8, source, text);
                explanation.setAlignment(Pos.TOP_LEFT);
                HBox.setHgrow(text, Priority.ALWAYS);
                card.getChildren().add(explanation);
                Label rule = muted("Rule: " + issue.ruleMessage());
                rule.getStyleClass().add("summary-reason");
                card.getChildren().add(rule);
            } else {
                Label rule = new Label(issue.ruleMessage());
                rule.setWrapText(true);
                card.getChildren().add(rule);
            }
            if (!issue.examples().isEmpty()) {
                card.getChildren().add(muted((issue.count() > issue.examples().size() ? "For example: " : "Files: ")
                        + String.join(", ", issue.examples())));
            }
            compatibilityIssues.getChildren().add(card);
        }
        if (aiRequested && summary.issues().stream().anyMatch(issue -> issue.aiExplanation().isEmpty()) && summary.notes().isEmpty()) {
            compatibilityIssues.getChildren().add(muted("Issues without an AI badge were not explained by the model; their "
                    + "rule wording is shown."));
        }
        summary.aiSource().ifPresent(source -> compatibilityIssues.getChildren().add(muted(source)));
        compatibilityIssues.setVisible(true);
    }

    // ---------------------------------------------------------------- step 4: plan

    private void clearPlan() {
        planGeneration.invalidate();
        planContext = null;
        planDetails.clear();
        planTable.getItems().clear();
        planBadges.getChildren().clear();
        planGuidance.setVisible(false);
        planSuggestionsCard.getChildren().clear();
        planSuggestionsCard.setVisible(false);
        refreshFlow();
    }

    private PlanRequest planRequest() {
        AssetManager.TargetProfile target = selectedTarget.get();
        if (target == null) throw new IllegalArgumentException("Choose an engine in the Target step.");
        return new PlanRequest(projectUuid(), workspace(), logDirectory(), requiredPath(outputRoot, "Output folder").normalize(), target);
    }

    private PlanContext requirePlan() {
        if (planContext == null || !planContext.request().equals(planRequest())) {
            throw new IllegalArgumentException("Inputs changed or no plan is displayed; create the plan again.");
        }
        return planContext;
    }

    private void createPlan() {
        createPlan("");
    }

    /** {@code note}: what led to this plan (for example applied fixes), kept at the start of the status. */
    private void createPlan(String note) {
        clearPlan();
        try {
            PlanRequest request = planRequest();
            long generation = planGeneration.current();
            boolean ai = planAi.isSelected();
            // The saved account is loaded on the worker thread, never the JavaFX thread.
            if (runner.submitValue(request.state(), request.logs(), manager -> ai
                    ? request.create(manager, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable())
                    : new PlanResult(request.create(manager), Optional.empty(), Optional.empty()), result -> {
                boolean current = planGeneration.isCurrent(generation);
                if (current && result.failure() == null && result.value() != null) {
                    showPlan(result.value().context());
                    showSuggestions(request, result.value());
                }
                busy(false);
                if (!current) return;
                if (result.failure() == null && result.value() != null) {
                    long blockers = blocking(planContext.plan());
                    status.setText(note + (!planContext.sourceErrors().isEmpty() ? "Source files could not be verified; approval is blocked."
                            : blockers > 0 ? blockers + " issue(s) block this plan. Choose Fix blockers, or fix them yourself and create a new plan."
                            : "Plan created. Check the files it will write, then approve it."));
                } else {
                    status.setText(note + failureText(result.failure(), null) + "\nA draft may have been created; nothing was approved or migrated.");
                }
            })) {
                busy(true);
                status.setText(note + (ai ? "Creating the plan and asking the AI for suggestions..." : "Creating the plan..."));
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    /** A fix run's outcome and, after a rescan, the new inventory to show. */
    record FixRun(BlockerFixes.Applied applied, InventorySnapshot rescanned) {}

    /** Looks for a fix for each blocker of the displayed plan; {@code ai}: let the AI choose where the rules cannot. */
    private void fixBlockers(boolean ai) {
        try {
            PlanContext context = requirePlan();
            PlanRequest request = context.request();
            MigrationPlan plan = context.plan();
            // The saved AI account is loaded on the worker thread, never the JavaFX thread.
            if (runner.submitValue(request.state(), request.logs(), manager -> manager.proposeBlockerFixes(request.project(), plan,
                    ai ? Optional.of(io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable()) : Optional.empty()), result -> {
                busy(false);
                if (result.failure() != null || result.value() == null) {
                    status.setText(failureText(result.failure(), null) + "\nNothing was changed.");
                    return;
                }
                chooseFixes(request, result.value(), ai);
            })) {
                busy(true);
                status.setText(ai ? "Asking the AI about the blockers the rules could not fix..." : "Looking for a fix for each blocker...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    /** Lets the user pick fixes: safe ones are preselected; leaving files out is offered but never preselected. */
    private void chooseFixes(PlanRequest request, BlockerFixes.Proposal proposal, boolean aiUsed) {
        List<BlockerFixes.Fix> recommended = proposal.fixes().stream().filter(BlockerFixes.Fix::recommended).toList();
        List<BlockerFixes.Fix> lastResort = proposal.fixes().stream().filter(fix -> !fix.recommended()).toList();
        VBox list = new VBox(8);
        Map<CheckBox, BlockerFixes.Fix> boxes = new java.util.LinkedHashMap<>();
        if (!recommended.isEmpty()) {
            list.getChildren().add(fieldLabel("Fixes (selected)"));
            recommended.forEach(fix -> list.getChildren().add(fixRow(fix, true, boxes)));
        }
        if (!lastResort.isEmpty()) {
            list.getChildren().add(fieldLabel("Last resort: leave files out (not selected)"));
            list.getChildren().add(muted("No safe fix was found for these. Leaving a file out means it is not migrated."));
            lastResort.forEach(fix -> list.getChildren().add(fixRow(fix, false, boxes)));
        }
        if (!proposal.unfixable().isEmpty()) {
            list.getChildren().add(fieldLabel("Needs you"));
            proposal.unfixable().forEach(line -> list.getChildren().add(muted("• " + line)));
        }
        Label note = muted("Fixes are saved as review decisions (or files left out of the scan), exactly as if you made them by "
                + "hand, and a new plan is created. Your asset folder is not changed and nothing is copied or approved."
                + (proposal.fixes().stream().anyMatch(fix -> !fix.leaveOut().isEmpty())
                ? " Leaving files out rescans the folder; your decisions are kept for files that did not change." : ""));
        note.setWrapText(true);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        // Most fixes wrap to two lines; size for that so short lists show without scrolling.
        scroll.setPrefViewportHeight(Math.min(400, 40 + 58.0 * (boxes.size() + proposal.unfixable().size())));
        scroll.getStyleClass().add("fix-list");

        javafx.scene.control.ButtonType apply = new javafx.scene.control.ButtonType("Apply fixes",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType askAi = new javafx.scene.control.ButtonType("Ask AI for the rest",
                javafx.scene.control.ButtonBar.ButtonData.LEFT);
        boolean offerAi = !aiUsed && proposal.fixes().stream().anyMatch(fix -> fix.code().equals("UNRESOLVED_REFERENCE") && !fix.recommended());
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Fix blockers");
        dialog.setHeaderText(fixHeader(proposal));
        dialog.getDialogPane().setContent(new VBox(12, scroll, note));
        dialog.getDialogPane().getButtonTypes().addAll(offerAi ? List.of(askAi, apply, javafx.scene.control.ButtonType.CANCEL)
                : List.of(apply, javafx.scene.control.ButtonType.CANCEL));
        dialog.getDialogPane().getStylesheets().add(stylesheet());
        dialog.getDialogPane().setPrefWidth(640);
        if (stepHost.getScene() != null) dialog.initOwner(stepHost.getScene().getWindow());
        Node applyButton = dialog.getDialogPane().lookupButton(apply);
        Runnable refresh = () -> {
            long chosen = boxes.keySet().stream().filter(CheckBox::isSelected).count();
            applyButton.setDisable(chosen == 0);
            ((Button) applyButton).setText(chosen == 0 ? "Apply fixes" : "Apply " + chosen + " fix(es)");
        };
        boxes.keySet().forEach(box -> box.selectedProperty().addListener((observable, was, now) -> refresh.run()));
        refresh.run();
        if (offerAi) ((Button) dialog.getDialogPane().lookupButton(askAi)).setTooltip(new Tooltip(
                "Sends relative paths, types and reference text (never file contents) to OpenAI so it can choose a target "
                        + "for broken references. Its choices are shown here for you to confirm."));

        var answer = dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        if (answer == askAi) {
            fixBlockers(true);
        } else if (answer == apply) {
            applyFixes(request, proposal, boxes.entrySet().stream().filter(e -> e.getKey().isSelected()).map(Map.Entry::getValue).toList());
        } else {
            status.setText("No fixes were applied.");
        }
    }

    /** Shows the AI's checked suggestions for the displayed plan, or why there are none. */
    private void showSuggestions(PlanRequest request, PlanResult result) {
        planSuggestionsCard.getChildren().clear();
        if (result.suggestions().isEmpty() && result.suggestionFailure().isEmpty()) {
            planSuggestionsCard.setVisible(false);
            return;
        }
        Label title = new Label("AI suggestions for " + request.target().id() + " " + request.target().version());
        title.getStyleClass().add("h3");
        planSuggestionsCard.getChildren().add(title);
        if (result.suggestionFailure().isPresent()) {
            planSuggestionsCard.getChildren().add(muted("No suggestions: " + result.suggestionFailure().get()));
            planSuggestionsCard.setVisible(true);
            return;
        }
        AssetManager.PlanSuggestions suggestions = result.suggestions().get();
        planSuggestionsCard.getChildren().add(muted(suggestionsSummary(suggestions)));
        if (!suggestions.proposal().fixes().isEmpty()) {
            Button review = styled(new Button("Review " + suggestions.proposal().fixes().size() + " suggestion(s)..."), "primary");
            review.setOnAction(event -> chooseDecisions(Optional.of(request), suggestions.proposal(), "AI suggestions",
                    "Each suggestion passed the program's checks. Tick the ones you want; they are saved as review decisions "
                            + "and a new plan is created. Your asset folder is not changed and nothing is approved."));
            planSuggestionsCard.getChildren().add(review);
        }
        planSuggestionsCard.setVisible(true);
    }

    /** One line about a suggestion run: how many passed, were dropped, or why the AI gave none. */
    static String suggestionsSummary(AssetManager.PlanSuggestions suggestions) {
        int passed = suggestions.proposal().fixes().size(), dropped = suggestions.proposal().unfixable().size();
        Optional<String> unavailable = suggestions.findings().stream()
                .filter(f -> f.code().equals("AI_UNAVAILABLE")).map(Finding::message).findFirst();
        if (unavailable.isPresent()) return unavailable.get();
        if (passed == 0 && dropped == 0) return "The AI suggested no changes for this engine; the plan is used as it is.";
        return passed + " suggestion(s) passed the program's checks"
                + (dropped == 0 ? "." : "; " + dropped + " failed them and are not offered (see the list when you review).");
    }

    /**
     * Lets the user accept proposed decisions one by one (none is preselected), with the ones the checks dropped listed
     * underneath. Accepted ones are saved like Fix blockers saves fixes; with a plan request a new plan is created.
     */
    private void chooseDecisions(Optional<PlanRequest> request, BlockerFixes.Proposal proposal, String title, String explanation) {
        VBox list = new VBox(8);
        Map<CheckBox, BlockerFixes.Fix> boxes = new java.util.LinkedHashMap<>();
        proposal.fixes().forEach(fix -> list.getChildren().add(fixRow(fix, false, boxes)));
        if (!proposal.unfixable().isEmpty()) {
            list.getChildren().add(fieldLabel("Dropped by the program's checks"));
            proposal.unfixable().forEach(line -> {
                Label item = muted("\u2022 " + line);
                item.setWrapText(true);
                list.getChildren().add(item);
            });
        }
        Label note = muted(explanation);
        note.setWrapText(true);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(420, 40 + 58.0 * (boxes.size() + proposal.unfixable().size())));
        scroll.getStyleClass().add("fix-list");
        javafx.scene.control.ButtonType apply = new javafx.scene.control.ButtonType("Apply",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType all = new javafx.scene.control.ButtonType("Select all",
                javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(proposal.fixes().isEmpty() ? "Nothing to accept." : proposal.fixes().size() + " proposed decision(s)");
        dialog.getDialogPane().setContent(new VBox(12, scroll, note));
        dialog.getDialogPane().getButtonTypes().addAll(all, apply, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().getStylesheets().add(stylesheet());
        dialog.getDialogPane().setPrefWidth(660);
        if (stepHost.getScene() != null) dialog.initOwner(stepHost.getScene().getWindow());
        Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);
        Button allButton = (Button) dialog.getDialogPane().lookupButton(all);
        // Select all ticks every box and keeps the dialog open.
        allButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            boxes.keySet().forEach(box -> box.setSelected(true));
            event.consume();
        });
        Runnable refresh = () -> {
            long chosen = boxes.keySet().stream().filter(CheckBox::isSelected).count();
            applyButton.setDisable(chosen == 0);
            applyButton.setText(chosen == 0 ? "Apply" : "Apply " + chosen);
        };
        boxes.keySet().forEach(box -> box.selectedProperty().addListener((observable, was, now) -> refresh.run()));
        refresh.run();
        var answer = dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        List<BlockerFixes.Fix> chosen = boxes.entrySet().stream().filter(e -> e.getKey().isSelected()).map(Map.Entry::getValue).toList();
        if (answer != apply || chosen.isEmpty()) {
            status.setText("Nothing was accepted; no decisions were saved.");
            return;
        }
        if (request.isPresent()) {
            applyFixes(request.get(), proposal, chosen);
            return;
        }
        // No plan inputs yet (for example from the agent): save the decisions only.
        if (runner.submitValue(workspace(), logDirectory(), manager -> {
            BlockerFixes.Applied applied = manager.applyBlockerFixes(proposal.projectId(), proposal.inventoryRevision(), chosen);
            return new FixRun(applied, applied.rescanned() ? manager.loadInventory(proposal.projectId()) : null);
        }, result -> {
            busy(false);
            if (result.failure() != null || result.value() == null) {
                status.setText(failureText(result.failure(), null) + "\nSome decisions may have been saved; check Saved decisions in the Review step.");
                return;
            }
            if (result.value().rescanned() != null) {
                showSnapshot(result.value().rescanned());
                clearAnalysis();
            }
            clearPlan();
            status.setText("Saved " + result.value().applied().decisionsSaved() + " decision(s). Choose an output folder in the "
                    + "Plan step and create the plan; nothing was approved or migrated.");
        })) {
            busy(true);
            status.setText("Saving " + chosen.size() + " decision(s)...");
        }
    }

    static String fixHeader(BlockerFixes.Proposal proposal) {
        long recommended = proposal.fixes().stream().filter(BlockerFixes.Fix::recommended).count();
        if (proposal.fixes().isEmpty()) return "No quick fix was found for these blockers.";
        return recommended == proposal.fixes().size() && proposal.unfixable().isEmpty()
                ? "A fix was found for every blocker."
                : recommended + " fix(es) found; some blockers need a choice from you.";
    }

    private Node fixRow(BlockerFixes.Fix fix, boolean selected, Map<CheckBox, BlockerFixes.Fix> boxes) {
        CheckBox box = new CheckBox(fix.description());
        box.setMnemonicParsing(false); // file names keep their underscores
        box.setWrapText(true);
        box.setSelected(selected);
        box.setMaxWidth(Double.MAX_VALUE);
        boxes.put(box, fix);
        if (fix.source() != BlockerFixes.Source.AI) return box;
        Label ai = badge("AI", "badge-info");
        ai.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, ai, box);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        return row;
    }

    private void applyFixes(PlanRequest request, BlockerFixes.Proposal proposal, List<BlockerFixes.Fix> chosen) {
        if (runner.submitValue(request.state(), request.logs(), manager -> {
            BlockerFixes.Applied applied = manager.applyBlockerFixes(request.project(), proposal.inventoryRevision(), chosen);
            return new FixRun(applied, applied.rescanned() ? manager.loadInventory(request.project()) : null);
        }, result -> {
            busy(false);
            if (result.failure() != null || result.value() == null) {
                status.setText(failureText(result.failure(), null) + "\nSome fixes may have been saved; check Saved decisions in "
                        + "the Review step before trying again.");
                return;
            }
            BlockerFixes.Applied applied = result.value().applied();
            if (result.value().rescanned() != null) {
                // Decisions now belong to the new revision; the Review step's last check is out of date.
                showSnapshot(result.value().rescanned());
                clearAnalysis();
            }
            createPlan(fixSummary(applied) + " ");
        })) {
            busy(true);
            status.setText("Applying " + chosen.size() + " fix(es)...");
        }
    }

    static String fixSummary(BlockerFixes.Applied applied) {
        return "Applied " + applied.fixes() + " fix(es)."
                + (applied.rescanned() ? " Rescanned to revision " + applied.inventoryRevision() + "." : "")
                + (applied.decisionsDropped() > 0 ? " " + applied.decisionsDropped()
                + " decision(s) about files that changed or were left out were not kept." : "");
    }

    private void showPlan(PlanContext context) {
        planContext = context;
        MigrationPlan plan = context.plan();
        planTable.getItems().setAll(plan.commands());
        planDetails.setText(formatPlan(context));
        long blockers = blocking(plan) + context.sourceErrors().size();
        planBadges.getChildren().setAll(
                badge(plan.commands().size() + " files", ""),
                badge(blockers == 0 ? "No blockers" : blockers + " blockers", blockers == 0 ? "badge-success" : "badge-danger"),
                badge(plan.status() == PlanStatus.APPROVED ? "Approved" : "Draft, not approved",
                        plan.status() == PlanStatus.APPROVED ? "badge-success" : "badge-warning"),
                badge("Version " + plan.version(), ""));
        planGuidance.getStyleClass().remove("callout-danger");
        if (blockers > 0) {
            planGuidance.setText(blockerGuidance(plan, context.sourceErrors()));
            planGuidance.getStyleClass().add("callout-danger");
            planGuidance.setVisible(true);
        } else if (plan.status() == PlanStatus.DRAFT) {
            planGuidance.setText("No blockers. Check the files below, then approve this exact version. Changing a decision "
                    + "or the output folder later needs a new plan.");
            planGuidance.setVisible(true);
        } else {
            planGuidance.setText("Approved. Continue to Migrate to run it.");
            planGuidance.setVisible(true);
        }
        refreshFlow();
    }

    private void approvePlan() {
        try {
            PlanContext context = requirePlan();
            if (!context.sourcesVerified()) throw new IllegalArgumentException("Source paths could not be verified; approval is blocked.");
            long generation = planGeneration.current();
            if (runner.submitValue(context.request().state(), context.request().logs(), context::approve, result -> {
                boolean current = planGeneration.isCurrent(generation);
                if (current && result.failure() == null && result.value() != null) {
                    showPlan(new PlanContext(context.request(), result.value(), context.sources(), context.sourceErrors()));
                }
                busy(false);
                if (!current) return;
                if (result.failure() == null && result.value() != null) {
                    status.setText("Plan approved. Continue to Migrate to run it.");
                } else {
                    clearPlan();
                    status.setText(failureText(result.failure(), null) + "\nApproval may have succeeded; create a new plan before continuing.");
                }
            })) {
                busy(true);
                status.setText("Approving this plan version...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- step 5: migrate

    private void migratePlan() {
        try {
            PlanContext context = requirePlan();
            if (context.plan().status() != PlanStatus.APPROVED) throw new IllegalArgumentException("Approve the plan first.");
            long generation = planGeneration.current();
            boolean ai = validationAi.isSelected();
            // The saved account is loaded on the worker thread, never the JavaFX thread.
            if (runner.submitValue(context.request().state(), context.request().logs(), manager -> context.migrate(manager,
                    ai ? Optional.of(io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable()) : Optional.empty()), result -> {
                boolean current = planGeneration.isCurrent(generation);
                if (current && result.failure() == null && result.value() != null) {
                    var outcome = result.value();
                    lastOutcome = outcome;
                    validationRun.setText(outcome.run().id().toString());
                    validationResults.setText(formatResult(outcome, context)
                            + outcome.report().map(report -> "\n\n" + formatReport(report, true)).orElse(""));
                    migrateBadges.getChildren().setAll(outcomeBadges(outcome));
                    showNextSteps(outcome.report().orElse(null));
                    showReport(outcome.report().orElse(null));
                    // Without a report the log is the only account of what happened.
                    runLogPane.setExpanded(outcome.report().isEmpty());
                }
                if (current) clearPlan();
                busy(false);
                if (!current) return;
                if (result.failure() != null || result.value() == null) {
                    status.setText(failureText(result.failure(), null)
                            + "\nMigration may have started; check the output folder and report history before retrying.");
                    return;
                }
                var outcome = result.value();
                status.setText("Migration " + label(outcome.run().status().toString()).toLowerCase(Locale.ROOT) + "; "
                        + outcome.report().map(report -> "validation " + label(report.validationStatus().toString()).toLowerCase(Locale.ROOT)
                        + ". The report is saved.").orElse("validation could not run; use Validate again."));
            })) {
                validationResults.clear();
                migrateBadges.getChildren().clear();
                migrateGuidance.setVisible(false);
                showReport(null);
                busy(true);
                status.setText("Copying files, then validating the outputs...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    /**
     * Shows what to do about each failed or unchecked output, hidden when nothing needs doing. A problem that
     * leaving the file out settles gets a Leave out button next to it.
     */
    private void showNextSteps(Report report) {
        var steps = report == null ? List.<io.github.aiassetmigrate.validation.ValidationNextSteps.Step>of()
                : io.github.aiassetmigrate.validation.ValidationNextSteps.of(report.findings());
        migrateGuidance.getChildren().clear();
        migrateGuidance.setVisible(!steps.isEmpty());
        if (steps.isEmpty()) return;
        Label title = new Label("What to do next");
        title.getStyleClass().add("h3");
        migrateGuidance.getChildren().add(title);
        for (var step : steps) {
            Label text = new Label("• " + (step.files().isEmpty() ? "" : fileList(step.files()) + ": ") + step.action());
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(10, text);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            if (step.canLeaveOut()) {
                Button leaveOut = new Button(step.files().size() == 1 ? "Leave out" : "Leave out " + step.files().size() + " files");
                leaveOut.setMinWidth(Region.USE_PREF_SIZE);
                leaveOut.setTooltip(new Tooltip("Save an EXCLUSION for " + fileList(step.files()) + " and create a new plan "
                        + "without it, ready to approve. Your asset folder is not changed."));
                leaveOut.setOnAction(event -> leaveOutAfterRun(step, report, leaveOut));
                row.getChildren().add(leaveOut);
            }
            migrateGuidance.getChildren().add(row);
        }
        migrateGuidance.getChildren().add(muted(io.github.aiassetmigrate.validation.ValidationNextSteps.NEW_RUN));
    }

    /** A report's findings by outcome; the AI step's own notes are not findings about outputs. */
    record ReportSections(List<Finding> failed, List<Finding> unchecked, List<Finding> warnings, long passed,
                          Optional<String> aiSource, Optional<String> aiMissing) {}

    static ReportSections reportSections(Report report) {
        List<Finding> failed = new ArrayList<>(), unchecked = new ArrayList<>(), warnings = new ArrayList<>();
        long passed = 0;
        Optional<String> source = Optional.empty(), missing = Optional.empty();
        for (Finding finding : report.findings()) {
            switch (finding.code()) {
                case "AI_SOURCE" -> source = Optional.of(finding.message());
                case "AI_UNAVAILABLE", "AI_OUTPUT_REJECTED" -> missing = Optional.of(finding.message());
                case "OUTPUT_VERIFIED" -> passed++;
                default -> {
                    if (finding.code().startsWith("AI_")) continue;
                    switch (finding.severity()) {
                        case ERROR -> failed.add(finding);
                        case UNKNOWN -> unchecked.add(finding);
                        case WARNING -> warnings.add(finding);
                        default -> { }
                    }
                }
            }
        }
        return new ReportSections(failed, unchecked, warnings, passed, source, missing);
    }

    /** Fills the AI summary card and the findings section for a report; null hides both. */
    private void showReport(Report report) {
        reportAiSummary.getChildren().clear();
        reportFindings.getChildren().clear();
        reportAiSummary.setVisible(false);
        reportFindings.setVisible(report != null);
        if (report == null) return;
        ReportSections sections = reportSections(report);

        if (report.aiSummary().isPresent() || sections.aiMissing().isPresent()) {
            Label title = new Label("AI summary");
            title.getStyleClass().add("h3");
            Label ai = badge("AI", "badge-info");
            ai.setMinWidth(Region.USE_PREF_SIZE);
            HBox header = new HBox(8, ai, title);
            header.setAlignment(Pos.CENTER_LEFT);
            Label text = new Label(report.aiSummary().orElseGet(() -> "No AI summary: " + sections.aiMissing().orElseThrow()));
            text.setWrapText(true);
            if (report.aiSummary().isEmpty()) text.getStyleClass().add("muted");
            reportAiSummary.getChildren().addAll(header, text, muted("Advisory. The validation status ("
                    + label(report.validationStatus().toString()).toLowerCase(Locale.ROOT) + ") comes from the checks, never "
                    + "from the AI." + sections.aiSource().map(source -> " " + source.split(";", 2)[0]
                    + "; it saw only statuses, counts, finding codes and relative file paths.").orElse("")));
            reportAiSummary.setVisible(true);
        }

        Label title = new Label("Validation findings");
        title.getStyleClass().add("h3");
        Label summary = new Label(sections.passed() + " output(s) passed every check; " + sections.failed().size()
                + " failed, " + sections.unchecked().size() + " not fully checked"
                + (sections.warnings().isEmpty() ? "." : ", " + sections.warnings().size() + " warning(s)."));
        summary.setWrapText(true);
        reportFindings.getChildren().addAll(title, summary);
        addFindingRows(sections.failed(), "Failed", "badge-danger");
        addFindingRows(sections.unchecked(), "Not checked", "badge-warning");
        addFindingRows(sections.warnings(), "Warning", "badge-neutral");
        if (sections.failed().isEmpty() && sections.unchecked().isEmpty() && sections.warnings().isEmpty()) {
            reportFindings.getChildren().add(muted("Nothing to look at. Every check for every output is in the full log below."));
        }
    }

    private void addFindingRows(List<Finding> findings, String label, String variant) {
        for (Finding finding : findings) {
            Label kind = badge(label, variant);
            kind.setMinWidth(Region.USE_PREF_SIZE);
            Label file = new Label(finding.sourceRelativePath().map(io.github.aiassetmigrate.domain.PortablePath::of).orElse("Run"));
            file.getStyleClass().add("summary-file");
            HBox header = new HBox(8, kind, file);
            header.setAlignment(Pos.CENTER_LEFT);
            Label message = new Label(finding.message());
            message.setWrapText(true);
            Label code = muted(label(finding.code()));
            VBox row = new VBox(4, header, message, code);
            row.getStyleClass().add("summary-item");
            reportFindings.getChildren().add(row);
        }
    }

    private static String fileList(List<String> files) {
        return String.join(", ", files.stream().limit(3).toList()) + (files.size() > 3 ? " and " + (files.size() - 3) + " more" : "");
    }

    /**
     * Leaves a problem's files out of the next plan: saves an EXCLUSION for each, picks a fresh output folder (the
     * last run's folder already holds files that are never overwritten), and creates the new plan in the Plan step,
     * ready for the user to approve. {@code button} (nullable) is marked as done once the decision is saved.
     */
    private void leaveOutAfterRun(io.github.aiassetmigrate.validation.ValidationNextSteps.Step step, Report report, Button button) {
        InventorySnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.project().id().equals(report.projectId())) {
            status.setText("Open this report's project in the Project step first, then try again.");
            return;
        }
        Map<String, UUID> ids = new HashMap<>();
        snapshot.project().assets().forEach(asset -> ids.put(io.github.aiassetmigrate.domain.PortablePath.of(asset.relativePath()), asset.id()));
        List<ReviewedChange> changes = leaveOutChanges(step, ids, Instant.now());
        if (changes.isEmpty()) {
            status.setText("These files are not in the project's current scan, so they cannot be left out. Rescan in the Project step.");
            return;
        }
        String files = fileList(step.files());
        if (!confirm("Leave files out", "Leave " + files + " out of the next plan?",
                "Each file is saved as an EXCLUSION decision, so the next plan does not copy it. Your asset folder is not "
                        + "changed, and the run that already finished is not touched.\n\nA new plan without it is then created "
                        + "for you to approve and run. Existing outputs are never overwritten, so if the output folder already "
                        + "has files, an empty folder next to it (for example messy-2) is created and used.",
                "Leave out")) {
            status.setText("Nothing was left out.");
            return;
        }
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID project = snapshot.project().id();
            long revision = snapshot.project().inventoryRevision();
            String previous = outputRoot.getText().trim();
            if (runner.submitValue(statePath, logPath, manager -> {
                manager.review(project, revision, changes);
                return previous.isEmpty() ? Optional.<Path>empty() : Optional.of(freshOutputFolder(Path.of(previous)));
            }, result -> {
                busy(false);
                if (result.failure() != null || result.value() == null) {
                    status.setText(failureText(result.failure(), null) + "\nIf the decisions were saved, Saved decisions in the "
                            + "Review step lists them; choose an empty output folder before the next run.");
                    return;
                }
                Optional<Path> next = result.value();
                next.ifPresent(folder -> outputRoot.setText(folder.toString()));
                if (button != null) {
                    button.setText("Left out");
                    button.setDisable(true);
                }
                showStep(WizardFlow.Step.PLAN);
                // The new plan is created right away so the exclusion takes effect; approving and running it stay
                // explicit, since nothing is written without an approved plan.
                createPlan("Left out " + files + ". " + (next.isPresent() && !next.get().toString().equals(previous)
                        ? "New output folder: " + next.get() + " (the last one already has files). " : ""));
            })) {
                busy(true);
                status.setText("Saving the decision to leave " + files + " out...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    /** One accepted EXCLUSION per file of the step that is in the inventory; files no longer scanned are skipped. */
    static List<ReviewedChange> leaveOutChanges(io.github.aiassetmigrate.validation.ValidationNextSteps.Step step,
                                                Map<String, UUID> ids, Instant now) {
        List<ReviewedChange> changes = new ArrayList<>();
        for (String file : step.files()) {
            UUID asset = ids.get(file);
            if (asset != null) changes.add(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.EXCLUSION,
                    ReviewedChange.Decision.ACCEPTED, Map.of("reason", "Left out after validation (" + step.code() + ")"), now));
        }
        return changes;
    }

    /**
     * Runs on the worker thread: the folder for the next run, which must exist. This one if it is empty; otherwise
     * an empty sibling ({@code <name>-2}, ...) is created. Nothing else is created or changed.
     */
    static Path freshOutputFolder(Path folder) throws IOException {
        Path next = nextOutputFolder(folder);
        if (!Files.exists(next)) Files.createDirectory(next);
        return next;
    }

    /**
     * The folder for the next run: this one if it is missing or empty, otherwise the first free sibling named
     * {@code <name>-2}, {@code <name>-3}, ...
     */
    static Path nextOutputFolder(Path folder) {
        try {
            if (!Files.exists(folder)) return folder;
            try (var entries = Files.list(folder)) {
                if (entries.findAny().isEmpty()) return folder;
            }
        } catch (IOException | RuntimeException unreadable) {
            // Unreadable: propose a sibling rather than a folder that may not be usable.
        }
        String name = folder.getFileName() == null ? "output" : folder.getFileName().toString();
        for (int n = 2; n < 1000; n++) {
            Path candidate = folder.resolveSibling(name + "-" + n);
            if (!Files.exists(candidate)) return candidate;
        }
        return folder;
    }

    private void validateRun() {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID run = UUID.fromString(validationRun.getText().trim());
            boolean ai = validationAi.isSelected();
            if (runner.submitValue(statePath, logPath, manager -> ai
                    // The saved account is loaded on the worker thread, never the JavaFX thread.
                    ? manager.validate(run, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable())
                    : manager.validate(run), result -> {
                if (result.failure() == null && result.value() != null) {
                    validationResults.setText(formatReport(result.value(), true));
                    migrateBadges.getChildren().setAll(validationBadge(result.value().validationStatus()));
                    showNextSteps(result.value());
                    showReport(result.value());
                }
                busy(false);
                status.setText(result.failure() == null && result.value() != null
                        ? "Validation " + label(result.value().validationStatus().toString()).toLowerCase(Locale.ROOT) + ". Report saved."
                        : failureText(result.failure(), null) + "\nNo report was displayed; show the history to check whether one was saved.");
            })) {
                validationResults.clear();
                busy(true);
                status.setText("Validating the run's outputs...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void loadHistory() {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID project = projectUuid();
            if (runner.submitValue(statePath, logPath, manager -> manager.history(project), result -> {
                if (result.failure() == null && result.value() != null) {
                    validationResults.setText(formatHistory(project, result.value()));
                    runLogPane.setExpanded(true); // the history is shown in the log
                }
                busy(false);
                status.setText(result.failure() != null ? failureText(result.failure(), null)
                        : result.value().isEmpty() ? "No reports for this project yet."
                        : "Loaded " + result.value().size() + " report(s).");
            })) {
                validationResults.clear();
                busy(true);
                status.setText("Loading report history...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- tools

    private void sendAgentRequest() {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID project = projectUuid();
            String request = agentRequest.getText().strip();
            if (request.isEmpty()) throw new IllegalArgumentException("Enter a request.");
            if (runner.submitValue(statePath, logPath, manager -> manager.command(project, request,
                    io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable()), result -> {
                agentTranscript.appendText("> " + request + "\n" + (result.failure() == null && result.value() != null
                        ? formatCommand(result.value()) : failureText(result.failure(), null) + "\n") + "\n");
                Optional<BlockerFixes.Proposal> decisions = result.failure() == null && result.value() != null
                        ? result.value().decisions().filter(d -> !d.fixes().isEmpty()) : Optional.empty();
                agentDecisions.setVisible(decisions.isPresent());
                decisions.ifPresent(proposal -> {
                    agentDecisions.setText("Review " + proposal.fixes().size() + " proposed decision(s)...");
                    agentDecisions.setOnAction(event -> {
                        Optional<PlanRequest> plan;
                        try { plan = Optional.of(planRequest()); } catch (IllegalArgumentException incomplete) { plan = Optional.empty(); }
                        chooseDecisions(plan, proposal, "Proposed by the agent", "The agent gathered these with the program's "
                                + "read-only tools, and the program checked each one. Tick the ones you want; they are saved as "
                                + "review decisions" + (plan.isPresent() ? " and a new plan is created" : "")
                                + ". Your asset folder is not changed and nothing is approved.");
                    });
                });
                busy(false);
                status.setText(result.failure() == null ? "Agent request finished; requests never approve or migrate."
                        : "Agent request failed.");
            })) {
                agentRequest.clear();
                busy(true);
                status.setText("Interpreting request...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    static String formatCommand(AssetManager.CommandResult result) {
        StringBuilder text = new StringBuilder(result.message()).append('\n');
        result.draftPlan().ifPresent(plan -> text.append("Draft plan ").append(plan.id()).append(" version ").append(plan.version())
                .append(" (").append(plan.status()).append("). Review and approve it in the Plan step or with 'approve'.\n"));
        for (Finding finding : result.findings()) {
            if (finding.severity() == Finding.Severity.INFO && !finding.code().startsWith("AI_")) continue;
            text.append("  ").append(finding.severity()).append(' ').append(finding.code()).append(": ").append(finding.message()).append('\n');
        }
        return text.toString();
    }

    private void followLogs() {
        try {
            stopLogs();
            Path logPath = logDirectory();
            UUID project = projectUuid();
            long after = Long.parseLong(logAfter.getText().trim());
            if (after < 0) throw new IllegalArgumentException("After sequence must be nonnegative.");
            // Observer: the follower thread delivers events; each update is marshalled onto the JavaFX thread.
            logFollower = io.github.aiassetmigrate.logging.LogFollower.start(logPath, project, after,
                    new io.github.aiassetmigrate.application.LogObserver() {
                        @Override public void update(io.github.aiassetmigrate.domain.LogEvent event) {
                            String line = formatEvent(event);
                            Platform.runLater(() -> {
                                logView.appendText(line);
                                logAfter.setText(Long.toString(event.sequence()));
                            });
                        }
                        @Override public void onError(Throwable failure) {
                            Platform.runLater(() -> logStatus.setText("Disconnected: " + failure.getMessage()
                                    + ". Work is not affected; press Follow to reconnect from the last event."));
                        }
                    }, java.time.Duration.ofMillis(500));
            logStatus.setText("Following project " + shortId(project) + " after event " + after + ".");
        } catch (NumberFormatException failure) {
            logStatus.setText("Invalid input: after must be a whole number.");
        } catch (IllegalArgumentException failure) {
            logStatus.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void stopLogs() {
        if (logFollower != null) {
            logFollower.close();
            logFollower = null;
            logStatus.setText("Stopped following. Work continues; Follow resumes after the last event shown.");
        }
    }

    /** Loads the saved account and, when connected, the models it may use, then fills the model list. */
    private void refreshAiStatus() {
        aiStatus.setText("Checking AI account...");
        refreshModels.setDisable(true);
        Thread worker = new Thread(() -> {
            String text;
            boolean connected = false;
            List<io.github.aiassetmigrate.ai.SiwcInference.Model> models = List.of();
            Optional<String> saved = Optional.empty();
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var account = store.load();
                if (account.isEmpty()) {
                    text = "Not connected. AI features are optional.";
                } else {
                    connected = true;
                    saved = account.get().model();
                    String who = "Connected as " + accountName(account.get()) + ".";
                    try {
                        String token = io.github.aiassetmigrate.auth.AccountSession.forStore(store).accessToken(false);
                        models = io.github.aiassetmigrate.ai.SiwcInference.listModels(
                                new io.github.aiassetmigrate.ai.SiwcHttpTransport(), token, java.time.Duration.ofSeconds(30));
                        text = who + " " + modelStatus(models, saved);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        text = who + " Loading the model list was interrupted.";
                    } catch (Exception e) {
                        text = who + " Could not load the model list (" + e.getMessage() + "). AI features keep using "
                                + saved.orElse("<no model>") + "; use Refresh to try again.";
                    }
                }
            } catch (Exception e) {
                text = "Unavailable (" + e.getMessage() + ").";
            }
            showAi(text, connected, models, saved);
        }, "ai-status");
        worker.setDaemon(true);
        worker.start();
    }

    /** Updates only the status text and header label; the model list is left as it is. */
    private void showAi(String text, boolean connected) {
        Platform.runLater(() -> {
            aiStatus.setText(text);
            aiTool.setText(connected ? "AI: connected" : "AI: off");
        });
    }

    /** Updates the status and replaces the model list, selecting the saved model if it is listed. */
    private void showAi(String text, boolean connected, List<io.github.aiassetmigrate.ai.SiwcInference.Model> models,
                        Optional<String> saved) {
        Platform.runLater(() -> {
            aiStatus.setText(text);
            aiTool.setText(connected ? "AI: connected" : "AI: off");
            updatingModels = true;
            try {
                aiModel.getItems().setAll(models);
                aiModel.setValue(chosenModel(models, saved).orElse(null));
            } finally {
                updatingModels = false;
            }
            aiModel.setPromptText(!connected ? "Connect to list models"
                    : models.isEmpty() ? "No models listed; use Refresh" : "Choose a model");
            aiModel.setDisable(models.isEmpty());
            refreshModels.setDisable(!connected);
        });
    }

    /** Saves the user's model choice; every later AI request reads it from the saved account. */
    private void selectAiModel(io.github.aiassetmigrate.ai.SiwcInference.Model model) {
        aiModel.setDisable(true);
        aiStatus.setText("Switching to " + modelLabel(model) + "...");
        Thread worker = new Thread(() -> {
            String text;
            boolean saved = false;
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                // Under the store lock so a concurrent token refresh cannot overwrite the choice.
                var account = store.locked(() -> {
                    var current = store.load().orElseThrow(io.github.aiassetmigrate.auth.AccountSession.NotConnectedException::new);
                    var updated = new io.github.aiassetmigrate.auth.AccountStore.Account(current.credentials(),
                            Optional.of(model.slug()));
                    store.save(updated);
                    return updated;
                });
                saved = true;
                text = "Connected as " + accountName(account) + ". Using " + modelLabel(model) + " for AI features.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "Switching model was interrupted.";
            } catch (Exception e) {
                text = "Could not switch model (" + e.getMessage() + ").";
            }
            // On failure, show the model still in use rather than the rejected choice.
            Optional<String> inUse = Optional.of(model.slug());
            if (!saved) {
                try {
                    inUse = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser().load()
                            .flatMap(io.github.aiassetmigrate.auth.AccountStore.Account::model);
                } catch (Exception unreadable) {
                    inUse = Optional.empty();
                }
            }
            String message = text;
            Optional<String> selection = inUse;
            Platform.runLater(() -> {
                aiStatus.setText(message);
                updatingModels = true;
                try {
                    aiModel.setValue(chosenModel(aiModel.getItems(), selection).orElse(null));
                } finally {
                    updatingModels = false;
                }
                aiModel.setDisable(aiModel.getItems().isEmpty());
            });
        }, "ai-model");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectAi() {
        aiStatus.setText("Complete sign-in in your browser (up to 5 minutes)...");
        Thread worker = new Thread(() -> {
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var previous = store.load();
                var credentials = new io.github.aiassetmigrate.auth.OAuthSession(store.hostId(),
                        previous.map(io.github.aiassetmigrate.auth.AccountStore.Account::registration)).authorize();
                var models = io.github.aiassetmigrate.ai.SiwcInference.listModels(
                        new io.github.aiassetmigrate.ai.SiwcHttpTransport(), credentials.accessToken(), java.time.Duration.ofSeconds(30));
                var model = previous.flatMap(io.github.aiassetmigrate.auth.AccountStore.Account::model)
                        .filter(slug -> models.stream().anyMatch(m -> m.slug().equals(slug)))
                        .or(() -> models.stream().findFirst().map(io.github.aiassetmigrate.ai.SiwcInference.Model::slug));
                var account = new io.github.aiassetmigrate.auth.AccountStore.Account(credentials, model);
                store.save(account);
                showAi("Connected as " + accountName(account) + ". " + modelStatus(models, model)
                        + " Migration approval is unchanged.", true, models, model);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                showAi("Sign-in interrupted.", false);
            } catch (Exception e) {
                showAi("Sign-in failed (" + e.getMessage() + ").", false);
            }
        }, "ai-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnectAi() {
        Thread worker = new Thread(() -> {
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var account = store.load();
                String text;
                if (account.isEmpty()) {
                    text = "Not connected.";
                } else {
                    var credentials = account.get().credentials();
                    boolean revoked = new io.github.aiassetmigrate.auth.OAuthSession(store.hostId(),
                            java.util.Optional.of(account.get().registration())).revoke(credentials);
                    store.clear();
                    text = "Disconnected. " + (revoked ? "Remote session revoked."
                            : "Remote revocation not confirmed; you can also disconnect in ChatGPT Settings.");
                }
                showAi(text, false, List.of(), Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                showAi("Disconnect interrupted.", true);
            } catch (Exception e) {
                showAi("Disconnect failed (" + e.getMessage() + ").", true);
            }
        }, "ai-disconnect");
        worker.setDaemon(true);
        worker.start();
    }

    private static String accountName(io.github.aiassetmigrate.auth.AccountStore.Account account) {
        return account.credentials().email().orElse(account.credentials().subject());
    }

    /** Dropdown text: "Display name (slug)", or just the slug when the name adds nothing. */
    static String modelLabel(io.github.aiassetmigrate.ai.SiwcInference.Model model) {
        String name = model.displayName() == null ? "" : model.displayName().strip();
        return name.isEmpty() || name.equals(model.slug()) ? model.slug() : name + " (" + model.slug() + ")";
    }

    /** The listed model matching the saved selection; empty when none is saved or it is no longer listed. */
    static Optional<io.github.aiassetmigrate.ai.SiwcInference.Model> chosenModel(
            List<io.github.aiassetmigrate.ai.SiwcInference.Model> models, Optional<String> saved) {
        return saved.flatMap(slug -> models.stream().filter(m -> m.slug().equals(slug)).findFirst());
    }

    static String modelStatus(List<io.github.aiassetmigrate.ai.SiwcInference.Model> models, Optional<String> saved) {
        if (models.isEmpty()) return "No models are available to this account.";
        Optional<io.github.aiassetmigrate.ai.SiwcInference.Model> chosen = chosenModel(models, saved);
        if (chosen.isPresent()) return "Using " + modelLabel(chosen.get()) + ".";
        if (saved.isPresent()) {
            return "The saved model " + saved.get() + " is no longer available to this account; choose another.";
        }
        return "Choose a model to use AI features.";
    }

    // ---------------------------------------------------------------- formatting (tested)

    static String formatEvent(io.github.aiassetmigrate.domain.LogEvent event) {
        return event.sequence() + " " + event.occurredAt() + " " + event.level() + " "
                + event.message().replace("\r", "\\r").replace("\n", "\\n") + "\n";
    }

    static String formatHistory(UUID project, List<Report> reports) {
        if (reports.isEmpty()) return "No reports for project " + project + " yet. Each migration saves one; Validate again adds another.";
        StringBuilder text = new StringBuilder("Report history for project ").append(project)
                .append(" (").append(reports.size()).append(" report(s), oldest first)\n");
        for (var report : reports) text.append('\n').append(formatReport(report, false));
        return text.toString();
    }

    static String formatReport(Report report, boolean findings) {
        StringBuilder text = new StringBuilder("Validation status: ").append(report.validationStatus())
                .append("\nReport ID: ").append(report.id()).append("\nCreated at: ").append(report.createdAt())
                .append("\nRun ID: ").append(report.runId()).append(" (run status ").append(report.runStatus()).append(")")
                .append("\nPlan ID: ").append(report.planId())
                .append("\nSummary: ").append(report.summary()).append('\n');
        report.aiSummary().ifPresent(summary -> text.append("AI summary (advisory; the status above is authoritative): ")
                .append(summary).append('\n'));
        if (!findings) return text.toString();
        text.append("Findings (").append(report.findings().size()).append("):\n");
        for (Finding finding : report.findings()) {
            text.append(finding.severity()).append(' ').append(finding.code()).append(" | ")
                    .append(finding.sourceRelativePath().map(io.github.aiassetmigrate.domain.PortablePath::of).orElse("<run>"))
                    .append(" | ").append(finding.message()).append('\n');
        }
        return text.toString();
    }

    /** The run per file, using the plan it executed to show paths instead of command IDs. */
    static String formatResult(AssetManager.MigrationOutcome outcome, PlanContext context) {
        MigrationRun run = outcome.run();
        long succeeded = run.stepStatuses().values().stream().filter(s -> s == io.github.aiassetmigrate.domain.StepStatus.SUCCESS).count();
        StringBuilder text = new StringBuilder("Run ").append(run.status()).append(": ").append(succeeded).append(" of ")
                .append(run.stepStatuses().size()).append(" step(s) succeeded. Output folder: ").append(run.outputRoot()).append('\n');
        run.message().ifPresent(message -> text.append(message).append('\n'));
        for (MigrationCommand command : context.plan().commands()) {
            var stepStatus = run.stepStatuses().get(command.id());
            if (stepStatus == null) continue;
            text.append(stepStatus == io.github.aiassetmigrate.domain.StepStatus.SUCCESS ? "  ✓ " : "  ✗ ")
                    .append(String.format(Locale.ROOT, "%-8s", stepStatus)).append(' ')
                    .append(io.github.aiassetmigrate.domain.PortablePath.of(command.relativeOutputPath()))
                    .append(" (").append(label(command.operation().toString()).toLowerCase(Locale.ROOT)).append(')');
            String detail = run.stepDetails().get(command.id());
            if (detail != null && !detail.isBlank() && !detail.equals("Published")) text.append(" - ").append(detail);
            text.append('\n');
        }
        outcome.migrationFailure().ifPresent(failure -> text.append("Migration interrupted: ").append(failure).append('\n'));
        if (outcome.report().isEmpty()) {
            text.append("Validation failed: ").append(outcome.validationFailure().orElseThrow())
                    .append("\nNo report was saved; use Validate again to retry.\n");
        }
        return text.toString();
    }

    static String formatPlan(PlanContext context) {
        MigrationPlan plan = context.plan();
        StringBuilder text = new StringBuilder("Copy-only migration plan\nPlan ID: ").append(plan.id())
                .append("\nProject: ").append(plan.projectId()).append("\nPlan version: ").append(plan.version())
                .append("\nInventory revision: ").append(plan.inventoryRevision()).append("\nStatus: ").append(plan.status())
                .append("\nTarget: ").append(plan.targetProfile()).append(" / ").append(plan.targetProfileVersion())
                .append("\nOutput root: ").append(plan.outputRoot()).append("\nCommands (complete):\n");
        if (plan.commands().isEmpty()) text.append("None\n");
        for (var command : plan.commands()) {
            text.append("Source asset ID: ").append(command.assetId()).append(" | Source relative path: ")
                    .append(context.sources().containsKey(command.assetId()) ? io.github.aiassetmigrate.domain.PortablePath.of(context.sources().get(command.assetId())) : "<unknown>").append('\n')
                    .append(command).append('\n');
        }
        text.append("Reviewed decisions (complete):\n");
        if (plan.reviewedChanges().isEmpty()) text.append("None\n");
        for (ReviewedChange change : plan.reviewedChanges()) {
            text.append("Review ID: ").append(change.id()).append(" | Asset ID: ").append(change.assetId())
                    .append(" | Source relative path: ")
                    .append(context.sources().containsKey(change.assetId()) ? io.github.aiassetmigrate.domain.PortablePath.of(context.sources().get(change.assetId())) : "<unknown>")
                    .append(" | Kind: ").append(change.kind()).append(" | Decision: ").append(change.decision())
                    .append(" | Values: ").append(change.values()).append(" | Reviewed at: ").append(change.reviewedAt()).append('\n');
        }
        text.append("Source verification errors:\n");
        if (context.sourceErrors().isEmpty()) text.append("None\n");
        for (String error : context.sourceErrors()) text.append("ERROR: ").append(error).append('\n');
        text.append("Blockers and other findings:\n");
        if (plan.findings().isEmpty()) text.append("None\n");
        for (Finding finding : plan.findings()) text.append(finding).append('\n');
        return text.toString();
    }

    static String formatCompatibility(AssetManager.CompatibilityResult result) {
        StringBuilder text = new StringBuilder("Target compatibility findings (not full validation or approval).\nInventory revision: ")
                .append(result.inventoryRevision()).append("\nTarget: ").append(result.target().id())
                .append(" / ").append(result.target().version()).append("\nFindings:\n");
        if (result.findings().isEmpty()) return text.append("No compatibility findings.\n").toString();
        for (Finding finding : result.findings()) {
            text.append(finding.severity()).append(" [").append(finding.code()).append("] ");
            finding.sourceRelativePath().ifPresent(path -> text.append(io.github.aiassetmigrate.domain.PortablePath.of(path)).append(": "));
            finding.assetId().ifPresent(id -> text.append("asset ").append(id).append(": "));
            text.append(finding.message()).append('\n');
        }
        return text.toString();
    }

    static String formatPreview(OrganizationPreview.Result preview) {
        StringBuilder text = new StringBuilder("Read-only preview, not approval. Inventory revision: ")
                .append(preview.inventoryRevision()).append("\nStatus: ")
                .append(preview.blocked() ? "BLOCKED" : "No blocking findings").append("\nEntries:\n");
        for (OrganizationPreview.Entry entry : preview.entries()) {
            text.append("Asset: ").append(entry.assetId()).append(" | Source: ")
                    .append(io.github.aiassetmigrate.domain.PortablePath.of(entry.source()))
                    .append(" | Destination: ").append(entry.destination().orElse("(none)"))
                    .append(" | Excluded: ").append(entry.excluded()).append('\n');
            for (var rewrite : entry.rewrites()) {
                text.append("    Rewrite ").append(rewrite.kind()).append(" reference: ").append(rewrite.original())
                        .append(" -> ").append(rewrite.replacement()).append('\n');
            }
        }
        text.append("Blocking findings:\n");
        if (preview.findings().isEmpty()) text.append("None\n");
        for (OrganizationPreview.Finding finding : preview.findings()) {
            text.append('[').append(finding.code()).append("] ");
            finding.assetId().ifPresent(id -> text.append("Asset: ").append(id).append(" | "));
            text.append(finding.message()).append('\n');
        }
        return text.toString();
    }

    static String formatReviews(List<ReviewedChange> reviews) {
        if (reviews.isEmpty()) return "No saved reviews for this inventory revision.";
        StringBuilder text = new StringBuilder();
        for (ReviewedChange review : reviews) {
            text.append("Asset: ").append(review.assetId()).append(" | Kind: ").append(review.kind())
                    .append(" | Decision: ").append(review.decision()).append(" | Values: ")
                    .append(review.values()).append('\n');
        }
        return text.toString();
    }

    static String formatAnalysisFindings(List<Finding> items) {
        StringBuilder text = new StringBuilder();
        for (Finding finding : items) {
            text.append(finding.severity()).append(" [").append(finding.code()).append("] ");
            finding.sourceRelativePath().ifPresent(path -> text.append(io.github.aiassetmigrate.domain.PortablePath.of(path)).append(": "));
            finding.assetId().ifPresent(id -> text.append("asset ").append(id).append(": "));
            text.append(finding.message()).append('\n');
        }
        return text.isEmpty() ? "No analysis findings." : text.toString();
    }

    static String failureText(Throwable failure, InventorySnapshot snapshot) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        StringBuilder text = new StringBuilder("Operation failed: ").append(message);
        Throwable cause = failure.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().equals(message)) {
            text.append("\nCause: ").append(cause.getMessage());
        }
        if (failure instanceof ImportException importFailure && importFailure.committedRevision().isPresent()) {
            text.append("\nInventory was committed; it was not rolled back.");
            text.append(" Project ID: ").append(importFailure.projectId().map(UUID::toString).orElse("unavailable"));
            text.append(" Revision: ").append(importFailure.committedRevision().orElseThrow());
            text.append(". Use Open a saved project to recover it.");
        } else if (snapshot != null) {
            text.append("\nThe displayed inventory was obtained, but resource cleanup failed.");
        }
        return text.toString();
    }

    static String formatFindings(InventorySnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        for (Finding finding : snapshot.findings()) {
            text.append(finding.severity()).append(" [").append(finding.code()).append("] ");
            finding.sourceRelativePath().ifPresent(path -> text.append(path.toString().isEmpty() ? "(source root)" : io.github.aiassetmigrate.domain.PortablePath.of(path)).append(": "));
            text.append(finding.message()).append('\n');
        }
        for (PathOutcome outcome : snapshot.pathOutcomes()) {
            if (outcome.status() == PathOutcome.Status.FAILED || outcome.status() == PathOutcome.Status.UNVISITED
                    || outcome.status() == PathOutcome.Status.SKIPPED) {
                text.append("Coverage ").append(outcome.status()).append(" ").append(outcome.kind()).append(": ")
                        .append(io.github.aiassetmigrate.domain.PortablePath.of(outcome.relativePath()).isEmpty() ? "(source root)" : io.github.aiassetmigrate.domain.PortablePath.of(outcome.relativePath())).append('\n');
            }
        }
        return text.isEmpty() ? "No findings or skipped or incomplete coverage." : text.toString();
    }

    /** Plain-language next step for a blocking finding code; empty when the message speaks for itself. */
    static Optional<String> blockerHint(String code) {
        return Optional.ofNullable(switch (code) {
            case "PARTIAL_INVENTORY" -> "Some files were skipped during the scan. In the Project step, choose Leave these files "
                    + "out (they are not copied and your folder is not changed), or fix them and Rescan.";
            case "UNRESOLVED_REFERENCE" -> "A model or material points to a file that cannot be found. Accept a suggested "
                    + "correction in Review (Relationships or Materials), or exclude the file.";
            case "INSPECTION_UNSUPPORTED" -> "This model format cannot be inspected. In Review, add an ACKNOWLEDGMENT (to "
                    + "include it anyway) or an EXCLUSION, using Edit or add a decision.";
            case "INSPECTION_UNREADABLE" -> "The built-in FBX reader could not read this file (it may be damaged or use an "
                    + "unsupported FBX version). Add an ACKNOWLEDGMENT to include it unchecked, re-export it, or exclude it.";
            case "FBX_REFERENCE_NOT_REWRITABLE" -> "An FBX is copied as it is, so its texture must stay where the FBX expects it. "
                    + "Keep the texture's place relative to the FBX in Review, or convert the model to GLB.";
            case "CONVERSION_CHECK_FAILED" -> "A conversion cannot be done as reviewed. Change or reject the CONVERSION "
                    + "decision in Review (for example add background=#FFFFFF for a transparent texture).";
            case "CONVERSION_UNSUPPORTED", "INVALID_CONVERSION" -> "This conversion is not available for the file. "
                    + "Reject the CONVERSION decision in Review, or choose a supported format.";
            case "DESTINATION_CONFLICT", "CASE_COLLISION" -> "Two files would be written to the same place (names that "
                    + "differ only in letter case count as the same). Rename or move one of them in Review.";
            case "INVALID_DESTINATION" -> "A reviewed name or folder is not a valid path. Change it in Review.";
            case "SOURCE_IMAGE_INVALID" -> "An image in your folder is damaged and cannot be decoded, so its copy would fail "
                    + "validation. Replace it and Rescan, or leave it out (EXCLUSION).";
            case "INVALID_CORRECTION", "CONFLICTING_CORRECTIONS" -> "A reference correction points to the wrong kind of "
                    + "file, or two corrections disagree. Reject one of them in Review.";
            case "MODEL_CONVERSION_LAYOUT" -> "To convert an OBJ, its material file must end up in the same output folder. "
                    + "Adjust the folders in Review.";
            default -> null;
        });
    }

    /** The blocking findings of a plan, grouped by code with a next step for each. */
    static String blockerGuidance(MigrationPlan plan, List<String> sourceErrors) {
        StringBuilder text = new StringBuilder("This plan cannot be approved yet. Choose Fix blockers to fix these in one step, "
                + "or fix them yourself and create a new plan:\n");
        for (String error : sourceErrors) text.append("\n\u2022 ").append(error);
        Map<String, List<Finding>> byCode = new java.util.LinkedHashMap<>();
        plan.findings().stream().filter(AssetMigrateApplication::isBlocking)
                .forEach(finding -> byCode.computeIfAbsent(finding.code(), code -> new ArrayList<>()).add(finding));
        int shown = 0;
        for (var entry : byCode.entrySet()) {
            if (shown++ == MAX_BLOCKERS_SHOWN) {
                text.append("\n\u2022 ...and ").append(byCode.size() - MAX_BLOCKERS_SHOWN).append(" more kinds; see Full plan details.");
                break;
            }
            List<Finding> group = entry.getValue();
            Finding first = group.getFirst();
            text.append("\n\u2022 ").append(entry.getKey());
            first.sourceRelativePath().ifPresent(path -> text.append(" (").append(io.github.aiassetmigrate.domain.PortablePath.of(path))
                    .append(group.size() > 1 ? " and " + (group.size() - 1) + " more" : "").append(')'));
            text.append(": ").append(blockerHint(entry.getKey()).orElse(first.message()));
        }
        return text.toString();
    }

    private static boolean isBlocking(Finding finding) {
        return finding.severity() == Finding.Severity.ERROR || finding.severity() == Finding.Severity.UNKNOWN;
    }

    private static long blocking(MigrationPlan plan) {
        return plan.findings().stream().filter(AssetMigrateApplication::isBlocking).count();
    }

    private static long count(List<Finding> findings, Finding.Severity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    /** "CATEGORY_CONFLICT" -> "Category conflict"; enum names become sentence case for display. */
    static String label(String constant) {
        if (constant == null || constant.isBlank()) return "";
        String words = constant.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static String humanBytes(String bytes) {
        try {
            double value = Long.parseLong(bytes);
            String[] units = {"B", "KB", "MB", "GB"};
            int unit = 0;
            while (value >= 1024 && unit < units.length - 1) {
                value /= 1024;
                unit++;
            }
            return unit == 0 ? (long) value + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
        } catch (NumberFormatException unknown) {
            return bytes;
        }
    }

    static String targetName(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "unity" -> "Unity";
            case "godot" -> "Godot";
            default -> label(id);
        };
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- small view helpers

    private List<Node> severityBadges(List<Finding> items) {
        long errors = count(items, Finding.Severity.ERROR), warnings = count(items, Finding.Severity.WARNING),
                unknown = count(items, Finding.Severity.UNKNOWN);
        return List.of(badge(errors + " errors", errors > 0 ? "badge-danger" : "badge-success"),
                badge(warnings + " warnings", warnings > 0 ? "badge-warning" : ""),
                badge(unknown + " not checked", unknown > 0 ? "badge-warning" : ""));
    }

    private List<Node> outcomeBadges(AssetManager.MigrationOutcome outcome) {
        RunStatus run = outcome.run().status();
        List<Node> badges = new ArrayList<>();
        badges.add(badge("Run " + label(run.toString()).toLowerCase(Locale.ROOT),
                run == RunStatus.SUCCESS ? "badge-success" : run == RunStatus.PARTIAL ? "badge-warning" : "badge-danger"));
        outcome.report().ifPresentOrElse(report -> badges.add(validationBadge(report.validationStatus())),
                () -> badges.add(badge("Not validated", "badge-warning")));
        return badges;
    }

    private Label validationBadge(Report.ValidationStatus validation) {
        return badge("Validation " + label(validation.toString()).toLowerCase(Locale.ROOT),
                validation == Report.ValidationStatus.PASSED ? "badge-success"
                        : validation == Report.ValidationStatus.FAILED ? "badge-danger" : "badge-warning");
    }

    private static Label badge(String text, String variant) {
        Label badge = new Label(text);
        badge.getStyleClass().add("badge");
        if (!variant.isEmpty()) badge.getStyleClass().add(variant);
        return badge;
    }

    private static Button styled(Button button, String styleClass) {
        button.getStyleClass().add(styleClass);
        return button;
    }

    private static VBox stepView(String title, String description, Node... body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("h2");
        Label text = muted(description);
        VBox view = new VBox(16);
        view.getChildren().add(new VBox(4, heading, text));
        view.getChildren().addAll(body);
        view.getStyleClass().add("step-view");
        return view;
    }

    /** Scrolls only when content is taller than the window; otherwise growing children fill the height. */
    private static ScrollPane scrollable(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.viewportBoundsProperty().addListener((o, a, bounds) -> content.setMinHeight(bounds.getHeight()));
        return scroll;
    }

    private static TitledPane collapsible(String title, Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private static HBox segmented(ToggleButton... buttons) {
        HBox group = new HBox(2, buttons);
        group.getStyleClass().add("segmented");
        group.setMaxWidth(Region.USE_PREF_SIZE);
        group.setAlignment(Pos.CENTER_LEFT);
        return group;
    }

    /** A segmented control always has one selected option. */
    private static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((o, previous, selected) -> {
            if (selected == null && previous != null) previous.setSelected(true);
        });
    }

    private static void showWhen(Node node, ToggleButton toggle) {
        node.visibleProperty().bind(toggle.selectedProperty());
        node.managedProperty().bind(toggle.selectedProperty());
    }

    private static HBox row(Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        for (Node node : nodes) if (node instanceof TextField) HBox.setHgrow(node, Priority.ALWAYS);
        return row;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    private static void readOnly(TextArea area, String prompt) {
        area.setEditable(false);
        area.setWrapText(true);
        area.setPromptText(prompt);
    }

    private static Button browseButton(Stage stage, TextField field, String title) {
        Button browse = new Button("Browse...");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(title);
            File selected = chooser.showDialog(stage);
            if (selected != null) field.setText(selected.toPath().toString());
        });
        return browse;
    }

    private void addColumn(String title, Function<Asset, String> value, double width, boolean visible) {
        TableColumn<Asset, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setVisible(visible);
        inventory.getColumns().add(column);
    }

    private void suggestionColumn(String title, Function<AssetManager.Suggestion, String> value, double width, boolean visible) {
        TableColumn<AssetManager.Suggestion, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setVisible(visible);
        suggestions.getColumns().add(column);
    }

    private void planColumn(String title, Function<MigrationCommand, String> value, double width) {
        TableColumn<MigrationCommand, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        planTable.getColumns().add(column);
    }

    // ---------------------------------------------------------------- inputs and preferences

    private Path workspace() {
        return requiredPath(state, "Workspace folder (Project step)");
    }

    private Path logDirectory() {
        return workspace().resolve("logs");
    }

    private UUID projectUuid() {
        String text = projectId.getText().trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Import or open a project first.");
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Project ID is not valid: " + text);
        }
    }

    private static Path requiredPath(TextField field, String label) {
        String value = field.getText().trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return Path.of(value).toAbsolutePath();
    }

    private String preference(String key, String fallback) {
        try {
            return preferences.get(key, fallback);
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private void savePreference(String key, String value) {
        try {
            preferences.put(key, value);
        } catch (RuntimeException unavailable) {
            // Remembering the last workspace is a convenience only.
        }
    }

    @Override
    public void stop() {
        runner.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
