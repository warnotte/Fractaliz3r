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

    // Render samples callbacks
    private Consumer<Integer> fullSamplesCallback;
    private Supplier<Integer> fullSamplesSupplier;


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
    private EnhancedSlider anamorphicSlider;
    private ComboBox<String> bokehShapeCombo;
    private EnhancedSlider bokehRotationSlider;
    private EnhancedSlider opticalVignettingSlider;
    private CheckBox tiltShiftCheck;
    private EnhancedSlider tiltAngleXSlider;
    private EnhancedSlider tiltAngleYSlider;
    private EnhancedSlider dofChromaticSlider;

    // Path tracing
    private CheckBox pathTracingCheck;
    private CheckBox neeEnabledCheck;
    private EnhancedSlider renderSamplesSlider;
    private EnhancedSlider bouncesSlider;
    private EnhancedSlider skyIntensitySlider;
    private EnhancedSlider indirectSlider;

    // Raymarcher
    private CheckBox coneTracingCheck;
    private EnhancedSlider fudgeFactorSlider;
    private EnhancedSlider refinementStepsSlider;
    private EnhancedSlider stepRelaxationSlider;

    // Adaptive sampling
    private CheckBox adaptiveSamplingCheck;
    private EnhancedSlider varianceThresholdSlider;
    private EnhancedSlider minAdaptiveSamplesSlider;


    public QualityPanel(Supplier<AbstractFractalParams> paramsSupplier,
                        RenderCallback renderCallback,
                        Consumer<Boolean> autoFullQualityCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;
        this.autoFullQualityCallback = autoFullQualityCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    /**
     * Set callbacks for controlling render samples (fullSamples in the controller).
     */
    public void setFullSamplesCallbacks(Consumer<Integer> setter, Supplier<Integer> getter) {
        this.fullSamplesCallback = setter;
        this.fullSamplesSupplier = getter;
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
        panel.getChildren().add(createRaymarcherPane());
        panel.getChildren().add(createAdaptiveSamplingPane());
        // Erosion / Crystal / Moss removed — now per-node via EffectNode in Node Graph
        panel.getChildren().add(createPresetsPane());

        return panel;
    }

    // ========================================================================
    // Top-level sections (always visible)
    // ========================================================================

    private VBox createRenderPassSection() {
        VBox box = new VBox(5);

        Label passLabel = new Label("Render Pass:");
        passLabel.getStyleClass().add("bold-label");

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
        projLabel.getStyleClass().add("bold-label");

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
        infoLabel.getStyleClass().add("hint-label");

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
        infoLabel.getStyleClass().add("hint-label");

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
        dofInfoLabel.getStyleClass().add("hint-label");

        // --- Cinematic Lens Simulation ---
        Label lensLabel = new Label("Cinematic Lens");
        lensLabel.getStyleClass().add("bold-label");

        anamorphicSlider = new EnhancedSlider("Anamorphic Ratio", 0.3, 1.0, 1.0, false);
        anamorphicSlider.setPrecision(2);
        anamorphicSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setAnamorphicRatio(v.floatValue());
                renderCallback.requestRender();
            }
        });

        Label bokehShapeLabel = new Label("Bokeh Shape:");
        bokehShapeCombo = new ComboBox<>();
        bokehShapeCombo.getItems().addAll(
            "Circle", "Triangle (3)", "Square (4)", "Pentagon (5)",
            "Hexagon (6)", "Heptagon (7)", "Octagon (8)"
        );
        bokehShapeCombo.getSelectionModel().select(0);
        bokehShapeCombo.setMaxWidth(Double.MAX_VALUE);
        bokehShapeCombo.setOnAction(e -> {
            if (!suppressRender) {
                int idx = bokehShapeCombo.getSelectionModel().getSelectedIndex();
                int blades = (idx == 0) ? 0 : idx + 2; // 0->0, 1->3, 2->4, ..., 6->8
                getParams().setBokehBlades(blades);
                renderCallback.requestRender();
            }
        });

        bokehRotationSlider = new EnhancedSlider("Bokeh Rotation", 0, 360, 0, true);
        bokehRotationSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setBokehRotation((float) Math.toRadians(v.doubleValue()));
                renderCallback.requestRender();
            }
        });

        opticalVignettingSlider = new EnhancedSlider("Optical Vignetting", 0, 1, 0, false);
        opticalVignettingSlider.setPrecision(2);
        opticalVignettingSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setOpticalVignettingStrength(v.floatValue());
                renderCallback.requestRender();
            }
        });

        tiltShiftCheck = new CheckBox("Tilt-Shift");
        tiltShiftCheck.setSelected(false);
        tiltShiftCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setTiltShiftEnabled(tiltShiftCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        tiltAngleXSlider = new EnhancedSlider("Tilt Angle X", -45, 45, 0, false);
        tiltAngleXSlider.setPrecision(1);
        tiltAngleXSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setTiltAngleX((float) Math.toRadians(v.doubleValue()));
                renderCallback.requestRender();
            }
        });

        tiltAngleYSlider = new EnhancedSlider("Tilt Angle Y", -45, 45, 0, false);
        tiltAngleYSlider.setPrecision(1);
        tiltAngleYSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setTiltAngleY((float) Math.toRadians(v.doubleValue()));
                renderCallback.requestRender();
            }
        });

        dofChromaticSlider = new EnhancedSlider("Longitudinal CA", 0, 0.1, 0, false);
        dofChromaticSlider.setPrecision(3);
        dofChromaticSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setDofChromaticStrength(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // --- Lens Presets ---
        Label presetsLabel = new Label("Lens Presets:");
        presetsLabel.getStyleClass().add("bold-label");

        HBox presetRow1 = new HBox(4);
        HBox presetRow2 = new HBox(4);

        Button stdBtn = new Button("Standard");
        stdBtn.setOnAction(e -> applyLensPreset(1.0f, 0, 0, 0.0f, false, 0, 0, 0.0f));

        Button cinemaBtn = new Button("Cinema");
        cinemaBtn.setOnAction(e -> applyLensPreset(1.0f, 6, 0, 0.2f, false, 0, 0, 0.01f));

        Button anamBtn = new Button("Anamorphic");
        anamBtn.setOnAction(e -> applyLensPreset(0.5f, 0, 0, 0.3f, false, 0, 0, 0.02f));

        Button vintageBtn = new Button("Vintage");
        vintageBtn.setOnAction(e -> applyLensPreset(1.0f, 8, 15, 0.6f, false, 0, 0, 0.04f));

        Button petzvalBtn = new Button("Petzval");
        petzvalBtn.setOnAction(e -> applyLensPreset(1.0f, 0, 0, 0.85f, false, 0, 0, 0.025f));

        Button miniBtn = new Button("Miniature");
        miniBtn.setOnAction(e -> applyLensPreset(1.0f, 0, 0, 0.0f, true, 15, 0, 0.0f));

        Button dreamBtn = new Button("Dream");
        dreamBtn.setOnAction(e -> applyLensPreset(0.6f, 0, 0, 0.4f, false, 0, 0, 0.06f));

        Button nightBtn = new Button("Night");
        nightBtn.setOnAction(e -> applyLensPreset(1.0f, 5, 18, 0.15f, false, 0, 0, 0.008f));

        Button prismBtn = new Button("Prism");
        prismBtn.setOnAction(e -> applyLensPreset(1.0f, 3, 30, 0.0f, false, 0, 0, 0.0f));

        presetRow1.getChildren().addAll(stdBtn, cinemaBtn, anamBtn, vintageBtn, petzvalBtn);
        presetRow2.getChildren().addAll(miniBtn, dreamBtn, nightBtn, prismBtn);

        box.getChildren().addAll(dofEnabledCheck, focalDistSlider,
                apertureSlider, dofSamplesSlider, dofInfoLabel,
                lensLabel, anamorphicSlider,
                bokehShapeLabel, bokehShapeCombo, bokehRotationSlider,
                opticalVignettingSlider,
                tiltShiftCheck, tiltAngleXSlider, tiltAngleYSlider,
                dofChromaticSlider,
                presetsLabel, presetRow1, presetRow2);

        TitledPane pane = new TitledPane("Depth of Field", box);
        pane.setExpanded(false);
        return pane;
    }

    private void applyLensPreset(float anamorphic, int blades, float rotDeg, float vignetting,
                                  boolean tiltShift, float tiltXDeg, float tiltYDeg, float chromatic) {
        AbstractFractalParams p = getParams();
        p.setAnamorphicRatio(anamorphic);
        p.setBokehBlades(blades);
        p.setBokehRotation((float) Math.toRadians(rotDeg));
        p.setOpticalVignettingStrength(vignetting);
        p.setTiltShiftEnabled(tiltShift);
        p.setTiltAngleX((float) Math.toRadians(tiltXDeg));
        p.setTiltAngleY((float) Math.toRadians(tiltYDeg));
        p.setDofChromaticStrength(chromatic);
        refreshFromParams(true);
        renderCallback.requestRender();
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
        pathTracingCheck.setSelected(true);
        pathTracingCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setPathTracingEnabled(pathTracingCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        neeEnabledCheck = new CheckBox("NEE + MIS (env importance sampling)");
        neeEnabledCheck.setSelected(true);
        neeEnabledCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setNeeEnabled(neeEnabledCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        renderSamplesSlider = new EnhancedSlider("Preview Samples", 16, 4096, 64, true);
        renderSamplesSlider.showTickMarks(true);
        renderSamplesSlider.setMajorTickUnit(512);
        renderSamplesSlider.setOnAction(v -> {
            if (!suppressRender && fullSamplesCallback != null) {
                fullSamplesCallback.accept(v.intValue());
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
        indirectInfo.getStyleClass().add("hint-label");

        Label pathTracingInfo = new Label("Preview Samples = iterations for\nAuto Full Quality preview render.");
        pathTracingInfo.getStyleClass().add("hint-label");

        box.getChildren().addAll(pathTracingCheck, neeEnabledCheck, renderSamplesSlider, bouncesSlider,
                skyIntensitySlider, indirectSlider,
                indirectInfo, pathTracingInfo);

        TitledPane pane = new TitledPane("Path Tracing (GI)", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createRaymarcherPane() {
        VBox box = new VBox(5);

        coneTracingCheck = new CheckBox("Cone Tracing");
        coneTracingCheck.setSelected(true);
        coneTracingCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setConeTracingEnabled(coneTracingCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        fudgeFactorSlider = new EnhancedSlider("Fudge Factor", 0.1, 2.0, 1.0, false);
        fudgeFactorSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setFudgeFactor(v.floatValue());
                renderCallback.requestRender();
            }
        });

        refinementStepsSlider = new EnhancedSlider("Refinement Steps", 0, 8, 4, true);
        refinementStepsSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setRefinementSteps(v.intValue());
                renderCallback.requestRender();
            }
        });

        stepRelaxationSlider = new EnhancedSlider("Step Relaxation", 0.0, 1.0, 0.0, false);
        stepRelaxationSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setStepRelaxation(v.floatValue());
                renderCallback.requestRender();
            }
        });

        box.getChildren().addAll(coneTracingCheck, fudgeFactorSlider, refinementStepsSlider, stepRelaxationSlider);

        TitledPane pane = new TitledPane("Raymarcher", box);
        pane.setExpanded(false);
        return pane;
    }

    private TitledPane createAdaptiveSamplingPane() {
        VBox box = new VBox(5);

        adaptiveSamplingCheck = new CheckBox("Enable Adaptive Sampling");
        adaptiveSamplingCheck.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setAdaptiveSampling(adaptiveSamplingCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        varianceThresholdSlider = new EnhancedSlider("Threshold", 0.00001, 0.005, 0.0005, false);
        varianceThresholdSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setVarianceThreshold(v.floatValue());
                renderCallback.requestRender();
            }
        });

        minAdaptiveSamplesSlider = new EnhancedSlider("Min Samples", 4, 64, 16, true);
        minAdaptiveSamplesSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setMinAdaptiveSamples(v.intValue());
                renderCallback.requestRender();
            }
        });

        box.getChildren().addAll(adaptiveSamplingCheck, varianceThresholdSlider, minAdaptiveSamplesSlider);

        TitledPane pane = new TitledPane("Adaptive Sampling", box);
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
            anamorphicSlider.setValue(p.getAnamorphicRatio());
            // Map blades to combo index: 0->0, 3->1, 4->2, 5->3, 6->4, 7->5, 8->6
            int blades = p.getBokehBlades();
            bokehShapeCombo.getSelectionModel().select(blades < 3 ? 0 : blades - 2);
            bokehRotationSlider.setValue(Math.toDegrees(p.getBokehRotation()));
            opticalVignettingSlider.setValue(p.getOpticalVignettingStrength());
            tiltShiftCheck.setSelected(p.isTiltShiftEnabled());
            tiltAngleXSlider.setValue(Math.toDegrees(p.getTiltAngleX()));
            tiltAngleYSlider.setValue(Math.toDegrees(p.getTiltAngleY()));
            dofChromaticSlider.setValue(p.getDofChromaticStrength());

            // Path tracing
            pathTracingCheck.setSelected(p.isPathTracingEnabled());
            neeEnabledCheck.setSelected(p.isNeeEnabled());
            if (fullSamplesSupplier != null) {
                renderSamplesSlider.setValue(fullSamplesSupplier.get());
            }
            bouncesSlider.setValue(p.getMaxBounces());
            skyIntensitySlider.setValue(p.getSkyIntensity());
            indirectSlider.setValue(p.getIndirectMultiplier() * 100.0);

            // Raymarcher
            coneTracingCheck.setSelected(p.isConeTracingEnabled());
            fudgeFactorSlider.setValue(p.getFudgeFactor());
            refinementStepsSlider.setValue(p.getRefinementSteps());
            stepRelaxationSlider.setValue(p.getStepRelaxation());

            // Adaptive sampling
            adaptiveSamplingCheck.setSelected(p.isAdaptiveSampling());
            varianceThresholdSlider.setValue(p.getVarianceThreshold());
            minAdaptiveSamplesSlider.setValue(p.getMinAdaptiveSamples());

        } finally {
            suppressRender = false;
        }
    }
}