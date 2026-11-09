package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.ImportException;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.ReviewedChange;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class AssetMigrateApplication extends Application {
    private final ImportRunner runner = new ImportRunner(Platform::runLater);
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
    private final TableView<AssetManager.Suggestion> suggestions = new TableView<>();
    private final TextArea analysisFindings = new TextArea();
    private final TextField reviewValue = new TextField();
    private final TextField reviewReference = new TextField();
    private final ComboBox<ReviewedChange.Decision> decision = new ComboBox<>();
    private UUID analysisProject;
    private Path analysisState;
    private Path analysisLogs;

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

        addColumn("Path", asset -> asset.relativePath().toString(), 260);
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
        Tab inventoryTab = new Tab("Inventory", results);
        ScrollPane analysisScroll = new ScrollPane(analysisPane);
        analysisScroll.setFitToWidth(true);
        Tab analysisTab = new Tab("Analysis and Review", analysisScroll);
        inventoryTab.setClosable(false);
        analysisTab.setClosable(false);
        TabPane tabs = new TabPane(inventoryTab, analysisTab);
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

    private void setupAnalysis() {
        Label explanation = new Label("Analysis uses the latest saved inventory, not the revision displayed in the Inventory tab. Suggestions are not reviews.");
        explanation.setWrapText(true);
        Button categories = new Button("Analyze Categories");
        Button relationships = new Button("Analyze Relationships");
        Button materials = new Button("Analyze Materials");
        categories.setOnAction(event -> analyze(AssetManager.AnalysisKind.CATEGORIES));
        relationships.setOnAction(event -> analyze(AssetManager.AnalysisKind.RELATIONSHIPS));
        materials.setOnAction(event -> analyze(AssetManager.AnalysisKind.MATERIALS));
        HBox analyzeActions = new HBox(8, categories, relationships, materials);
        suggestionColumn("Asset ID", suggestion -> suggestion.assetId().toString(), 260);
        suggestionColumn("Kind", suggestion -> suggestion.kind().toString(), 120);
        suggestionColumn("Proposed value", AssetManager.Suggestion::proposedValue, 360);
        suggestions.setPlaceholder(new Label("Run analysis to display suggestions."));
        suggestions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            reviewValue.setText(selected == null ? "" : selected.proposedValue());
            reviewReference.setText(selected == null ? "" : selected.reference().orElse(""));
            decision.setValue(null);
        });
        analysisFindings.setEditable(false);
        analysisFindings.setWrapText(true);
        analysisFindings.setPromptText("Analysis findings appear here.");
        analysisFindings.setPrefRowCount(4);
        reviewValue.setPromptText("Category or target asset UUID (editable)");
        reviewReference.setPromptText("Original reference (required for material mapping)");
        decision.getItems().setAll(ReviewedChange.Decision.ACCEPTED, ReviewedChange.Decision.REJECTED);
        decision.setPromptText("Choose decision");
        Button submitReview = new Button("Save Explicit Review");
        submitReview.setOnAction(event -> review());
        HBox reviewActions = new HBox(8, decision, submitReview);
        analysisPane.getChildren().addAll(explanation, analyzeActions, new Label("Suggestions"), suggestions,
                new Label("Analysis findings"), analysisFindings, new Label("Selected suggestion review"),
                reviewValue, reviewReference, reviewActions);
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
        progress.setVisible(value);
    }

    private void analyze(AssetManager.AnalysisKind kind) {
        try {
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            if (runner.submitValue(statePath, logPath, manager -> manager.analyze(id, kind), result -> {
                busy(false);
                if (result.value() != null) {
                    analysisProject = id;
                    analysisState = statePath;
                    analysisLogs = logPath;
                    suggestions.getItems().setAll(result.value().suggestions());
                    analysisFindings.setText(formatAnalysisFindings(result.value().findings()));
                }
                status.setText(result.failure() == null ? "Analysis complete. Select a suggestion to review."
                        : failureText(result.failure(), null) + (result.value() != null ? "\nAnalysis results were obtained, but cleanup failed." : ""));
            })) {
                busy(true);
                status.setText("Analyzing latest inventory...");
                analysisProject = null;
                suggestions.getItems().clear();
                analysisFindings.clear();
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    private void review() {
        try {
            AssetManager.Suggestion selected = suggestions.getSelectionModel().getSelectedItem();
            if (selected == null) throw new IllegalArgumentException("Select a suggestion.");
            Path statePath = requiredPath(state, "State directory");
            Path logPath = requiredPath(logs, "Log directory");
            UUID id = UUID.fromString(projectId.getText().trim());
            if (!id.equals(analysisProject) || !statePath.equals(analysisState) || !logPath.equals(analysisLogs)) {
                throw new IllegalArgumentException("Project or directories changed; run analysis again before reviewing.");
            }
            ReviewedChange change = makeReview(selected, decision.getValue(), reviewValue.getText(), reviewReference.getText());
            if (runner.submitValue(statePath, logPath, manager -> manager.review(id, List.of(change)), result -> {
                busy(false);
                if (result.value() != null) {
                    suggestions.getItems().remove(selected);
                }
                status.setText(result.failure() == null ? "Review saved: " + change.decision()
                        : failureText(result.failure(), null) + (result.value() != null
                        ? "\nThe review call returned, but cleanup failed; it may have been saved." : "\nThe review may have been saved; verify before retrying."));
            })) {
                busy(true);
                status.setText("Saving explicit review...");
            }
        } catch (IllegalArgumentException failure) {
            status.setText("Invalid input: " + failure.getMessage());
        }
    }

    static ReviewedChange makeReview(AssetManager.Suggestion suggestion, ReviewedChange.Decision decision,
                                     String value, String reference) {
        if (decision == null) throw new IllegalArgumentException("Choose ACCEPTED or REJECTED.");
        String edited = value.trim();
        if (edited.isEmpty()) throw new IllegalArgumentException("Review value is required.");
        ReviewedChange.Kind kind;
        Map<String, String> values;
        switch (suggestion.kind()) {
            case CATEGORIES -> {
                kind = ReviewedChange.Kind.CATEGORY;
                values = Map.of("category", edited);
            }
            case RELATIONSHIPS, MATERIALS -> {
                String target = UUID.fromString(edited).toString();
                if (suggestion.kind() == AssetManager.AnalysisKind.MATERIALS) {
                    if (reference.isBlank()) throw new IllegalArgumentException("Original reference is required for material mapping.");
                    kind = ReviewedChange.Kind.MATERIAL_MAPPING;
                    values = Map.of("targetAssetId", target, "reference", reference);
                } else {
                    kind = ReviewedChange.Kind.RELATIONSHIP;
                    values = Map.of("targetAssetId", target);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported suggestion kind: " + suggestion.kind());
        }
        return new ReviewedChange(UUID.randomUUID(), suggestion.assetId(), kind, decision, values, Instant.now());
    }

    static String formatAnalysisFindings(List<Finding> items) {
        StringBuilder text = new StringBuilder();
        for (Finding finding : items) {
            text.append(finding.severity()).append(" [").append(finding.code()).append("] ");
            finding.sourceRelativePath().ifPresent(path -> text.append(path).append(": "));
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
                status.setText(load ? "Loading inventory..." : rescan ? "Rescanning project..." : "Importing project...");
                summary.setText("No inventory loaded.");
                inventory.getItems().clear();
                findings.clear();
                analysisProject = null;
                suggestions.getItems().clear();
                analysisFindings.clear();
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
            finding.sourceRelativePath().ifPresent(path -> text.append(path.toString().isEmpty() ? "(source root)" : path).append(": "));
            text.append(finding.message()).append('\n');
        }
        for (PathOutcome outcome : snapshot.pathOutcomes()) {
            if (outcome.status() == PathOutcome.Status.FAILED || outcome.status() == PathOutcome.Status.UNVISITED
                    || outcome.status() == PathOutcome.Status.SKIPPED) {
                text.append("Coverage ").append(outcome.status()).append(" ").append(outcome.kind()).append(": ")
                        .append(outcome.relativePath().toString().isEmpty() ? "(source root)" : outcome.relativePath()).append('\n');
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
