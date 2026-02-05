package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.ui.components.EnhancedSlider;

import java.util.function.Consumer;

/**
 * Panel for post-processing settings.
 * Controls bloom, chromatic aberration, vignette, film grain, etc.
 * Uses EnhancedSlider for professional control.
 */
public class PostProcessingPanel extends ScrollPane {

    private final PostProcessParams params;
    private final Runnable onUpdate;

    // Tone mapping
    private ComboBox<String> toneMapCombo;
    private EnhancedSlider exposureSlider;
    private EnhancedSlider saturationSlider;

    // Bloom
    private CheckBox bloomCheck;
    private EnhancedSlider bloomIntensitySlider;
    private EnhancedSlider bloomThresholdSlider;
    private EnhancedSlider bloomRadiusSlider;

    // Chromatic Aberration
    private CheckBox chromaticCheck;
    private EnhancedSlider chromaticIntensitySlider;

    // Vignette
    private CheckBox vignetteCheck;
    private EnhancedSlider vignetteIntensitySlider;
    private EnhancedSlider vignetteSoftnessSlider;

    // Film Grain
    private CheckBox filmGrainCheck;
    private EnhancedSlider filmGrainIntensitySlider;

    // Sharpening
    private CheckBox sharpenCheck;
    private EnhancedSlider sharpenIntensitySlider;

    public PostProcessingPanel(PostProcessParams params, Runnable onUpdate) {
        this.params = params;
        this.onUpdate = onUpdate;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        // Presets
        panel.getChildren().add(createPresetsSection());
        panel.getChildren().add(new Separator());

        // Tone Mapping
        panel.getChildren().add(createToneMappingSection());
        panel.getChildren().add(new Separator());

        // Bloom
        panel.getChildren().add(createBloomSection());
        panel.getChildren().add(new Separator());

        // Chromatic Aberration
        panel.getChildren().add(createChromaticAberrationSection());
        panel.getChildren().add(new Separator());

        // Vignette
        panel.getChildren().add(createVignetteSection());
        panel.getChildren().add(new Separator());

        // Film Grain
        panel.getChildren().add(createFilmGrainSection());
        panel.getChildren().add(new Separator());

        // Sharpening
        panel.getChildren().add(createSharpeningSection());

        return panel;
    }

    private VBox createPresetsSection() {
        VBox section = new VBox(5);

        Label titleLabel = new Label("Presets");
        titleLabel.setStyle("-fx-font-weight: bold;");

        HBox presetButtons = new HBox(5);
        Button cinematicBtn = new Button("Cinematic");
        cinematicBtn.setOnAction(e -> {
            params.applyCinematicPreset();
            refreshUI();
            onUpdate.run();
        });

        Button cleanBtn = new Button("Clean");
        cleanBtn.setOnAction(e -> {
            params.applyCleanPreset();
            refreshUI();
            onUpdate.run();
        });

        Button vibrantBtn = new Button("Vibrant");
        vibrantBtn.setOnAction(e -> {
            params.applyVibrantPreset();
            refreshUI();
            onUpdate.run();
        });

        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> {
            params.reset();
            refreshUI();
            onUpdate.run();
        });

        presetButtons.getChildren().addAll(cinematicBtn, cleanBtn, vibrantBtn, resetBtn);

        section.getChildren().addAll(titleLabel, presetButtons);
        return section;
    }

    private VBox createToneMappingSection() {
        VBox section = new VBox(5);

        Label titleLabel = new Label("Tone Mapping & Color");
        titleLabel.setStyle("-fx-font-weight: bold;");

        // Tone map mode
        HBox toneMapBox = new HBox(10);
        Label modeLabel = new Label("Mode:");
        toneMapCombo = new ComboBox<>();
        toneMapCombo.getItems().addAll("ACES", "Reinhard", "Filmic", "None");
        toneMapCombo.setValue(getToneMapName(params.toneMapMode));
        toneMapCombo.setOnAction(e -> {
            params.toneMapMode = toneMapCombo.getSelectionModel().getSelectedIndex();
            onUpdate.run();
        });
        toneMapBox.getChildren().addAll(modeLabel, toneMapCombo);

        // Exposure
        exposureSlider = new EnhancedSlider("Exposure", 0.1, 3.0, params.exposure, false);
        exposureSlider.setPrecision(2);
        exposureSlider.setOnAction(v -> { params.exposure = v.floatValue(); onUpdate.run(); });

        // Saturation
        saturationSlider = new EnhancedSlider("Saturation", 0.0, 2.0, params.saturation, false);
        saturationSlider.setPrecision(2);
        saturationSlider.setOnAction(v -> { params.saturation = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(titleLabel, toneMapBox, exposureSlider, saturationSlider);
        return section;
    }

    private VBox createBloomSection() {
        VBox section = new VBox(5);

        bloomCheck = new CheckBox("Bloom");
        bloomCheck.setSelected(params.bloomEnabled);
        bloomCheck.setStyle("-fx-font-weight: bold;");
        bloomCheck.setOnAction(e -> {
            params.bloomEnabled = bloomCheck.isSelected();
            onUpdate.run();
        });

        bloomIntensitySlider = new EnhancedSlider("Intensity", 0.0, 2.0, params.bloomIntensity, false);
        bloomIntensitySlider.setPrecision(2);
        bloomIntensitySlider.setOnAction(v -> { params.bloomIntensity = v.floatValue(); onUpdate.run(); });

        bloomThresholdSlider = new EnhancedSlider("Threshold", 0.0, 3.0, params.bloomThreshold, false);
        bloomThresholdSlider.setPrecision(2);
        bloomThresholdSlider.setOnAction(v -> { params.bloomThreshold = v.floatValue(); onUpdate.run(); });

        bloomRadiusSlider = new EnhancedSlider("Radius (Blur Passes)", 1, 8, params.bloomRadius, true);
        bloomRadiusSlider.showTickMarks(true);
        bloomRadiusSlider.setMajorTickUnit(1);
        bloomRadiusSlider.setOnAction(v -> { params.bloomRadius = v.intValue(); onUpdate.run(); });

        section.getChildren().addAll(bloomCheck, bloomIntensitySlider, bloomThresholdSlider, bloomRadiusSlider);
        return section;
    }

    private VBox createChromaticAberrationSection() {
        VBox section = new VBox(5);

        chromaticCheck = new CheckBox("Chromatic Aberration");
        chromaticCheck.setSelected(params.chromaticAberrationEnabled);
        chromaticCheck.setStyle("-fx-font-weight: bold;");
        chromaticCheck.setOnAction(e -> {
            params.chromaticAberrationEnabled = chromaticCheck.isSelected();
            onUpdate.run();
        });

        chromaticIntensitySlider = new EnhancedSlider("Intensity", 0.0, 0.02, params.chromaticAberrationIntensity, false);
        chromaticIntensitySlider.setPrecision(4);
        chromaticIntensitySlider.setOnAction(v -> { params.chromaticAberrationIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(chromaticCheck, chromaticIntensitySlider);
        return section;
    }

    private VBox createVignetteSection() {
        VBox section = new VBox(5);

        vignetteCheck = new CheckBox("Vignette");
        vignetteCheck.setSelected(params.vignetteEnabled);
        vignetteCheck.setStyle("-fx-font-weight: bold;");
        vignetteCheck.setOnAction(e -> {
            params.vignetteEnabled = vignetteCheck.isSelected();
            onUpdate.run();
        });

        vignetteIntensitySlider = new EnhancedSlider("Intensity", 0.0, 1.0, params.vignetteIntensity, false);
        vignetteIntensitySlider.setPrecision(2);
        vignetteIntensitySlider.setOnAction(v -> { params.vignetteIntensity = v.floatValue(); onUpdate.run(); });

        vignetteSoftnessSlider = new EnhancedSlider("Softness", 0.0, 1.0, params.vignetteSoftness, false);
        vignetteSoftnessSlider.setPrecision(2);
        vignetteSoftnessSlider.setOnAction(v -> { params.vignetteSoftness = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(vignetteCheck, vignetteIntensitySlider, vignetteSoftnessSlider);
        return section;
    }

    private VBox createFilmGrainSection() {
        VBox section = new VBox(5);

        filmGrainCheck = new CheckBox("Film Grain");
        filmGrainCheck.setSelected(params.filmGrainEnabled);
        filmGrainCheck.setStyle("-fx-font-weight: bold;");
        filmGrainCheck.setOnAction(e -> {
            params.filmGrainEnabled = filmGrainCheck.isSelected();
            onUpdate.run();
        });

        filmGrainIntensitySlider = new EnhancedSlider("Intensity", 0.0, 0.1, params.filmGrainIntensity, false);
        filmGrainIntensitySlider.setPrecision(3);
        filmGrainIntensitySlider.setOnAction(v -> { params.filmGrainIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(filmGrainCheck, filmGrainIntensitySlider);
        return section;
    }

    private VBox createSharpeningSection() {
        VBox section = new VBox(5);

        sharpenCheck = new CheckBox("Sharpening");
        sharpenCheck.setSelected(params.sharpenEnabled);
        sharpenCheck.setStyle("-fx-font-weight: bold;");
        sharpenCheck.setOnAction(e -> {
            params.sharpenEnabled = sharpenCheck.isSelected();
            onUpdate.run();
        });

        sharpenIntensitySlider = new EnhancedSlider("Intensity", 0.0, 1.0, params.sharpenIntensity, false);
        sharpenIntensitySlider.setPrecision(2);
        sharpenIntensitySlider.setOnAction(v -> { params.sharpenIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(sharpenCheck, sharpenIntensitySlider);
        return section;
    }

    private String getToneMapName(int mode) {
        return switch (mode) {
            case 0 -> "ACES";
            case 1 -> "Reinhard";
            case 2 -> "Filmic";
            default -> "None";
        };
    }

    /**
     * Refresh UI to match current params (after preset change).
     */
    public void refreshUI() {
        toneMapCombo.setValue(getToneMapName(params.toneMapMode));
        exposureSlider.setValue(params.exposure);
        saturationSlider.setValue(params.saturation);

        bloomCheck.setSelected(params.bloomEnabled);
        bloomIntensitySlider.setValue(params.bloomIntensity);
        bloomThresholdSlider.setValue(params.bloomThreshold);
        bloomRadiusSlider.setValue(params.bloomRadius);

        chromaticCheck.setSelected(params.chromaticAberrationEnabled);
        chromaticIntensitySlider.setValue(params.chromaticAberrationIntensity);

        vignetteCheck.setSelected(params.vignetteEnabled);
        vignetteIntensitySlider.setValue(params.vignetteIntensity);
        vignetteSoftnessSlider.setValue(params.vignetteSoftness);

        filmGrainCheck.setSelected(params.filmGrainEnabled);
        filmGrainIntensitySlider.setValue(params.filmGrainIntensity);

        sharpenCheck.setSelected(params.sharpenEnabled);
        sharpenIntensitySlider.setValue(params.sharpenIntensity);
    }

    /**
     * Get current post-process parameters.
     */
    public PostProcessParams getParams() {
        return params;
    }
}