package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.components.EnhancedSlider;

import java.util.function.Supplier;

/**
 * Panel for configuring material properties:
 * - Color Palette (Hue, Presets)
 * - Physical Properties (Type, Roughness, Metalness, IOR)
 * - Surface Properties (Specular)
 */
public class MaterialPanel extends ScrollPane implements Refreshable {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;
    private boolean suppressRender = false;

    // Palette controls
    private ComboBox<String> paletteCombo;
    private EnhancedSlider colorStrengthSlider;
    private EnhancedSlider paletteOffsetSlider;
    private ColorPicker baseColorPicker;
    
    // Material Type
    private ComboBox<String> materialTypeCombo;
    
    // Physical Properties
    private EnhancedSlider roughnessSlider;
    private EnhancedSlider metalnessSlider;
    private EnhancedSlider iorSlider;
    
    // Specular
    private EnhancedSlider specularIntensitySlider;
    private EnhancedSlider specularPowerSlider;

    public MaterialPanel(Supplier<AbstractFractalParams> paramsSupplier, RenderCallback renderCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;
        
        setContent(createContent());
        setFitToWidth(true);
        setPadding(new Insets(10));
    }

    private VBox createContent() {
        VBox root = new VBox(10);
        
        // === COLOR PALETTE SECTION ===
        Label paletteLabel = new Label("Color Palette");
        paletteLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa;");

        // Palette Selector
        Label presetLabel = new Label("Palette Preset:");
        paletteCombo = new ComboBox<>();
        paletteCombo.getItems().addAll(
            "Custom (Original)", "Magma / Fire", "Ice / Ocean", 
            "Forest / Nature", "Cyberpunk / Neon", "Spectral / Rainbow"
        );
        paletteCombo.getSelectionModel().select(0);
        paletteCombo.setMaxWidth(Double.MAX_VALUE);
        paletteCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setPaletteIndex(paletteCombo.getSelectionModel().getSelectedIndex());
                renderCallback.requestRender();
            }
        });

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
        
        // Base Color Picker
        Label baseColorLabel = new Label("Base Color (for Custom):");
        baseColorPicker = new ColorPicker(Color.BLUE);
        baseColorPicker.setMaxWidth(Double.MAX_VALUE);
        baseColorPicker.setOnAction(e -> {
            if (!suppressRender) {
                Color c = baseColorPicker.getValue();
                getParams().setMaterialHue((float)c.getRed(), (float)c.getGreen(), (float)c.getBlue());
                renderCallback.requestRender();
            }
        });

        // Presets (Moved from LightingPanel)
        VBox presetsBox = createPresetsBox();

        // === PHYSICAL MATERIAL SECTION ===
        Label physLabel = new Label("Physical Material");
        physLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa;");
        
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
        
        // === SPECULAR SECTION (Legacy / Raytracing) ===
        Label specLabel = new Label("Specular Highlights");
        specLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #aaa;");
        
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

        root.getChildren().addAll(
            paletteLabel,
            presetLabel, paletteCombo,
            colorStrengthSlider,
            paletteOffsetSlider,
            baseColorLabel, baseColorPicker,
            new Separator(),
            new Label("Material Presets:"),
            presetsBox,
            new Separator(),
            physLabel,
            typeBox,
            roughnessSlider,
            metalnessSlider,
            iorSlider,
            new Separator(),
            specLabel,
            specularIntensitySlider,
            specularPowerSlider
        );
        
        return root;
    }

    private VBox createPresetsBox() {
        VBox box = new VBox(5);
        
        // Row 1: Metals and Specialized
        HBox row1 = new HBox(5);
        row1.getChildren().addAll(
            createFullPresetBtn("Gold", 1.0f, 0.7f, 0.2f, 1, 0.1f, 1.0f, 1.5f),
            createFullPresetBtn("Silver", 0.9f, 0.9f, 0.95f, 1, 0.05f, 1.0f, 1.5f),
            createFullPresetBtn("Copper", 0.95f, 0.64f, 0.54f, 1, 0.15f, 1.0f, 1.5f),
            createFullPresetBtn("Cyber", 0.0f, 1.0f, 1.0f, 1, 0.2f, 0.8f, 1.5f)
        );
        
        // Row 2: Dielectrics (Glass, Plastic, etc)
        HBox row2 = new HBox(5);
        row2.getChildren().addAll(
            createFullPresetBtn("Crystal", 0.9f, 0.95f, 1.0f, 2, 0.0f, 0.0f, 1.5f),
            createFullPresetBtn("Ruby", 1.0f, 0.1f, 0.2f, 2, 0.05f, 0.0f, 1.8f),
            createFullPresetBtn("Plastic", 1.0f, 0.2f, 0.2f, 0, 0.3f, 0.0f, 1.5f),
            createFullPresetBtn("Concrete", 0.5f, 0.5f, 0.5f, 0, 0.9f, 0.0f, 1.5f)
        );
        
        box.getChildren().addAll(row1, row2);
        return box;
    }
    
    private Button createFullPresetBtn(String name, float r, float g, float b, int type, float rough, float metal, float ior) {
        Button btn = new Button(name);
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setOnAction(e -> applyMaterialPreset(r, g, b, type, rough, metal, ior));
        return btn;
    }
    
    private Button createPresetBtn(String name, float r, float g, float b) {
        // Keep for backward compatibility or simple color shifts if needed
        Button btn = new Button(name);
        btn.setOnAction(e -> {
            baseColorPicker.setValue(Color.color(r, g, b));
            if (!suppressRender) {
                getParams().setMaterialHue(r, g, b);
                renderCallback.requestRender();
            }
        });
        return btn;
    }
    
    private void applyMaterialPreset(float r, float g, float b, int type, float rough, float metal, float ior) {
        suppressRender = true;
        try {
            baseColorPicker.setValue(Color.color(r, g, b));
            getParams().setMaterialHue(r, g, b);
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
        boolean isMetal = (type == 1);
        boolean isGlass = (type == 2);
        
        metalnessSlider.setDisable(!isMetal);
        iorSlider.setDisable(!isGlass);
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }

    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress; // Should be true ideally, or handle carefully
        try {
            AbstractFractalParams p = getParams();
            
            // Color
            paletteCombo.getSelectionModel().select(p.getPaletteIndex());
            colorStrengthSlider.setValue(p.getColorStrength());
            paletteOffsetSlider.setValue(p.getPaletteOffset());
            baseColorPicker.setValue(Color.color(
                Math.max(0, Math.min(1, p.getHueR())),
                Math.max(0, Math.min(1, p.getHueG())),
                Math.max(0, Math.min(1, p.getHueB()))
            ));
            
            // Material Props
            materialTypeCombo.getSelectionModel().select(p.getMaterialType());
            roughnessSlider.setValue(p.getRoughness());
            metalnessSlider.setValue(p.getMetalness());
            iorSlider.setValue(p.getIor());
            
            // Specular
            specularIntensitySlider.setValue(p.getSpecularIntensity());
            specularPowerSlider.setValue(p.getSpecularPower());
            
            updateVisibility();
        } finally {
            suppressRender = false;
        }
    }
}
