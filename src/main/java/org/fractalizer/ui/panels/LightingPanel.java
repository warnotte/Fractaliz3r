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
 */
public class LightingPanel extends ScrollPane {

    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final RenderCallback renderCallback;

    // Light direction sliders
    private Slider lightXSlider;
    private Slider lightYSlider;
    private Slider lightZSlider;

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
            getParams().lightDirection(val.floatValue(),
                (float) lightYSlider.getValue(),
                (float) lightZSlider.getValue());
            renderCallback.requestRender();
        });

        Label lightYLabel = new Label("Y: 3.0");
        lightYSlider = new Slider(-5, 5, 3);
        lightYSlider.valueProperty().addListener((obs, old, val) -> {
            lightYLabel.setText(String.format("Y: %.1f", val.doubleValue()));
            getParams().lightDirection((float) lightXSlider.getValue(),
                val.floatValue(),
                (float) lightZSlider.getValue());
            renderCallback.requestRender();
        });

        Label lightZLabel = new Label("Z: -2.0");
        lightZSlider = new Slider(-5, 5, -2);
        lightZSlider.valueProperty().addListener((obs, old, val) -> {
            lightZLabel.setText(String.format("Z: %.1f", val.doubleValue()));
            getParams().lightDirection((float) lightXSlider.getValue(),
                (float) lightYSlider.getValue(),
                val.floatValue());
            renderCallback.requestRender();
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
        Slider lightIntensitySlider = new Slider(0, 3, 1.2);
        lightIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            lightIntLabel.setText(String.format("Light Intensity: %.1f", val.doubleValue()));
            getParams().lightIntensity(val.floatValue());
            renderCallback.requestRender();
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
        Slider ambientIntensitySlider = new Slider(0, 1, 0.3);
        ambientIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            ambientIntLabel.setText(String.format("Ambient Intensity: %.2f", val.doubleValue()));
            getParams().ambientIntensity(val.floatValue());
            renderCallback.requestRender();
        });

        // === Material Hue ===
        Label hueLabel = new Label("Material Hue Offset:");
        hueLabel.setStyle("-fx-font-weight: bold;");

        Label hueOffsetLabel = new Label("Hue: 0.33");
        hueOffsetSlider = new Slider(0, 1, 0.33);
        hueOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            hueOffsetLabel.setText(String.format("Hue: %.2f", val.doubleValue()));
            float h = val.floatValue();
            getParams().materialHue(h * 0.3f, h, (1.0f - h) * 0.67f + 0.33f);
            renderCallback.requestRender();
        });

        // Preset colors
        Label presetLabel = new Label("Color Presets:");
        HBox presetBox = new HBox(5);

        Button preset1 = new Button("Blue");
        preset1.setOnAction(e -> applyPreset(0.0f, 0.33f, 0.67f, null, 0.33));

        Button preset2 = new Button("Fire");
        preset2.setOnAction(e -> {
            getParams().materialHue(0.0f, 0.1f, 0.2f);
            lightColorPicker.setValue(Color.rgb(255, 200, 150));
            getParams().lightColor(1.0f, 0.78f, 0.59f);
            renderCallback.requestRender();
        });

        Button preset3 = new Button("Ice");
        preset3.setOnAction(e -> {
            getParams().materialHue(0.5f, 0.6f, 0.7f);
            lightColorPicker.setValue(Color.rgb(200, 220, 255));
            getParams().lightColor(0.78f, 0.86f, 1.0f);
            renderCallback.requestRender();
        });

        Button preset4 = new Button("Gold");
        preset4.setOnAction(e -> {
            getParams().materialHue(0.1f, 0.15f, 0.0f);
            lightColorPicker.setValue(Color.rgb(255, 230, 180));
            getParams().lightColor(1.0f, 0.9f, 0.7f);
            renderCallback.requestRender();
        });

        presetBox.getChildren().addAll(preset1, preset2, preset3, preset4);

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
        getParams().materialHue(r, g, b);
        if (lightColor != null) {
            lightColorPicker.setValue(lightColor);
            getParams().lightColor((float) lightColor.getRed(),
                (float) lightColor.getGreen(),
                (float) lightColor.getBlue());
        }
        hueOffsetSlider.setValue(hueSlider);
        renderCallback.requestRender();
    }
}