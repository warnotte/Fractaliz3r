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
    private EnhancedSlider skyParallaxSlider;
    
    // Volumetric Fog controls
    private CheckBox fogEnabledCheck;
    private EnhancedSlider fogDensitySlider;
    private ColorPicker fogColorPicker;
    private EnhancedSlider fogScatteringSlider;
    private EnhancedSlider fogStepsSlider;

    // Ocean controls
    private CheckBox oceanEnabledCheck;
    private EnhancedSlider oceanHeightSlider;
    private ColorPicker oceanColorPicker;
    private EnhancedSlider oceanWaveScaleSlider;
    private EnhancedSlider oceanWaveHeightSlider;
    private EnhancedSlider oceanSpeedSlider;
    private EnhancedSlider oceanTimeSlider;

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

        // Status
        statusLabel = new Label("Using procedural sky");
        statusLabel.setStyle("-fx-font-style: italic;");

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

        // Sky Type
        HBox typeBox = new HBox(10);
        Label typeLabel = new Label("Type:");
        skyTypeCombo = new ComboBox<>();
        skyTypeCombo.getItems().addAll("Clouds", "Deep Space (Legacy)", "Ocean", "Studio", "Deep Space (Cinematic)", "Deep Space (Ultra)");
        skyTypeCombo.setValue("Deep Space (Legacy)");
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

        skyParallaxSlider = new EnhancedSlider("Parallax Strength", 0, 1, 0.25, false);
        skyParallaxSlider.setPrecision(2);
        skyParallaxSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSkyParallax(v.floatValue());
                onUpdate.run();
            }
        });

        // Volumetric Fog Section
        
        fogEnabledCheck = new CheckBox("Enable Volumetric Fog");
        fogEnabledCheck.getStyleClass().add("bold-label");
        fogEnabledCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setVolumetricFogEnabled(fogEnabledCheck.isSelected());
                onUpdate.run();
            }
        });
        
        fogDensitySlider = new EnhancedSlider("Fog Density", 0, 1, 0.15, false);
        fogDensitySlider.setPrecision(2);
        fogDensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setFogDensity(v.floatValue());
                onUpdate.run();
            }
        });
        
        HBox fogColorBox = new HBox(10);
        Label colorLabel = new Label("Fog Color:");
        fogColorPicker = new ColorPicker(javafx.scene.paint.Color.rgb(128, 153, 178));
        fogColorPicker.setMaxWidth(Double.MAX_VALUE);
        fogColorPicker.setOnAction(e -> {
            if (!suppressRender) {
                javafx.scene.paint.Color c = fogColorPicker.getValue();
                getParams().setFogColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
                onUpdate.run();
            }
        });
        fogColorBox.getChildren().addAll(colorLabel, fogColorPicker);
        
        fogScatteringSlider = new EnhancedSlider("Scattering (Anisotropy)", -0.9, 0.9, 0.5, false);
        fogScatteringSlider.setPrecision(2);
        fogScatteringSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setFogScattering(v.floatValue());
                onUpdate.run();
            }
        });
        
        fogStepsSlider = new EnhancedSlider("Quality (Samples)", 8, 64, 32, true);
        fogStepsSlider.showTickMarks(true);
        fogStepsSlider.setMajorTickUnit(8);
        fogStepsSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setFogSteps(v.intValue());
                onUpdate.run();
            }
        });

        // Ocean Section
        oceanEnabledCheck = new CheckBox("Enable Physical Ocean");
        oceanEnabledCheck.getStyleClass().add("bold-label");
        oceanEnabledCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setOceanEnabled(oceanEnabledCheck.isSelected());
                onUpdate.run();
            }
        });

        oceanHeightSlider = new EnhancedSlider("Water Height", -5, 5, -1, false);
        oceanHeightSlider.setPrecision(2);
        oceanHeightSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOceanHeight(v.floatValue());
                onUpdate.run();
            }
        });

        HBox oceanColorBox = new HBox(10);
        oceanColorPicker = new ColorPicker(javafx.scene.paint.Color.rgb(13, 38, 77));
        oceanColorPicker.setMaxWidth(Double.MAX_VALUE);
        oceanColorPicker.setOnAction(e -> {
            if (!suppressRender) {
                javafx.scene.paint.Color c = oceanColorPicker.getValue();
                getParams().setOceanColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
                onUpdate.run();
            }
        });
        oceanColorBox.getChildren().addAll(new Label("Water Color:"), oceanColorPicker);

        oceanWaveScaleSlider = new EnhancedSlider("Wave Scale", 0.1, 10, 2, false);
        oceanWaveScaleSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOceanWaveScale(v.floatValue());
                onUpdate.run();
            }
        });

        oceanWaveHeightSlider = new EnhancedSlider("Wave Height", 0, 1, 0.1, false);
        oceanWaveHeightSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOceanWaveHeight(v.floatValue());
                onUpdate.run();
            }
        });

        oceanSpeedSlider = new EnhancedSlider("Wave Speed", 0, 5, 1, false);
        oceanSpeedSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOceanSpeed(v.floatValue());
                onUpdate.run();
            }
        });

        oceanTimeSlider = new EnhancedSlider("Wave Time (Anim)", 0, 100, 0, false);
        oceanTimeSlider.setPrecision(3);
        oceanTimeSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOceanTime(v.floatValue());
                onUpdate.run();
            }
        });

        // Info
        Label infoLabel = new Label(
            "Procedural sky uses FBM noise clouds.\n" +
            "Volumetric fog creates rays of light (God Rays)."
        );
        infoLabel.getStyleClass().add("info-label");

        VBox hdriBox = new VBox(5, statusLabel, buttonBox, rotationSlider, lightingMixSlider);
        TitledPane hdriPane = new TitledPane("HDRI Environment Map", hdriBox);
        hdriPane.setExpanded(true);

        VBox skyBox = new VBox(5, typeBox, cloudDensitySlider, skySpeedSlider, skyTimeSlider, skyParallaxSlider);
        TitledPane skyPane = new TitledPane("Dynamic Procedural Sky", skyBox);
        skyPane.setExpanded(false);

        VBox oceanBox = new VBox(5, oceanEnabledCheck, oceanHeightSlider, oceanColorBox,
            oceanWaveScaleSlider, oceanWaveHeightSlider, oceanSpeedSlider, oceanTimeSlider);
        TitledPane oceanPane = new TitledPane("Physical Ocean (Raymarched)", oceanBox);
        oceanPane.setExpanded(false);

        VBox fogBox = new VBox(5, fogEnabledCheck, fogDensitySlider, fogColorBox,
            fogScatteringSlider, fogStepsSlider, infoLabel);
        TitledPane fogPane = new TitledPane("Volumetric Fog & God Rays", fogBox);
        fogPane.setExpanded(false);

        panel.getChildren().addAll(hdriPane, skyPane, oceanPane, fogPane);

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
            skyParallaxSlider.setValue(p.getSkyParallax());
            
            fogEnabledCheck.setSelected(p.isVolumetricFogEnabled());
            fogDensitySlider.setValue(p.getFogDensity());
            float[] fc = p.getFogColor();
            fogColorPicker.setValue(javafx.scene.paint.Color.color(fc[0], fc[1], fc[2]));
            fogScatteringSlider.setValue(p.getFogScattering());
            fogStepsSlider.setValue(p.getFogSteps());

            oceanEnabledCheck.setSelected(p.isOceanEnabled());
            oceanHeightSlider.setValue(p.getOceanHeight());
            oceanColorPicker.setValue(javafx.scene.paint.Color.color(p.getOceanColorR(), p.getOceanColorG(), p.getOceanColorB()));
            oceanWaveScaleSlider.setValue(p.getOceanWaveScale());
            oceanWaveHeightSlider.setValue(p.getOceanWaveHeight());
            oceanSpeedSlider.setValue(p.getOceanSpeed());
            oceanTimeSlider.setValue(p.getOceanTime());

            // Re-sync engine state if needed
            rotationSlider.setValue(Math.toDegrees(engine.getEnvRotation()));
            lightingMixSlider.setValue(engine.getEnvLightingMix() * 100.0);
            
            if (engine.isEnvMapLoaded()) {
                statusLabel.setText("HDRI map loaded");
            } else {
                statusLabel.setText("Using procedural sky");
            }
        } finally {
            this.suppressRender = false;
        }
    }
}