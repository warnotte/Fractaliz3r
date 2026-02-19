package org.fractalizer.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A professional UI component combining a Label and a Slider.
 * Supports fine control via mouse wheel and modifier keys.
 * Double-click on the label to type an exact value (Enter to confirm, Escape to cancel).
 */
public class EnhancedSlider extends VBox {

    private final Label label;
    private final Slider slider;
    private final HBox labelRow;
    private final String title;
    private final boolean isInteger;
    private final ToggleButton lockBtn;
    private int precision = 3;
    private boolean editing = false;

    public EnhancedSlider(String title, double min, double max, double initial, boolean isInteger) {
        super(4); // Spacing
        this.title = title;
        this.isInteger = isInteger;

        this.label = new Label();
        this.label.getStyleClass().add("bold-label");

        this.lockBtn = new ToggleButton("\uD83D\uDD13"); // unlocked icon
        lockBtn.getStyleClass().add("lock-btn");
        lockBtn.setTooltip(new Tooltip("Lock: protect from dice randomizer"));
        lockBtn.setFocusTraversable(false);
        lockBtn.selectedProperty().addListener((obs, old, locked) ->
                lockBtn.setText(locked ? "\uD83D\uDD12" : "\uD83D\uDD13"));

        labelRow = new HBox(4, label, lockBtn);
        labelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);

        this.slider = new Slider(min, max, initial);
        this.slider.setFocusTraversable(false);

        updateLabel(initial);
        slider.valueProperty().addListener((obs, old, val) -> {
            if (!editing) updateLabel(val.doubleValue());
        });

        // Double-click on label → inline text field for exact value entry
        label.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                startInlineEdit();
                e.consume();
            }
        });

        // Also support double-click on the slider track itself
        slider.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                startInlineEdit();
                e.consume();
            }
        });

        // Improved Tooltip for better guidance
        Tooltip help = new Tooltip(
            "Double-click label or slider to type exact value\n" +
            "Adjust Value: Hold a modifier and Scroll:\n" +
            " \u2022 ALT + Scroll: Normal adjustment\n" +
            " \u2022 SHIFT + Scroll: Fine control (Precision)\n" +
            " \u2022 CTRL + Scroll: Fast movement"
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

        this.getChildren().addAll(labelRow, slider);
    }

    private void startInlineEdit() {
        if (editing) return;
        editing = true;

        String currentValue;
        if (isInteger) {
            currentValue = String.valueOf((int) Math.round(slider.getValue()));
        } else {
            // Show real value, not display-rounded value
            currentValue = String.valueOf(slider.getValue());
        }

        TextField field = new TextField(currentValue);
        field.getStyleClass().add("inline-edit-field");
        field.setPrefWidth(label.getWidth());
        field.selectAll();

        // Replace label with text field in the HBox
        int idx = labelRow.getChildren().indexOf(label);
        labelRow.getChildren().set(idx, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.requestFocus();

        Runnable commit = () -> {
            if (!editing) return;
            try {
                double val = Double.parseDouble(field.getText().replace(',', '.'));
                val = Math.max(slider.getMin(), Math.min(slider.getMax(), val));
                if (isInteger) val = Math.round(val);
                slider.setValue(val);
            } catch (NumberFormatException ignored) {
                // Invalid input — keep current value
            }
            finishEdit(field);
        };

        Runnable cancel = () -> {
            if (!editing) return;
            finishEdit(field);
        };

        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) { commit.run(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { cancel.run(); e.consume(); }
        });

        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commit.run();
        });
    }

    private void finishEdit(TextField field) {
        editing = false;
        int idx = labelRow.getChildren().indexOf(field);
        if (idx >= 0) {
            labelRow.getChildren().set(idx, label);
            HBox.setHgrow(label, Priority.ALWAYS);
        }
        updateLabel(slider.getValue());
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

    public boolean isInteger() { return isInteger; }

    public boolean isLocked() { return lockBtn.isSelected(); }

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
