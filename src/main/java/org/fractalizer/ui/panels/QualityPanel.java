package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fractalizer.fractals.AbstractFractalParams;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel for quality settings: shadows, AO, specular, DoF, render modes.
 * Uses collapsible TitledPane sections for better organization.
 */
public class QualityPanel extends ScrollPane {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;
    private final Consumer<Boolean> autoFullQualityCallback;

    // DoF controls (stored for external updates)
    private Slider focalDistSlider;
    private Label focalDistLabel;

    public QualityPanel(Supplier<AbstractFractalParams> paramsSupplier,
                        RenderCallback renderCallback,
                        Consumer<Boolean> autoFullQualityCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;
        this.autoFullQualityCallback = autoFullQualityCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(10));

        // Top-level controls (always visible)
        panel.getChildren().add(createRenderPassSection());
        panel.getChildren().add(createQualitySection());

        // Collapsible sections
        panel.getChildren().add(createShadowsPane());
        panel.getChildren().add(createAOPane());
        panel.getChildren().add(createSpecularPane());
        panel.getChildren().add(createGlowPane());
        panel.getChildren().add(createDoFPane());
        panel.getChildren().add(createPathTracingPane());
        panel.getChildren().add(createMaterialPane());
        panel.getChildren().add(createPresetsPane());

        return panel;
    }

    // ========================================================================
    // Top-level sections (always visible)
    // ========================================================================

    private VBox createRenderPassSection() {
        VBox box = new VBox(5);

        Label passLabel = new Label("Render Pass:");
        passLabel.setStyle("-fx-font-weight: bold;");

        ComboBox<String> passCombo = new ComboBox<>();
        passCombo.getItems().addAll(
            "Final (Complete)",
            "Normals",
            "Depth",
            "Ambient Occlusion",
            "Shadows",
            "Diffuse",
            "Specular",
            "Orbit Trap (Colors)",
            "Iterations"
        );
        passCombo.setValue("Final (Complete)");
        passCombo.setMaxWidth(Double.MAX_VALUE);
        passCombo.setOnAction(e -> {
            int mode = passCombo.getSelectionModel().getSelectedIndex();
            getParams().setRenderMode(mode);
            renderCallback.requestRender();
        });

        CheckBox autoFullQualityCheck = new CheckBox("Auto Full Quality (slower)");
        autoFullQualityCheck.setSelected(true);
        autoFullQualityCallback.accept(true);
        autoFullQualityCheck.setOnAction(e -> {
            autoFullQualityCallback.accept(autoFullQualityCheck.isSelected());
            renderCallback.requestRender();
        });

        box.getChildren().addAll(passLabel, passCombo, autoFullQualityCheck);
        return box;
    }

    private TitledPane createQualitySection() {
        VBox box = new VBox(5);

        Label qualityValueLabel = new Label("Quality: 1.0x (Normal)");
        Slider qualitySlider = new Slider(0.5, 5.0, 1.0);
        qualitySlider.setMajorTickUnit(1.0);
        qualitySlider.setShowTickLabels(true);
        qualitySlider.valueProperty().addListener((obs, old, val) -> {
            float q = val.floatValue();
            String desc;
            if (q < 0.8f) desc = "Fast Preview";
            else if (q < 1.2f) desc = "Normal";
            else if (q < 2.0f) desc = "High";
            else if (q < 3.0f) desc = "Ultra";
            else desc = "Ultimate";
            qualityValueLabel.setText(String.format("Quality: %.1fx (%s)", q, desc));
            getParams().setQualityMultiplier(q);
            renderCallback.requestRender();
        });

        Label infoLabel = new Label("Higher = more detail when close to surface.\nWarning: >2x is slow!");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(qualityValueLabel, qualitySlider, infoLabel);

        TitledPane pane = new TitledPane("Quality Multiplier", box);
        pane.setExpanded(false);
        return pane;
    }

    // ========================================================================
    // Collapsible sections
    // ========================================================================

    private TitledPane createShadowsPane() {
        VBox box = new VBox(5);

        Label shadowSoftLabel = new Label("Shadow Softness: 16");
        Slider shadowSoftnessSlider = new Slider(1, 64, 16);
        shadowSoftnessSlider.valueProperty().addListener((obs, old, val) -> {
            shadowSoftLabel.setText(String.format("Shadow Softness: %.0f", val.doubleValue()));
            getParams().shadowSoftness(val.floatValue());
            renderCallback.requestRender();
        });

        Label shadowStepsLabel = new Label("Shadow Steps: 128");
        Slider shadowStepsSlider = new Slider(32, 256, 128);
        shadowStepsSlider.setMajorTickUnit(64);
        shadowStepsSlider.setShowTickLabels(true);
        shadowStepsSlider.valueProperty().addListener((obs, old, val) -> {
            int steps = val.intValue();
            shadowStepsLabel.setText(String.format("Shadow Steps: %d", steps));
            getParams().setShadowSteps(steps);
            renderCallback.requestRender();
        });

        box.getChildren().addAll(shadowSoftLabel, shadowSoftnessSlider, shadowStepsLabel, shadowStepsSlider);

        TitledPane pane = new TitledPane("Shadows", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createAOPane() {
        VBox box = new VBox(5);

        Label aoIntLabel = new Label("AO Intensity: 0.5");
        Slider aoIntensitySlider = new Slider(0, 1, 0.5);
        aoIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            aoIntLabel.setText(String.format("AO Intensity: %.2f", val.doubleValue()));
            getParams().aoIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        box.getChildren().addAll(aoIntLabel, aoIntensitySlider);

        TitledPane pane = new TitledPane("Ambient Occlusion", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createSpecularPane() {
        VBox box = new VBox(5);

        Label specIntLabel = new Label("Specular Intensity: 0.5");
        Slider specularIntensitySlider = new Slider(0, 2, 0.5);
        specularIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            specIntLabel.setText(String.format("Specular Intensity: %.2f", val.doubleValue()));
            getParams().specularIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        Label specPowLabel = new Label("Specular Power: 32");
        Slider specularPowerSlider = new Slider(4, 128, 32);
        specularPowerSlider.valueProperty().addListener((obs, old, val) -> {
            specPowLabel.setText(String.format("Specular Power: %.0f", val.doubleValue()));
            getParams().specularPower(val.floatValue());
            renderCallback.requestRender();
        });

        box.getChildren().addAll(specIntLabel, specularIntensitySlider, specPowLabel, specularPowerSlider);

        TitledPane pane = new TitledPane("Specular", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createGlowPane() {
        VBox box = new VBox(5);

        Label glowIntLabel = new Label("Glow Intensity: 0.15");
        Slider glowIntensitySlider = new Slider(0, 1, 0.15);
        glowIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            glowIntLabel.setText(String.format("Glow Intensity: %.2f", val.doubleValue()));
            getParams().glowIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        box.getChildren().addAll(glowIntLabel, glowIntensitySlider);

        TitledPane pane = new TitledPane("Glow", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createDoFPane() {
        VBox box = new VBox(5);

        CheckBox dofEnabledCheck = new CheckBox("Enable DoF");
        dofEnabledCheck.setSelected(false);
        dofEnabledCheck.setOnAction(e -> {
            getParams().setDofEnabled(dofEnabledCheck.isSelected());
            renderCallback.requestRender();
        });

        focalDistLabel = new Label("Focal Distance: 2.5");
        focalDistSlider = new Slider(0.1, 10, 2.5);
        focalDistSlider.valueProperty().addListener((obs, old, val) -> {
            focalDistLabel.setText(String.format("Focal Distance: %.2f", val.doubleValue()));
            getParams().setFocalDistance(val.floatValue());
            renderCallback.requestRender();
        });

        Label apertureLabel = new Label("Aperture: 0.02");
        Slider apertureSlider = new Slider(0, 0.2, 0.02);
        apertureSlider.valueProperty().addListener((obs, old, val) -> {
            apertureLabel.setText(String.format("Aperture: %.3f", val.doubleValue()));
            getParams().setAperture(val.floatValue());
            renderCallback.requestRender();
        });

        Label dofSamplesLabel = new Label("DoF Samples: 16");
        Slider dofSamplesSlider = new Slider(4, 64, 16);
        dofSamplesSlider.setMajorTickUnit(16);
        dofSamplesSlider.setShowTickLabels(true);
        dofSamplesSlider.valueProperty().addListener((obs, old, val) -> {
            int samples = val.intValue();
            dofSamplesLabel.setText(String.format("DoF Samples: %d", samples));
            getParams().setDofSamples(samples);
            renderCallback.requestRender();
        });

        Label dofInfoLabel = new Label("Middle-click or Ctrl+click to pick\nfocal distance from the image.");
        dofInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(dofEnabledCheck, focalDistLabel, focalDistSlider,
                apertureLabel, apertureSlider, dofSamplesLabel, dofSamplesSlider, dofInfoLabel);

        TitledPane pane = new TitledPane("Depth of Field", box);
        pane.setExpanded(false);
        return pane;
    }

    /**
     * Update the focal distance display from an external source (e.g., click-to-focus).
     * @param distance The new focal distance value
     */
    public void updateFocalDistanceDisplay(float distance) {
        if (focalDistSlider != null && focalDistLabel != null) {
            // Clamp to slider range
            double clampedDistance = Math.max(focalDistSlider.getMin(),
                    Math.min(focalDistSlider.getMax(), distance));
            focalDistSlider.setValue(clampedDistance);
            focalDistLabel.setText(String.format("Focal Distance: %.2f", clampedDistance));
        }
    }

    private TitledPane createPathTracingPane() {
        VBox box = new VBox(5);

        CheckBox pathTracingCheck = new CheckBox("Enable Path Tracing");
        pathTracingCheck.setSelected(false);
        pathTracingCheck.setOnAction(e -> {
            getParams().setPathTracingEnabled(pathTracingCheck.isSelected());
            renderCallback.requestRender();
        });

        Label bouncesLabel = new Label("Max Bounces: 4");
        Slider bouncesSlider = new Slider(1, 8, 4);
        bouncesSlider.setMajorTickUnit(1);
        bouncesSlider.setMinorTickCount(0);
        bouncesSlider.setSnapToTicks(true);
        bouncesSlider.setShowTickLabels(true);
        bouncesSlider.valueProperty().addListener((obs, old, val) -> {
            int bounces = val.intValue();
            bouncesLabel.setText(String.format("Max Bounces: %d", bounces));
            getParams().setMaxBounces(bounces);
            renderCallback.requestRender();
        });

        Label skyIntensityLabel = new Label("Sky Intensity: 1.0");
        Slider skyIntensitySlider = new Slider(0.0, 3.0, 1.0);
        skyIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            skyIntensityLabel.setText(String.format("Sky Intensity: %.2f", val.doubleValue()));
            getParams().setSkyIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        Label indirectLabel = new Label("Indirect Light: 50%");
        Slider indirectSlider = new Slider(0.0, 1.0, 0.5);
        indirectSlider.valueProperty().addListener((obs, old, val) -> {
            indirectLabel.setText(String.format("Indirect Light: %.0f%%", val.doubleValue() * 100));
            getParams().setIndirectMultiplier(val.floatValue());
            renderCallback.requestRender();
        });

        Label indirectInfo = new Label("0% = Hard shadows (direct only)\n100% = Soft shadows (full GI)");
        indirectInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label pathTracingInfo = new Label("Path tracing adds global illumination.\nSlower but more realistic.");
        pathTracingInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(pathTracingCheck, bouncesLabel, bouncesSlider,
                skyIntensityLabel, skyIntensitySlider, indirectLabel, indirectSlider,
                indirectInfo, pathTracingInfo);

        TitledPane pane = new TitledPane("Path Tracing (GI)", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createMaterialPane() {
        VBox box = new VBox(5);

        ComboBox<String> materialCombo = new ComboBox<>();
        materialCombo.getItems().addAll("Lambertian (Diffuse)", "Metallic", "Glass");
        materialCombo.getSelectionModel().select(0);
        materialCombo.setMaxWidth(Double.MAX_VALUE);
        materialCombo.setOnAction(e -> {
            getParams().setMaterialType(materialCombo.getSelectionModel().getSelectedIndex());
            renderCallback.requestRender();
        });

        Label metalnessLabel = new Label("Metalness: 0.90");
        Slider metalnessSlider = new Slider(0.0, 1.0, 0.9);
        metalnessSlider.valueProperty().addListener((obs, old, val) -> {
            metalnessLabel.setText(String.format("Metalness: %.2f", val.doubleValue()));
            getParams().setMetalness(val.floatValue());
            renderCallback.requestRender();
        });

        Label iorLabel = new Label("IOR (Glass): 1.50");
        Slider iorSlider = new Slider(1.0, 2.5, 1.5);
        iorSlider.valueProperty().addListener((obs, old, val) -> {
            iorLabel.setText(String.format("IOR (Glass): %.2f", val.doubleValue()));
            getParams().setIor(val.floatValue());
            renderCallback.requestRender();
        });

        Label roughnessLabel = new Label("Roughness: 0.50");
        Slider roughnessSlider = new Slider(0.0, 1.0, 0.5);
        roughnessSlider.valueProperty().addListener((obs, old, val) -> {
            roughnessLabel.setText(String.format("Roughness: %.2f", val.doubleValue()));
            getParams().setRoughness(val.floatValue());
            renderCallback.requestRender();
        });

        Label materialInfo = new Label(
            "Lambertian: Classic diffuse\n" +
            "Metallic: Reflective with roughness\n" +
            "Glass: Transparent with refraction"
        );
        materialInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(materialCombo, metalnessLabel, metalnessSlider,
                iorLabel, iorSlider, roughnessLabel, roughnessSlider, materialInfo);

        TitledPane pane = new TitledPane("Materials", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createPresetsPane() {
        VBox box = new VBox(5);

        HBox presetBox = new HBox(5);

        Button fastBtn = new Button("Fast");
        fastBtn.setOnAction(e -> applyPreset(0.5f, 8, 0.3f, 0.3f));

        Button balancedBtn = new Button("Balanced");
        balancedBtn.setOnAction(e -> applyPreset(1.0f, 16, 0.5f, 0.5f));

        Button highBtn = new Button("High");
        highBtn.setOnAction(e -> applyPreset(2.0f, 32, 0.7f, 0.6f));

        Button ultimateBtn = new Button("Ultimate");
        ultimateBtn.setOnAction(e -> applyPreset(5.0f, 48, 0.8f, 0.7f));

        presetBox.getChildren().addAll(fastBtn, balancedBtn, highBtn, ultimateBtn);
        box.getChildren().add(presetBox);

        TitledPane pane = new TitledPane("Quality Presets", box);
        pane.setExpanded(false);
        return pane;
    }

    private void applyPreset(float quality, float shadowSoftness, float aoIntensity, float specIntensity) {
        AbstractFractalParams params = getParams();
        params.setQualityMultiplier(quality);
        params.shadowSoftness(shadowSoftness);
        params.aoIntensity(aoIntensity);
        params.specularIntensity(specIntensity);
        renderCallback.requestRender();
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }
}
