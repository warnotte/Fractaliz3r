package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
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

    // Directional light toggle
    private CheckBox directionalLightToggle;
    private float savedLightIntensity = 1.2f;

    // Color pickers
    private ColorPicker lightColorPicker;
    private ColorPicker ambientColorPicker;
    private ColorPicker extraLightColorPicker;

    // Additional light controls
    private ComboBox<String> extraLightTypeCombo;
    private EnhancedSlider extraLightPosXSlider;
    private EnhancedSlider extraLightPosYSlider;
    private EnhancedSlider extraLightPosZSlider;
    private EnhancedSlider extraLightDirXSlider;
    private EnhancedSlider extraLightDirYSlider;
    private EnhancedSlider extraLightDirZSlider;
    private EnhancedSlider extraLightIntensitySlider;
    private EnhancedSlider extraLightRangeSlider;
    private EnhancedSlider extraLightAreaRadiusSlider;
    private EnhancedSlider extraLightConeAngleSlider;
    private EnhancedSlider extraLightConeSoftnessSlider;

    public LightingPanel(Supplier<AbstractFractalParams> paramsSupplier, RenderCallback renderCallback) {
        this.paramsSupplier = paramsSupplier;
        this.renderCallback = renderCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        directionalLightToggle = new CheckBox("Directional Light");
        directionalLightToggle.setSelected(true);
        directionalLightToggle.setOnAction(e -> {
            if (!suppressRender) {
                if (directionalLightToggle.isSelected()) {
                    getParams().lightIntensity(savedLightIntensity);
                    lightIntensitySlider.setValue(savedLightIntensity);
                } else {
                    savedLightIntensity = getParams().getLightIntensity();
                    if (savedLightIntensity <= 0) savedLightIntensity = 1.2f;
                    getParams().lightIntensity(0f);
                    lightIntensitySlider.setValue(0);
                }
                lightIntensitySlider.setDisable(!directionalLightToggle.isSelected());
                renderCallback.requestRender();
            }
        });

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

        Label extraLightInfo = new Label("Used in Path Tracing mode.\nPosition/Direction are camera-relative.");
        extraLightInfo.getStyleClass().add("hint-label");

        extraLightTypeCombo = new ComboBox<>();
        extraLightTypeCombo.getItems().addAll("Off", "Point (Omni)", "Spot");
        extraLightTypeCombo.getSelectionModel().select(0);
        extraLightTypeCombo.setMaxWidth(Double.MAX_VALUE);
        extraLightTypeCombo.setOnAction(e -> {
            if (!suppressRender) {
                getParams().setExtraLightType(uiIndexToExtraLightType(extraLightTypeCombo.getSelectionModel().getSelectedIndex()));
                updateAdditionalLightControlState();
                renderCallback.requestRender();
            }
        });

        extraLightPosXSlider = new EnhancedSlider("Offset X", -5, 5, 0, false);
        extraLightPosXSlider.setPrecision(2);
        extraLightPosXSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightPosition(v.floatValue(), (float) extraLightPosYSlider.getValue(), (float) extraLightPosZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        extraLightPosYSlider = new EnhancedSlider("Offset Y", -5, 5, 0, false);
        extraLightPosYSlider.setPrecision(2);
        extraLightPosYSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightPosition((float) extraLightPosXSlider.getValue(), v.floatValue(), (float) extraLightPosZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        extraLightPosZSlider = new EnhancedSlider("Offset Z", -5, 5, 0, false);
        extraLightPosZSlider.setPrecision(2);
        extraLightPosZSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightPosition((float) extraLightPosXSlider.getValue(), (float) extraLightPosYSlider.getValue(), v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightDirXSlider = new EnhancedSlider("Direction X (Local)", -1, 1, 0, false);
        extraLightDirXSlider.setPrecision(2);
        extraLightDirXSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightDirection(v.floatValue(), (float) extraLightDirYSlider.getValue(), (float) extraLightDirZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        extraLightDirYSlider = new EnhancedSlider("Direction Y (Local)", -1, 1, 0, false);
        extraLightDirYSlider.setPrecision(2);
        extraLightDirYSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightDirection((float) extraLightDirXSlider.getValue(), v.floatValue(), (float) extraLightDirZSlider.getValue());
                renderCallback.requestRender();
            }
        });

        extraLightDirZSlider = new EnhancedSlider("Direction Z (Local)", -1, 1, 1, false);
        extraLightDirZSlider.setPrecision(2);
        extraLightDirZSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightDirection((float) extraLightDirXSlider.getValue(), (float) extraLightDirYSlider.getValue(), v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightColorPicker = new ColorPicker(Color.rgb(255, 242, 230));
        extraLightColorPicker.setMaxWidth(Double.MAX_VALUE);
        extraLightColorPicker.setOnAction(e -> {
            if (!suppressRender) {
                Color c = extraLightColorPicker.getValue();
                getParams().setExtraLightColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
                renderCallback.requestRender();
            }
        });

        extraLightIntensitySlider = new EnhancedSlider("Extra Intensity", 0, 10, 1.5, false);
        extraLightIntensitySlider.setPrecision(2);
        extraLightIntensitySlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightIntensity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightRangeSlider = new EnhancedSlider("Extra Range", 0.05, 20, 2, false);
        extraLightRangeSlider.setPrecision(3);
        extraLightRangeSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightRange(v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightAreaRadiusSlider = new EnhancedSlider("Area Radius (Soft Shadows)", 0.0, 0.1, 0.03, false);
        extraLightAreaRadiusSlider.setPrecision(3);
        extraLightAreaRadiusSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightAreaRadius(v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightConeAngleSlider = new EnhancedSlider("Spot Cone Angle", 5, 89, 35, false);
        extraLightConeAngleSlider.setPrecision(1);
        extraLightConeAngleSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightConeAngle(v.floatValue());
                renderCallback.requestRender();
            }
        });

        extraLightConeSoftnessSlider = new EnhancedSlider("Spot Edge Softness", 0, 1, 0.3, false);
        extraLightConeSoftnessSlider.setPrecision(2);
        extraLightConeSoftnessSlider.setOnAction(v -> {
            if (!suppressRender) {
                getParams().setExtraLightConeSoftness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Collapsible sections
        VBox dirBox = new VBox(5, directionalLightToggle, lightXSlider, lightYSlider, lightZSlider);
        TitledPane dirPane = new TitledPane("Directional Light", dirBox);
        dirPane.setExpanded(true);

        VBox colBox = new VBox(5, lightColorPicker, lightIntensitySlider);
        TitledPane colPane = new TitledPane("Light Color & Intensity", colBox);
        colPane.setExpanded(true);

        VBox ambBox = new VBox(5, ambientColorPicker, ambientIntensitySlider);
        TitledPane ambPane = new TitledPane("Ambient", ambBox);
        ambPane.setExpanded(false);

        VBox extraBox = new VBox(5, extraLightInfo, extraLightTypeCombo,
            extraLightPosXSlider, extraLightPosYSlider, extraLightPosZSlider,
            extraLightDirXSlider, extraLightDirYSlider, extraLightDirZSlider,
            extraLightColorPicker, extraLightIntensitySlider, extraLightRangeSlider,
            extraLightAreaRadiusSlider, extraLightConeAngleSlider, extraLightConeSoftnessSlider);
        TitledPane extraPane = new TitledPane("Additional Light", extraBox);
        extraPane.setExpanded(false);

        panel.getChildren().addAll(dirPane, colPane, ambPane, extraPane);

        updateAdditionalLightControlState();
        return panel;
    }

    private int uiIndexToExtraLightType(int uiIndex) {
        return switch (uiIndex) {
            case 1 -> AbstractFractalParams.EXTRA_LIGHT_POINT;
            case 2 -> AbstractFractalParams.EXTRA_LIGHT_SPOT;
            default -> AbstractFractalParams.EXTRA_LIGHT_OFF;
        };
    }

    private int extraLightTypeToUiIndex(int type) {
        return switch (type) {
            case AbstractFractalParams.EXTRA_LIGHT_POINT -> 1;
            case AbstractFractalParams.EXTRA_LIGHT_SPOT -> 2;
            default -> 0; // Includes directional for backward compatibility.
        };
    }

    private void updateAdditionalLightControlState() {
        if (extraLightTypeCombo == null) return;

        int type = uiIndexToExtraLightType(extraLightTypeCombo.getSelectionModel().getSelectedIndex());
        boolean off = type == AbstractFractalParams.EXTRA_LIGHT_OFF;
        boolean spot = type == AbstractFractalParams.EXTRA_LIGHT_SPOT;

        boolean posEnabled = !off;
        boolean dirEnabled = !off && spot;
        boolean rangeEnabled = !off;
        boolean coneEnabled = !off && spot;

        extraLightPosXSlider.setDisable(!posEnabled);
        extraLightPosYSlider.setDisable(!posEnabled);
        extraLightPosZSlider.setDisable(!posEnabled);
        extraLightDirXSlider.setDisable(!dirEnabled);
        extraLightDirYSlider.setDisable(!dirEnabled);
        extraLightDirZSlider.setDisable(!dirEnabled);
        extraLightRangeSlider.setDisable(!rangeEnabled);
        extraLightAreaRadiusSlider.setDisable(!rangeEnabled);
        extraLightConeAngleSlider.setDisable(!coneEnabled);
        extraLightConeSoftnessSlider.setDisable(!coneEnabled);
        extraLightColorPicker.setDisable(off);
        extraLightIntensitySlider.setDisable(off);
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
            boolean lightOn = p.getLightIntensity() > 0;
            directionalLightToggle.setSelected(lightOn);
            lightIntensitySlider.setDisable(!lightOn);
            if (lightOn) savedLightIntensity = p.getLightIntensity();

            // Ambient
            ambientColorPicker.setValue(Color.color(p.getAmbientR(), p.getAmbientG(), p.getAmbientB()));
            ambientIntensitySlider.setValue(p.getAmbientIntensity());

            // Additional light
            int extraType = p.getExtraLightType();
            if (extraType < 0 || extraType > AbstractFractalParams.EXTRA_LIGHT_SPOT) {
                extraType = AbstractFractalParams.EXTRA_LIGHT_OFF;
            }
            extraLightTypeCombo.getSelectionModel().select(extraLightTypeToUiIndex(extraType));
            extraLightPosXSlider.setValue(p.getExtraLightX());
            extraLightPosYSlider.setValue(p.getExtraLightY());
            extraLightPosZSlider.setValue(p.getExtraLightZ());
            extraLightDirXSlider.setValue(p.getExtraLightDirX());
            extraLightDirYSlider.setValue(p.getExtraLightDirY());
            extraLightDirZSlider.setValue(p.getExtraLightDirZ());
            extraLightColorPicker.setValue(Color.color(p.getExtraLightR(), p.getExtraLightG(), p.getExtraLightB()));
            extraLightIntensitySlider.setValue(p.getExtraLightIntensity());
            extraLightRangeSlider.setValue(p.getExtraLightRange());
            extraLightAreaRadiusSlider.setValue(p.getExtraLightAreaRadius());
            extraLightConeAngleSlider.setValue(p.getExtraLightConeAngle());
            extraLightConeSoftnessSlider.setValue(p.getExtraLightConeSoftness());
            updateAdditionalLightControlState();

        } finally {
            suppressRender = false;
        }
    }
}
