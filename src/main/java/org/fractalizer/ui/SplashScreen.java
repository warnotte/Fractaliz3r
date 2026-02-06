package org.fractalizer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * A beautiful splash screen to show during long shader compilation.
 * Gift for the birthday boy!
 */
public class SplashScreen {

    private final Stage stage;
    private final ProgressBar progressBar;
    private final Label statusLabel;

    public SplashScreen() {
        this.stage = new Stage(StageStyle.UNDECORATED);
        
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #333; -fx-border-width: 2px;");

        // Logo from resources
        ImageView logoView = new ImageView();
        try {
            var resource = getClass().getResource("/Logo.png");
            if (resource != null) {
                Image img = new Image(resource.toExternalForm());
                logoView.setImage(img);
                logoView.setFitWidth(400);
                logoView.setPreserveRatio(true);
            }
        } catch (Exception e) {
            System.err.println("Failed to load splash logo: " + e.getMessage());
        }

        Label titleLabel = new Label("Fractaliz3r GLSL");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");

        this.statusLabel = new Label("Initializing engine...");
        this.statusLabel.setStyle("-fx-text-fill: #aaa;");

        this.progressBar = new ProgressBar(0);
        this.progressBar.setPrefWidth(380);
        this.progressBar.setStyle("-fx-accent: #4a90e2;");

        root.getChildren().addAll(logoView, titleLabel, statusLabel, progressBar);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    public void show() {
        stage.show();
    }

    public void hide() {
        stage.close();
    }

    public void update(String status, double progress) {
        statusLabel.setText(status);
        progressBar.setProgress(progress);
    }
}