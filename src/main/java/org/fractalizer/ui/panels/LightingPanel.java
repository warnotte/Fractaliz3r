package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;

import java.util.function.Supplier;

/**
 * Panel for lighting configuration: light direction, colors, materials.
 * Implements Refreshable for save/load configuration support.
 */
public class LightingPanel extends ScrollPane implements Refreshable {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;

    // Suppress render during refresh
    private boolean suppressRender = false;

    // Light direction sliders
    private Slider lightXSlider;
    private Slider lightYSlider;
    private Slider lightZSlider;

    // Intensity sliders
    private Slider lightIntensitySlider;
    private Slider ambientIntensitySlider;

    // Color pickers
    private ColorPicker lightColorPicker;
    private ColorPicker ambientColorPicker;

    // Material hue slider
    private Slider hueOffsetSlider;

    public LightingPanel(Supplier<AbstractFractalParams> paramsSupplier, RenderCallback renderCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // === Light Direction ===
        Label dirLabel = new Label("Light Direction:");
        dirLabel.setStyle("-fx-font-weight: bold;");

        Label lightXLabel = new Label("X: 2.0");
        lightXSlider = new Slider(-5, 5, 2);
        lightXSlider.valueProperty().addListener((obs, old, val) -> {
            lightXLabel.setText(String.format("X: %.1f", val.doubleValue()));
            if (!suppressRender) {
                getParams().lightDirection(val.floatValue(),
                    (float) lightYSlider.getValue(),
                    (float) lightZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        Label lightYLabel = new Label("Y: 3.0");
        lightYSlider = new Slider(-5, 5, 3);
        lightYSlider.valueProperty().addListener((obs, old, val) -> {
            lightYLabel.setText(String.format("Y: %.1f", val.doubleValue()));
            if (!suppressRender) {
                getParams().lightDirection((float) lightXSlider.getValue(),
                    val.floatValue(),
                    (float) lightZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        Label lightZLabel = new Label("Z: -2.0");
        lightZSlider = new Slider(-5, 5, -2);
        lightZSlider.valueProperty().addListener((obs, old, val) -> {
            lightZLabel.setText(String.format("Z: %.1f", val.doubleValue()));
            if (!suppressRender) {
                getParams().lightDirection((float) lightXSlider.getValue(),
                    (float) lightYSlider.getValue(),
                    val.floatValue());
                renderCallback.requestRender();
            }
        });

        // === Light Color ===
        Label lightColLabel = new Label("Light Color:");
        lightColLabel.setStyle("-fx-font-weight: bold;");

        lightColorPicker = new ColorPicker(Color.rgb(255, 242, 230));
        lightColorPicker.setMaxWidth(Double.MAX_VALUE);
        lightColorPicker.setOnAction(e -> {
            Color c = lightColorPicker.getValue();
            getParams().lightColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
            renderCallback.requestRender();
        });

        Label lightIntLabel = new Label("Light Intensity: 1.2");
        lightIntensitySlider = new Slider(0, 3, 1.2);
        lightIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            lightIntLabel.setText(String.format("Light Intensity: %.1f", val.doubleValue()));
            if (!suppressRender) {
                getParams().lightIntensity(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // === Ambient ===
        Label ambientLabel = new Label("Ambient Color:");
        ambientLabel.setStyle("-fx-font-weight: bold;");

        ambientColorPicker = new ColorPicker(Color.rgb(26, 38, 64));
        ambientColorPicker.setMaxWidth(Double.MAX_VALUE);
        ambientColorPicker.setOnAction(e -> {
            Color c = ambientColorPicker.getValue();
            getParams().ambientColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
            renderCallback.requestRender();
        });

        Label ambientIntLabel = new Label("Ambient Intensity: 0.3");
        ambientIntensitySlider = new Slider(0, 1, 0.3);
        ambientIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            ambientIntLabel.setText(String.format("Ambient Intensity: %.2f", val.doubleValue()));
            if (!suppressRender) {
                getParams().ambientIntensity(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // === Material Hue ===
        Label hueLabel = new Label("Material Hue Offset:");
        hueLabel.setStyle("-fx-font-weight: bold;");

        Label hueOffsetLabel = new Label("Hue: 0.33");
        hueOffsetSlider = new Slider(0, 1, 0.33);
        hueOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            hueOffsetLabel.setText(String.format("Hue: %.2f", val.doubleValue()));
            if (!suppressRender) {
                float h = val.floatValue();
                getParams().materialHue(h * 0.3f, h, (1.0f - h) * 0.67f + 0.33f);
                renderCallback.requestRender();
            }
        });

        // Preset colors
        Label presetLabel = new Label("Color Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        // Row 1: Classic presets
        HBox presetRow1 = new HBox(5);

        Button presetBlue = new Button("Blue");
        presetBlue.setOnAction(e -> applyPreset(0.0f, 0.33f, 0.67f, Color.rgb(255, 242, 230), 0.33));

        Button presetFire = new Button("Fire");
        presetFire.setOnAction(e -> applyPreset(0.0f, 0.1f, 0.2f, Color.rgb(255, 200, 150), 0.1));

        Button presetIce = new Button("Ice");
        presetIce.setOnAction(e -> applyPreset(0.5f, 0.6f, 0.7f, Color.rgb(200, 220, 255), 0.6));

        Button presetGold = new Button("Gold");
        presetGold.setOnAction(e -> applyPreset(0.1f, 0.15f, 0.0f, Color.rgb(255, 230, 180), 0.15));

        presetRow1.getChildren().addAll(presetBlue, presetFire, presetIce, presetGold);

        // Row 2: Nature presets
        HBox presetRow2 = new HBox(5);

        Button presetForest = new Button("Forest");
        presetForest.setOnAction(e -> applyPreset(0.15f, 0.4f, 0.2f, Color.rgb(255, 250, 230), 0.35));

        Button presetOcean = new Button("Ocean");
        presetOcean.setOnAction(e -> applyPreset(0.3f, 0.5f, 0.6f, Color.rgb(230, 245, 255), 0.5));

        Button presetEarth = new Button("Earth");
        presetEarth.setOnAction(e -> applyPreset(0.08f, 0.12f, 0.05f, Color.rgb(255, 240, 220), 0.1));

        Button presetSunset = new Button("Sunset");
        presetSunset.setOnAction(e -> applyPreset(0.0f, 0.2f, 0.5f, Color.rgb(255, 200, 100), 0.15));

        presetRow2.getChildren().addAll(presetForest, presetOcean, presetEarth, presetSunset);

        // Row 3: Vibrant/Artistic presets
        HBox presetRow3 = new HBox(5);

        Button presetNeon = new Button("Neon");
        presetNeon.setOnAction(e -> applyPreset(0.8f, 0.2f, 0.9f, Color.rgb(255, 255, 255), Color.rgb(20, 0, 40), 0.7));

        Button presetCosmic = new Button("Cosmic");
        presetCosmic.setOnAction(e -> applyPreset(0.6f, 0.3f, 0.8f, Color.rgb(200, 180, 255), Color.rgb(30, 20, 50), 0.65));

        Button presetCandy = new Button("Candy");
        presetCandy.setOnAction(e -> applyPreset(0.9f, 0.5f, 0.7f, Color.rgb(255, 230, 240), Color.rgb(40, 20, 35), 0.8));

        Button presetRainbow = new Button("Rainbow");
        presetRainbow.setOnAction(e -> applyPreset(0.5f, 0.5f, 0.5f, Color.rgb(255, 255, 255), Color.rgb(30, 30, 40), 0.5));

        presetRow3.getChildren().addAll(presetNeon, presetCosmic, presetCandy, presetRainbow);

        // Row 4: Monochrome/Special
        HBox presetRow4 = new HBox(5);

        Button presetMono = new Button("Mono");
        presetMono.setOnAction(e -> applyPreset(0.0f, 0.0f, 0.0f, Color.rgb(255, 255, 255), 0.0));

        Button presetSepia = new Button("Sepia");
        presetSepia.setOnAction(e -> applyPreset(0.05f, 0.08f, 0.02f, Color.rgb(255, 240, 200), 0.07));

        Button presetLava = new Button("Lava");
        presetLava.setOnAction(e -> applyPreset(0.0f, 0.05f, 0.1f, Color.rgb(255, 150, 50), 0.05));

        Button presetAurora = new Button("Aurora");
        presetAurora.setOnAction(e -> applyPreset(0.4f, 0.7f, 0.5f, Color.rgb(200, 255, 220), 0.55));

        presetRow4.getChildren().addAll(presetMono, presetSepia, presetLava, presetAurora);

        VBox presetBox = new VBox(3, presetRow1, presetRow2, presetRow3, presetRow4);

        panel.getChildren().addAll(
            dirLabel,
            lightXLabel, lightXSlider,
            lightYLabel, lightYSlider,
            lightZLabel, lightZSlider,
            new Separator(),
            lightColLabel, lightColorPicker,
            lightIntLabel, lightIntensitySlider,
            new Separator(),
            ambientLabel, ambientColorPicker,
            ambientIntLabel, ambientIntensitySlider,
            new Separator(),
            hueLabel, hueOffsetLabel, hueOffsetSlider,
            new Separator(),
            presetLabel, presetBox
        );

        return panel;
    }

    private AbstractFractalParams getParams() {
        return paramsSupplier.get();
    }

    private void applyPreset(float r, float g, float b, Color lightColor, double hueSlider) {
        applyPreset(r, g, b, lightColor, null, hueSlider);
    }

    private void applyPreset(float r, float g, float b, Color lightColor, Color ambientColor, double hueSlider) {
        getParams().materialHue(r, g, b);
        if (lightColor != null) {
            lightColorPicker.setValue(lightColor);
            getParams().lightColor((float) lightColor.getRed(),
                (float) lightColor.getGreen(),
                (float) lightColor.getBlue());
        }
        if (ambientColor != null) {
            ambientColorPicker.setValue(ambientColor);
            getParams().ambientColor((float) ambientColor.getRed(),
                (float) ambientColor.getGreen(),
                (float) ambientColor.getBlue());
        }
        hueOffsetSlider.setValue(hueSlider);
        renderCallback.requestRender();
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

            // Light direction
            lightXSlider.setValue(p.getLightX());
            lightYSlider.setValue(p.getLightY());
            lightZSlider.setValue(p.getLightZ());

            // Light color and intensity
            lightColorPicker.setValue(Color.color(p.getLightR(), p.getLightG(), p.getLightB()));
            lightIntensitySlider.setValue(p.getLightIntensity());

            // Ambient
            ambientColorPicker.setValue(Color.color(p.getAmbientR(), p.getAmbientG(), p.getAmbientB()));
            ambientIntensitySlider.setValue(p.getAmbientIntensity());

            // Material hue (approximate - use green component as hue slider value)
            hueOffsetSlider.setValue(p.getHueG());

        } finally {
            suppressRender = false;
        }
    }
}