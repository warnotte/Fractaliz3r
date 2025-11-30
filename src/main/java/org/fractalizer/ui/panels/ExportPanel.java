package org.fractalizer.ui.panels;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fractalizer.ui.RenderController;

import java.io.File;
import java.util.function.Consumer;

/**
 * Panel for export settings: output size and export actions.
 */
public class ExportPanel extends ScrollPane {

    private final RenderController controller;
    private final Runnable renderFullCallback;
    private final Consumer<Double> progressCallback;
    private final Consumer<String> statusCallback;

    private TextField widthField;
    private TextField heightField;

    public ExportPanel(RenderController controller,
                       Runnable renderFullCallback,
                       Consumer<Double> progressCallback,
                       Consumer<String> statusCallback) {
        this.controller = controller;
        this.renderFullCallback = renderFullCallback;
        this.progressCallback = progressCallback;
        this.statusCallback = statusCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Output size
        Label sizeLabel = new Label("Output Size:");
        sizeLabel.setStyle("-fx-font-weight: bold;");

        // Preset sizes
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("1920x1080 (Full HD)", "3840x2160 (4K)", "7680x4320 (8K)", "Custom");
        presetCombo.setValue("1920x1080 (Full HD)");
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        presetCombo.setOnAction(e -> {
            String preset = presetCombo.getValue();
            if (preset.startsWith("1920")) {
                widthField.setText("1920");
                heightField.setText("1080");
            } else if (preset.startsWith("3840")) {
                widthField.setText("3840");
                heightField.setText("2160");
            } else if (preset.startsWith("7680")) {
                widthField.setText("7680");
                heightField.setText("4320");
            }
            updateOutputSize();
        });

        HBox sizeBox = new HBox(5);
        sizeBox.setAlignment(Pos.CENTER_LEFT);
        widthField = new TextField("1920");
        widthField.setPrefWidth(80);
        heightField = new TextField("1080");
        heightField.setPrefWidth(80);
        sizeBox.getChildren().addAll(widthField, new Label("x"), heightField);

        // Apply size button
        Button applySizeBtn = new Button("Apply Size");
        applySizeBtn.setOnAction(e -> updateOutputSize());
        applySizeBtn.setMaxWidth(Double.MAX_VALUE);

        // Buttons
        Button renderBtn = new Button("Render Full Quality (Space)");
        renderBtn.setOnAction(e -> renderFullCallback.run());
        renderBtn.setMaxWidth(Double.MAX_VALUE);
        renderBtn.setStyle("-fx-font-weight: bold;");

        Button exportBtn = new Button("Export PNG...");
        exportBtn.setOnAction(e -> exportImage());
        exportBtn.setMaxWidth(Double.MAX_VALUE);

        // Info
        Label infoLabel = new Label(
            "Tips:\n" +
            "- Use preview to find a good angle\n" +
            "- Press Space for full quality render\n" +
            "- Higher iterations = more detail\n" +
            "- 4K/8K may take several minutes"
        );
        infoLabel.setStyle("-fx-font-size: 11px;");
        infoLabel.setWrapText(true);

        panel.getChildren().addAll(
            sizeLabel, presetCombo, sizeBox,
            applySizeBtn,
            new Separator(),
            renderBtn,
            exportBtn,
            new Separator(),
            infoLabel
        );

        return panel;
    }

    public void updateOutputSize() {
        try {
            int width = Integer.parseInt(widthField.getText());
            int height = Integer.parseInt(heightField.getText());
            controller.setOutputSize(width, height);
        } catch (NumberFormatException e) {
            // Keep current size
        }
    }

    private void exportImage() {
        updateOutputSize();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Image");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fileChooser.setInitialFileName("fractal.png");

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            statusCallback.accept("Exporting...");
            progressCallback.accept(0.0);

            controller.exportToPNG(file, progressCallback::accept)
                .thenRun(() -> Platform.runLater(() ->
                    statusCallback.accept("Exported to: " + file.getName())
                ))
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Export failed");
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                    return null;
                });
        }
    }

    public int getOutputWidth() {
        try {
            return Integer.parseInt(widthField.getText());
        } catch (NumberFormatException e) {
            return 1920;
        }
    }

    public int getOutputHeight() {
        try {
            return Integer.parseInt(heightField.getText());
        } catch (NumberFormatException e) {
            return 1080;
        }
    }
}