package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.components.EnhancedSlider;

import java.io.File;
import java.util.function.Supplier;

/**
 * Panel for environment map settings.
 * Allows loading HDRI images or configuring a dynamic procedural sky.
 */
public class EnvironmentPanel extends ScrollPane implements Refreshable {

    private final GLSLEngine engine;
    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final Runnable onUpdate;
    private boolean suppressRender = false;

    private Label statusLabel;
    private EnhancedSlider rotationSlider;
    private EnhancedSlider lightingMixSlider;
    
    // Procedural Sky controls
    private ComboBox<String> skyTypeCombo;
    private EnhancedSlider cloudDensitySlider;
    private EnhancedSlider skySpeedSlider; // Variation
    private EnhancedSlider skyTimeSlider;

    public EnvironmentPanel(GLSLEngine engine, Supplier<AbstractFractalParams> paramsSupplier, Runnable onUpdate) {
        this.engine = engine;
        this.paramsSupplier = paramsSupplier;
        this.onUpdate = onUpdate;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        // Title
        Label titleLabel = new Label("Environment & Sky");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Status
        statusLabel = new Label("Using procedural sky");
        statusLabel.setStyle("-fx-font-style: italic;");

        // HDRI Section
        Label hdriLabel = new Label("HDRI Environment Map");
        hdriLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa;");

        Button loadButton = new Button("Load HDRI...");
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setOnAction(e -> loadEnvironmentMap());

        Button clearButton = new Button("Use Procedural Sky");
        clearButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setOnAction(e -> {
            engine.clearEnvironmentMap();
            statusLabel.setText("Using procedural sky");
            onUpdate.run();
        });

        HBox buttonBox = new HBox(5, loadButton, clearButton);

        rotationSlider = new EnhancedSlider("HDRI Rotation", 0, 360, 0, true);
        rotationSlider.showTickMarks(true);
        rotationSlider.setMajorTickUnit(90);
        rotationSlider.setOnAction(v -> {
            engine.setEnvRotation((float) Math.toRadians(v));
            onUpdate.run();
        });

        lightingMixSlider = new EnhancedSlider("Lighting Mix %", 0, 100, 50, true);
        lightingMixSlider.showTickMarks(true);
        lightingMixSlider.setMajorTickUnit(25);
        lightingMixSlider.setOnAction(v -> {
            engine.setEnvLightingMix((float) (v / 100.0));
            onUpdate.run();
        });

        // Procedural Sky Section
        Label skyLabel = new Label("Dynamic Procedural Sky");
        skyLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa;");

        // Sky Type
        HBox typeBox = new HBox(10);
        Label typeLabel = new Label("Type:");
        skyTypeCombo = new ComboBox<>();
        skyTypeCombo.getItems().addAll("Clouds", "Deep Space", "Ocean", "Studio");
        skyTypeCombo.setValue("Clouds");
        skyTypeCombo.setMaxWidth(Double.MAX_VALUE);
        skyTypeCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setSkyType(skyTypeCombo.getSelectionModel().getSelectedIndex());
                onUpdate.run();
            }
        });
        typeBox.getChildren().addAll(typeLabel, skyTypeCombo);

        cloudDensitySlider = new EnhancedSlider("Density", 0, 1, 0.5, false);
        cloudDensitySlider.setPrecision(2);
        cloudDensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setCloudDensity(v.floatValue());
                onUpdate.run();
            }
        });

        skySpeedSlider = new EnhancedSlider("Variation Scale", 0.1, 5.0, 1.0, false);
        skySpeedSlider.setPrecision(2);
        skySpeedSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSkySpeed(v.floatValue());
                onUpdate.run();
            }
        });
        
        skyTimeSlider = new EnhancedSlider("Time (Animation)", 0, 100, 0.0, false);
        skyTimeSlider.setPrecision(2);
        skyTimeSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSkyTime(v.floatValue());
                onUpdate.run();
            }
        });

        // Info
        Label infoLabel = new Label(
            "Procedural sky uses FBM noise clouds.\n" +
            "HDRI provides realistic 360° lighting."
        );
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        panel.getChildren().addAll(
            titleLabel, statusLabel,
            new Separator(),
            hdriLabel, buttonBox, rotationSlider, lightingMixSlider,
            new Separator(),
            skyLabel, typeBox, cloudDensitySlider, skySpeedSlider, skyTimeSlider,
            new Separator(),
            infoLabel
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

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }

    @Override
    public void refreshFromParams(boolean suppress) {
        this.suppressRender = suppress;
        try {
            AbstractFractalParams p = getParams();
            skyTypeCombo.getSelectionModel().select(p.getSkyType());
            cloudDensitySlider.setValue(p.getCloudDensity());
            skySpeedSlider.setValue(p.getSkySpeed());
            skyTimeSlider.setValue(p.getSkyTime());
            
            // Re-sync engine state if needed
            rotationSlider.setValue(Math.toDegrees(engine.getEnvRotation()));
            lightingMixSlider.setValue(engine.getEnvLightingMix() * 100.0);
            
            if (engine.isEnvMapLoaded()) {
                statusLabel.setText("HDRI Map Loaded");
            } else {
                statusLabel.setText("Using procedural sky");
            }
        } finally {
            this.suppressRender = false;
        }
    }
}