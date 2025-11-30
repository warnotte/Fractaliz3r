package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fractalizer.engine.GLSLEngine;

import java.io.File;

/**
 * Panel for environment map settings.
 * Allows loading HDRI images for environment lighting.
 */
public class EnvironmentPanel extends ScrollPane {

    private final GLSLEngine engine;
    private final Runnable onUpdate;

    private Label statusLabel;
    private Slider rotationSlider;
    private Slider lightingMixSlider;

    public EnvironmentPanel(GLSLEngine engine, Runnable onUpdate) {
        this.engine = engine;
        this.onUpdate = onUpdate;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        // Title
        Label titleLabel = new Label("Environment Map");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Status
        statusLabel = new Label("Using procedural sky");
        statusLabel.setStyle("-fx-font-style: italic;");

        // Load button
        Button loadButton = new Button("Load HDRI...");
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setOnAction(e -> loadEnvironmentMap());

        // Clear button
        Button clearButton = new Button("Use Procedural Sky");
        clearButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setOnAction(e -> {
            engine.clearEnvironmentMap();
            statusLabel.setText("Using procedural sky");
            onUpdate.run();
        });

        HBox buttonBox = new HBox(5, loadButton, clearButton);

        // Rotation slider
        Label rotationLabel = new Label("Rotation:");
        rotationLabel.setStyle("-fx-font-weight: bold;");

        Label rotationValueLabel = new Label("0°");
        rotationSlider = new Slider(0, 360, 0);
        rotationSlider.setShowTickLabels(true);
        rotationSlider.setMajorTickUnit(90);
        rotationSlider.valueProperty().addListener((obs, old, val) -> {
            rotationValueLabel.setText(String.format("%.0f°", val.doubleValue()));
            engine.setEnvRotation((float) Math.toRadians(val.doubleValue()));
            onUpdate.run();
        });

        HBox rotationBox = new HBox(10, rotationSlider, rotationValueLabel);

        // Lighting Mix slider - controls blend between directional and HDRI lighting
        Label lightingMixLabel = new Label("Lighting Mix:");
        lightingMixLabel.setStyle("-fx-font-weight: bold;");

        Label lightingMixValueLabel = new Label("50%");
        lightingMixSlider = new Slider(0, 100, 50);
        lightingMixSlider.setShowTickLabels(true);
        lightingMixSlider.setMajorTickUnit(25);
        lightingMixSlider.valueProperty().addListener((obs, old, val) -> {
            lightingMixValueLabel.setText(String.format("%.0f%%", val.doubleValue()));
            engine.setEnvLightingMix((float) (val.doubleValue() / 100.0));
            onUpdate.run();
        });

        HBox lightingMixBox = new HBox(10, lightingMixSlider, lightingMixValueLabel);

        Label lightingMixInfoLabel = new Label(
            "0% = Directional light only\n" +
            "100% = Full HDRI lighting"
        );
        lightingMixInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Info
        Label infoLabel = new Label(
            "Load an equirectangular HDRI image for\n" +
            "realistic environment lighting.\n\n" +
            "Supported formats: PNG, JPG, HDR\n\n" +
            "Free HDRIs available at:\n" +
            "• polyhaven.com/hdris\n" +
            "• hdrihaven.com"
        );
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Separator
        Separator sep = new Separator();

        // Sky intensity is already in Quality panel via path tracing settings
        Label noteLabel = new Label("Tip: Adjust 'Sky Intensity' in the\nQuality tab for brightness control.");
        noteLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        panel.getChildren().addAll(
            titleLabel,
            statusLabel,
            buttonBox,
            sep,
            rotationLabel,
            rotationBox,
            new Separator(),
            lightingMixLabel,
            lightingMixBox,
            lightingMixInfoLabel,
            new Separator(),
            infoLabel,
            noteLabel
        );

        return panel;
    }

    private void loadEnvironmentMap() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load Environment Map");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.hdr"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            engine.loadEnvironmentMap(file.getAbsolutePath());
            statusLabel.setText("Loaded: " + file.getName());
            onUpdate.run();
        }
    }
}
