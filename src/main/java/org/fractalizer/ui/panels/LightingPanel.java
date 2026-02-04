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
            ambientIntLabel, ambientIntensitySlider
        );

        return panel;
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

        } finally {
            suppressRender = false;
        }
    }
}