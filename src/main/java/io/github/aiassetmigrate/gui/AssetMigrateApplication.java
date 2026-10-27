package io.github.aiassetmigrate.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class AssetMigrateApplication extends Application {
    @Override
    public void start(Stage stage) {
        Label title = new Label("AIAssetMigrate");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        Label scope = new Label("Unity asset preparation | Bootstrap");
        Label status = new Label("Application shell ready. Import, review, migration, and AI are not implemented yet.\n"
                + "No asset files are read or modified by this shell.");
        status.setWrapText(true);
        VBox root = new VBox(16, title, scope, status);
        root.setPadding(new Insets(32));
        stage.setTitle("AIAssetMigrate");
        stage.setScene(new Scene(root, 720, 360));
        stage.setMinWidth(420);
        stage.setMinHeight(240);
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(() -> {
                System.out.println("JavaFX shell launched successfully.");
                stage.close();
                Platform.exit();
            });
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
