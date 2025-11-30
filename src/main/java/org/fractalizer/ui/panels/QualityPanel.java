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
 */
public class QualityPanel extends ScrollPane {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;
    private final Consumer<Boolean> autoFullQualityCallback;

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
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // === Render Pass Visualization ===
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

        // === Auto Full Quality ===
        CheckBox autoFullQualityCheck = new CheckBox("Auto Full Quality (slower)");
        autoFullQualityCheck.setSelected(true);  // Enabled by default
        // Trigger callback to apply the default value
        autoFullQualityCallback.accept(true);
        autoFullQualityCheck.setOnAction(e -> {
            autoFullQualityCallback.accept(autoFullQualityCheck.isSelected());
            renderCallback.requestRender();
        });

        // === Quality Multiplier (Ultimate Quality) ===
        Label qualityLabel = new Label("Quality Multiplier:");
        qualityLabel.setStyle("-fx-font-weight: bold;");

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

        Label qualityInfoLabel = new Label("Higher = more detail when close to surface.\nWarning: >2x is slow!");
        qualityInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // === Shadows ===
        Label shadowLabel = new Label("Shadows:");
        shadowLabel.setStyle("-fx-font-weight: bold;");

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

        // === Ambient Occlusion ===
        Label aoLabel = new Label("Ambient Occlusion:");
        aoLabel.setStyle("-fx-font-weight: bold;");

        Label aoIntLabel = new Label("AO Intensity: 0.5");
        Slider aoIntensitySlider = new Slider(0, 1, 0.5);
        aoIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            aoIntLabel.setText(String.format("AO Intensity: %.2f", val.doubleValue()));
            getParams().aoIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        // === Specular ===
        Label specLabel = new Label("Specular:");
        specLabel.setStyle("-fx-font-weight: bold;");

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

        // === Glow ===
        Label glowLabel = new Label("Glow:");
        glowLabel.setStyle("-fx-font-weight: bold;");

        Label glowIntLabel = new Label("Glow Intensity: 0.15");
        Slider glowIntensitySlider = new Slider(0, 1, 0.15);
        glowIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            glowIntLabel.setText(String.format("Glow Intensity: %.2f", val.doubleValue()));
            getParams().glowIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        // === Depth of Field ===
        Label dofLabel = new Label("Depth of Field:");
        dofLabel.setStyle("-fx-font-weight: bold;");

        CheckBox dofEnabledCheck = new CheckBox("Enable DoF");
        dofEnabledCheck.setSelected(false);
        dofEnabledCheck.setOnAction(e -> {
            getParams().setDofEnabled(dofEnabledCheck.isSelected());
            renderCallback.requestRender();
        });

        Label focalDistLabel = new Label("Focal Distance: 2.5");
        Slider focalDistSlider = new Slider(0.1, 10, 2.5);
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

        Label dofInfoLabel = new Label("Note: DoF is slow. Use low samples\nfor preview, increase for final.");
        dofInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // === Path Tracing ===
        Label pathTracingLabel = new Label("Path Tracing (GI):");
        pathTracingLabel.setStyle("-fx-font-weight: bold;");

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

        Label pathTracingInfo = new Label("Path tracing adds global illumination\n(soft indirect lighting). Slower but\nmore realistic. Works best with\nmany samples.");
        pathTracingInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Quality presets
        Label presetLabel = new Label("Quality Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        HBox presetBox = new HBox(5);
        Button fastBtn = new Button("Fast");
        fastBtn.setOnAction(e -> {
            qualitySlider.setValue(0.5);
            shadowSoftnessSlider.setValue(8);
            aoIntensitySlider.setValue(0.3);
            specularIntensitySlider.setValue(0.3);
        });
        Button balancedBtn = new Button("Balanced");
        balancedBtn.setOnAction(e -> {
            qualitySlider.setValue(1.0);
            shadowSoftnessSlider.setValue(16);
            aoIntensitySlider.setValue(0.5);
            specularIntensitySlider.setValue(0.5);
        });
        Button highBtn = new Button("High");
        highBtn.setOnAction(e -> {
            qualitySlider.setValue(2.0);
            shadowSoftnessSlider.setValue(32);
            aoIntensitySlider.setValue(0.7);
            specularIntensitySlider.setValue(0.6);
        });
        Button ultimateBtn = new Button("Ultimate");
        ultimateBtn.setOnAction(e -> {
            qualitySlider.setValue(5.0);
            shadowSoftnessSlider.setValue(48);
            aoIntensitySlider.setValue(0.8);
            specularIntensitySlider.setValue(0.7);
        });
        presetBox.getChildren().addAll(fastBtn, balancedBtn, highBtn, ultimateBtn);

        panel.getChildren().addAll(
            passLabel, passCombo,
            autoFullQualityCheck,
            new Separator(),
            qualityLabel, qualityValueLabel, qualitySlider, qualityInfoLabel,
            new Separator(),
            shadowLabel, shadowSoftLabel, shadowSoftnessSlider,
            shadowStepsLabel, shadowStepsSlider,
            new Separator(),
            aoLabel, aoIntLabel, aoIntensitySlider,
            new Separator(),
            specLabel,
            specIntLabel, specularIntensitySlider,
            specPowLabel, specularPowerSlider,
            new Separator(),
            glowLabel, glowIntLabel, glowIntensitySlider,
            new Separator(),
            dofLabel, dofEnabledCheck,
            focalDistLabel, focalDistSlider,
            apertureLabel, apertureSlider,
            dofSamplesLabel, dofSamplesSlider,
            dofInfoLabel,
            new Separator(),
            pathTracingLabel, pathTracingCheck,
            bouncesLabel, bouncesSlider,
            skyIntensityLabel, skyIntensitySlider,
            pathTracingInfo,
            new Separator(),
            presetLabel, presetBox
        );

        return panel;
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }
}
