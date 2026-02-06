package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.components.EnhancedSlider;

/**
 * Panel for fractal type selection and fractal-specific parameters.
 * Implements Refreshable for save/load configuration support.
 */
public class FractalPanel extends ScrollPane implements Refreshable {

    private final RenderController controller;
    private final RenderCallback renderCallback;

    // Current state
    private AbstractFractalParams params;
    private Camera camera;

    // Suppress render during refresh
    private boolean suppressRender = false;

    // Callback notified when fractal type changes (for animation manager)
    private java.util.function.BiConsumer<FractalType, AbstractFractalParams> onFractalTypeChanged;

    // Fractal type combo
    private ComboBox<FractalType> typeCombo;

    // Fractal-specific control containers
    private VBox mandelbulbControls;
    private VBox mandelboxControls;
    private VBox mengerControls;
    private VBox kaleidoscopicControls;
    private VBox julia3dControls;
    private VBox polyhedralControls;

    // Common controls
    private EnhancedSlider speedSlider;
    private Label positionLabel;

    // Mandelbulb sliders
    private EnhancedSlider mbPowerSlider;
    private EnhancedSlider mbIterSlider;
    private EnhancedSlider mbBailoutSlider;

    // Mandelbox sliders
    private EnhancedSlider mbxScaleSlider;
    private EnhancedSlider mbxMinRadiusSlider;
    private EnhancedSlider mbxFixedRadiusSlider;
    private EnhancedSlider mbxFoldingLimitSlider;
    private EnhancedSlider mbxIterSlider;

    // Menger sliders
    private EnhancedSlider mengerIterSlider;
    private EnhancedSlider mengerScaleSlider;

    // Kaleidoscopic sliders
    private EnhancedSlider kIterSlider;
    private EnhancedSlider kScaleSlider;
    private EnhancedSlider kOffsetSlider;
    private EnhancedSlider kFoldXSlider;
    private EnhancedSlider kFoldYSlider;

    // Julia3D sliders
    private EnhancedSlider j3dIterSlider;
    private EnhancedSlider j3dCxSlider;
    private EnhancedSlider j3dCySlider;
    private EnhancedSlider j3dCzSlider;
    private EnhancedSlider j3dCwSlider;

    // Polyhedral sliders
    private ComboBox<PolyhedralIFSParams.PolyType> polyTypeCombo;
    private EnhancedSlider polyIterSlider;
    private EnhancedSlider polyScaleSlider;
    private EnhancedSlider polyOffXSlider, polyOffYSlider, polyOffZSlider;
    private EnhancedSlider polyShiftXSlider, polyShiftYSlider, polyShiftZSlider;
    private EnhancedSlider polyRot1XSlider, polyRot1YSlider, polyRot1ZSlider;
    private EnhancedSlider polyRot2XSlider, polyRot2YSlider, polyRot2ZSlider;

    public FractalPanel(RenderController controller, AbstractFractalParams initialParams,
                        RenderCallback renderCallback) {
        this.controller = controller;
        this.params = initialParams;
        this.camera = initialParams.getCamera();
        this.renderCallback = renderCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Fractal type selector
        Label typeLabel = new Label("Fractal Type:");
        typeCombo = createTypeComboBox();

        // Create all fractal-specific controls
        createMandelbulbControls();
        createMandelboxControls();
        createMengerControls();
        createKaleidoscopicControls();
        createJulia3dControls();
        createPolyhedralControls();

        // Movement speed
        speedSlider = new EnhancedSlider("Move Speed", 0.001, 0.5, 0.1, false);
        speedSlider.setOnAction(v -> camera.setMoveSpeed(v.floatValue()));

        // Position display
        positionLabel = new Label("Pos: (0.00, 0.00, -3.00)");
        positionLabel.setStyle("-fx-font-family: monospace;");

        // Navigation help
        Label navLabel = new Label("Navigation:");
        navLabel.setStyle("-fx-font-weight: bold;");
        Label helpLabel = new Label(
            "Drag: Look around\n" +
            "Arrows: Move\n" +
            "Q/E: Roll\n" +
            "PgUp/Dn: Up/Down\n" +
            "R: Reset camera\n" +
            "Space: Render full\n" +
            "Scroll: Adjust speed"
        );
        helpLabel.setStyle("-fx-font-size: 11px;");

        // Reset button
        Button resetBtn = new Button("Reset Camera (R)");
        resetBtn.setOnAction(e -> {
            camera.reset();
            renderCallback.requestRender();
            updatePositionLabel();
        });
        resetBtn.setMaxWidth(Double.MAX_VALUE);

        panel.getChildren().addAll(
            typeLabel, typeCombo,
            new Separator(),
            mandelbulbControls,
            mandelboxControls,
            mengerControls,
            kaleidoscopicControls,
            julia3dControls,
            polyhedralControls,
            new Separator(),
            speedSlider,
            new Separator(),
            positionLabel,
            new Separator(),
            navLabel, helpLabel,
            new Separator(),
            resetBtn
        );

        return panel;
    }

    private void createPolyhedralControls() {
        polyhedralControls = new VBox(8);

        Label typeLabel = new Label("Symmetry Type:");
        polyTypeCombo = new ComboBox<>();
        polyTypeCombo.getItems().addAll(PolyhedralIFSParams.PolyType.values());
        polyTypeCombo.setValue(PolyhedralIFSParams.PolyType.OCTAHEDRAL);
        polyTypeCombo.setMaxWidth(Double.MAX_VALUE);
        polyTypeCombo.setOnAction(e -> {
            if (!suppressRender && params instanceof PolyhedralIFSParams p) {
                p.setPolyType(polyTypeCombo.getValue());
                renderCallback.requestRender();
            }
        });

        polyIterSlider = new EnhancedSlider("Iterations", 4, 25, 15, true);
        polyIterSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setMaxIterations(v.intValue()); renderCallback.requestRender(); });

        polyScaleSlider = new EnhancedSlider("Scale", 1.1, 3.0, 2.0, false);
        polyScaleSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setScale(v.floatValue()); renderCallback.requestRender(); });

        // Offset group
        polyOffXSlider = new EnhancedSlider("X Offset", 0, 3, 1, false);
        polyOffXSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setOffsetX(v.floatValue()); renderCallback.requestRender(); });
        polyOffYSlider = new EnhancedSlider("Y Offset", 0, 3, 1, false);
        polyOffYSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setOffsetY(v.floatValue()); renderCallback.requestRender(); });
        polyOffZSlider = new EnhancedSlider("Z Offset", 0, 3, 1, false);
        polyOffZSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setOffsetZ(v.floatValue()); renderCallback.requestRender(); });

        // Shift group
        polyShiftXSlider = new EnhancedSlider("Shift X", -1, 1, 0, false);
        polyShiftXSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setShiftX(v.floatValue()); renderCallback.requestRender(); });
        polyShiftYSlider = new EnhancedSlider("Shift Y", -1, 1, 0, false);
        polyShiftYSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setShiftY(v.floatValue()); renderCallback.requestRender(); });
        polyShiftZSlider = new EnhancedSlider("Shift Z", -1, 1, 0, false);
        polyShiftZSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setShiftZ(v.floatValue()); renderCallback.requestRender(); });

        // Rotation 1 group
        polyRot1XSlider = new EnhancedSlider("Rot1 X", -180, 180, 0, false);
        polyRot1XSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot1X(v.floatValue()); renderCallback.requestRender(); });
        polyRot1YSlider = new EnhancedSlider("Rot1 Y", -180, 180, 0, false);
        polyRot1YSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot1Y(v.floatValue()); renderCallback.requestRender(); });
        polyRot1ZSlider = new EnhancedSlider("Rot1 Z", -180, 180, 0, false);
        polyRot1ZSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot1Z(v.floatValue()); renderCallback.requestRender(); });

        // Rotation 2 group
        polyRot2XSlider = new EnhancedSlider("Rot2 X", -180, 180, 0, false);
        polyRot2XSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot2X(v.floatValue()); renderCallback.requestRender(); });
        polyRot2YSlider = new EnhancedSlider("Rot2 Y", -180, 180, 0, false);
        polyRot2YSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot2Y(v.floatValue()); renderCallback.requestRender(); });
        polyRot2ZSlider = new EnhancedSlider("Rot2 Z", -180, 180, 0, false);
        polyRot2ZSlider.setOnAction(v -> { if(!suppressRender && params instanceof PolyhedralIFSParams p) p.setRot2Z(v.floatValue()); renderCallback.requestRender(); });

        // Presets
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button octaClassicBtn = new Button("Octa Classic");
        octaClassicBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.OCTAHEDRAL, 15, 2.0, 1,1,1, 0,0,0, 0,0,0, 0,0,0));

        Button twistedOctaBtn = new Button("Twisted Octa");
        twistedOctaBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.OCTAHEDRAL, 15, 2.0, 1,1,1, 0,0,0, 15,10,0, 0,0,0));

        Button sierpTetraBtn = new Button("Sierpinski Tetra");
        sierpTetraBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.TETRAHEDRON, 15, 2.0, 1,1,1, 0,0,0, 0,0,0, 0,0,0));

        Button icosaCrystalBtn = new Button("Icosa Crystal");
        icosaCrystalBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.ICOSAHEDRON, 15, 2.0, 1,1,1, 0,0,0, 5,5,0, 0,0,0));

        Button dodecaFlowerBtn = new Button("Dodeca Flower");
        dodecaFlowerBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.DODECAHEDRON, 12, 2.0, 1,1,1, 0,0,0, 6,0,4, 0,3,0));

        Button alienBtn = new Button("Alien Artifact");
        alienBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.OCTAHEDRAL, 18, 2.3, 1.2,0.8,1.0, 0.1,-0.05,0.1, 12,8,5, -5,3,0));

        Button coralBtn = new Button("Deep Coral");
        coralBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.ICOSAHEDRON, 16, 1.9, 1.0,1.0,1.0, 0,0,0, 8,12,0, 3,-3,0));

        Button cathedralBtn = new Button("Cathedral");
        cathedralBtn.setOnAction(e -> applyPolyPreset(
            PolyhedralIFSParams.PolyType.DODECAHEDRON, 15, 2.0, 1,1.15,1, 0,0,0, 0,5,0, 3,0,0));

        javafx.scene.layout.HBox presetRow1 = new javafx.scene.layout.HBox(5);
        presetRow1.getChildren().addAll(octaClassicBtn, twistedOctaBtn, sierpTetraBtn, icosaCrystalBtn);
        javafx.scene.layout.HBox presetRow2 = new javafx.scene.layout.HBox(5);
        presetRow2.getChildren().addAll(dodecaFlowerBtn, alienBtn, coralBtn, cathedralBtn);

        polyhedralControls.getChildren().addAll(
            typeLabel, polyTypeCombo,
            polyIterSlider, polyScaleSlider,
            new Separator(),
            polyOffXSlider, polyOffYSlider, polyOffZSlider,
            new Separator(),
            polyShiftXSlider, polyShiftYSlider, polyShiftZSlider,
            new Separator(),
            polyRot1XSlider, polyRot1YSlider, polyRot1ZSlider,
            new Separator(),
            polyRot2XSlider, polyRot2YSlider, polyRot2ZSlider,
            new Separator(),
            presetLabel, presetRow1, presetRow2
        );
        polyhedralControls.setVisible(false);
        polyhedralControls.setManaged(false);
    }

    private void applyPolyPreset(PolyhedralIFSParams.PolyType type, int iter, double scale,
                                  double ox, double oy, double oz,
                                  double sx, double sy, double sz,
                                  double r1x, double r1y, double r1z,
                                  double r2x, double r2y, double r2z) {
        polyTypeCombo.setValue(type);
        polyIterSlider.setValue(iter);
        polyScaleSlider.setValue(scale);
        polyOffXSlider.setValue(ox);
        polyOffYSlider.setValue(oy);
        polyOffZSlider.setValue(oz);
        polyShiftXSlider.setValue(sx);
        polyShiftYSlider.setValue(sy);
        polyShiftZSlider.setValue(sz);
        polyRot1XSlider.setValue(r1x);
        polyRot1YSlider.setValue(r1y);
        polyRot1ZSlider.setValue(r1z);
        polyRot2XSlider.setValue(r2x);
        polyRot2YSlider.setValue(r2y);
        polyRot2ZSlider.setValue(r2z);
    }

    private void createJulia3dControls() {
        julia3dControls = new VBox(8);

        Label titleLabel = new Label("Julia 3D (Quaternion)");
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label infoLabel = new Label("q' = q² + c (quaternion iteration)");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Iterations
        j3dIterSlider = new EnhancedSlider("Iterations", 4, 20, 12, true);
        j3dIterSlider.showTickMarks(true);
        j3dIterSlider.setMajorTickUnit(4.0);
        j3dIterSlider.setOnAction(v -> { if(!suppressRender && params instanceof Julia3DParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        // Julia constant C components
        Label cLabel = new Label("Julia Constant (c):");
        cLabel.setStyle("-fx-font-weight: bold;");

        j3dCxSlider = new EnhancedSlider("cx", -1.0, 1.0, -0.2, false);
        j3dCxSlider.setOnAction(v -> { if(!suppressRender && params instanceof Julia3DParams p) { p.setJuliaCx(v.floatValue()); renderCallback.requestRender(); } });

        j3dCySlider = new EnhancedSlider("cy", -1.0, 1.0, 0.8, false);
        j3dCySlider.setOnAction(v -> { if(!suppressRender && params instanceof Julia3DParams p) { p.setJuliaCy(v.floatValue()); renderCallback.requestRender(); } });

        j3dCzSlider = new EnhancedSlider("cz", -1.0, 1.0, 0.0, false);
        j3dCzSlider.setOnAction(v -> { if(!suppressRender && params instanceof Julia3DParams p) { p.setJuliaCz(v.floatValue()); renderCallback.requestRender(); } });

        j3dCwSlider = new EnhancedSlider("cw", -1.0, 1.0, 0.0, false);
        j3dCwSlider.setOnAction(v -> { if(!suppressRender && params instanceof Julia3DParams p) { p.setJuliaCw(v.floatValue()); renderCallback.requestRender(); } });

        // Preset buttons
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button classicBtn = new Button("Classic");
        classicBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.2);
            j3dCySlider.setValue(0.8);
            j3dCzSlider.setValue(0.0);
            j3dCwSlider.setValue(0.0);
        });

        Button organicBtn = new Button("Organic");
        organicBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.291);
            j3dCySlider.setValue(-0.399);
            j3dCzSlider.setValue(0.339);
            j3dCwSlider.setValue(0.437);
        });

        Button spikyBtn = new Button("Spiky");
        spikyBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.125);
            j3dCySlider.setValue(-0.256);
            j3dCzSlider.setValue(0.847);
            j3dCwSlider.setValue(0.0895);
        });

        Button spiralBtn = new Button("Spiral");
        spiralBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.4);
            j3dCySlider.setValue(0.6);
            j3dCzSlider.setValue(0.2);
            j3dCwSlider.setValue(-0.1);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(classicBtn, organicBtn, spikyBtn, spiralBtn);

        julia3dControls.getChildren().addAll(
            titleLabel, infoLabel,
            j3dIterSlider,
            new Separator(),
            cLabel,
            j3dCxSlider, j3dCySlider, j3dCzSlider, j3dCwSlider,
            new Separator(),
            presetLabel, presetBox
        );
        julia3dControls.setVisible(false);
        julia3dControls.setManaged(false);
    }

    private void createKaleidoscopicControls() {
        kaleidoscopicControls = new VBox(8);

        // Info label about parameter relationships
        Label infoLabel = new Label("Classic Sierpinski: Scale=2, Offset=3");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        kIterSlider = new EnhancedSlider("Iterations", 4, 25, 15, true);
        kIterSlider.showTickMarks(true);
        kIterSlider.setMajorTickUnit(5.0);
        kIterSlider.setOnAction(v -> { if(!suppressRender && params instanceof KaleidoscopicIFSParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        kScaleSlider = new EnhancedSlider("Scale", 1.5, 3.0, 2.0, false);
        kScaleSlider.showTickMarks(true);
        kScaleSlider.setMajorTickUnit(0.5);
        kScaleSlider.setOnAction(v -> { if(!suppressRender && params instanceof KaleidoscopicIFSParams p) { p.setScale(v.floatValue()); renderCallback.requestRender(); } });

        kOffsetSlider = new EnhancedSlider("Offset", 1.0, 5.0, 3.0, false);
        kOffsetSlider.showTickMarks(true);
        kOffsetSlider.setMajorTickUnit(1.0);
        kOffsetSlider.setOnAction(v -> { if(!suppressRender && params instanceof KaleidoscopicIFSParams p) { p.setOffset(v.floatValue(), 0, 0); renderCallback.requestRender(); } });

        kFoldXSlider = new EnhancedSlider("Rotation X", -30, 30, 0, false);
        kFoldXSlider.showTickMarks(true);
        kFoldXSlider.setMajorTickUnit(15.0);
        kFoldXSlider.setOnAction(v -> { if(!suppressRender && params instanceof KaleidoscopicIFSParams p) { p.setFoldAngleX(v.floatValue()); renderCallback.requestRender(); } });

        kFoldYSlider = new EnhancedSlider("Rotation Y", -30, 30, 0, false);
        kFoldYSlider.showTickMarks(true);
        kFoldYSlider.setMajorTickUnit(15.0);
        kFoldYSlider.setOnAction(v -> { if(!suppressRender && params instanceof KaleidoscopicIFSParams p) { p.setFoldAngleY(v.floatValue()); renderCallback.requestRender(); } });

        // Preset buttons for common configurations
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button sierpinskiBtn = new Button("Sierpinski");
        sierpinskiBtn.setOnAction(e -> {
            kScaleSlider.setValue(2.0);
            kOffsetSlider.setValue(3.0);
            kFoldXSlider.setValue(0);
            kFoldYSlider.setValue(0);
        });

        Button variation1Btn = new Button("Variation 1");
        variation1Btn.setOnAction(e -> {
            kScaleSlider.setValue(2.0);
            kOffsetSlider.setValue(2.5);
            kFoldXSlider.setValue(10);
            kFoldYSlider.setValue(5);
        });

        Button variation2Btn = new Button("Variation 2");
        variation2Btn.setOnAction(e -> {
            kScaleSlider.setValue(2.2);
            kOffsetSlider.setValue(3.5);
            kFoldXSlider.setValue(-15);
            kFoldYSlider.setValue(8);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(sierpinskiBtn, variation1Btn, variation2Btn);

        kaleidoscopicControls.getChildren().addAll(
            infoLabel,
            kIterSlider, kScaleSlider, kOffsetSlider,
            kFoldXSlider, kFoldYSlider,
            new Separator(),
            presetLabel, presetBox
        );
        kaleidoscopicControls.setVisible(false);
        kaleidoscopicControls.setManaged(false);
    }

    private void createMengerControls() {
        mengerControls = new VBox(8);

        mengerIterSlider = new EnhancedSlider("Iterations", 2, 10, 6, true);
        mengerIterSlider.showTickMarks(true);
        mengerIterSlider.setMajorTickUnit(2.0);
        mengerIterSlider.setOnAction(v -> { if(!suppressRender && params instanceof MengerSpongeParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        mengerScaleSlider = new EnhancedSlider("Scale", 2.0, 4.0, 3.0, false);
        mengerScaleSlider.setOnAction(v -> { if(!suppressRender && params instanceof MengerSpongeParams p) { p.setScale(v.floatValue()); renderCallback.requestRender(); } });

        mengerControls.getChildren().addAll(
            mengerIterSlider, mengerScaleSlider
        );
        mengerControls.setVisible(false);
        mengerControls.setManaged(false);
    }

    private void createMandelboxControls() {
        mandelboxControls = new VBox(8);

        mbxScaleSlider = new EnhancedSlider("Scale", -3, 3, 2, false);
        mbxScaleSlider.showTickMarks(true);
        mbxScaleSlider.setMajorTickUnit(1.0);
        mbxScaleSlider.setOnAction(v -> { if(!suppressRender && params instanceof MandelboxParams p) { p.setScale(v.floatValue()); renderCallback.requestRender(); } });

        mbxMinRadiusSlider = new EnhancedSlider("Min Radius", 0.01, 1.0, 0.25, false);
        mbxMinRadiusSlider.setOnAction(v -> { if(!suppressRender && params instanceof MandelboxParams p) { p.setMinRadius(v.floatValue()); renderCallback.requestRender(); } });

        mbxFixedRadiusSlider = new EnhancedSlider("Fixed Radius", 0.5, 2.0, 1.0, false);
        mbxFixedRadiusSlider.setOnAction(v -> { if(!suppressRender && params instanceof MandelboxParams p) { p.setFixedRadius(v.floatValue()); renderCallback.requestRender(); } });

        mbxFoldingLimitSlider = new EnhancedSlider("Folding Limit", 0.5, 2.0, 1.0, false);
        mbxFoldingLimitSlider.setOnAction(v -> { if(!suppressRender && params instanceof MandelboxParams p) { p.setFoldingLimit(v.floatValue()); renderCallback.requestRender(); } });

        mbxIterSlider = new EnhancedSlider("Iterations", 5, 30, 15, true);
        mbxIterSlider.showTickMarks(true);
        mbxIterSlider.setMajorTickUnit(5.0);
        mbxIterSlider.setOnAction(v -> { if(!suppressRender && params instanceof MandelboxParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        mandelboxControls.getChildren().addAll(
            mbxScaleSlider, mbxMinRadiusSlider, mbxFixedRadiusSlider,
            mbxFoldingLimitSlider, mbxIterSlider
        );
        mandelboxControls.setVisible(false);
        mandelboxControls.setManaged(false);
    }

    private void createMandelbulbControls() {
        mandelbulbControls = new VBox(8);

        mbPowerSlider = new EnhancedSlider("Power", 2, 16, 8, false);
        mbPowerSlider.showTickMarks(true);
        mbPowerSlider.setMajorTickUnit(2.0);
        mbPowerSlider.setPrecision(1);
        mbPowerSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.power(v.floatValue());
                renderCallback.requestRender();
            }
        });

        mbIterSlider = new EnhancedSlider("Iterations", 5, 30, 15, true);
        mbIterSlider.showTickMarks(true);
        mbIterSlider.setMajorTickUnit(5.0);
        mbIterSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.iterations(v.intValue());
                renderCallback.requestRender();
            }
        });

        mbBailoutSlider = new EnhancedSlider("Bailout", 1, 16, 2.0, false);
        mbBailoutSlider.setPrecision(1);
        mbBailoutSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.setBailout(v.floatValue());
                renderCallback.requestRender();
            }
        });

        mandelbulbControls.getChildren().addAll(
            mbPowerSlider, mbIterSlider, mbBailoutSlider
        );
    }

    private ComboBox<FractalType> createTypeComboBox() {
        ComboBox<FractalType> combo = new ComboBox<>();
        combo.getItems().addAll(FractalType.values());
        combo.setValue(FractalType.MANDELBULB);
        combo.setMaxWidth(Double.MAX_VALUE);

        // Display friendly names
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });

        // Fractal type change handler
        combo.setOnAction(e -> {
            if (suppressRender) return;
            FractalType selectedType = combo.getValue();
            controller.setFractalType(selectedType);
            params = (AbstractFractalParams) controller.getParams();
            camera = params.getCamera();

            // Hide all fractal-specific controls
            mandelbulbControls.setVisible(false);
            mandelbulbControls.setManaged(false);
            mandelboxControls.setVisible(false);
            mandelboxControls.setManaged(false);
            mengerControls.setVisible(false);
            mengerControls.setManaged(false);
            kaleidoscopicControls.setVisible(false);
            kaleidoscopicControls.setManaged(false);
            julia3dControls.setVisible(false);
            julia3dControls.setManaged(false);
            polyhedralControls.setVisible(false);
            polyhedralControls.setManaged(false);

            // Show only the relevant controls
            switch (selectedType) {
                case MANDELBULB -> {
                    mandelbulbControls.setVisible(true);
                    mandelbulbControls.setManaged(true);
                }
                case MANDELBOX -> {
                    mandelboxControls.setVisible(true);
                    mandelboxControls.setManaged(true);
                }
                case MENGER_SPONGE -> {
                    mengerControls.setVisible(true);
                    mengerControls.setManaged(true);
                }
                case KALEIDOSCOPIC_IFS -> {
                    kaleidoscopicControls.setVisible(true);
                    kaleidoscopicControls.setManaged(true);
                }
                case JULIA_3D -> {
                    julia3dControls.setVisible(true);
                    julia3dControls.setManaged(true);
                }
                case POLYHEDRAL_IFS -> {
                    polyhedralControls.setVisible(true);
                    polyhedralControls.setManaged(true);
                }
            }

            // Sync all UI controls to the new (or cached) parameters
            refreshFromParams(true);

            // Notify animation manager of fractal type change
            if (onFractalTypeChanged != null) {
                onFractalTypeChanged.accept(selectedType, params);
            }

            renderCallback.requestRender();
        });

        return combo;
    }

    /**
     * Refresh all UI controls from current params.
     * Called after loading a configuration.
     */
    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress;
        try {
            // Update fractal type combo and show correct controls
            FractalType type = params.getType();
            typeCombo.setValue(type);

            // Hide all, then show relevant
            mandelbulbControls.setVisible(false);
            mandelbulbControls.setManaged(false);
            mandelboxControls.setVisible(false);
            mandelboxControls.setManaged(false);
            mengerControls.setVisible(false);
            mengerControls.setManaged(false);
            kaleidoscopicControls.setVisible(false);
            kaleidoscopicControls.setManaged(false);
            julia3dControls.setVisible(false);
            julia3dControls.setManaged(false);
            polyhedralControls.setVisible(false);
            polyhedralControls.setManaged(false);

            // Update fractal-specific controls based on type
            if (params instanceof MandelbulbParams mb) {
                mandelbulbControls.setVisible(true);
                mandelbulbControls.setManaged(true);
                mbPowerSlider.setValue(mb.getPower());
                mbIterSlider.setValue(mb.getMaxIterations());
                mbBailoutSlider.setValue(mb.getBailout());
            } else if (params instanceof MandelboxParams mbx) {
                mandelboxControls.setVisible(true);
                mandelboxControls.setManaged(true);
                mbxScaleSlider.setValue(mbx.getScale());
                mbxMinRadiusSlider.setValue(mbx.getMinRadius());
                mbxFixedRadiusSlider.setValue(mbx.getFixedRadius());
                mbxFoldingLimitSlider.setValue(mbx.getFoldingLimit());
                mbxIterSlider.setValue(mbx.getMaxIterations());
            } else if (params instanceof MengerSpongeParams ms) {
                mengerControls.setVisible(true);
                mengerControls.setManaged(true);
                mengerIterSlider.setValue(ms.getMaxIterations());
                mengerScaleSlider.setValue(ms.getScale());
            } else if (params instanceof KaleidoscopicIFSParams k) {
                kaleidoscopicControls.setVisible(true);
                kaleidoscopicControls.setManaged(true);
                kIterSlider.setValue(k.getMaxIterations());
                kScaleSlider.setValue(k.getScale());
                kOffsetSlider.setValue(k.getOffsetX());
                kFoldXSlider.setValue(k.getFoldAngleX());
                kFoldYSlider.setValue(k.getFoldAngleY());
            } else if (params instanceof Julia3DParams j) {
                julia3dControls.setVisible(true);
                julia3dControls.setManaged(true);
                j3dIterSlider.setValue(j.getMaxIterations());
                j3dCxSlider.setValue(j.getJuliaCx());
                j3dCySlider.setValue(j.getJuliaCy());
                j3dCzSlider.setValue(j.getJuliaCz());
                j3dCwSlider.setValue(j.getJuliaCw());
            } else if (params instanceof PolyhedralIFSParams p) {
                polyhedralControls.setVisible(true);
                polyhedralControls.setManaged(true);
                polyTypeCombo.setValue(p.getPolyType());
                polyIterSlider.setValue(p.getMaxIterations());
                polyScaleSlider.setValue(p.getScale());
                polyOffXSlider.setValue(p.getOffsetX());
                polyOffYSlider.setValue(p.getOffsetY());
                polyOffZSlider.setValue(p.getOffsetZ());
                polyShiftXSlider.setValue(p.getShiftX());
                polyShiftYSlider.setValue(p.getShiftY());
                polyShiftZSlider.setValue(p.getShiftZ());
                polyRot1XSlider.setValue(p.getRot1X());
                polyRot1YSlider.setValue(p.getRot1Y());
                polyRot1ZSlider.setValue(p.getRot1Z());
                polyRot2XSlider.setValue(p.getRot2X());
                polyRot2YSlider.setValue(p.getRot2Y());
                polyRot2ZSlider.setValue(p.getRot2Z());
            }

            // Update common controls
            speedSlider.setValue(camera.getMoveSpeed());
            updatePositionLabel();

        } finally {
            suppressRender = false;
        }
    }

    // Public methods for external access
    public void updatePositionLabel() {
        if (positionLabel != null && camera != null) {
            float[] pos = camera.getPosition();
            positionLabel.setText(String.format("Pos: (%.2f, %.2f, %.2f)", pos[0], pos[1], pos[2]));
        }
    }

    public Camera getCamera() {
        return camera;
    }

    public AbstractFractalParams getParams() {
        return params;
    }

    public void setSpeedSliderValue(double value) {
        if (speedSlider != null) {
            speedSlider.setValue(value);
        }
    }

    /**
     * Update params reference (for load configuration).
     */
    public void setParams(AbstractFractalParams newParams) {
        this.params = newParams;
        this.camera = newParams.getCamera();
        refreshFromParams(true);
    }

    /**
     * Set callback for fractal type changes (used by AnimationManager).
     */
    public void setOnFractalTypeChanged(java.util.function.BiConsumer<FractalType, AbstractFractalParams> callback) {
        this.onFractalTypeChanged = callback;
    }
}