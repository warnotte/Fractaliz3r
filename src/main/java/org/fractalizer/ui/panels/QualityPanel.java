package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.components.EnhancedSlider;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel for quality settings: shadows, AO, specular, DoF, render modes.
 * Uses collapsible TitledPane sections for better organization.
 * Implements Refreshable for save/load configuration support.
 */
public class QualityPanel extends ScrollPane implements Refreshable {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;
    private final Consumer<Boolean> autoFullQualityCallback;

    // Suppress render during refresh
    private boolean suppressRender = false;

    // Render pass
    private ComboBox<String> passCombo;
    
    // Projection mode
    private ComboBox<String> projectionCombo;

    // Quality
    private EnhancedSlider qualitySlider;

    // Shadows
    private EnhancedSlider shadowSoftnessSlider;
    private EnhancedSlider shadowStepsSlider;

    // AO
    private EnhancedSlider aoIntensitySlider;

    // Glow
    private EnhancedSlider glowIntensitySlider;

    // DoF controls
    private CheckBox dofEnabledCheck;
    private EnhancedSlider focalDistSlider;
    private EnhancedSlider apertureSlider;
    private EnhancedSlider dofSamplesSlider;

    // Path tracing
    private CheckBox pathTracingCheck;
    private EnhancedSlider bouncesSlider;
    private EnhancedSlider skyIntensitySlider;
    private EnhancedSlider indirectSlider;

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
        panel.getChildren().add(createProjectionSection());
        panel.getChildren().add(createQualitySection());

        // Collapsible sections
        panel.getChildren().add(createShadowsPane());
        panel.getChildren().add(createAOPane());
        panel.getChildren().add(createGlowPane());
        panel.getChildren().add(createDoFPane());
        panel.getChildren().add(createPathTracingPane());
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

        passCombo = new ComboBox<>();
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
            if (!suppressRender) {
                int mode = passCombo.getSelectionModel().getSelectedIndex();
                getParams().setRenderMode(mode);
                renderCallback.requestRender();
            }
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

    private VBox createProjectionSection() {
        VBox box = new VBox(5);

        Label projLabel = new Label("Camera Projection:");
        projLabel.setStyle("-fx-font-weight: bold;");

        projectionCombo = new ComboBox<>();
        projectionCombo.getItems().addAll(
            "Perspective (Standard)",
            "360° Equirectangular (VR)"
        );
        projectionCombo.getSelectionModel().select(0);
        projectionCombo.setMaxWidth(Double.MAX_VALUE);
        projectionCombo.setOnAction(e -> {
            if (!suppressRender) {
                int mode = projectionCombo.getSelectionModel().getSelectedIndex();
                getParams().setProjectionMode(mode);
                renderCallback.requestRender();
            }
        });

        Label infoLabel = new Label("360° mode creates a spherical map.\nUse 2:1 aspect ratio for best results.");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(projLabel, projectionCombo, infoLabel);
        return box;
    }

    private TitledPane createQualitySection() {
        VBox box = new VBox(5);

        qualitySlider = new EnhancedSlider("Quality Multiplier", 0.5, 5.0, 1.0, false);
        qualitySlider.showTickMarks(true);
        qualitySlider.setMajorTickUnit(1.0);
        qualitySlider.setPrecision(1);
        qualitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setQualityMultiplier(v.floatValue());
                renderCallback.requestRender();
            }
        });

        Label infoLabel = new Label("Higher = more detail when close to surface.\nWarning: >2x is slow!");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(qualitySlider, infoLabel);

        TitledPane pane = new TitledPane("Quality Settings", box);
        pane.setExpanded(false);
        return pane;
    }

    // ========================================================================
    // Collapsible sections
    // ========================================================================

    private TitledPane createShadowsPane() {
        VBox box = new VBox(5);

        shadowSoftnessSlider = new EnhancedSlider("Shadow Softness", 1, 64, 16, false);
        shadowSoftnessSlider.setPrecision(0);
        shadowSoftnessSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().shadowSoftness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        shadowStepsSlider = new EnhancedSlider("Shadow Steps", 32, 256, 128, true);
        shadowStepsSlider.showTickMarks(true);
        shadowStepsSlider.setMajorTickUnit(64);
        shadowStepsSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setShadowSteps(v.intValue());
                renderCallback.requestRender();
            }
        });

        box.getChildren().addAll(shadowSoftnessSlider, shadowStepsSlider);

        TitledPane pane = new TitledPane("Shadows", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createAOPane() {
        VBox box = new VBox(5);

        aoIntensitySlider = new EnhancedSlider("AO Intensity", 0, 1, 0.5, false);
        aoIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().aoIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        box.getChildren().addAll(aoIntensitySlider);

        TitledPane pane = new TitledPane("Ambient Occlusion", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createGlowPane() {
        VBox box = new VBox(5);

        glowIntensitySlider = new EnhancedSlider("Glow Intensity", 0, 1, 0.15, false);
        glowIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().glowIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        box.getChildren().addAll(glowIntensitySlider);

        TitledPane pane = new TitledPane("Glow", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createDoFPane() {
        VBox box = new VBox(5);

        dofEnabledCheck = new CheckBox("Enable DoF");
        dofEnabledCheck.setSelected(false);
        dofEnabledCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setDofEnabled(dofEnabledCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        focalDistSlider = new EnhancedSlider("Focal Distance", 0.1, 10, 2.5, false);
        focalDistSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setFocalDistance(v.floatValue());
                renderCallback.requestRender();
            }
        });

        apertureSlider = new EnhancedSlider("Aperture", 0, 0.2, 0.02, false);
        apertureSlider.setPrecision(3);
        apertureSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setAperture(v.floatValue());
                renderCallback.requestRender();
            }
        });

        dofSamplesSlider = new EnhancedSlider("DoF Samples", 4, 64, 16, true);
        dofSamplesSlider.showTickMarks(true);
        dofSamplesSlider.setMajorTickUnit(16);
        dofSamplesSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setDofSamples(v.intValue());
                renderCallback.requestRender();
            }
        });

        Label dofInfoLabel = new Label("Middle-click or Ctrl+click to pick\nfocal distance from the image.");
        dofInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(dofEnabledCheck, focalDistSlider,
                apertureSlider, dofSamplesSlider, dofInfoLabel);

        TitledPane pane = new TitledPane("Depth of Field", box);
        pane.setExpanded(false);
        return pane;
    }

    /**
     * Update the focal distance display from an external source (e.g., click-to-focus).
     * @param distance The new focal distance value
     */
    public void updateFocalDistanceDisplay(float distance) {
        if (focalDistSlider != null) {
            // Clamp to slider range
            double clampedDistance = Math.max(focalDistSlider.getSlider().getMin(),
                    Math.min(focalDistSlider.getSlider().getMax(), distance));
            focalDistSlider.setValue(clampedDistance);
        }
    }

    private TitledPane createPathTracingPane() {
        VBox box = new VBox(5);

        pathTracingCheck = new CheckBox("Enable Path Tracing");
        pathTracingCheck.setSelected(false);
        pathTracingCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setPathTracingEnabled(pathTracingCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        bouncesSlider = new EnhancedSlider("Max Bounces", 1, 8, 4, true);
        bouncesSlider.showTickMarks(true);
        bouncesSlider.setMajorTickUnit(1.0);
        bouncesSlider.getSlider().setSnapToTicks(true);
        bouncesSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setMaxBounces(v.intValue());
                renderCallback.requestRender();
            }
        });

        skyIntensitySlider = new EnhancedSlider("Sky Intensity", 0.0, 3.0, 1.0, false);
        skyIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSkyIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        indirectSlider = new EnhancedSlider("Indirect Light %", 0.0, 100.0, 50.0, true);
        indirectSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setIndirectMultiplier(v.floatValue() / 100.0f);
                renderCallback.requestRender();
            }
        });

        Label indirectInfo = new Label("0% = Hard shadows (direct only)\n100% = Soft shadows (full GI)");
        indirectInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label pathTracingInfo = new Label("Path tracing adds global illumination.\nSlower but more realistic.");
        pathTracingInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        box.getChildren().addAll(pathTracingCheck, bouncesSlider,
                skyIntensitySlider, indirectSlider,
                indirectInfo, pathTracingInfo);

        TitledPane pane = new TitledPane("Path Tracing (GI)", box);
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
        
        // Update UI state
        refreshFromParams(true);
        
        renderCallback.requestRender();
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }

    /**
     * Refresh all UI controls from current params.
     * Called after loading a configuration.
     */
    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress;
        try {
            AbstractFractalParams p = getParams();

            // Render mode
            passCombo.getSelectionModel().select(p.getRenderMode());
            
            // Projection mode
            projectionCombo.getSelectionModel().select(p.getProjectionMode());

            // Quality
            qualitySlider.setValue(p.getQualityMultiplier());

            // Shadows
            shadowSoftnessSlider.setValue(p.getShadowSoftness());
            shadowStepsSlider.setValue(p.getShadowSteps());

            // AO
            aoIntensitySlider.setValue(p.getAoIntensity());

            // Glow
            glowIntensitySlider.setValue(p.getGlowIntensity());

            // DoF
            dofEnabledCheck.setSelected(p.isDofEnabled());
            focalDistSlider.setValue(p.getFocalDistance());
            apertureSlider.setValue(p.getAperture());
            dofSamplesSlider.setValue(p.getDofSamples());

            // Path tracing
            pathTracingCheck.setSelected(p.isPathTracingEnabled());
            bouncesSlider.setValue(p.getMaxBounces());
            skyIntensitySlider.setValue(p.getSkyIntensity());
            indirectSlider.setValue(p.getIndirectMultiplier() * 100.0);

        } finally {
            suppressRender = false;
        }
    }
}