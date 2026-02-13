package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import org.fractalizer.ui.GLSLFractalizerController;

/**
 * Panel showing GLSL/OpenGL device information.
 * Unlike OpenCL, GLSL uses the system's OpenGL driver directly.
 */
public class GLSLDevicePanel extends ScrollPane {

    private final GLSLFractalizerController controller;

    public GLSLDevicePanel(GLSLFractalizerController controller) {
        this.controller = controller;
        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        Label header = new Label("Rendu GLSL/OpenGL");
        header.getStyleClass().add("title-label");

        Label rendererLabel = new Label("GPU: " + controller.getDeviceName());
        rendererLabel.setWrapText(true);

        Label glVersionLabel = new Label("OpenGL: " + controller.getGLVersion());
        glVersionLabel.setWrapText(true);

        Label glslVersionLabel = new Label("GLSL: " + controller.getGLSLVersion());
        glslVersionLabel.setWrapText(true);

        Label infoLabel = new Label(
            "Le moteur GLSL utilise le rendu progressif :\n" +
            "- Chaque frame accumule des samples\n" +
            "- La qualité s'améliore au fil du temps\n" +
            "- Pas de timeout GPU (watchdog)\n" +
            "- Idéal pour le path tracing"
        );
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("small-label");

        Label tip = new Label(
            "Conseil :\n" +
            "Augmentez le nombre de samples dans\n" +
            "l'onglet Quality pour une meilleure qualité."
        );
        tip.setWrapText(true);
        tip.getStyleClass().add("info-label");

        box.getChildren().addAll(
            header,
            new Separator(),
            rendererLabel,
            glVersionLabel,
            glslVersionLabel,
            new Separator(),
            infoLabel,
            new Separator(),
            tip
        );

        return box;
    }
}
