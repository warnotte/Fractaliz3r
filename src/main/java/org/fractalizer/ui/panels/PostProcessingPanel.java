package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;

import java.util.function.Consumer;

/**
 * Panel for post-processing settings.
 * Controls bloom, chromatic aberration, vignette, film grain, etc.
 */
public class PostProcessingPanel extends ScrollPane {

    private final PostProcessParams params;
    private final Runnable onUpdate;

    // Tone mapping
    private ComboBox<String> toneMapCombo;
    private Slider exposureSlider;

    // Bloom
    private CheckBox bloomCheck;
    private Slider bloomIntensitySlider;
    private Slider bloomThresholdSlider;
    private Slider bloomRadiusSlider;

    // Chromatic Aberration
    private CheckBox chromaticCheck;
    private Slider chromaticIntensitySlider;

    // Vignette
    private CheckBox vignetteCheck;
    private Slider vignetteIntensitySlider;
    private Slider vignetteSoftnessSlider;

    // Film Grain
    private CheckBox filmGrainCheck;
    private Slider filmGrainIntensitySlider;

    // Sharpening
    private CheckBox sharpenCheck;
    private Slider sharpenIntensitySlider;

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

        Label titleLabel = new Label("Tone Mapping");
        titleLabel.setStyle("-fx-font-weight: bold;");

        // Tone map mode
        HBox toneMapBox = new HBox(10);
        toneMapBox.getChildren().add(new Label("Mode:"));
        toneMapCombo = new ComboBox<>();
        toneMapCombo.getItems().addAll("ACES", "Reinhard", "Filmic", "None");
        toneMapCombo.setValue(getToneMapName(params.toneMapMode));
        toneMapCombo.setOnAction(e -> {
            params.toneMapMode = toneMapCombo.getSelectionModel().getSelectedIndex();
            onUpdate.run();
        });
        toneMapBox.getChildren().add(toneMapCombo);

        // Exposure
        exposureSlider = new Slider(0.1, 3.0, params.exposure);
        HBox exposureBox = createSliderRow("Exposure", exposureSlider,
            v -> { params.exposure = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(titleLabel, toneMapBox, exposureBox);
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

        bloomIntensitySlider = new Slider(0.0, 2.0, params.bloomIntensity);
        HBox intensityBox = createSliderRow("Intensity", bloomIntensitySlider,
            v -> { params.bloomIntensity = v.floatValue(); onUpdate.run(); });

        bloomThresholdSlider = new Slider(0.0, 3.0, params.bloomThreshold);
        HBox thresholdBox = createSliderRow("Threshold", bloomThresholdSlider,
            v -> { params.bloomThreshold = v.floatValue(); onUpdate.run(); });

        bloomRadiusSlider = new Slider(1, 8, params.bloomRadius);
        HBox radiusBox = createSliderRow("Radius", bloomRadiusSlider,
            v -> { params.bloomRadius = v.intValue(); onUpdate.run(); });

        section.getChildren().addAll(bloomCheck, intensityBox, thresholdBox, radiusBox);
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

        chromaticIntensitySlider = new Slider(0.0, 0.02, params.chromaticAberrationIntensity);
        HBox intensityBox = createSliderRow("Intensity", chromaticIntensitySlider,
            v -> { params.chromaticAberrationIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(chromaticCheck, intensityBox);
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

        vignetteIntensitySlider = new Slider(0.0, 1.0, params.vignetteIntensity);
        HBox intensityBox = createSliderRow("Intensity", vignetteIntensitySlider,
            v -> { params.vignetteIntensity = v.floatValue(); onUpdate.run(); });

        vignetteSoftnessSlider = new Slider(0.0, 1.0, params.vignetteSoftness);
        HBox softnessBox = createSliderRow("Softness", vignetteSoftnessSlider,
            v -> { params.vignetteSoftness = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(vignetteCheck, intensityBox, softnessBox);
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

        filmGrainIntensitySlider = new Slider(0.0, 0.1, params.filmGrainIntensity);
        HBox intensityBox = createSliderRow("Intensity", filmGrainIntensitySlider,
            v -> { params.filmGrainIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(filmGrainCheck, intensityBox);
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

        sharpenIntensitySlider = new Slider(0.0, 1.0, params.sharpenIntensity);
        HBox intensityBox = createSliderRow("Intensity", sharpenIntensitySlider,
            v -> { params.sharpenIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(sharpenCheck, intensityBox);
        return section;
    }

    private HBox createSliderRow(String name, Slider slider, Consumer<Double> onChange) {
        HBox box = new HBox(10);
        box.setPadding(new Insets(0, 0, 0, 15));

        Label nameLabel = new Label(name + ":");
        nameLabel.setMinWidth(70);

        slider.setPrefWidth(120);
        slider.setShowTickMarks(true);

        Label valueLabel = new Label(formatValue(slider.getValue()));
        valueLabel.setMinWidth(40);
        valueLabel.setStyle("-fx-font-family: monospace;");

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            valueLabel.setText(formatValue(newVal.doubleValue()));
            onChange.accept(newVal.doubleValue());
        });

        box.getChildren().addAll(nameLabel, slider, valueLabel);
        return box;
    }

    private String formatValue(double value) {
        if (value < 0.1) {
            return String.format("%.3f", value);
        } else if (value < 10) {
            return String.format("%.2f", value);
        } else {
            return String.format("%.0f", value);
        }
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
