package org.fractalizer.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A professional UI component combining a Label and a Slider.
 * Supports fine control via mouse wheel and modifier keys.
 * Only captures scroll events when a modifier key is pressed to avoid conflicts with parent ScrollPanes.
 */
public class EnhancedSlider extends VBox {

    private final Label label;
    private final Slider slider;
    private final String title;
    private final boolean isInteger;
    private int precision = 3;

    public EnhancedSlider(String title, double min, double max, double initial, boolean isInteger) {
        super(4); // Spacing
        this.title = title;
        this.isInteger = isInteger;

        this.label = new Label();
        this.label.setStyle("-fx-font-weight: bold;");
        
        this.slider = new Slider(min, max, initial);
        this.slider.setFocusTraversable(false);

        updateLabel(initial);
        slider.valueProperty().addListener((obs, old, val) -> updateLabel(val.doubleValue()));

        // Improved Tooltip for better guidance
        Tooltip help = new Tooltip(
            "Navigation: Scroll normally to move the panel\n" +
            "Adjust Value: Hold a modifier and Scroll over slider:\n" +
            " • ALT + Scroll: Normal adjustment\n" +
            " • SHIFT + Scroll: Fine control (Precision)\n" +
            " • CTRL + Scroll: Fast movement"
        );
        help.setShowDelay(Duration.millis(300));
        Tooltip.install(this, help);

        // Smart Mouse Wheel control with ScrollPane compatibility
        this.setOnScroll(e -> {
            double scrollDelta = e.getDeltaY();
            if (scrollDelta == 0) scrollDelta = e.getDeltaX(); // Handle SHIFT remapping
            
            // CRITICAL: If no modifier is pressed, do NOT consume the event.
            // This allows the parent ScrollPane to scroll normally.
            boolean hasModifier = e.isShiftDown() || e.isControlDown() || e.isAltDown();
            if (!hasModifier || scrollDelta == 0) return;

            double range = slider.getMax() - slider.getMin();
            double step = range / 100.0; // Default 1% of range

            // Adjust speed based on specific modifiers
            if (e.isShiftDown()) step /= 10.0;      // 0.1% precision
            else if (e.isControlDown()) step *= 10.0; // 10% movement
            // ALT (or any other modifier) results in the default 1% step

            if (isInteger) {
                step = Math.max(1.0, Math.round(step));
            }

            double newValue = slider.getValue() + (scrollDelta > 0 ? step : -step);
            slider.setValue(newValue);
            
            // Consume the event ONLY when we are actually adjusting the value
            e.consume();
        });

        this.getChildren().addAll(label, slider);
    }

    private void updateLabel(double value) {
        if (isInteger) {
            label.setText(String.format("%s: %d", title, (int) Math.round(value)));
        } else {
            String format = "%s: %." + precision + "f";
            label.setText(String.format(format, title, value));
        }
    }

    // --- Configuration API ---

    public Slider getSlider() { return slider; }
    
    public double getValue() { return slider.getValue(); }
    
    public void setValue(double value) { slider.setValue(value); }

    public void setPrecision(int decimals) {
        this.precision = decimals;
        updateLabel(slider.getValue());
    }

    public void showTickMarks(boolean show) {
        slider.setShowTickMarks(show);
        slider.setShowTickLabels(show);
    }

    public void setMajorTickUnit(double value) {
        slider.setMajorTickUnit(value);
    }

    public void setOnAction(java.util.function.Consumer<Double> action) {
        slider.valueProperty().addListener((obs, old, val) -> action.accept(val.doubleValue()));
    }
}
