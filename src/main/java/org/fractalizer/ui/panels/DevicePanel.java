package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.control.Alert;
import org.fractalizer.engine.OpenCLEngine;
import org.fractalizer.ui.FractalizerController;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel to choose the OpenCL device (GPU/CPU) at runtime.
 * Rebuilds the engine and kernels when switching.
 */
public class DevicePanel extends ScrollPane {

    private final FractalizerController controller;
    private final RenderCallback renderCallback;
    private final Consumer<String> statusCallback;

    private ComboBox<OpenCLEngine.DeviceDescriptor> deviceCombo;
    private Label currentLabel;

    public DevicePanel(FractalizerController controller,
                       RenderCallback renderCallback,
                       Consumer<String> statusCallback) {
        this.controller = controller;
        this.renderCallback = renderCallback;
        this.statusCallback = statusCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        currentLabel = new Label(getCurrentDeviceLabel());

        deviceCombo = new ComboBox<>();
        deviceCombo.setMaxWidth(Double.MAX_VALUE);
        deviceCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(OpenCLEngine.DeviceDescriptor item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(formatDescriptor(item));
                }
            }
        });
        deviceCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(OpenCLEngine.DeviceDescriptor item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(formatDescriptor(item));
                }
            }
        });

        refreshDevices();

        Button switchBtn = new Button("Utiliser ce device");
        switchBtn.setMaxWidth(Double.MAX_VALUE);
        switchBtn.setOnAction(e -> switchDevice());

        Button refreshBtn = new Button("Rafraîchir la liste");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        refreshBtn.setOnAction(e -> refreshDevices());

        Label tip = new Label("Astuce : sélectionner CPU pour comparer performances/qualité.\nLe rendu en cours est annulé lors du changement.");
        tip.setWrapText(true);
        tip.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        box.getChildren().addAll(
            new Label("Device OpenCL actuel :"),
            currentLabel,
            new Separator(),
            new Label("Choisir un device :"),
            deviceCombo,
            switchBtn,
            refreshBtn,
            new Separator(),
            tip
        );

        return box;
    }

    private void refreshDevices() {
        List<OpenCLEngine.DeviceDescriptor> devices = controller.getAvailableDeviceDescriptors();
        deviceCombo.getItems().setAll(devices);

        // Préselectionner le device courant si possible
        for (OpenCLEngine.DeviceDescriptor d : devices) {
            String current = controller.getDeviceName();
            if (d.name().equalsIgnoreCase(current)) {
                deviceCombo.getSelectionModel().select(d);
                return;
            }
        }
        if (!devices.isEmpty()) {
            deviceCombo.getSelectionModel().select(0);
        }
    }

    private void switchDevice() {
        OpenCLEngine.DeviceDescriptor selected = deviceCombo.getValue();
        if (selected == null) {
            return;
        }

        OpenCLEngine.DevicePreference pref = "CPU".equalsIgnoreCase(selected.type())
            ? OpenCLEngine.DevicePreference.CPU_ONLY
            : OpenCLEngine.DevicePreference.GPU_ONLY;

        try {
            int typeIndex = computeTypeIndex(selected);
            controller.switchDevice(pref, typeIndex);
            refreshDevices();
            currentLabel.setText(getCurrentDeviceLabel());
            statusCallback.accept("Device (" + controller.getDeviceType() + "): " + controller.getDeviceName());
            renderCallback.requestRender();
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Changement de device échoué");
            alert.setHeaderText("Impossible de changer de device");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        } catch (RuntimeException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("OpenCL");
            alert.setHeaderText("Erreur lors du changement de device");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    private String getCurrentDeviceLabel() {
        return controller.getDeviceType() + " - " + controller.getDeviceName();
    }

    private String formatDescriptor(OpenCLEngine.DeviceDescriptor d) {
        return "[" + d.index() + "] " + d.type() + " - " + d.vendor() + " - " + d.name();
    }

    private int computeTypeIndex(OpenCLEngine.DeviceDescriptor target) {
        List<OpenCLEngine.DeviceDescriptor> devices = controller.getAvailableDeviceDescriptors();
        int count = 0;
        for (OpenCLEngine.DeviceDescriptor d : devices) {
            if (d.type().equalsIgnoreCase(target.type())) {
                if (d.index() == target.index()) {
                    return count;
                }
                count++;
            }
        }
        return 0;
    }
}
