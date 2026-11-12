package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    // Step 3: target
    private final ObjectProperty<AssetManager.TargetProfile> selectedTarget = new SimpleObjectProperty<>();
    private final ToggleGroup targetGroup = new ToggleGroup();
    private final FlowPane targetCards = new FlowPane(10, 10);
    private final CheckBox compatibilityAi = new CheckBox("Explain issues with AI");
    private final HBox compatibilityBadges = new HBox(6);
    private final TextArea compatibilityResults = new TextArea();
    private final CompatibilityGeneration compatibilityGeneration = new CompatibilityGeneration();

    // Step 4: plan
    private final TextField outputRoot = new TextField();
    private final CheckBox planAi = new CheckBox("Ask AI to propose steps");
    private final Button createPlanButton = styled(new Button("Create plan"), "primary");
    private final Button approvePlanButton = new Button("Approve this plan");
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
    private final VBox agentPane = new VBox(10);
    private final TextField agentRequest = new TextField();
    private final TextArea agentTranscript = new TextArea();
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

        /** AI-assisted draft (F06): proposals become findings; the commands and approval rules are unchanged. */
        PlanContext create(AssetManager manager, io.github.aiassetmigrate.ai.LLMClient assistant) {
            MigrationPlan plan = manager.createPlan(project, target, output, assistant);
            if (!project.equals(plan.projectId()) || !target.id().equals(plan.targetProfile())
                    || !target.version().equals(plan.targetProfileVersion()) || !output.equals(plan.outputRoot())
                    || plan.status() != PlanStatus.DRAFT) {
                throw new IllegalStateException("Created plan does not match the requested draft context.");
            }
            InventorySnapshot inventory = manager.loadInventory(plan.projectId(), plan.inventoryRevision());
            return PlanContext.fromInventory(this, plan, inventory);
        }
    }

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
            return manager.review(project, result.inventoryRevision(), List.of(change));
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
        loadedBox.getChildren().addAll(loadedHeader, inventory, findingsPane);
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
        suggestionColumn("Source", suggestion -> label(suggestion.source().toString()), 70, true);
        suggestionColumn("Asset ID", suggestion -> suggestion.assetId().toString(), 260, false);
        suggestions.setTableMenuButtonVisible(true);
        suggestions.setPlaceholder(new Label("Pick a check above to see its suggestions."));
        suggestions.setPrefHeight(300);
        suggestions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
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
        accept.setOnAction(event -> decide(ReviewedChange.Decision.ACCEPTED));
        reject.setOnAction(event -> decide(ReviewedChange.Decision.REJECTED));
        for (Button button : List.of(accept, reject, edit)) {
            button.disableProperty().bind(suggestions.getSelectionModel().selectedItemProperty().isNull());
        }
        analysisRevision.getStyleClass().add("muted");
        HBox decisionBar = new HBox(8, analysisRevision, spacer(), accept, reject, edit);
        decisionBar.setAlignment(Pos.CENTER_LEFT);

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
                        + "an ACKNOWLEDGMENT lets an FBX through, a CONVERSION changes a format (for example glb or jpg)."), form));
        edit.setOnAction(event -> manualReviewPane.setExpanded(true));

        nameStyle.getItems().setAll(AssetManager.NameStyle.values());
        nameStyle.setValue(DEFAULT_ORGANIZATION_RULES.nameStyle());
        folderStyle.getItems().setAll(AssetManager.FolderStyle.values());
        folderStyle.setValue(DEFAULT_ORGANIZATION_RULES.folderStyle());
        Button generateOrganization = new Button("Suggest with these rules");
        generateOrganization.setOnAction(event -> {
            var rules = new AssetManager.OrganizationRules(nameStyle.getValue(), folderStyle.getValue());
            analyze(organizationAnalysis(rules));
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
                "Optional. Run a check, then accept or reject what it suggests. Accepted decisions are used when you "
                        + "create the plan; nothing is copied or changed yet.",
                checks, suggestions, decisionBar, analysisFindingsPane, manualReviewPane, rulesPane, savedPane, previewPane);
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
        VBox view = stepView("Choose a target engine",
                "Pick the engine these assets are going to, then check them against it. The check lists likely "
                        + "problems before anything is copied; it is not a full validation.",
                fieldLabel("Engine"), targetCards, actions, compatibilityBadges, compatibilityResults);
        VBox.setVgrow(compatibilityResults, Priority.ALWAYS);
        return scrollable(view);
    }

    private Node buildPlanStep(Stage stage) {
        outputRoot.setPromptText("An existing, preferably empty folder outside the asset folder");
        planAi.setTooltip(new Tooltip("The AI proposes steps; each is checked and recorded as a note. "
                + "It never changes the plan or approves it."));
        createPlanButton.setOnAction(event -> createPlan());
        approvePlanButton.setOnAction(event -> approvePlan());
        HBox actions = new HBox(10, createPlanButton, approvePlanButton, planAi);
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
                actions, planBadges, planGuidance, planTable, details);
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
        readOnly(validationResults, "The run result and its validation report appear here.");

        validationRun.setPromptText("Run ID (filled in after a migration)");
        Button validate = new Button("Validate again");
        validate.setTooltip(new Tooltip("Re-check the outputs of this run, for example after moving or editing them"));
        validate.setOnAction(event -> validateRun());
        Button history = new Button("Show report history");
        history.setOnAction(event -> loadHistory());
        TitledPane historyPane = collapsible("Re-validate or view earlier reports", new VBox(8,
                row(validationRun, validate, history),
                muted("Validation checks each output exists, is unchanged, decodes (PNG/JPEG/GLB) and that its references "
                        + "resolve. Checks that cannot be done, such as FBX contents, are reported as not checked, never as passed.")));

        VBox view = stepView("Run the migration",
                "Copies the approved plan into the output folder, then checks every output file and saves a report. "
                        + "Your source files are never changed.",
                migrateReady, actions, migrateBadges, validationResults, historyPane);
        VBox.setVgrow(validationResults, Priority.ALWAYS);
        return scrollable(view);
    }

    private void buildTools() {
        Label agentNote = muted("Ask in plain language, for example \"check unity 6000.0 compatibility\" or \"create a plan for "
                + "godot 4.3 into C:\\out\". It can show, check, preview, draft plans and validate. It never approves, "
                + "migrates or saves decisions. Needs a connected AI account.");
        agentRequest.setPromptText("What should the agent do?");
        Button send = styled(new Button("Send"), "primary");
        send.setOnAction(event -> sendAgentRequest());
        agentRequest.setOnAction(event -> sendAgentRequest());
        readOnly(agentTranscript, "Requests and results appear here.");
        agentPane.getChildren().addAll(agentNote, row(agentRequest, send), agentTranscript);
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
        aiPane.getChildren().addAll(aiStatus, new HBox(8, connectAi, disconnectAi),
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
        approvePlanButton.getStyleClass().remove("primary");
        createPlanButton.getStyleClass().remove("primary");
        if (draft) approvePlanButton.getStyleClass().add("primary");
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
                            : rescan ? manager.rescan(id, sourcePath).snapshot()
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
                    : "Some files were skipped (see Scan findings). A plan needs a complete scan: remove or fix those files, then Rescan.");
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
        inventory.getItems().setAll(project.assets());
        findings.setText(formatFindings(snapshot));
        findingsPane.setText("Scan findings (" + snapshot.findings().size() + ")");
        if (!complete) findingsPane.setExpanded(true);
    }

    // ---------------------------------------------------------------- step 2: review

    private void analyze(AssetManager.AnalysisKind kind) {
        analyze(useAi.isSelected() ? assistedAnalysis(kind) : (manager, id) -> manager.analyze(id, kind));
    }

    /** Runs on the worker thread: loads the saved account there, never on the JavaFX thread. */
    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> assistedAnalysis(AssetManager.AnalysisKind kind) {
        return (manager, id) -> manager.analyze(id, kind, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable());
    }

    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> organizationAnalysis(AssetManager.OrganizationRules rules) {
        return (manager, id) -> manager.analyzeOrganization(id, rules);
    }

    private void analyze(BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> operation) {
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID id = projectUuid();
            if (runner.submitValue(statePath, logPath, manager -> operation.apply(manager, id), result -> {
                if (result.value() != null) {
                    analysisContext = new AnalysisContext(id, statePath, logPath, result.value());
                    analysisRevision.setText(result.value().suggestions().size() + " suggestion(s) for inventory revision "
                            + result.value().inventoryRevision());
                    suggestions.getItems().setAll(result.value().suggestions());
                    analysisFindings.setText(formatAnalysisFindings(result.value().findings()));
                    analysisFindingsPane.setText("Check findings (" + result.value().findings().size() + ")");
                }
                busy(false);
                status.setText(result.failure() == null ? "Select a suggestion, then Accept or Reject it. Undecided suggestions are simply ignored."
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

    /** One-click decision on the selected suggestion, using its proposed value unchanged. */
    private void decide(ReviewedChange.Decision choice) {
        AssetManager.Suggestion selected = suggestions.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a suggestion first.");
            return;
        }
        try {
            ReviewedChange change = makeReview(selected, choice, selected.proposedValue(), selected.reference().orElse(""));
            saveReview(change, () -> suggestions.getItems().remove(selected));
        } catch (IllegalArgumentException failure) {
            status.setText("This suggestion needs editing first (" + failure.getMessage() + "). Use Edit...");
            manualReviewPane.setExpanded(true);
        }
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
        try {
            Path statePath = workspace();
            Path logPath = logDirectory();
            UUID id = projectUuid();
            AnalysisContext context = requireAnalysisContext(id, statePath, logPath);
            String file = assetPaths.getOrDefault(change.assetId(), change.assetId().toString());
            if (runner.submitValue(statePath, logPath, manager -> context.review(manager, change), result -> {
                if (result.value() != null) onSaved.run();
                busy(false);
                status.setText(result.failure() == null ? label(change.decision().toString()) + ": " + label(change.kind().toString())
                        + " for " + file + ". Saved."
                        : failureText(result.failure(), null) + (result.value() != null
                        ? "\nThe review call returned, but cleanup failed; it may have been saved." : "\nThe review may have been saved; verify before retrying."));
            })) {
                clearDisplayedResults();
                clearCompatibility();
                clearPlan();
                busy(true);
                status.setText("Saving decision...");
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
        analysisRevision.setText("No check run yet.");
        suggestions.getItems().clear();
        analysisFindings.clear();
        analysisFindingsPane.setText("Check findings");
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

    private static ReviewedChange.Kind suggestionKind(AssetManager.AnalysisKind kind) {
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
                    compatibilityBadges.getChildren().setAll(severityBadges(result.value().findings()));
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
        compatibilityBadges.getChildren().clear();
    }

    // ---------------------------------------------------------------- step 4: plan

    private void clearPlan() {
        planGeneration.invalidate();
        planContext = null;
        planDetails.clear();
        planTable.getItems().clear();
        planBadges.getChildren().clear();
        planGuidance.setVisible(false);
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
        clearPlan();
        try {
            PlanRequest request = planRequest();
            long generation = planGeneration.current();
            boolean ai = planAi.isSelected();
            // The saved account is loaded on the worker thread, never the JavaFX thread.
            if (runner.submitValue(request.state(), request.logs(), manager -> ai
                    ? request.create(manager, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable())
                    : request.create(manager), result -> {
                boolean current = planGeneration.isCurrent(generation);
                if (current && result.failure() == null && result.value() != null) showPlan(result.value());
                busy(false);
                if (!current) return;
                if (result.failure() == null && result.value() != null) {
                    long blockers = blocking(planContext.plan());
                    status.setText(!planContext.sourceErrors().isEmpty() ? "Source files could not be verified; approval is blocked."
                            : blockers > 0 ? blockers + " issue(s) block this plan. Fix them, then create a new plan."
                            : "Plan created. Check the files it will write, then approve it.");
                } else {
                    status.setText(failureText(result.failure(), null) + "\nA draft may have been created; nothing was approved or migrated.");
                }
            })) {
                busy(true);
                status.setText("Creating the plan...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
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
                busy(true);
                status.setText("Copying files, then validating the outputs...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
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
                if (result.failure() == null && result.value() != null) validationResults.setText(formatHistory(project, result.value()));
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

    private void refreshAiStatus() {
        Thread worker = new Thread(() -> {
            String text;
            boolean connected = false;
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var account = store.load();
                connected = account.isPresent();
                text = account.map(value -> "Connected as " + value.credentials().email().orElse(value.credentials().subject())
                        + ", model " + value.model().orElse("<none selected>") + ".")
                        .orElse("Not connected. AI features are optional.");
            } catch (Exception e) {
                text = "Unavailable (" + e.getMessage() + ").";
            }
            showAi(text, connected);
        }, "ai-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void showAi(String text, boolean connected) {
        Platform.runLater(() -> {
            aiStatus.setText(text);
            aiTool.setText(connected ? "AI: connected" : "AI: off");
        });
    }

    private void connectAi() {
        aiStatus.setText("Complete sign-in in your browser (up to 5 minutes)...");
        Thread worker = new Thread(() -> {
            String text;
            boolean connected = false;
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
                store.save(new io.github.aiassetmigrate.auth.AccountStore.Account(credentials, model));
                connected = true;
                text = "Connected as " + credentials.email().orElse(credentials.subject())
                        + ", model " + model.orElse("<none available>") + ". Migration approval is unchanged.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "Sign-in interrupted.";
            } catch (Exception e) {
                text = "Sign-in failed (" + e.getMessage() + ").";
            }
            showAi(text, connected);
        }, "ai-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnectAi() {
        Thread worker = new Thread(() -> {
            String text;
            boolean connected = false;
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var account = store.load();
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "Disconnect interrupted.";
                connected = true;
            } catch (Exception e) {
                text = "Disconnect failed (" + e.getMessage() + ").";
                connected = true;
            }
            showAi(text, connected);
        }, "ai-disconnect");
        worker.setDaemon(true);
        worker.start();
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
            case "PARTIAL_INVENTORY" -> "Some files were skipped during the scan. Remove or fix them, then Rescan in the Project step.";
            case "UNRESOLVED_REFERENCE" -> "A model or material points to a file that cannot be found. Accept a suggested "
                    + "correction in Review (Relationships or Materials), or exclude the file.";
            case "INSPECTION_UNSUPPORTED" -> "FBX files cannot be inspected. In Review, add an ACKNOWLEDGMENT (to include it "
                    + "anyway) or an EXCLUSION, using Edit or add a decision.";
            case "CONVERSION_CHECK_FAILED" -> "A conversion cannot be done as reviewed. Change or reject the CONVERSION "
                    + "decision in Review (for example add background=#FFFFFF for a transparent texture).";
            case "CONVERSION_UNSUPPORTED", "INVALID_CONVERSION" -> "This conversion is not available for the file. "
                    + "Reject the CONVERSION decision in Review, or choose a supported format.";
            case "DESTINATION_CONFLICT", "CASE_COLLISION" -> "Two files would be written to the same place (names that "
                    + "differ only in letter case count as the same). Rename or move one of them in Review.";
            case "INVALID_DESTINATION" -> "A reviewed name or folder is not a valid path. Change it in Review.";
            case "INVALID_CORRECTION", "CONFLICTING_CORRECTIONS" -> "A reference correction points to the wrong kind of "
                    + "file, or two corrections disagree. Reject one of them in Review.";
            case "MODEL_CONVERSION_LAYOUT" -> "To convert an OBJ, its material file must end up in the same output folder. "
                    + "Adjust the folders in Review.";
            default -> null;
        });
    }

    /** The blocking findings of a plan, grouped by code with a next step for each. */
    static String blockerGuidance(MigrationPlan plan, List<String> sourceErrors) {
        StringBuilder text = new StringBuilder("This plan cannot be approved yet. Fix the following, then create a new plan:\n");
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
