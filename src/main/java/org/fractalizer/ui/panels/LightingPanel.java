package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.components.EnhancedSlider;

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
    private EnhancedSlider lightXSlider;
    private EnhancedSlider lightYSlider;
    private EnhancedSlider lightZSlider;

    // Intensity sliders
    private EnhancedSlider lightIntensitySlider;
    private EnhancedSlider ambientIntensitySlider;

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

        lightXSlider = new EnhancedSlider("X", -5, 5, 2, false);
        lightXSlider.setPrecision(1);
        lightXSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().lightDirection(v.floatValue(), (float)lightYSlider.getValue(), (float)lightZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        lightYSlider = new EnhancedSlider("Y", -5, 5, 3, false);
        lightYSlider.setPrecision(1);
        lightYSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().lightDirection((float)lightXSlider.getValue(), v.floatValue(), (float)lightZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        lightZSlider = new EnhancedSlider("Z", -5, 5, -2, false);
        lightZSlider.setPrecision(1);
        lightZSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().lightDirection((float)lightXSlider.getValue(), (float)lightYSlider.getValue(), v.floatValue());
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

        lightIntensitySlider = new EnhancedSlider("Light Intensity", 0, 3, 1.2, false);
        lightIntensitySlider.setPrecision(1);
        lightIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().lightIntensity(v.floatValue());
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

        ambientIntensitySlider = new EnhancedSlider("Ambient Intensity", 0, 1, 0.3, false);
        ambientIntensitySlider.setPrecision(2);
        ambientIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().ambientIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        panel.getChildren().addAll(
            dirLabel,
            lightXSlider,
            lightYSlider,
            lightZSlider,
            new Separator(),
            lightColLabel, lightColorPicker,
            lightIntensitySlider,
            new Separator(),
            ambientLabel, ambientColorPicker,
            ambientIntensitySlider
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