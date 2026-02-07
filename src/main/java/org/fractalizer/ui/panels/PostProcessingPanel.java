package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.components.EnhancedSlider;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel for post-processing settings.
 * Controls bloom, chromatic aberration, vignette, film grain, etc.
 * Uses EnhancedSlider for professional control.
 */
public class PostProcessingPanel extends ScrollPane implements Refreshable {

    private final PostProcessParams params;
    private final Supplier<AbstractFractalParams> fractalParamsSupplier;
    private final Runnable onUpdate;
    private boolean suppressRender = false;

    // Tone mapping
    private ComboBox<String> toneMapCombo;
    private EnhancedSlider exposureSlider;
    private EnhancedSlider saturationSlider;
    
    // Color Grading
    private ComboBox<String> gradingCombo;
    private EnhancedSlider gradingIntensitySlider;

    // Bloom
    private CheckBox bloomCheck;
    private EnhancedSlider bloomIntensitySlider;
    private EnhancedSlider bloomThresholdSlider;
    private EnhancedSlider bloomRadiusSlider;

    // Lens Effects
    private CheckBox lensEffectsCheck;
    private EnhancedSlider lensDirtSlider;
    private EnhancedSlider starburstSlider;

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

    public PostProcessingPanel(PostProcessParams params, Supplier<AbstractFractalParams> fractalParamsSupplier, Runnable onUpdate) {
        this.params = params;
        this.fractalParamsSupplier = fractalParamsSupplier;
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
        
        // Color Grading
        panel.getChildren().add(createColorGradingSection());
        panel.getChildren().add(new Separator());

        // Bloom
        panel.getChildren().add(createBloomSection());
        panel.getChildren().add(new Separator());

        // Lens Effects
        panel.getChildren().add(createLensEffectsSection());
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
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) {
                afp.setVolumetricFogEnabled(true);
                afp.setFogDensity(0.12f);
                afp.setFogColor(0.5f, 0.6f, 0.7f);
                afp.setFogScattering(0.6f);
                afp.setLensEffectsEnabled(true);
                afp.setLensDirtIntensity(params.lensDirtIntensity);
                afp.setStarburstIntensity(params.starburstIntensity);
            }
            refreshUI();
            onUpdate.run();
        });

        Button cleanBtn = new Button("Clean");
        cleanBtn.setOnAction(e -> {
            params.applyCleanPreset();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) {
                afp.setVolumetricFogEnabled(false);
                afp.setLensEffectsEnabled(false);
                afp.setLensDirtIntensity(0.0f);
                afp.setStarburstIntensity(0.0f);
            }
            refreshUI();
            onUpdate.run();
        });

        Button vibrantBtn = new Button("Vibrant");
        vibrantBtn.setOnAction(e -> {
            params.applyVibrantPreset();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) {
                afp.setVolumetricFogEnabled(true);
                afp.setFogDensity(0.25f);
                afp.setFogColor(0.8f, 0.6f, 0.4f);
                afp.setFogScattering(0.8f);
                afp.setLensEffectsEnabled(true);
                afp.setLensDirtIntensity(params.lensDirtIntensity);
                afp.setStarburstIntensity(params.starburstIntensity);
            }
            refreshUI();
            onUpdate.run();
        });

        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> {
            params.reset();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) {
                afp.setVolumetricFogEnabled(false);
                afp.setLensEffectsEnabled(false);
                afp.setLensDirtIntensity(0.0f);
                afp.setStarburstIntensity(0.0f);
            }
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

    private VBox createColorGradingSection() {
        VBox section = new VBox(5);

        Label titleLabel = new Label("Color Grading (LUT Styles)");
        titleLabel.setStyle("-fx-font-weight: bold;");

        // Grading Style
        HBox styleBox = new HBox(10);
        Label styleLabel = new Label("Style:");
        gradingCombo = new ComboBox<>();
        gradingCombo.getItems().addAll("None", "Cinema (Teal/Orange)", "Vintage", "Matrix", "Neon", "Noir (B&W)");
        gradingCombo.getSelectionModel().select(params.colorGradingMode);
        gradingCombo.setOnAction(e -> {
            params.colorGradingMode = gradingCombo.getSelectionModel().getSelectedIndex();
            onUpdate.run();
        });
        styleBox.getChildren().addAll(styleLabel, gradingCombo);

        // Intensity
        gradingIntensitySlider = new EnhancedSlider("Intensity", 0.0, 1.0, params.colorGradingIntensity, false);
        gradingIntensitySlider.setPrecision(2);
        gradingIntensitySlider.setOnAction(v -> { params.colorGradingIntensity = v.floatValue(); onUpdate.run(); });

        section.getChildren().addAll(titleLabel, styleBox, gradingIntensitySlider);
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

    private VBox createLensEffectsSection() {
        VBox section = new VBox(5);

        lensEffectsCheck = new CheckBox("Lens Effects (JJ Abrams Style)");
        lensEffectsCheck.setSelected(params.lensEffectsEnabled);
        lensEffectsCheck.setStyle("-fx-font-weight: bold;");
        lensEffectsCheck.setOnAction(e -> {
            params.lensEffectsEnabled = lensEffectsCheck.isSelected();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) afp.setLensEffectsEnabled(lensEffectsCheck.isSelected());
            onUpdate.run();
        });

        lensDirtSlider = new EnhancedSlider("Lens Dirt Intensity", 0.0, 2.0, params.lensDirtIntensity, false);
        lensDirtSlider.setPrecision(2);
        lensDirtSlider.setOnAction(v -> {
            params.lensDirtIntensity = v.floatValue();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) afp.setLensDirtIntensity(v.floatValue());
            onUpdate.run();
        });

        starburstSlider = new EnhancedSlider("Starburst Intensity", 0.0, 1.0, params.starburstIntensity, false);
        starburstSlider.setPrecision(2);
        starburstSlider.setOnAction(v -> {
            params.starburstIntensity = v.floatValue();
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) afp.setStarburstIntensity(v.floatValue());
            onUpdate.run();
        });

        section.getChildren().addAll(lensEffectsCheck, lensDirtSlider, starburstSlider);
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
     * Refresh all UI controls from current params.
     */
    @Override
    public void refreshFromParams(boolean suppress) {
        this.suppressRender = suppress;
        try {
            // Sync from AbstractFractalParams to PostProcessParams for lens effects
            AbstractFractalParams afp = fractalParamsSupplier.get();
            if (afp != null) {
                params.lensEffectsEnabled = afp.isLensEffectsEnabled();
                params.lensDirtIntensity = afp.getLensDirtIntensity();
                params.starburstIntensity = afp.getStarburstIntensity();
            }
            refreshUI();
        } finally {
            this.suppressRender = false;
        }
    }

    /**
     * Refresh UI to match current params (after preset change).
     */
    public void refreshUI() {
        toneMapCombo.setValue(getToneMapName(params.toneMapMode));
        exposureSlider.setValue(params.exposure);
        saturationSlider.setValue(params.saturation);
        
        gradingCombo.getSelectionModel().select(params.colorGradingMode);
        gradingIntensitySlider.setValue(params.colorGradingIntensity);

        bloomCheck.setSelected(params.bloomEnabled);
        bloomIntensitySlider.setValue(params.bloomIntensity);
        bloomThresholdSlider.setValue(params.bloomThreshold);
        bloomRadiusSlider.setValue(params.bloomRadius);

        lensEffectsCheck.setSelected(params.lensEffectsEnabled);
        lensDirtSlider.setValue(params.lensDirtIntensity);
        starburstSlider.setValue(params.starburstIntensity);

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