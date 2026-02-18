package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.components.EnhancedSlider;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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
    private VBox polyhedralControls;
    private VBox sierpinskiControls;
    private VBox pseudoKleinianControls;
    private VBox apollonianControls;
    private VBox bristorbrotControls;
    private VBox quaternionJulia4DControls;
    private VBox testSceneControls;
    private VBox cornellBoxControls;

    // Container for all fractal-specific controls (used by dice randomizer)
    private VBox fractalParamsBox;

    // Dice history (undo/redo for randomization)
    private static final int MAX_HISTORY = 50;
    private final List<Map<Object, Object>> diceHistory = new ArrayList<>();
    private int historyIndex = -1;
    private Button prevBtn, nextBtn;
    private Slider mutationStrengthSlider;

    // Morph crossfade (A ↔ B)
    private Map<Object, Object> morphA, morphB;
    private FractalType morphTypeA, morphTypeB;
    private Slider morphSlider;
    private Label morphLabel;


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

    // Polyhedral sliders
    private ComboBox<PolyhedralIFSParams.PolyType> polyTypeCombo;
    private EnhancedSlider polyIterSlider;
    private EnhancedSlider polyScaleSlider;
    private EnhancedSlider polyOffXSlider, polyOffYSlider, polyOffZSlider;
    private EnhancedSlider polyShiftXSlider, polyShiftYSlider, polyShiftZSlider;
    private EnhancedSlider polyRot1XSlider, polyRot1YSlider, polyRot1ZSlider;
    private EnhancedSlider polyRot2XSlider, polyRot2YSlider, polyRot2ZSlider;

    // Test Scene sliders
    private EnhancedSlider tsScaleSlider;

    // Sierpinski sliders
    private EnhancedSlider siIterSlider;
    private EnhancedSlider siScaleSlider;

    // Pseudo-Kleinian sliders
    private EnhancedSlider pkIterSlider;
    private EnhancedSlider pkCSizeXSlider;
    private EnhancedSlider pkCSizeYSlider;
    private EnhancedSlider pkCSizeZSlider;
    private EnhancedSlider pkSizeSlider;
    private EnhancedSlider pkDEOffsetSlider;
    private EnhancedSlider pkFoldCxSlider;
    private EnhancedSlider pkFoldCySlider;
    private EnhancedSlider pkFoldCzSlider;

    // Apollonian sliders
    private EnhancedSlider apIterSlider;
    private EnhancedSlider apScaleSlider;
    private EnhancedSlider apFoldRadiusSlider;

    // Bristorbrot sliders
    private EnhancedSlider brIterSlider;
    private EnhancedSlider brBailoutSlider;
    private EnhancedSlider brJuliaCxSlider;
    private EnhancedSlider brJuliaCySlider;
    private EnhancedSlider brJuliaCzSlider;

    // Quaternion Julia 4D sliders
    private EnhancedSlider qjIterSlider;
    private EnhancedSlider qjBailoutSlider;
    private EnhancedSlider qjCxSlider, qjCySlider, qjCzSlider, qjCwSlider;
    private EnhancedSlider qjSliceWSlider;
    private EnhancedSlider qjRotXWSlider, qjRotYWSlider, qjRotZWSlider;

    // Fractal Terrain controls
    private VBox fractalTerrainControls;
    private EnhancedSlider ftHeightSlider;
    private EnhancedSlider ftFrequencySlider;
    private EnhancedSlider ftOctavesSlider;
    private EnhancedSlider ftLacunaritySlider;
    private EnhancedSlider ftRoughnessSlider;
    private EnhancedSlider ftWarpSlider;
    private EnhancedSlider ftRidgeSlider;
    private EnhancedSlider ftOffsetSlider;

    // Cornell Box sliders
    private EnhancedSlider cbScaleSlider;
    private EnhancedSlider cbGlassXSlider, cbGlassYSlider, cbGlassZSlider, cbGlassRadiusSlider;
    private EnhancedSlider cbMetalXSlider, cbMetalYSlider, cbMetalZSlider, cbMetalRadiusSlider;
    private EnhancedSlider cbLightXSlider, cbLightYSlider, cbLightZSlider, cbLightWSlider, cbLightDSlider;

    // Boolean Operations
    private TitledPane booleanOpsPane;
    private CheckBox boolEnableCheck;
    private ComboBox<String> boolSecondaryCombo;
    private ComboBox<String> boolOpCombo;
    private EnhancedSlider boolOffsetXSlider, boolOffsetYSlider, boolOffsetZSlider;
    private EnhancedSlider boolScaleSlider;
    private EnhancedSlider boolBlendSlider;

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
        createPolyhedralControls();
        createSierpinskiControls();
        createPseudoKleinianControls();
        createApollonianControls();
        createBristorbrotControls();
        createQuaternionJulia4DControls();
        createFractalTerrainControls();
        createTestSceneControls();
        createCornellBoxControls();
        createBooleanOpsPane();

        // Movement speed
        speedSlider = new EnhancedSlider("Move Speed", 0.001, 0.5, 0.1, false);
        speedSlider.setOnAction(v -> camera.setMoveSpeed(v.floatValue()));

        // Position display
        positionLabel = new Label("Pos: (0.00, 0.00, -3.00)");
        positionLabel.getStyleClass().add("mono-label");

        // Navigation help
        Label navLabel = new Label("Navigation:");
        navLabel.getStyleClass().add("bold-label");
        Label helpLabel = new Label(
            "Drag: Look around\n" +
            "Arrows: Move\n" +
            "Q/E: Roll\n" +
            "PgUp/Dn: Up/Down\n" +
            "R: Reset camera\n" +
            "Space: Render full\n" +
            "Scroll: Adjust speed"
        );
        helpLabel.getStyleClass().add("small-label");

        // Reset button
        Button resetBtn = new Button("Reset Camera (R)");
        resetBtn.setOnAction(e -> {
            camera.reset();
            renderCallback.requestRender();
            updatePositionLabel();
        });
        resetBtn.setMaxWidth(Double.MAX_VALUE);

        // Dice randomizer with history navigation
        prevBtn = new Button("\u25C0");  // ◀
        prevBtn.setTooltip(new Tooltip("Previous dice result"));
        prevBtn.setOnAction(e -> navigateHistory(-1));
        prevBtn.setDisable(true);

        Button diceBtn = new Button("\uD83C\uDFB2");  // dice emoji
        diceBtn.setTooltip(new Tooltip("Full randomize (locked sliders preserved)"));
        diceBtn.setOnAction(e -> randomizeCurrentFractal());

        Button mutateBtn = new Button("\uD83E\uDDEC");  // 🧬 DNA emoji
        mutateBtn.setTooltip(new Tooltip("Soft mutation: nudge parameters around current values"));
        mutateBtn.setOnAction(e -> mutateCurrentFractal());

        nextBtn = new Button("\u25B6");  // ▶
        nextBtn.setTooltip(new Tooltip("Next dice result"));
        nextBtn.setOnAction(e -> navigateHistory(+1));
        nextBtn.setDisable(true);

        HBox diceRow = new HBox(2, prevBtn, diceBtn, mutateBtn, nextBtn);
        diceRow.setAlignment(Pos.CENTER);

        // Mutation strength slider (5% to 100%)
        mutationStrengthSlider = new Slider(0.05, 1.0, 0.15);
        mutationStrengthSlider.setTooltip(new Tooltip("Mutation strength: how far values move from current"));
        mutationStrengthSlider.setPrefWidth(80);

        Label mutLabel = new Label("15%");
        mutLabel.getStyleClass().add("small-label");
        mutLabel.setPrefWidth(30);
        mutationStrengthSlider.valueProperty().addListener((obs, old, val) ->
            mutLabel.setText((int)(val.doubleValue() * 100) + "%"));

        HBox mutRow = new HBox(4, new Label("\uD83E\uDDEC"), mutationStrengthSlider, mutLabel);
        mutRow.setAlignment(Pos.CENTER_LEFT);

        // Fractal type selector (always visible at top)
        HBox typeRow = new HBox(6, typeCombo, diceRow);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(typeCombo, javafx.scene.layout.Priority.ALWAYS);
        VBox typeBox = new VBox(5, typeLabel, typeRow, mutRow);

        // Fractal-specific parameters (dynamic visibility)
        fractalParamsBox = new VBox(8,
            mandelbulbControls,
            mandelboxControls,
            mengerControls,
            kaleidoscopicControls,
            polyhedralControls,
            sierpinskiControls,
            pseudoKleinianControls,
            apollonianControls,
            bristorbrotControls,
            quaternionJulia4DControls,
            fractalTerrainControls,
            testSceneControls,
            cornellBoxControls
        );
        TitledPane paramsPane = new TitledPane("Fractal Parameters", fractalParamsBox);
        paramsPane.setExpanded(true);

        // --- Morph Crossfade ---
        Button setABtn = new Button("Set A");
        setABtn.setTooltip(new Tooltip("Capture current params as morph start"));
        setABtn.setOnAction(e -> {
            morphA = captureSnapshot();
            morphTypeA = params.getType();
            updateMorphLabel();
        });

        Button setBBtn = new Button("Set B");
        setBBtn.setTooltip(new Tooltip("Capture current params as morph end"));
        setBBtn.setOnAction(e -> {
            morphB = captureSnapshot();
            morphTypeB = params.getType();
            updateMorphLabel();
        });

        morphSlider = new Slider(0, 1, 0);
        morphSlider.setDisable(true);
        HBox.setHgrow(morphSlider, javafx.scene.layout.Priority.ALWAYS);
        morphSlider.valueProperty().addListener((obs, old, val) -> {
            if (morphA != null && morphB != null) {
                applyMorph(val.doubleValue());
                renderCallback.requestRender();
            }
        });

        morphLabel = new Label("Morph: set A and B first");
        morphLabel.getStyleClass().add("small-label");

        HBox morphRow = new HBox(6, setABtn, morphSlider, setBBtn);
        morphRow.setAlignment(Pos.CENTER_LEFT);

        VBox morphBox = new VBox(4, morphLabel, morphRow);

        // Tools pane (collapsed by default)
        TitledPane toolsPane = new TitledPane("Morph A \u2194 B", morphBox);
        toolsPane.setExpanded(false);

        // Navigation & camera
        VBox navBox = new VBox(5, speedSlider, positionLabel, navLabel, helpLabel, resetBtn);
        TitledPane navPane = new TitledPane("Navigation", navBox);
        navPane.setExpanded(true);

        panel.getChildren().addAll(typeBox, paramsPane, booleanOpsPane, toolsPane, navPane);

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
        presetLabel.getStyleClass().add("bold-label");

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

    private void createKaleidoscopicControls() {
        kaleidoscopicControls = new VBox(8);

        // Info label about parameter relationships
        Label infoLabel = new Label("Classic Sierpinski: Scale=2, Offset=3");
        infoLabel.getStyleClass().add("hint-label");

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
        presetLabel.getStyleClass().add("bold-label");

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

    private void createSierpinskiControls() {
        sierpinskiControls = new VBox(8);

        siIterSlider = new EnhancedSlider("Iterations", 5, 30, 15, true);
        siIterSlider.showTickMarks(true);
        siIterSlider.setMajorTickUnit(5.0);
        siIterSlider.setOnAction(v -> { if (!suppressRender && params instanceof SierpinskiParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        siScaleSlider = new EnhancedSlider("Scale", 1.5, 3.0, 2.0, false);
        siScaleSlider.setOnAction(v -> { if (!suppressRender && params instanceof SierpinskiParams p) { p.setScale(v.floatValue()); renderCallback.requestRender(); } });

        sierpinskiControls.getChildren().addAll(siIterSlider, siScaleSlider);
        sierpinskiControls.setVisible(false);
        sierpinskiControls.setManaged(false);
    }

    private void createPseudoKleinianControls() {
        pseudoKleinianControls = new VBox(8);

        pkIterSlider = new EnhancedSlider("Iterations", 5, 30, 7, true);
        pkIterSlider.showTickMarks(true);
        pkIterSlider.setMajorTickUnit(5.0);
        pkIterSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        pkCSizeXSlider = new EnhancedSlider("CSize X", 0.1, 2.0, 1.0, false);
        pkCSizeXSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setCSizeX(v.floatValue()); renderCallback.requestRender(); } });

        pkCSizeYSlider = new EnhancedSlider("CSize Y", 0.1, 2.0, 1.0, false);
        pkCSizeYSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setCSizeY(v.floatValue()); renderCallback.requestRender(); } });

        pkCSizeZSlider = new EnhancedSlider("CSize Z", 0.1, 2.0, 1.3, false);
        pkCSizeZSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setCSizeZ(v.floatValue()); renderCallback.requestRender(); } });

        pkSizeSlider = new EnhancedSlider("Size", 0.5, 2.0, 1.0, false);
        pkSizeSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setSize(v.floatValue()); renderCallback.requestRender(); } });

        pkDEOffsetSlider = new EnhancedSlider("DE Offset", 0.0, 0.5, 0.0, false);
        pkDEOffsetSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setDEOffset(v.floatValue()); renderCallback.requestRender(); } });

        Label foldLabel = new Label("Fold C (Julia constant):");
        foldLabel.getStyleClass().add("hint-label");

        pkFoldCxSlider = new EnhancedSlider("Fold Cx", -2.0, 2.0, -0.62, false);
        pkFoldCxSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setFoldCx(v.floatValue()); renderCallback.requestRender(); } });

        pkFoldCySlider = new EnhancedSlider("Fold Cy", -2.0, 2.0, -0.015, false);
        pkFoldCySlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setFoldCy(v.floatValue()); renderCallback.requestRender(); } });

        pkFoldCzSlider = new EnhancedSlider("Fold Cz", -2.0, 2.0, -0.025, false);
        pkFoldCzSlider.setOnAction(v -> { if (!suppressRender && params instanceof PseudoKleinianParams p) { p.setFoldCz(v.floatValue()); renderCallback.requestRender(); } });

        pseudoKleinianControls.getChildren().addAll(
            pkIterSlider, pkCSizeXSlider, pkCSizeYSlider, pkCSizeZSlider,
            pkSizeSlider, pkDEOffsetSlider,
            new Separator(), foldLabel, pkFoldCxSlider, pkFoldCySlider, pkFoldCzSlider
        );
        pseudoKleinianControls.setVisible(false);
        pseudoKleinianControls.setManaged(false);
    }

    private void createApollonianControls() {
        apollonianControls = new VBox(8);

        apIterSlider = new EnhancedSlider("Iterations", 5, 30, 15, true);
        apIterSlider.showTickMarks(true);
        apIterSlider.setMajorTickUnit(5.0);
        apIterSlider.setOnAction(v -> { if (!suppressRender && params instanceof ApollonianParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        apScaleSlider = new EnhancedSlider("Scale", 1.5, 4.0, 2.0, false);
        apScaleSlider.setOnAction(v -> { if (!suppressRender && params instanceof ApollonianParams p) { p.setScale(v.floatValue()); renderCallback.requestRender(); } });

        apFoldRadiusSlider = new EnhancedSlider("Fold Radius", 0.5, 2.0, 1.0, false);
        apFoldRadiusSlider.setOnAction(v -> { if (!suppressRender && params instanceof ApollonianParams p) { p.setFoldRadius(v.floatValue()); renderCallback.requestRender(); } });

        apollonianControls.getChildren().addAll(apIterSlider, apScaleSlider, apFoldRadiusSlider);
        apollonianControls.setVisible(false);
        apollonianControls.setManaged(false);
    }

    private void createBristorbrotControls() {
        bristorbrotControls = new VBox(8);

        brIterSlider = new EnhancedSlider("Iterations", 5, 30, 15, true);
        brIterSlider.showTickMarks(true);
        brIterSlider.setMajorTickUnit(5.0);
        brIterSlider.setOnAction(v -> { if (!suppressRender && params instanceof BristorbrotParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        brBailoutSlider = new EnhancedSlider("Bailout", 1, 16, 4.0, false);
        brBailoutSlider.setPrecision(1);
        brBailoutSlider.setOnAction(v -> { if (!suppressRender && params instanceof BristorbrotParams p) { p.setBailout(v.floatValue()); renderCallback.requestRender(); } });

        Label juliaLabel = new Label("Julia C (0 = Mandelbrot mode):");
        juliaLabel.getStyleClass().add("hint-label");

        brJuliaCxSlider = new EnhancedSlider("Julia Cx", -2.0, 2.0, 0.0, false);
        brJuliaCxSlider.setOnAction(v -> { if (!suppressRender && params instanceof BristorbrotParams p) { p.setJuliaCx(v.floatValue()); renderCallback.requestRender(); } });

        brJuliaCySlider = new EnhancedSlider("Julia Cy", -2.0, 2.0, 0.0, false);
        brJuliaCySlider.setOnAction(v -> { if (!suppressRender && params instanceof BristorbrotParams p) { p.setJuliaCy(v.floatValue()); renderCallback.requestRender(); } });

        brJuliaCzSlider = new EnhancedSlider("Julia Cz", -2.0, 2.0, 0.0, false);
        brJuliaCzSlider.setOnAction(v -> { if (!suppressRender && params instanceof BristorbrotParams p) { p.setJuliaCz(v.floatValue()); renderCallback.requestRender(); } });

        bristorbrotControls.getChildren().addAll(brIterSlider, brBailoutSlider,
            new Separator(), juliaLabel, brJuliaCxSlider, brJuliaCySlider, brJuliaCzSlider);
        bristorbrotControls.setVisible(false);
        bristorbrotControls.setManaged(false);
    }

    private void createQuaternionJulia4DControls() {
        quaternionJulia4DControls = new VBox(8);

        qjIterSlider = new EnhancedSlider("Iterations", 4, 20, 12, true);
        qjIterSlider.showTickMarks(true);
        qjIterSlider.setMajorTickUnit(4.0);
        qjIterSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setMaxIterations(v.intValue()); renderCallback.requestRender(); } });

        qjBailoutSlider = new EnhancedSlider("Bailout", 1, 10, 4.0, false);
        qjBailoutSlider.setPrecision(1);
        qjBailoutSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setBailout(v.floatValue()); renderCallback.requestRender(); } });

        Label juliaLabel = new Label("Julia Constant:");
        juliaLabel.getStyleClass().add("hint-label");

        qjCxSlider = new EnhancedSlider("Cx", -2.0, 2.0, -0.2, false);
        qjCxSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setJuliaCx(v.floatValue()); renderCallback.requestRender(); } });

        qjCySlider = new EnhancedSlider("Cy", -2.0, 2.0, 0.8, false);
        qjCySlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setJuliaCy(v.floatValue()); renderCallback.requestRender(); } });

        qjCzSlider = new EnhancedSlider("Cz", -2.0, 2.0, 0.0, false);
        qjCzSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setJuliaCz(v.floatValue()); renderCallback.requestRender(); } });

        qjCwSlider = new EnhancedSlider("Cw", -2.0, 2.0, 0.0, false);
        qjCwSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setJuliaCw(v.floatValue()); renderCallback.requestRender(); } });

        Label sliceLabel = new Label("4D Hyperplane Slice:");
        sliceLabel.getStyleClass().add("hint-label");

        qjSliceWSlider = new EnhancedSlider("Slice W", -2.0, 2.0, 0.0, false);
        qjSliceWSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setSliceW(v.floatValue()); renderCallback.requestRender(); } });

        Label rotLabel = new Label("4D Rotations:");
        rotLabel.getStyleClass().add("hint-label");

        qjRotXWSlider = new EnhancedSlider("Rot XW", -180, 180, 0.0, false);
        qjRotXWSlider.setPrecision(1);
        qjRotXWSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setRotXW(v.floatValue()); renderCallback.requestRender(); } });

        qjRotYWSlider = new EnhancedSlider("Rot YW", -180, 180, 0.0, false);
        qjRotYWSlider.setPrecision(1);
        qjRotYWSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setRotYW(v.floatValue()); renderCallback.requestRender(); } });

        qjRotZWSlider = new EnhancedSlider("Rot ZW", -180, 180, 0.0, false);
        qjRotZWSlider.setPrecision(1);
        qjRotZWSlider.setOnAction(v -> { if (!suppressRender && params instanceof QuaternionJulia4DParams p) { p.setRotZW(v.floatValue()); renderCallback.requestRender(); } });

        // Presets
        Label presetLabel = new Label("Presets:");
        presetLabel.getStyleClass().add("hint-label");

        Button classicBtn = new Button("Classic");
        classicBtn.setOnAction(e -> applyQJ4DPreset(QuaternionJulia4DParams.classicPreset()));
        Button flowerBtn = new Button("4D Flower");
        flowerBtn.setOnAction(e -> applyQJ4DPreset(QuaternionJulia4DParams.flowerPreset()));
        Button wormholeBtn = new Button("Wormhole");
        wormholeBtn.setOnAction(e -> applyQJ4DPreset(QuaternionJulia4DParams.wormholePreset()));
        Button crystalBtn = new Button("Crystal");
        crystalBtn.setOnAction(e -> applyQJ4DPreset(QuaternionJulia4DParams.crystalPreset()));
        Button hypersphereBtn = new Button("Hypersphere");
        hypersphereBtn.setOnAction(e -> applyQJ4DPreset(QuaternionJulia4DParams.hyperspherePreset()));

        HBox presetRow1 = new HBox(4, classicBtn, flowerBtn, wormholeBtn);
        HBox presetRow2 = new HBox(4, crystalBtn, hypersphereBtn);

        quaternionJulia4DControls.getChildren().addAll(
            qjIterSlider, qjBailoutSlider,
            new Separator(), juliaLabel, qjCxSlider, qjCySlider, qjCzSlider, qjCwSlider,
            new Separator(), sliceLabel, qjSliceWSlider,
            new Separator(), rotLabel, qjRotXWSlider, qjRotYWSlider, qjRotZWSlider,
            new Separator(), presetLabel, presetRow1, presetRow2
        );
        quaternionJulia4DControls.setVisible(false);
        quaternionJulia4DControls.setManaged(false);
    }

    private void applyQJ4DPreset(QuaternionJulia4DParams preset) {
        if (params instanceof QuaternionJulia4DParams p) {
            p.setMaxIterations(preset.getMaxIterations());
            p.setBailout(preset.getBailout());
            p.setJuliaCx(preset.getJuliaCx());
            p.setJuliaCy(preset.getJuliaCy());
            p.setJuliaCz(preset.getJuliaCz());
            p.setJuliaCw(preset.getJuliaCw());
            p.setSliceW(preset.getSliceW());
            p.setRotXW(preset.getRotXW());
            p.setRotYW(preset.getRotYW());
            p.setRotZW(preset.getRotZW());
            refreshFromParams(true);
            renderCallback.requestRender();
        }
    }

    private void createFractalTerrainControls() {
        fractalTerrainControls = new VBox(8);

        Label titleLabel = new Label("Fractal Terrain");
        titleLabel.getStyleClass().add("bold-label");

        // Terrain Shape
        Label shapeLabel = new Label("Terrain Shape");
        shapeLabel.getStyleClass().add("bold-label");

        ftHeightSlider = new EnhancedSlider("Height", 0.1, 10.0, 2.0, false);
        ftHeightSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setTerrainHeight(v.floatValue());
                renderCallback.requestRender();
            }
        });

        ftFrequencySlider = new EnhancedSlider("Frequency", 0.05, 5.0, 0.5, false);
        ftFrequencySlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setTerrainFrequency(v.floatValue());
                renderCallback.requestRender();
            }
        });

        ftOffsetSlider = new EnhancedSlider("Vertical Offset", -5.0, 5.0, 0.0, false);
        ftOffsetSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setTerrainOffset(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Noise Detail
        Label detailLabel = new Label("Noise Detail");
        detailLabel.getStyleClass().add("bold-label");

        ftOctavesSlider = new EnhancedSlider("Octaves", 1, 12, 8, true);
        ftOctavesSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setOctaves(v.intValue());
                renderCallback.requestRender();
            }
        });

        ftLacunaritySlider = new EnhancedSlider("Lacunarity", 1.5, 4.0, 2.0, false);
        ftLacunaritySlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setLacunarity(v.floatValue());
                renderCallback.requestRender();
            }
        });

        ftRoughnessSlider = new EnhancedSlider("Roughness", 0.1, 0.9, 0.5, false);
        ftRoughnessSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setRoughness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Effects
        Label effectsLabel = new Label("Effects");
        effectsLabel.getStyleClass().add("bold-label");

        ftWarpSlider = new EnhancedSlider("Domain Warp", 0.0, 2.0, 0.0, false);
        ftWarpSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setWarpStrength(v.floatValue());
                renderCallback.requestRender();
            }
        });

        ftRidgeSlider = new EnhancedSlider("Ridge Sharpness", 0.0, 1.0, 0.0, false);
        ftRidgeSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof FractalTerrainParams ft) {
                ft.setRidgeSharpness(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // Presets
        Label presetLabel = new Label("Presets");
        presetLabel.getStyleClass().add("bold-label");

        Button hillsBtn = new Button("Rolling Hills");
        hillsBtn.setOnAction(e -> applyTerrainPreset(FractalTerrainParams.rollingHillsPreset()));
        Button mtnsBtn = new Button("Mountains");
        mtnsBtn.setOnAction(e -> applyTerrainPreset(FractalTerrainParams.mountainsPreset()));
        Button canyonsBtn = new Button("Canyons");
        canyonsBtn.setOnAction(e -> applyTerrainPreset(FractalTerrainParams.canyonsPreset()));
        Button alienBtn = new Button("Alien");
        alienBtn.setOnAction(e -> applyTerrainPreset(FractalTerrainParams.alienPreset()));

        HBox presetRow1 = new HBox(4, hillsBtn, mtnsBtn);
        HBox presetRow2 = new HBox(4, canyonsBtn, alienBtn);

        fractalTerrainControls.getChildren().addAll(
            titleLabel,
            shapeLabel, ftHeightSlider, ftFrequencySlider, ftOffsetSlider,
            new Separator(), detailLabel, ftOctavesSlider, ftLacunaritySlider, ftRoughnessSlider,
            new Separator(), effectsLabel, ftWarpSlider, ftRidgeSlider,
            new Separator(), presetLabel, presetRow1, presetRow2
        );
        fractalTerrainControls.setVisible(false);
        fractalTerrainControls.setManaged(false);
    }

    private void applyTerrainPreset(FractalTerrainParams preset) {
        if (params instanceof FractalTerrainParams p) {
            p.setTerrainHeight(preset.getTerrainHeight());
            p.setTerrainFrequency(preset.getTerrainFrequency());
            p.setOctaves(preset.getOctaves());
            p.setLacunarity(preset.getLacunarity());
            p.setRoughness(preset.getRoughness());
            p.setWarpStrength(preset.getWarpStrength());
            p.setRidgeSharpness(preset.getRidgeSharpness());
            p.setTerrainOffset(preset.getTerrainOffset());
            refreshFromParams(true);
            renderCallback.requestRender();
        }
    }

    private void createTestSceneControls() {
        testSceneControls = new VBox(8);

        Label titleLabel = new Label("SDF Primitives Showcase");
        titleLabel.getStyleClass().add("bold-label");

        Label infoLabel = new Label("Non-fractal scene for testing effects");
        infoLabel.getStyleClass().add("hint-label");

        tsScaleSlider = new EnhancedSlider("Scene Scale", 0.5, 3.0, 1.0, false);
        tsScaleSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof TestSceneParams ts) {
                ts.setSceneScale(v.floatValue());
                renderCallback.requestRender();
            }
        });

        testSceneControls.getChildren().addAll(titleLabel, infoLabel, tsScaleSlider);
        testSceneControls.setVisible(false);
        testSceneControls.setManaged(false);
    }

    private void createCornellBoxControls() {
        cornellBoxControls = new VBox(8);

        Label titleLabel = new Label("Cornell Box");
        titleLabel.getStyleClass().add("bold-label");

        Label infoLabel = new Label("Enable Path Tracing for caustics");
        infoLabel.getStyleClass().add("hint-label");

        cbScaleSlider = new EnhancedSlider("Scene Scale", 0.5, 3.0, 1.0, false);
        cbScaleSlider.setOnAction(v -> {
            if (!suppressRender && params instanceof CornellBoxParams cb) {
                cb.setSceneScale(v.floatValue());
                renderCallback.requestRender();
            }
        });

        // --- Glass Sphere ---
        Label glassLabel = new Label("Glass Sphere");
        glassLabel.getStyleClass().add("bold-label");

        cbGlassXSlider = new EnhancedSlider("X", -0.9, 0.9, -0.35, false);
        cbGlassXSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setGlassSphereX(v.floatValue()); renderCallback.requestRender(); } });

        cbGlassYSlider = new EnhancedSlider("Y", 0.05, 1.8, 0.4, false);
        cbGlassYSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setGlassSphereY(v.floatValue()); renderCallback.requestRender(); } });

        cbGlassZSlider = new EnhancedSlider("Z", 0.1, 1.9, 1.2, false);
        cbGlassZSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setGlassSphereZ(v.floatValue()); renderCallback.requestRender(); } });

        cbGlassRadiusSlider = new EnhancedSlider("Radius", 0.05, 0.8, 0.4, false);
        cbGlassRadiusSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setGlassSphereRadius(v.floatValue()); renderCallback.requestRender(); } });

        // --- Metal Sphere ---
        Label metalLabel = new Label("Metal Sphere");
        metalLabel.getStyleClass().add("bold-label");

        cbMetalXSlider = new EnhancedSlider("X", -0.9, 0.9, 0.35, false);
        cbMetalXSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setMetalSphereX(v.floatValue()); renderCallback.requestRender(); } });

        cbMetalYSlider = new EnhancedSlider("Y", 0.05, 1.8, 0.3, false);
        cbMetalYSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setMetalSphereY(v.floatValue()); renderCallback.requestRender(); } });

        cbMetalZSlider = new EnhancedSlider("Z", 0.1, 1.9, 0.7, false);
        cbMetalZSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setMetalSphereZ(v.floatValue()); renderCallback.requestRender(); } });

        cbMetalRadiusSlider = new EnhancedSlider("Radius", 0.05, 0.8, 0.3, false);
        cbMetalRadiusSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setMetalSphereRadius(v.floatValue()); renderCallback.requestRender(); } });

        // --- Light Panel ---
        Label lightLabel = new Label("Light Panel");
        lightLabel.getStyleClass().add("bold-label");

        cbLightXSlider = new EnhancedSlider("X", -0.8, 0.8, 0.0, false);
        cbLightXSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setLightPanelX(v.floatValue()); renderCallback.requestRender(); } });

        cbLightYSlider = new EnhancedSlider("Y", 0.5, 1.99, 1.99, false);
        cbLightYSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setLightPanelY(v.floatValue()); renderCallback.requestRender(); } });

        cbLightZSlider = new EnhancedSlider("Z", 0.1, 1.9, 1.0, false);
        cbLightZSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setLightPanelZ(v.floatValue()); renderCallback.requestRender(); } });

        cbLightWSlider = new EnhancedSlider("Width", 0.05, 0.8, 0.3, false);
        cbLightWSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setLightPanelW(v.floatValue()); renderCallback.requestRender(); } });

        cbLightDSlider = new EnhancedSlider("Depth", 0.05, 0.8, 0.3, false);
        cbLightDSlider.setOnAction(v -> { if (!suppressRender && params instanceof CornellBoxParams cb) { cb.setLightPanelD(v.floatValue()); renderCallback.requestRender(); } });

        cornellBoxControls.getChildren().addAll(
            titleLabel, infoLabel, cbScaleSlider,
            new Separator(),
            glassLabel, cbGlassXSlider, cbGlassYSlider, cbGlassZSlider, cbGlassRadiusSlider,
            new Separator(),
            metalLabel, cbMetalXSlider, cbMetalYSlider, cbMetalZSlider, cbMetalRadiusSlider,
            new Separator(),
            lightLabel, cbLightXSlider, cbLightYSlider, cbLightZSlider, cbLightWSlider, cbLightDSlider
        );
        cornellBoxControls.setVisible(false);
        cornellBoxControls.setManaged(false);
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
            polyhedralControls.setVisible(false);
            polyhedralControls.setManaged(false);
            sierpinskiControls.setVisible(false);
            sierpinskiControls.setManaged(false);
            pseudoKleinianControls.setVisible(false);
            pseudoKleinianControls.setManaged(false);
            apollonianControls.setVisible(false);
            apollonianControls.setManaged(false);
            bristorbrotControls.setVisible(false);
            bristorbrotControls.setManaged(false);
            quaternionJulia4DControls.setVisible(false);
            quaternionJulia4DControls.setManaged(false);
            fractalTerrainControls.setVisible(false);
            fractalTerrainControls.setManaged(false);
            testSceneControls.setVisible(false);
            testSceneControls.setManaged(false);
            cornellBoxControls.setVisible(false);
            cornellBoxControls.setManaged(false);

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
                case POLYHEDRAL_IFS -> {
                    polyhedralControls.setVisible(true);
                    polyhedralControls.setManaged(true);
                }
                case SIERPINSKI -> {
                    sierpinskiControls.setVisible(true);
                    sierpinskiControls.setManaged(true);
                }
                case PSEUDO_KLEINIAN -> {
                    pseudoKleinianControls.setVisible(true);
                    pseudoKleinianControls.setManaged(true);
                }
                case APOLLONIAN -> {
                    apollonianControls.setVisible(true);
                    apollonianControls.setManaged(true);
                }
                case BRISTORBROT -> {
                    bristorbrotControls.setVisible(true);
                    bristorbrotControls.setManaged(true);
                }
                case QUATERNION_JULIA_4D -> {
                    quaternionJulia4DControls.setVisible(true);
                    quaternionJulia4DControls.setManaged(true);
                }
                case FRACTAL_TERRAIN -> {
                    fractalTerrainControls.setVisible(true);
                    fractalTerrainControls.setManaged(true);
                }
                case TEST_SCENE -> {
                    testSceneControls.setVisible(true);
                    testSceneControls.setManaged(true);
                }
                case CORNELL_BOX -> {
                    cornellBoxControls.setVisible(true);
                    cornellBoxControls.setManaged(true);
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

    private void createBooleanOpsPane() {
        boolEnableCheck = new CheckBox("Enable");
        boolEnableCheck.setOnAction(e -> {
            if (!suppressRender && params != null) {
                params.setBooleanEnabled(boolEnableCheck.isSelected());
                renderCallback.requestRender();
            }
        });

        boolSecondaryCombo = new ComboBox<>();
        for (FractalType ft : FractalType.values()) {
            if (ft == FractalType.TEST_SCENE || ft == FractalType.CORNELL_BOX || ft == FractalType.FRACTAL_TERRAIN) continue;
            boolSecondaryCombo.getItems().add(ft.getKernelName());
        }
        boolSecondaryCombo.setMaxWidth(Double.MAX_VALUE);
        boolSecondaryCombo.setOnAction(e -> {
            if (!suppressRender && params != null) {
                params.setBoolSecondaryType(boolSecondaryCombo.getValue());
                renderCallback.requestRender();
            }
        });

        boolOpCombo = new ComboBox<>();
        boolOpCombo.getItems().addAll("Union", "Intersect", "Subtract");
        boolOpCombo.setValue("Union");
        boolOpCombo.setMaxWidth(Double.MAX_VALUE);
        boolOpCombo.setOnAction(e -> {
            if (!suppressRender && params != null) {
                int op = boolOpCombo.getSelectionModel().getSelectedIndex() + 1;
                params.setBooleanOp(op);
                renderCallback.requestRender();
            }
        });

        boolOffsetXSlider = new EnhancedSlider("Offset X", -5, 5, 0.5, false);
        boolOffsetXSlider.setOnAction(v -> { if (!suppressRender && params != null) { params.setBoolOffsetX(v.floatValue()); renderCallback.requestRender(); } });
        boolOffsetYSlider = new EnhancedSlider("Offset Y", -5, 5, 0, false);
        boolOffsetYSlider.setOnAction(v -> { if (!suppressRender && params != null) { params.setBoolOffsetY(v.floatValue()); renderCallback.requestRender(); } });
        boolOffsetZSlider = new EnhancedSlider("Offset Z", -5, 5, 0, false);
        boolOffsetZSlider.setOnAction(v -> { if (!suppressRender && params != null) { params.setBoolOffsetZ(v.floatValue()); renderCallback.requestRender(); } });

        boolScaleSlider = new EnhancedSlider("Scale", 0.01, 10, 1, false);
        boolScaleSlider.setOnAction(v -> { if (!suppressRender && params != null) { params.setBoolScale(v.floatValue()); renderCallback.requestRender(); } });

        boolBlendSlider = new EnhancedSlider("Smooth Blend", 0, 2, 0, false);
        boolBlendSlider.setOnAction(v -> { if (!suppressRender && params != null) { params.setBoolBlend(v.floatValue()); renderCallback.requestRender(); } });

        VBox content = new VBox(6,
            boolEnableCheck,
            new Label("Secondary Fractal:"), boolSecondaryCombo,
            new Label("Operation:"), boolOpCombo,
            boolOffsetXSlider, boolOffsetYSlider, boolOffsetZSlider,
            boolScaleSlider, boolBlendSlider
        );
        content.setPadding(new Insets(4));

        booleanOpsPane = new TitledPane("Boolean Operations", content);
        booleanOpsPane.setExpanded(false);
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
            polyhedralControls.setVisible(false);
            polyhedralControls.setManaged(false);
            sierpinskiControls.setVisible(false);
            sierpinskiControls.setManaged(false);
            pseudoKleinianControls.setVisible(false);
            pseudoKleinianControls.setManaged(false);
            apollonianControls.setVisible(false);
            apollonianControls.setManaged(false);
            bristorbrotControls.setVisible(false);
            bristorbrotControls.setManaged(false);
            quaternionJulia4DControls.setVisible(false);
            quaternionJulia4DControls.setManaged(false);
            fractalTerrainControls.setVisible(false);
            fractalTerrainControls.setManaged(false);
            testSceneControls.setVisible(false);
            testSceneControls.setManaged(false);
            cornellBoxControls.setVisible(false);
            cornellBoxControls.setManaged(false);

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
            } else if (params instanceof SierpinskiParams si) {
                sierpinskiControls.setVisible(true);
                sierpinskiControls.setManaged(true);
                siIterSlider.setValue(si.getMaxIterations());
                siScaleSlider.setValue(si.getScale());
            } else if (params instanceof PseudoKleinianParams pk) {
                pseudoKleinianControls.setVisible(true);
                pseudoKleinianControls.setManaged(true);
                pkIterSlider.setValue(pk.getMaxIterations());
                pkCSizeXSlider.setValue(pk.getCSizeX());
                pkCSizeYSlider.setValue(pk.getCSizeY());
                pkCSizeZSlider.setValue(pk.getCSizeZ());
                pkSizeSlider.setValue(pk.getSize());
                pkDEOffsetSlider.setValue(pk.getDEOffset());
                pkFoldCxSlider.setValue(pk.getFoldCx());
                pkFoldCySlider.setValue(pk.getFoldCy());
                pkFoldCzSlider.setValue(pk.getFoldCz());
            } else if (params instanceof ApollonianParams ap) {
                apollonianControls.setVisible(true);
                apollonianControls.setManaged(true);
                apIterSlider.setValue(ap.getMaxIterations());
                apScaleSlider.setValue(ap.getScale());
                apFoldRadiusSlider.setValue(ap.getFoldRadius());
            } else if (params instanceof BristorbrotParams br) {
                bristorbrotControls.setVisible(true);
                bristorbrotControls.setManaged(true);
                brIterSlider.setValue(br.getMaxIterations());
                brBailoutSlider.setValue(br.getBailout());
                brJuliaCxSlider.setValue(br.getJuliaCx());
                brJuliaCySlider.setValue(br.getJuliaCy());
                brJuliaCzSlider.setValue(br.getJuliaCz());
            } else if (params instanceof QuaternionJulia4DParams qj) {
                quaternionJulia4DControls.setVisible(true);
                quaternionJulia4DControls.setManaged(true);
                qjIterSlider.setValue(qj.getMaxIterations());
                qjBailoutSlider.setValue(qj.getBailout());
                qjCxSlider.setValue(qj.getJuliaCx());
                qjCySlider.setValue(qj.getJuliaCy());
                qjCzSlider.setValue(qj.getJuliaCz());
                qjCwSlider.setValue(qj.getJuliaCw());
                qjSliceWSlider.setValue(qj.getSliceW());
                qjRotXWSlider.setValue(qj.getRotXW());
                qjRotYWSlider.setValue(qj.getRotYW());
                qjRotZWSlider.setValue(qj.getRotZW());
            } else if (params instanceof FractalTerrainParams ft) {
                fractalTerrainControls.setVisible(true);
                fractalTerrainControls.setManaged(true);
                ftHeightSlider.setValue(ft.getTerrainHeight());
                ftFrequencySlider.setValue(ft.getTerrainFrequency());
                ftOctavesSlider.setValue(ft.getOctaves());
                ftLacunaritySlider.setValue(ft.getLacunarity());
                ftRoughnessSlider.setValue(ft.getRoughness());
                ftWarpSlider.setValue(ft.getWarpStrength());
                ftRidgeSlider.setValue(ft.getRidgeSharpness());
                ftOffsetSlider.setValue(ft.getTerrainOffset());
            } else if (params instanceof TestSceneParams ts) {
                testSceneControls.setVisible(true);
                testSceneControls.setManaged(true);
                tsScaleSlider.setValue(ts.getSceneScale());
            } else if (params instanceof CornellBoxParams cb) {
                cornellBoxControls.setVisible(true);
                cornellBoxControls.setManaged(true);
                cbScaleSlider.setValue(cb.getSceneScale());
                cbGlassXSlider.setValue(cb.getGlassSphereX());
                cbGlassYSlider.setValue(cb.getGlassSphereY());
                cbGlassZSlider.setValue(cb.getGlassSphereZ());
                cbGlassRadiusSlider.setValue(cb.getGlassSphereRadius());
                cbMetalXSlider.setValue(cb.getMetalSphereX());
                cbMetalYSlider.setValue(cb.getMetalSphereY());
                cbMetalZSlider.setValue(cb.getMetalSphereZ());
                cbMetalRadiusSlider.setValue(cb.getMetalSphereRadius());
                cbLightXSlider.setValue(cb.getLightPanelX());
                cbLightYSlider.setValue(cb.getLightPanelY());
                cbLightZSlider.setValue(cb.getLightPanelZ());
                cbLightWSlider.setValue(cb.getLightPanelW());
                cbLightDSlider.setValue(cb.getLightPanelD());
            }

            // Boolean Operations
            boolEnableCheck.setSelected(params.isBooleanEnabled());
            if (params.getBoolSecondaryType() != null) {
                boolSecondaryCombo.setValue(params.getBoolSecondaryType());
            }
            int opIdx = params.getBooleanOp() - 1;
            if (opIdx >= 0 && opIdx < boolOpCombo.getItems().size()) {
                boolOpCombo.getSelectionModel().select(opIdx);
            }
            boolOffsetXSlider.setValue(params.getBoolOffsetX());
            boolOffsetYSlider.setValue(params.getBoolOffsetY());
            boolOffsetZSlider.setValue(params.getBoolOffsetZ());
            boolScaleSlider.setValue(params.getBoolScale());
            boolBlendSlider.setValue(params.getBoolBlend());

            // Update common controls
            speedSlider.setValue(camera.getMoveSpeed());
            updatePositionLabel();

        } finally {
            suppressRender = false;
        }
    }

    /**
     * Randomize the fractal-specific parameters of the current fractal type.
     * Saves current state to history before randomizing.
     * Locked sliders and their ComboBoxes are preserved.
     */
    private void randomizeCurrentFractal() {
        // Snapshot current state before randomizing
        Map<Object, Object> snapshot = captureSnapshot();

        // Trim any forward history (new roll = new branch)
        while (diceHistory.size() > historyIndex + 1) {
            diceHistory.removeLast();
        }
        diceHistory.add(snapshot);
        if (diceHistory.size() > MAX_HISTORY) {
            diceHistory.removeFirst();
        }
        historyIndex = diceHistory.size() - 1;

        // Randomize
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        randomizeAllIn(fractalParamsBox, rng);
        renderCallback.requestRender();
        updateHistoryButtons();
    }

    private void navigateHistory(int direction) {
        int target = historyIndex + direction;
        if (target < 0 || target >= diceHistory.size()) return;
        historyIndex = target;
        restoreSnapshot(diceHistory.get(historyIndex));
        renderCallback.requestRender();
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        prevBtn.setDisable(historyIndex <= 0);
        nextBtn.setDisable(historyIndex >= diceHistory.size() - 1);
    }

    /** Capture all visible slider values and combo selections into a map. */
    private Map<Object, Object> captureSnapshot() {
        Map<Object, Object> snapshot = new IdentityHashMap<>();
        captureAllIn(fractalParamsBox, snapshot);
        return snapshot;
    }

    private void captureAllIn(Node node, Map<Object, Object> snapshot) {
        if (!node.isVisible()) return;
        if (node instanceof EnhancedSlider slider) {
            snapshot.put(slider, slider.getValue());
        } else if (node instanceof ComboBox<?> combo) {
            snapshot.put(combo, combo.getSelectionModel().getSelectedIndex());
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                captureAllIn(child, snapshot);
            }
        }
    }

    /** Restore slider values and combo selections from a snapshot. */
    private void restoreSnapshot(Map<Object, Object> snapshot) {
        // Don't suppress: slider callbacks must update the fractal params
        for (var entry : snapshot.entrySet()) {
            if (entry.getKey() instanceof EnhancedSlider slider) {
                slider.setValue((Double) entry.getValue());
            } else if (entry.getKey() instanceof ComboBox<?> combo) {
                combo.getSelectionModel().select((Integer) entry.getValue());
            }
        }
    }

    /**
     * Soft mutation: nudge parameters around their current values.
     * Uses the same history system as the dice randomizer.
     */
    private void mutateCurrentFractal() {
        Map<Object, Object> snapshot = captureSnapshot();

        while (diceHistory.size() > historyIndex + 1) {
            diceHistory.removeLast();
        }
        diceHistory.add(snapshot);
        if (diceHistory.size() > MAX_HISTORY) {
            diceHistory.removeFirst();
        }
        historyIndex = diceHistory.size() - 1;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double strength = mutationStrengthSlider.getValue();
        mutateAllIn(fractalParamsBox, rng, strength);
        renderCallback.requestRender();
        updateHistoryButtons();
    }

    private void mutateAllIn(Node node, ThreadLocalRandom rng, double strength) {
        if (!node.isVisible()) return;

        if (node instanceof EnhancedSlider slider) {
            if (slider.isLocked()) return;
            double current = slider.getValue();
            double min = slider.getSlider().getMin();
            double max = slider.getSlider().getMax();
            double range = max - min;
            // Gaussian-ish nudge: centered on current, strength controls spread
            double delta = (rng.nextDouble() - 0.5) * 2.0 * range * strength;
            double value = Math.max(min, Math.min(max, current + delta));
            if (slider.isInteger()) value = Math.round(value);
            slider.setValue(value);
        }
        // Don't mutate ComboBoxes (don't change fractal sub-type randomly)
        else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                mutateAllIn(child, rng, strength);
            }
        }
    }

    private void randomizeAllIn(Node node, ThreadLocalRandom rng) {
        if (!node.isVisible()) return;

        if (node instanceof EnhancedSlider slider) {
            if (slider.isLocked()) return;
            double min = slider.getSlider().getMin();
            double max = slider.getSlider().getMax();
            double value = min + rng.nextDouble() * (max - min);
            if (slider.isInteger()) {
                value = Math.round(value);
            }
            slider.setValue(value);
        } else if (node instanceof ComboBox<?> combo && !combo.getItems().isEmpty()) {
            combo.getSelectionModel().select(rng.nextInt(combo.getItems().size()));
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                randomizeAllIn(child, rng);
            }
        }
    }

    // ========================================================================
    // Morph Crossfade
    // ========================================================================

    private void updateMorphLabel() {
        if (morphA != null && morphB != null) {
            if (morphTypeA != morphTypeB) {
                morphSlider.setDisable(true);
                morphLabel.setText("Morph: A=" + morphTypeA + " \u2260 B=" + morphTypeB);
            } else {
                morphSlider.setDisable(false);
                morphLabel.setText("Morph: A \u2194 B ready (" + morphTypeA + ")");
            }
        } else if (morphA != null) {
            morphLabel.setText("Morph: A set (" + morphTypeA + "), need B");
        } else if (morphB != null) {
            morphLabel.setText("Morph: need A, B set (" + morphTypeB + ")");
        }
    }

    private void applyMorph(double t) {
        if (morphA == null || morphB == null) return;
        for (var entry : morphA.entrySet()) {
            Object key = entry.getKey();
            Object valB = morphB.get(key);
            if (valB == null) continue;

            if (key instanceof EnhancedSlider slider) {
                double a = (Double) entry.getValue();
                double b = (Double) valB;
                double lerped = a + (b - a) * t;
                if (slider.isInteger()) lerped = Math.round(lerped);
                slider.setValue(lerped);
            } else if (key instanceof ComboBox<?> combo) {
                // Snap: use A when t < 0.5, B otherwise
                combo.getSelectionModel().select((Integer) (t < 0.5 ? entry.getValue() : valB));
            }
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