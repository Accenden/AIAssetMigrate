package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.analysis.OrganizationPreview;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.ReviewedChange;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.MigrationRun;
import io.github.aiassetmigrate.domain.PlanStatus;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class AssetMigrateApplication extends Application {
    static final AssetManager.OrganizationRules DEFAULT_ORGANIZATION_RULES = new AssetManager.OrganizationRules(
            AssetManager.NameStyle.PRESERVE, AssetManager.FolderStyle.PRESERVE);
    private final ImportRunner runner = new ImportRunner(Platform::runLater);
    private final CheckBox useAi = new CheckBox(
            "Include AI suggestions (sends relative paths, types and reference text, never file contents, to OpenAI)");
    private final Label aiStatus = new Label("AI account: checking...");
    private final TextField source = new TextField();
    private final TextField state = new TextField();
    private final TextField logs = new TextField();
    private final TextField projectId = new TextField();
    private final TextField revision = new TextField();
    private final Label status = new Label("Choose a source folder and explicit state and log locations.");
    private final Label summary = new Label("No inventory loaded.");
    private final TableView<Asset> inventory = new TableView<>();
    private final TextArea findings = new TextArea();
    private final GridPane inputs = new GridPane();
    private final HBox actions = new HBox(10);
    private final ProgressIndicator progress = new ProgressIndicator();
    private final VBox analysisPane = new VBox(8);
    private final VBox compatibilityPane = new VBox(8);
    private final ComboBox<AssetManager.TargetProfile> compatibilityTarget = new ComboBox<>();
    private final TextArea compatibilityResults = new TextArea();
    private final CompatibilityGeneration compatibilityGeneration = new CompatibilityGeneration();
    private final TableView<AssetManager.Suggestion> suggestions = new TableView<>();
    private final Label analysisRevision = new Label("No analysis loaded.");
    private final TextArea analysisFindings = new TextArea();
    private final TextArea savedReviews = new TextArea();
    private final TextArea organizationPreview = new TextArea();
    private final TextField reviewAsset = new TextField();
    private final ComboBox<ReviewedChange.Kind> reviewKind = new ComboBox<>();
    private final TextField reviewValue = new TextField();
    private final TextField reviewReference = new TextField();
    private final ComboBox<ReviewedChange.Decision> decision = new ComboBox<>();
    private final ComboBox<AssetManager.NameStyle> nameStyle = new ComboBox<>();
    private final ComboBox<AssetManager.FolderStyle> folderStyle = new ComboBox<>();
    private AnalysisContext analysisContext;
    private final VBox planPane = new VBox(8);
    private final TextField outputRoot = new TextField();
    private final TextArea planDetails = new TextArea();
    private final CompatibilityGeneration planGeneration = new CompatibilityGeneration();
    private PlanContext planContext;

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

        MigrationRun migrate(AssetManager manager) {
            if (plan.status() != PlanStatus.APPROVED) throw new IllegalStateException("Explicitly approve the displayed plan first.");
            if (!sourcesVerified()) {
                throw new IllegalStateException("Source paths could not be verified; migration is blocked.");
            }
            if (!plan.equals(manager.loadPlan(plan.id()))) {
                throw new IllegalStateException("Stored plan changed; create and review a new draft.");
            }
            return manager.migrate(plan.id());
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
            var result = manager.assessCompatibility(project, target);
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

    @Override
    public void start(Stage stage) {
        Label title = new Label("Import Project");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label note = new Label("State and log locations must be separate from each other and the source folder. Source assets are not modified.");
        note.setWrapText(true);

        inputs.setHgap(10);
        inputs.setVgap(8);
        folderRow(stage, 0, "Source folder", source);
        folderRow(stage, 1, "State directory", state);
        folderRow(stage, 2, "Log directory", logs);
        projectId.setPromptText("Project ID for loading or rescanning a project");
        revision.setPromptText("Latest if blank");
        inputs.addRow(3, new Label("Project ID"), projectId);
        inputs.addRow(4, new Label("Revision"), revision);
        GridPane.setHgrow(projectId, Priority.ALWAYS);
        GridPane.setHgrow(revision, Priority.ALWAYS);

        Button importButton = new Button("Import Project");
        importButton.setDefaultButton(true);
        importButton.setOnAction(event -> begin(false));
        Button loadButton = new Button("Load Saved Inventory");
        loadButton.setOnAction(event -> begin(true));
        Button rescanButton = new Button("Rescan Project");
        rescanButton.setOnAction(event -> begin(false, true));
        actions.getChildren().addAll(importButton, rescanButton, loadButton);
        progress.setMaxSize(20, 20);
        progress.setVisible(false);
        status.setWrapText(true);
        HBox statusRow = new HBox(10, progress, status);
        HBox.setHgrow(status, Priority.ALWAYS);

        addColumn("Path", asset -> io.github.aiassetmigrate.domain.PortablePath.of(asset.relativePath()), 260);
        addColumn("Asset ID", asset -> asset.id().toString(), 270);
        addColumn("Type", asset -> asset.type().toString(), 90);
        addColumn("Format", asset -> asset.metadata().getOrDefault(Asset.FORMAT, ""), 75);
        addColumn("Bytes", asset -> asset.metadata().getOrDefault(Asset.SIZE_BYTES, ""), 90);
        addColumn("Modified", asset -> asset.metadata().getOrDefault(Asset.MODIFIED_AT, ""), 180);
        addColumn("Inspection", asset -> asset.metadata().getOrDefault(Asset.INSPECTION_STATUS, ""), 130);
        addColumn("SHA-256", Asset::fingerprint, 300);
        inventory.setPlaceholder(new Label("No assets in the displayed inventory."));
        findings.setEditable(false);
        findings.setWrapText(true);
        findings.setPromptText("Scan findings and incomplete coverage appear here.");
        VBox inventoryPane = new VBox(6, new Label("Inventory"), inventory);
        VBox.setVgrow(inventory, Priority.ALWAYS);
        VBox findingsPane = new VBox(6, new Label("Findings and coverage"), findings);
        VBox.setVgrow(findings, Priority.ALWAYS);
        SplitPane results = new SplitPane(inventoryPane, findingsPane);
        results.setDividerPositions(0.65);
        VBox top = new VBox(10, title, note, inputs, actions, statusRow, summary);
        setupAnalysis();
        setupCompatibility();
        setupPlan();
        Tab inventoryTab = new Tab("Inventory", results);
        ScrollPane analysisScroll = new ScrollPane(analysisPane);
        analysisScroll.setFitToWidth(true);
        Tab analysisTab = new Tab("Analysis and Review", analysisScroll);
        Tab compatibilityTab = new Tab("Target Compatibility", compatibilityPane);
        Tab planTab = new Tab("Copy Migration Plan", planPane);
        inventoryTab.setClosable(false);
        analysisTab.setClosable(false);
        compatibilityTab.setClosable(false);
        planTab.setClosable(false);
        TabPane tabs = new TabPane(inventoryTab, analysisTab, compatibilityTab, planTab);
        BorderPane root = new BorderPane(tabs);
        root.setTop(top);
        BorderPane.setMargin(tabs, new Insets(12, 0, 0, 0));
        root.setPadding(new Insets(20));
        stage.setTitle("AIAssetMigrate");
        stage.setScene(new Scene(root, 1000, 720));
        stage.setMinWidth(600);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(event -> {
            if (runner.isBusy()) {
                event.consume();
                status.setText("Wait for the current operation to finish before closing.");
            }
        });
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(() -> {
                System.out.println("JavaFX import window launched successfully.");
                stage.close();
                Platform.exit();
            });
        }
    }

    private void setupPlan() {
        Label note = new Label("Copy-only draft: review all commands and findings. Creating a draft does not approve or execute it. Select a supported target in Target Compatibility.");
        note.setWrapText(true);
        outputRoot.setPromptText("Explicit output root directory");
        Button create = new Button("Create Copy-Only Draft");
        Button approve = new Button("Approve Displayed Plan Version");
        Button migrate = new Button("Run Approved Copy Migration");
        create.setOnAction(event -> createPlan());
        approve.setOnAction(event -> approvePlan());
        migrate.setOnAction(event -> migratePlan());
        planDetails.setEditable(false);
        planDetails.setWrapText(false);
        planDetails.setPromptText("No plan displayed.");
        planPane.getChildren().addAll(note, new Label("Output root"), outputRoot,
                new FlowPane(8, 8, create, approve, migrate), new Label("Full plan and blockers"), planDetails);
        VBox.setVgrow(planDetails, Priority.ALWAYS);
        projectId.textProperty().addListener((o, a, b) -> clearPlan());
        state.textProperty().addListener((o, a, b) -> clearPlan());
        logs.textProperty().addListener((o, a, b) -> clearPlan());
        outputRoot.textProperty().addListener((o, a, b) -> clearPlan());
        source.textProperty().addListener((o, a, b) -> clearPlan());
        revision.textProperty().addListener((o, a, b) -> clearPlan());
        compatibilityTarget.valueProperty().addListener((o, a, b) -> clearPlan());
    }

    private void clearPlan() {
        planGeneration.invalidate();
        planContext = null;
        planDetails.clear();
    }

    private PlanRequest planRequest() {
        AssetManager.TargetProfile target = compatibilityTarget.getValue();
        if (target == null) throw new IllegalArgumentException("Select a supported target in Target Compatibility.");
        return new PlanRequest(UUID.fromString(projectId.getText().trim()), requiredPath(state, "State directory"),
                requiredPath(logs, "Log directory"), requiredPath(outputRoot, "Output root").normalize(), target);
    }

    private PlanContext requirePlan() {
        if (planContext == null || !planContext.request().equals(planRequest())) {
            throw new IllegalArgumentException("Inputs changed or no plan is displayed; create and review a draft.");
        }
        return planContext;
    }

    private void createPlan() {
        clearPlan();
        try {
            PlanRequest request = planRequest();
            long generation = planGeneration.current();
            if (runner.submitValue(request.state(), request.logs(), request::create, result -> {
                busy(false);
                if (!planGeneration.isCurrent(generation)) return;
                if (result.failure() == null && result.value() != null) {
                    planContext = result.value();
                    planDetails.setText(formatPlan(planContext));
                    status.setText(planContext.sourceErrors().isEmpty()
                            ? "Draft created only. Review it before explicit approval."
                            : "Source verification failed; approval is blocked. Review the errors in the plan.");
                } else status.setText(failureText(result.failure(), null) + "\nA draft may have been created; no approval or migration was requested.");
            })) {
                busy(true);
                status.setText("Creating copy-only draft...");
            }
        } catch (IllegalArgumentException failure) { status.setText("Invalid input: " + failure.getMessage()); }
    }

    private void approvePlan() {
        try {
            PlanContext context = requirePlan();
            if (!context.sourcesVerified()) throw new IllegalArgumentException("Source paths could not be verified; approval is blocked.");
            long generation = planGeneration.current();
            if (runner.submitValue(context.request().state(), context.request().logs(), context::approve, result -> {
                busy(false);
                if (!planGeneration.isCurrent(generation)) return;
                if (result.failure() == null && result.value() != null) {
                    planContext = new PlanContext(context.request(), result.value(), context.sources(), context.sourceErrors());
                    planDetails.setText(formatPlan(planContext));
                    status.setText("Displayed plan version approved. Migration has not been run.");
                } else {
                    clearPlan();
                    status.setText(failureText(result.failure(), null) + "\nApproval may have succeeded; create and review a new draft before proceeding.");
                }
            })) { busy(true); status.setText("Approving the displayed plan version..."); }
        } catch (IllegalArgumentException failure) { status.setText("Invalid input: " + failure.getMessage()); }
    }

    private void migratePlan() {
        try {
            PlanContext context = requirePlan();
            if (context.plan().status() != PlanStatus.APPROVED) throw new IllegalArgumentException("Explicitly approve the displayed plan first.");
            long generation = planGeneration.current();
            if (runner.submitValue(context.request().state(), context.request().logs(), context::migrate, result -> {
                busy(false);
                if (!planGeneration.isCurrent(generation)) return;
                clearPlan();
                status.setText(result.failure() == null ? formatRun(result.value())
                        : failureText(result.failure(), null) + "\nMigration may have started; inspect output and run history before retrying.");
            })) { busy(true); status.setText("Running approved copy migration..."); }
        } catch (IllegalArgumentException failure) { status.setText("Invalid input: " + failure.getMessage()); }
    }

    static String formatRun(MigrationRun run) {
        StringBuilder text = new StringBuilder("Migration run ").append(run.id()).append(" status: ").append(run.status());
        run.message().ifPresent(message -> text.append("\n").append(message));
        new java.util.TreeMap<>(run.stepStatuses()).forEach((id, stepStatus) -> {
            text.append("\n").append(id).append(": ").append(stepStatus);
            String detail = run.stepDetails().get(id);
            if (detail != null && !detail.isBlank()) text.append(" - ").append(detail);
        });
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

    private void setupCompatibility() {
        Label note = new Label("Checks the latest saved inventory against a target. This is not full validation or approval. Results may become stale if the project changes elsewhere.");
        note.setWrapText(true);
        compatibilityTarget.setPromptText("Load and select a target");
        compatibilityTarget.setConverter(new StringConverter<>() {
            @Override public String toString(AssetManager.TargetProfile target) {
                return target == null ? "" : target.id() + " / " + target.version();
            }
            @Override public AssetManager.TargetProfile fromString(String value) { throw new UnsupportedOperationException(); }
        });
        Button loadTargets = new Button("Load Supported Targets");
        loadTargets.setOnAction(event -> loadCompatibilityTargets());
        Button assess = new Button("Check Target Compatibility");
        assess.setOnAction(event -> assessCompatibility());
        compatibilityResults.setEditable(false);
        compatibilityResults.setWrapText(true);
        compatibilityResults.setPromptText("Compatibility findings appear here.");
        compatibilityPane.getChildren().addAll(note, new FlowPane(8, 8, loadTargets, compatibilityTarget, assess), compatibilityResults);
        VBox.setVgrow(compatibilityResults, Priority.ALWAYS);
        projectId.textProperty().addListener((observable, oldValue, newValue) -> clearCompatibility());
        state.textProperty().addListener((observable, oldValue, newValue) -> clearCompatibility());
        logs.textProperty().addListener((observable, oldValue, newValue) -> clearCompatibility());
        compatibilityTarget.valueProperty().addListener((observable, oldValue, newValue) -> clearCompatibility());
    }

    private void loadCompatibilityTargets() {
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            clearCompatibility();
            long generation = compatibilityGeneration.current();
            if (runner.submitValue(statePath, logPath, AssetManager::supportedTargets, result -> {
                busy(false);
                if (!compatibilityGeneration.isCurrent(generation)) {
                    status.setText("Inputs changed; supported target results discarded.");
                    return;
                }
                if (result.value() != null) compatibilityTarget.getItems().setAll(result.value());
                status.setText(result.failure() == null ? "Supported targets loaded."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nTargets were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Loading supported targets...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void assessCompatibility() {
        clearCompatibility();
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            AssetManager.TargetProfile target = compatibilityTarget.getValue();
            if (target == null) throw new IllegalArgumentException("Choose a supported target.");
            var request = new CompatibilityRequest(id, target);
            long generation = compatibilityGeneration.current();
            if (runner.submitValue(statePath, logPath, request::assess, result -> {
                busy(false);
                if (!compatibilityGeneration.isCurrent(generation)) {
                    status.setText("Inputs changed; compatibility results discarded.");
                    return;
                }
                if (result.value() != null) compatibilityResults.setText(formatCompatibility(result.value()));
                status.setText(result.failure() == null ? "Target compatibility check complete; not full validation or approval."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nCompatibility results were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Checking target compatibility...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void clearCompatibility() {
        compatibilityGeneration.invalidate();
        compatibilityResults.clear();
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

    private void setupAnalysis() {
        Label explanation = new Label("Analysis uses the latest saved inventory, not the revision displayed in the Inventory tab. Suggestions are not reviews.");
        explanation.setWrapText(true);
        Button categories = new Button("Analyze Categories");
        Button relationships = new Button("Analyze Relationships");
        Button materials = new Button("Analyze Materials");
        Button names = new Button("Analyze Names");
        Button folders = new Button("Analyze Folders");
        categories.setOnAction(event -> analyze(AssetManager.AnalysisKind.CATEGORIES));
        relationships.setOnAction(event -> analyze(AssetManager.AnalysisKind.RELATIONSHIPS));
        materials.setOnAction(event -> analyze(AssetManager.AnalysisKind.MATERIALS));
        names.setOnAction(event -> analyze(AssetManager.AnalysisKind.NAMES));
        folders.setOnAction(event -> analyze(AssetManager.AnalysisKind.FOLDERS));
        FlowPane analyzeActions = new FlowPane(8, 8, categories, relationships, materials, names, folders);
        Button connectAi = new Button("Connect AI Account");
        connectAi.setOnAction(event -> connectAi());
        Button disconnectAi = new Button("Disconnect AI Account");
        disconnectAi.setOnAction(event -> disconnectAi());
        aiStatus.setWrapText(true);
        FlowPane aiControls = new FlowPane(8, 8, useAi, connectAi, disconnectAi);
        refreshAiStatus();
        nameStyle.getItems().setAll(AssetManager.NameStyle.values());
        nameStyle.setValue(DEFAULT_ORGANIZATION_RULES.nameStyle());
        folderStyle.getItems().setAll(AssetManager.FolderStyle.values());
        folderStyle.setValue(DEFAULT_ORGANIZATION_RULES.folderStyle());
        Button generateOrganization = new Button("Generate Organization Suggestions");
        generateOrganization.setOnAction(event -> {
            var rules = new AssetManager.OrganizationRules(nameStyle.getValue(), folderStyle.getValue());
            analyze(organizationAnalysis(rules));
        });
        FlowPane organizationRules = new FlowPane(8, 8, new Label("Name style"), nameStyle,
                new Label("Folder style"), folderStyle, generateOrganization);
        Label rulesNote = new Label("Generates name and folder suggestions only. Suggestions are not accepted; no asset files are written.");
        rulesNote.setWrapText(true);
        suggestionColumn("Asset ID", suggestion -> suggestion.assetId().toString(), 260);
        suggestionColumn("Kind", suggestion -> suggestion.kind().toString(), 120);
        suggestionColumn("Proposed value", AssetManager.Suggestion::proposedValue, 360);
        suggestionColumn("Source", suggestion -> suggestion.source().toString(), 80);
        suggestions.setPlaceholder(new Label("Run analysis to display suggestions."));
        suggestions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected == null) return;
            reviewAsset.setText(selected.assetId().toString());
            reviewKind.setValue(suggestionKind(selected.kind()));
            reviewValue.setText(selected.proposedValue());
            reviewReference.setText(selected.reference().orElse(""));
            decision.setValue(null);
        });
        analysisFindings.setEditable(false);
        analysisFindings.setWrapText(true);
        analysisFindings.setPromptText("Analysis findings appear here.");
        analysisFindings.setPrefRowCount(4);
        reviewAsset.setPromptText("Asset UUID to review");
        reviewKind.getItems().setAll(ReviewedChange.Kind.CATEGORY, ReviewedChange.Kind.RELATIONSHIP,
                ReviewedChange.Kind.MATERIAL_MAPPING, ReviewedChange.Kind.EXCLUSION,
                ReviewedChange.Kind.NAME, ReviewedChange.Kind.FOLDER, ReviewedChange.Kind.ACKNOWLEDGMENT);
        reviewKind.setPromptText("Choose review kind");
        reviewValue.setPromptText("Category, target UUID, exclusion/acknowledgment reason, filename, or folder (blank for root)");
        reviewReference.setPromptText("Original reference (required for material mapping; optional for relationship)");
        decision.getItems().setAll(ReviewedChange.Decision.ACCEPTED, ReviewedChange.Decision.REJECTED);
        decision.setPromptText("Choose decision");
        Button submitReview = new Button("Save Explicit Review");
        submitReview.setOnAction(event -> review());
        Button manualReview = new Button("New Manual Review");
        manualReview.setOnAction(event -> clearReview());
        Button loadReviews = new Button("Load Saved Reviews");
        loadReviews.setOnAction(event -> loadReviews());
        savedReviews.setEditable(false);
        savedReviews.setWrapText(true);
        savedReviews.setPrefRowCount(5);
        savedReviews.setPromptText("Load saved reviews for the analyzed inventory revision.");
        projectId.textProperty().addListener((observable, oldValue, newValue) -> clearDisplayedResults());
        state.textProperty().addListener((observable, oldValue, newValue) -> clearDisplayedResults());
        logs.textProperty().addListener((observable, oldValue, newValue) -> clearDisplayedResults());
        Button preview = new Button("Preview Organization");
        preview.setOnAction(event -> previewOrganization());
        organizationPreview.setEditable(false);
        organizationPreview.setWrapText(true);
        organizationPreview.setPrefRowCount(8);
        organizationPreview.setPromptText("Read-only preview; not approval and no assets are written.");
        HBox reviewActions = new HBox(8, decision, submitReview, manualReview);
        analysisPane.getChildren().addAll(explanation, aiControls, aiStatus, analyzeActions, rulesNote, organizationRules, analysisRevision, new Label("Suggestions"), suggestions,
                new Label("Analysis findings"), analysisFindings, new Label("Explicit review (select a suggestion or enter manually)"),
                reviewAsset, reviewKind, reviewValue, reviewReference, reviewActions,
                new Label("Saved reviews for analyzed revision"), loadReviews, savedReviews,
                new Label("Organization preview: read-only, not approval, no assets written"), preview, organizationPreview);
        VBox.setVgrow(suggestions, Priority.ALWAYS);
    }

    private void suggestionColumn(String title, Function<AssetManager.Suggestion, String> value, double width) {
        TableColumn<AssetManager.Suggestion, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        suggestions.getColumns().add(column);
    }

    private void busy(boolean value) {
        inputs.setDisable(value);
        actions.setDisable(value);
        analysisPane.setDisable(value);
        compatibilityPane.setDisable(value);
        planPane.setDisable(value);
        progress.setVisible(value);
    }

    private void analyze(AssetManager.AnalysisKind kind) {
        analyze(useAi.isSelected() ? assistedAnalysis(kind) : (manager, id) -> manager.analyze(id, kind));
    }

    /** Runs on the worker thread: loads the saved account there, never on the JavaFX thread. */
    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> assistedAnalysis(AssetManager.AnalysisKind kind) {
        return (manager, id) -> manager.analyze(id, kind, io.github.aiassetmigrate.ai.OpenAIAdapter.connectedOrUnavailable());
    }

    private void refreshAiStatus() {
        Thread worker = new Thread(() -> {
            String text;
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                text = store.load().map(account -> "AI account: connected as "
                        + account.credentials().email().orElse(account.credentials().subject())
                        + ", model " + account.model().orElse("<none selected>"))
                        .orElse("AI account: not connected. AI suggestions are optional.");
            } catch (Exception e) {
                text = "AI account: unavailable (" + e.getMessage() + ")";
            }
            String result = text;
            Platform.runLater(() -> aiStatus.setText(result));
        }, "ai-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectAi() {
        aiStatus.setText("AI account: complete sign-in in your browser (up to 5 minutes)...");
        Thread worker = new Thread(() -> {
            String text;
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
                text = "AI account: connected as " + credentials.email().orElse(credentials.subject())
                        + ", model " + model.orElse("<none available>") + ". Migration approval is unchanged.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "AI account: sign-in interrupted.";
            } catch (Exception e) {
                text = "AI account: sign-in failed (" + e.getMessage() + ")";
            }
            String result = text;
            Platform.runLater(() -> aiStatus.setText(result));
        }, "ai-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnectAi() {
        Thread worker = new Thread(() -> {
            String text;
            try {
                var store = io.github.aiassetmigrate.auth.AccountStore.forCurrentUser();
                var account = store.load();
                if (account.isEmpty()) {
                    text = "AI account: not connected.";
                } else {
                    var credentials = account.get().credentials();
                    boolean revoked = new io.github.aiassetmigrate.auth.OAuthSession(store.hostId(),
                            java.util.Optional.of(account.get().registration())).revoke(credentials);
                    store.clear();
                    text = "AI account: disconnected. " + (revoked ? "Remote session revoked."
                            : "Remote revocation not confirmed; you can also disconnect in ChatGPT Settings.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "AI account: disconnect interrupted.";
            } catch (Exception e) {
                text = "AI account: disconnect failed (" + e.getMessage() + ")";
            }
            String result = text;
            Platform.runLater(() -> aiStatus.setText(result));
        }, "ai-disconnect");
        worker.setDaemon(true);
        worker.start();
    }

    static BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> organizationAnalysis(AssetManager.OrganizationRules rules) {
        return (manager, id) -> manager.analyzeOrganization(id, rules);
    }

    private void analyze(BiFunction<AssetManager, UUID, AssetManager.AnalysisResult> operation) {
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            if (runner.submitValue(statePath, logPath, manager -> operation.apply(manager, id), result -> {
                busy(false);
                if (result.value() != null) {
                    analysisContext = new AnalysisContext(id, statePath, logPath, result.value());
                    analysisRevision.setText(analysisContext.revisionText());
                    suggestions.getItems().setAll(result.value().suggestions());
                    analysisFindings.setText(formatAnalysisFindings(result.value().findings()));
                }
                status.setText(result.failure() == null ? "Analysis complete. Select a suggestion or enter a manual review."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nAnalysis results were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Analyzing latest inventory...");
                clearAnalysis();
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void review() {
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            AnalysisContext context = requireAnalysisContext(id, statePath, logPath);
            ReviewedChange change = makeReview(reviewAsset.getText(), reviewKind.getValue(), decision.getValue(),
                    reviewValue.getText(), reviewReference.getText());
            if (runner.submitValue(statePath, logPath, manager -> context.review(manager, change), result -> {
                busy(false);
                if (result.value() != null) clearReview();
                status.setText(result.failure() == null ? "Review saved: " + change.decision()
                        : failureText(result.failure(), null) + (result.value() != null
                        ? "\nThe review call returned, but cleanup failed; it may have been saved." : "\nThe review may have been saved; verify before retrying."));
            })) {
                busy(true);
                clearDisplayedResults();
                clearCompatibility();
                clearPlan();
                status.setText("Saving explicit review...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void previewOrganization() {
        organizationPreview.clear();
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            AnalysisContext context = requireAnalysisContext(id, statePath, logPath);
            if (runner.submitValue(statePath, logPath, context::preview, result -> {
                busy(false);
                if (result.value() != null) organizationPreview.setText(formatPreview(result.value()));
                status.setText(result.failure() == null ? "Read-only preview complete; this is not approval."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nPreview was obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Preparing read-only organization preview...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private AnalysisContext requireAnalysisContext(UUID id, Path statePath, Path logPath) {
        AnalysisContext context = analysisContext;
        if (context == null || !id.equals(context.project()) || !statePath.equals(context.state()) || !logPath.equals(context.logs())) {
            throw new IllegalArgumentException("Project or directories changed; run analysis again before reviewing.");
        }
        return context;
    }

    private void loadReviews() {
        savedReviews.clear();
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            AnalysisContext context = requireAnalysisContext(id, statePath, logPath);
            if (runner.submitValue(statePath, logPath, context::loadReviews, result -> {
                busy(false);
                if (result.value() != null) savedReviews.setText(formatReviews(result.value()));
                status.setText(result.failure() == null ? "Saved reviews loaded for analyzed revision " + context.result().inventoryRevision() + "."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nReviews were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Loading saved reviews...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void clearAnalysis() {
        analysisContext = null;
        analysisRevision.setText("No analysis loaded.");
        suggestions.getItems().clear();
        analysisFindings.clear();
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

    private void folderRow(Stage stage, int row, String label, TextField field) {
        field.setPromptText("Enter a directory path or browse");
        Button browse = new Button("Browse...");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select " + label.toLowerCase());
            File selected = chooser.showDialog(stage);
            if (selected != null) field.setText(selected.toPath().toString());
        });
        inputs.addRow(row, new Label(label), field, browse);
        GridPane.setHgrow(field, Priority.ALWAYS);
    }

    private void addColumn(String title, Function<Asset, String> value, double width) {
        TableColumn<Asset, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        inventory.getColumns().add(column);
    }

    private void begin(boolean load) {
        begin(load, false);
    }

    private void begin(boolean load, boolean rescan) {
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            Path sourcePath = load ? null : requiredPath(source, "Source folder");
            UUID id = load || rescan ? UUID.fromString(projectId.getText().trim()) : null;
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
                busy(true);
                clearCompatibility();
                clearPlan();
                status.setText(load ? "Loading inventory..." : rescan ? "Rescanning project..." : "Importing project...");
                summary.setText("No inventory loaded.");
                inventory.getItems().clear();
                findings.clear();
                clearAnalysis();
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private static Path requiredPath(TextField field, String label) {
        String value = field.getText().trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return Path.of(value).toAbsolutePath();
    }

    private void completed(ImportRunner.Outcome outcome) {
        busy(false);
        if (outcome.snapshot() != null) showSnapshot(outcome.snapshot());
        Throwable failure = outcome.failure();
        if (failure == null) {
            status.setText("Inventory loaded successfully.");
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
        summary.setText("Project: " + project.id() + " | Revision: " + project.inventoryRevision()
                + " | Scan: " + snapshot.scanStatus() + " | Assets: " + project.assets().size()
                + " | Findings: " + snapshot.findings().size());
        inventory.getItems().setAll(project.assets());
        findings.setText(formatFindings(snapshot));
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
            text.append(". Use Load Saved Inventory to recover it.");
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

    @Override
    public void stop() {
        runner.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
