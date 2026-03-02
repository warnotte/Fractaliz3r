package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.GradientPalette;
import org.fractalizer.ui.components.EnhancedSlider;
import org.fractalizer.ui.components.GradientEditor;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel for configuring material properties:
 * - Gradient Palette (visual editor with presets)
 * - Physical Properties (Type, Roughness, Metalness, IOR)
 * - Surface Properties (Specular)
 */
public class MaterialPanel extends ScrollPane implements Refreshable {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;
    private boolean suppressRender = false;

    // Gradient
    private GradientEditor gradientEditor;
    private Consumer<GradientPalette> onGradientChanged;
    private ComboBox<String> coloringModeCombo;
    private ComboBox<String> trapModeCombo;
    private EnhancedSlider colorStrengthSlider;
    private EnhancedSlider paletteOffsetSlider;

    // Material Type
    private ComboBox<String> materialTypeCombo;

    // Physical Properties
    private EnhancedSlider roughnessSlider;
    private EnhancedSlider metalnessSlider;
    private EnhancedSlider iorSlider;

    // Specular
    private EnhancedSlider specularIntensitySlider;
    private EnhancedSlider specularPowerSlider;

    // Advanced Effects
    private EnhancedSlider reflectionIntensitySlider;
    private EnhancedSlider emissiveIntensitySlider;
    private EnhancedSlider sssIntensitySlider;
    private EnhancedSlider sssRadiusSlider;

    public MaterialPanel(Supplier<AbstractFractalParams> paramsSupplier, RenderCallback renderCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    /**
     * Set callback for gradient changes (used by App to upload texture to GPU).
     */
    public void setOnGradientChanged(Consumer<GradientPalette> callback) {
        this.onGradientChanged = callback;
    }

    private VBox createContent() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        // === GRADIENT PALETTE SECTION ===

        // Gradient Editor (always visible)
        gradientEditor = new GradientEditor(getParams().getCustomGradient());
        gradientEditor.setOnGradientChanged(gradient -> {
            if (!suppressRender) {
                getParams().setCustomGradient(gradient);
                fireGradientChanged(gradient);
                renderCallback.requestRender();
            }
        });

        // Coloring Mode
        coloringModeCombo = new ComboBox<>();
        coloringModeCombo.getItems().addAll(
            "Standard",       // 0: flow + depth (current default)
            "Iteration Bands", // 1: sharp color bands by iteration
            "Distance",        // 2: structural/proximity based
            "Angular",         // 3: atan2 spiral patterns
            "Blend",           // 4: equal mix of all 3 factors
            "Contour",         // 5: high-frequency stripes (topographic)
            "HSV Direct",      // 6: factors → H/S/V independently (no palette)
            "Dual Palette",    // 7: two palette lookups blended by depth
            "Neon",            // 8: sharp hue bands, high saturation glow
            "Normal Map",      // 9: surface orientation → palette (scale-invariant)
            "Triplanar",       // 10: 3D noise via triplanar projection (scale-invariant)
            "Curvature",       // 11: Laplacian concave/convex → palette (scale-invariant)
            "Fresnel"          // 12: view-dependent rim lighting (scale-invariant)
        );
        coloringModeCombo.getSelectionModel().select(0);
        coloringModeCombo.setMaxWidth(Double.MAX_VALUE);
        coloringModeCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setColoringMode(coloringModeCombo.getSelectionModel().getSelectedIndex());
                renderCallback.requestRender();
            }
        });

        HBox modeBox = new HBox(8, new Label("Coloring:"), coloringModeCombo);
        modeBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(coloringModeCombo, javafx.scene.layout.Priority.ALWAYS);

        // Trap Mode
        trapModeCombo = new ComboBox<>();
        trapModeCombo.getItems().addAll(
            "Default",    // 0: passthrough (fractal's own traps)
            "Sphere",     // 1: distance to origin + angular
            "Line X",     // 2: distance to X axis
            "Line Y",     // 3: distance to Y axis
            "Line Z",     // 4: distance to Z axis
            "Cross",      // 5: min distance to any axis
            "Grid"        // 6: fract-based 3D cell
        );
        trapModeCombo.getSelectionModel().select(0);
        trapModeCombo.setMaxWidth(Double.MAX_VALUE);
        trapModeCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setTrapMode(trapModeCombo.getSelectionModel().getSelectedIndex());
                renderCallback.requestRender();
            }
        });

        HBox trapBox = new HBox(8, new Label("Trap Mode:"), trapModeCombo);
        trapBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(trapModeCombo, javafx.scene.layout.Priority.ALWAYS);

        // Color Strength
        colorStrengthSlider = new EnhancedSlider("Color Strength", 0.1, 5.0, 1.0, false);
        colorStrengthSlider.setPrecision(1);
        colorStrengthSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setColorStrength(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Palette Offset
        paletteOffsetSlider = new EnhancedSlider("Palette Shift", 0.0, 1.0, 0.0, false);
        paletteOffsetSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setPaletteOffset(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Material Presets
        VBox presetsBox = createPresetsBox();

        // === PHYSICAL MATERIAL SECTION ===

        // Material Type
        HBox typeBox = new HBox(10);
        Label typeLabel = new Label("Type:");
        materialTypeCombo = new ComboBox<>();
        materialTypeCombo.getItems().addAll("Lambertian (Matte)", "Metallic", "Glass");
        materialTypeCombo.getSelectionModel().select(0);
        materialTypeCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setMaterialType(materialTypeCombo.getSelectionModel().getSelectedIndex());
                updateVisibility();
                renderCallback.requestRender();
            }
        });
        typeBox.getChildren().addAll(typeLabel, materialTypeCombo);

        // Roughness
        roughnessSlider = new EnhancedSlider("Roughness", 0, 1, 0.5, false);
        roughnessSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setRoughness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Metalness
        metalnessSlider = new EnhancedSlider("Metalness", 0, 1, 0.9, false);
        metalnessSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setMetalness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // IOR (Index of Refraction)
        iorSlider = new EnhancedSlider("IOR (Glass)", 1.0, 3.0, 1.5, false);
        iorSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setIor(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // === SPECULAR SECTION ===

        specularIntensitySlider = new EnhancedSlider("Intensity", 0, 2, 0.5, false);
        specularIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSpecularIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        specularPowerSlider = new EnhancedSlider("Hardness (Power)", 1, 128, 32, true);
        specularPowerSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSpecularPower(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // === ADVANCED EFFECTS SECTION ===

        reflectionIntensitySlider = new EnhancedSlider("Reflections", 0, 1, 0, false);
        reflectionIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setReflectionIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        emissiveIntensitySlider = new EnhancedSlider("Emission", 0, 3, 0, false);
        emissiveIntensitySlider.setPrecision(1);
        emissiveIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setEmissiveIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        sssIntensitySlider = new EnhancedSlider("Subsurface", 0, 2, 0, false);
        sssIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSssIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        sssRadiusSlider = new EnhancedSlider("SSS Depth", 0.01, 0.5, 0.1, false);
        sssRadiusSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setSssRadius(v.floatValue());
                renderCallback.requestRender();
            }
        });

        VBox paletteBox = new VBox(5, gradientEditor, modeBox, trapBox, colorStrengthSlider, paletteOffsetSlider,
            new Label("Material Presets:"), presetsBox);
        TitledPane palettePane = new TitledPane("Color Palette", paletteBox);
        palettePane.setExpanded(true);

        VBox physBox = new VBox(5, typeBox, roughnessSlider, metalnessSlider, iorSlider);
        TitledPane physPane = new TitledPane("Physical Material", physBox);
        physPane.setExpanded(true);

        VBox specBox = new VBox(5, specularIntensitySlider, specularPowerSlider);
        TitledPane specPane = new TitledPane("Specular Highlights", specBox);
        specPane.setExpanded(false);

        VBox advBox = new VBox(5, reflectionIntensitySlider, emissiveIntensitySlider,
            sssIntensitySlider, sssRadiusSlider);
        TitledPane advPane = new TitledPane("Advanced Effects", advBox);
        advPane.setExpanded(false);

        root.getChildren().addAll(palettePane, physPane, specPane, advPane);

        return root;
    }

    private VBox createPresetsBox() {
        VBox box = new VBox(5);

        // Row 1: Metals and Specialized
        HBox row1 = new HBox(5);
        row1.getChildren().addAll(
            createFullPresetBtn("Gold", 1, 0.1f, 1.0f, 1.5f),
            createFullPresetBtn("Silver", 1, 0.05f, 1.0f, 1.5f),
            createFullPresetBtn("Copper", 1, 0.15f, 1.0f, 1.5f),
            createFullPresetBtn("Cyber", 1, 0.2f, 0.8f, 1.5f)
        );

        // Row 2: Dielectrics (Glass, Plastic, etc)
        HBox row2 = new HBox(5);
        row2.getChildren().addAll(
            createFullPresetBtn("Crystal", 2, 0.0f, 0.0f, 1.5f),
            createFullPresetBtn("Ruby", 2, 0.05f, 0.0f, 1.8f),
            createFullPresetBtn("Plastic", 0, 0.3f, 0.0f, 1.5f),
            createFullPresetBtn("Concrete", 0, 0.9f, 0.0f, 1.5f)
        );

        box.getChildren().addAll(row1, row2);
        return box;
    }

    private Button createFullPresetBtn(String name, int type, float rough, float metal, float ior) {
        Button btn = new Button(name);
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setOnAction(e -> applyMaterialPreset(type, rough, metal, ior));
        return btn;
    }

    private void applyMaterialPreset(int type, float rough, float metal, float ior) {
        suppressRender = true;
        try {
            getParams().setMaterialType(type);
            getParams().setRoughness(rough);
            getParams().setMetalness(metal);
            getParams().setIor(ior);

            // Update UI
            materialTypeCombo.getSelectionModel().select(type);
            roughnessSlider.setValue(rough);
            metalnessSlider.setValue(metal);
            iorSlider.setValue(ior);
            updateVisibility();
        } finally {
            suppressRender = false;
            renderCallback.requestRender();
        }
    }

    private void updateVisibility() {
        int type = materialTypeCombo.getSelectionModel().getSelectedIndex();
        metalnessSlider.setDisable(type != 1);
        iorSlider.setDisable(type != 2);
    }

    private void fireGradientChanged(GradientPalette gradient) {
        if (onGradientChanged != null) {
            onGradientChanged.accept(gradient);
        }
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }

    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress;
        try {
            AbstractFractalParams p = getParams();

            // Gradient
            gradientEditor.setPalette(p.getCustomGradient());
            coloringModeCombo.getSelectionModel().select(p.getColoringMode());
            trapModeCombo.getSelectionModel().select(p.getTrapMode());
            colorStrengthSlider.setValue(p.getColorStrength());
            paletteOffsetSlider.setValue(p.getPaletteOffset());

            // Material Props
            materialTypeCombo.getSelectionModel().select(p.getMaterialType());
            roughnessSlider.setValue(p.getRoughness());
            metalnessSlider.setValue(p.getMetalness());
            iorSlider.setValue(p.getIor());

            // Specular
            specularIntensitySlider.setValue(p.getSpecularIntensity());
            specularPowerSlider.setValue(p.getSpecularPower());

            // Advanced Effects
            reflectionIntensitySlider.setValue(p.getReflectionIntensity());
            emissiveIntensitySlider.setValue(p.getEmissiveIntensity());
            sssIntensitySlider.setValue(p.getSssIntensity());
            sssRadiusSlider.setValue(p.getSssRadius());

            updateVisibility();
        } finally {
            suppressRender = false;
        }
    }
}
