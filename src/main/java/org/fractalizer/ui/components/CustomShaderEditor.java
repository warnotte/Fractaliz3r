package org.fractalizer.ui.components;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.fractalizer.fractals.CustomShaderParams;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.panels.RenderCallback;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-contained custom shader editor with dynamic uniform sliders.
 * Parses @param annotations from GLSL uniform declarations and creates UI controls.
 */
public class CustomShaderEditor extends VBox {

    // ========================================================================
    // Uniform annotation format:
    //   uniform float name; // @param min:0 max:10 default:1
    //   uniform int name;   // @param min:1 max:30 default:15
    //   uniform vec3 name;  // @param min:-2 max:2 default:0,0,0
    // ========================================================================

    private static final Pattern UNIFORM_PATTERN = Pattern.compile(
        "^\\s*uniform\\s+(float|int|vec2|vec3|vec4)\\s+(\\w+)\\s*;\\s*//\\s*@param(.*)$",
        Pattern.MULTILINE
    );
    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+):([^\\s]+)");

    private final RenderController controller;
    private final RenderCallback renderCallback;

    private final ComboBox<String> templateCombo;
    private final TextArea shaderEditorArea;
    private final Button compileBtn;
    private final Label compileErrorLabel;
    private final VBox dynamicSlidersBox;

    private CustomShaderParams currentParams;
    private boolean suppressSliderEvents = false;
    private Runnable onCompileSuccess;

    public CustomShaderEditor(RenderController controller, RenderCallback renderCallback) {
        super(8);
        this.controller = controller;
        this.renderCallback = renderCallback;

        // Editor (initialized first — referenced by template combo handler)
        shaderEditorArea = new TextArea();
        shaderEditorArea.setFont(Font.font("Consolas", 12));
        shaderEditorArea.setPrefRowCount(20);
        shaderEditorArea.setWrapText(false);
        shaderEditorArea.setText(CustomShaderParams.DEFAULT_SHADER);

        // CUA keybinding: SHIFT+DELETE = Cut (not natively supported by JavaFX)
        shaderEditorArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE && e.isShiftDown()) {
                shaderEditorArea.cut();
                e.consume();
            }
        });

        // Template picker
        Label templateLabel = new Label("Template:");
        templateLabel.getStyleClass().add("hint-label");

        templateCombo = new ComboBox<>();
        templateCombo.getItems().addAll("Sphere", "Torus", "Gyroid", "Infinite Spheres", "Menger Sponge", "Mandelbulb (Simple)");
        templateCombo.setValue("Sphere");
        templateCombo.setMaxWidth(Double.MAX_VALUE);
        templateCombo.setOnAction(e -> {
            String selected = templateCombo.getValue();
            if (selected == null) return;
            String source = switch (selected) {
                case "Torus" -> TEMPLATE_TORUS;
                case "Gyroid" -> TEMPLATE_GYROID;
                case "Infinite Spheres" -> TEMPLATE_INFINITE_SPHERES;
                case "Menger Sponge" -> TEMPLATE_MENGER;
                case "Mandelbulb (Simple)" -> TEMPLATE_MANDELBULB;
                default -> TEMPLATE_SPHERE;
            };
            shaderEditorArea.setText(source);
            if (currentParams != null) {
                currentParams.setShaderSource(source);
            }
        });

        // Help text
        Label helpLabel = new Label(
            "Define: OrbitTrap struct, DE(), DE_simple(), getFactors().\n" +
            "Add // @param min:X max:Y default:Z to uniforms for sliders.");
        helpLabel.getStyleClass().add("hint-label");
        helpLabel.setWrapText(true);

        // Compile button
        compileBtn = new Button("Compile & Run");
        compileBtn.getStyleClass().add("compile-btn");
        compileBtn.setMaxWidth(Double.MAX_VALUE);
        compileBtn.setOnAction(e -> compileAndRun());

        // Status label
        compileErrorLabel = new Label();
        compileErrorLabel.getStyleClass().add("compile-status");
        compileErrorLabel.setWrapText(true);
        compileErrorLabel.setMaxWidth(Double.MAX_VALUE);

        // Dynamic sliders container
        dynamicSlidersBox = new VBox(6);

        getChildren().addAll(
            templateLabel, templateCombo, helpLabel,
            shaderEditorArea, compileBtn, compileErrorLabel,
            dynamicSlidersBox
        );
    }

    /**
     * Load params into the editor and auto-compile.
     */
    public void loadParams(CustomShaderParams params) {
        this.currentParams = params;
        shaderEditorArea.setText(params.getShaderSource());
        Platform.runLater(this::compileAndRun);
    }

    /**
     * Set a callback fired on successful compilation.
     * When set, the editor skips direct controller.compileCustomShader and
     * instead relies on this callback to trigger graph-level recompilation.
     */
    public void setOnCompileSuccess(Runnable callback) {
        this.onCompileSuccess = callback;
    }

    private void compileAndRun() {
        String source = shaderEditorArea.getText();
        if (currentParams != null) {
            currentParams.setShaderSource(source);
        }

        // When embedded in NodeGraphEditor, delegate compilation to the graph pipeline
        if (onCompileSuccess != null) {
            rebuildSliders(source);
            setCompileStatus("Source updated", "success");
            onCompileSuccess.run();
            return;
        }

        // Standalone mode: compile directly
        compileBtn.setDisable(true);
        shaderEditorArea.setDisable(true);
        templateCombo.setDisable(true);
        setCompileStatus("Compiling...", "pending");

        Thread compileThread = new Thread(() -> {
            String error = controller.compileCustomShader(source);
            Platform.runLater(() -> {
                compileBtn.setDisable(false);
                shaderEditorArea.setDisable(false);
                templateCombo.setDisable(false);
                if (error == null) {
                    setCompileStatus("Compilation successful", "success");
                    rebuildSliders(source);
                    renderCallback.requestRender();
                } else {
                    setCompileStatus(error, "error");
                }
            });
        }, "CustomShader-Compile");
        compileThread.setDaemon(true);
        compileThread.start();
    }

    private void setCompileStatus(String text, String state) {
        compileErrorLabel.setText(text);
        compileErrorLabel.getStyleClass().removeAll("success", "error", "pending");
        compileErrorLabel.getStyleClass().add(state);
    }

    // ========================================================================
    // Uniform parsing + dynamic slider generation
    // ========================================================================

    private record UniformInfo(String type, String name, double min, double max, double[] defaults) {}

    private List<UniformInfo> parseUniforms(String source) {
        List<UniformInfo> result = new ArrayList<>();
        Matcher m = UNIFORM_PATTERN.matcher(source);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            String attrs = m.group(3);

            double min = 0, max = 10;
            double[] defaults = null;
            Matcher am = ATTR_PATTERN.matcher(attrs);
            while (am.find()) {
                String key = am.group(1);
                String val = am.group(2);
                switch (key) {
                    case "min" -> min = Double.parseDouble(val);
                    case "max" -> max = Double.parseDouble(val);
                    case "default" -> {
                        String[] parts = val.split(",");
                        defaults = new double[parts.length];
                        for (int i = 0; i < parts.length; i++) {
                            defaults[i] = Double.parseDouble(parts[i].trim());
                        }
                    }
                }
            }

            int components = switch (type) {
                case "vec2" -> 2;
                case "vec3" -> 3;
                case "vec4" -> 4;
                default -> 1;
            };
            if (defaults == null) {
                defaults = new double[components];
            }
            // Pad defaults to match components
            if (defaults.length < components) {
                double[] padded = new double[components];
                System.arraycopy(defaults, 0, padded, 0, defaults.length);
                defaults = padded;
            }

            result.add(new UniformInfo(type, name, min, max, defaults));
        }
        return result;
    }

    private void rebuildSliders(String source) {
        dynamicSlidersBox.getChildren().clear();
        if (currentParams == null) return;

        List<UniformInfo> uniforms = parseUniforms(source);
        if (uniforms.isEmpty()) return;

        Label header = new Label("Shader Parameters");
        header.getStyleClass().add("bold-label");
        dynamicSlidersBox.getChildren().add(header);

        // Preserve existing values from params map
        Map<String, Object> existingValues = currentParams.getUniformValues();
        Map<String, Object> newValues = new LinkedHashMap<>();

        for (UniformInfo u : uniforms) {
            boolean isInt = u.type.equals("int");
            int components = u.defaults.length;

            if (components == 1) {
                // Scalar: float or int
                double existing = getExistingScalar(existingValues, u.name, u.defaults[0]);
                EnhancedSlider slider = new EnhancedSlider(u.name, u.min, u.max, existing, isInt);
                slider.setOnAction(v -> {
                    if (!suppressSliderEvents && currentParams != null) {
                        currentParams.setUniformValue(u.name, isInt ? (Object) v.intValue() : (Object) v.floatValue());
                        renderCallback.requestRender();
                    }
                });
                dynamicSlidersBox.getChildren().add(slider);
                newValues.put(u.name, isInt ? (Object)(int)existing : (Object)(float)existing);
            } else {
                // Vector: vec2/vec3/vec4
                String[] labels = {"X", "Y", "Z", "W"};
                float[] existingVec = getExistingVec(existingValues, u.name, u.defaults);
                float[] vecRef = Arrays.copyOf(existingVec, components);

                Label vecLabel = new Label(u.name);
                vecLabel.getStyleClass().add("hint-label");
                dynamicSlidersBox.getChildren().add(vecLabel);

                for (int i = 0; i < components; i++) {
                    final int idx = i;
                    String sliderName = u.name + " " + labels[i];
                    EnhancedSlider slider = new EnhancedSlider(sliderName, u.min, u.max, vecRef[i], false);
                    slider.setOnAction(v -> {
                        if (!suppressSliderEvents && currentParams != null) {
                            vecRef[idx] = v.floatValue();
                            currentParams.setUniformValue(u.name, Arrays.copyOf(vecRef, vecRef.length));
                            renderCallback.requestRender();
                        }
                    });
                    dynamicSlidersBox.getChildren().add(slider);
                }
                newValues.put(u.name, Arrays.copyOf(vecRef, components));
            }
        }

        // Replace uniform values: keep values for uniforms that still exist, add defaults for new ones
        currentParams.clearUniformValues();
        for (var entry : newValues.entrySet()) {
            currentParams.setUniformValue(entry.getKey(), entry.getValue());
        }
    }

    private double getExistingScalar(Map<String, Object> existing, String name, double fallback) {
        Object v = existing.get(name);
        if (v instanceof Number n) return n.doubleValue();
        return fallback;
    }

    private float[] getExistingVec(Map<String, Object> existing, String name, double[] fallback) {
        Object v = existing.get(name);
        if (v instanceof float[] arr && arr.length >= fallback.length) return Arrays.copyOf(arr, fallback.length);
        float[] result = new float[fallback.length];
        for (int i = 0; i < fallback.length; i++) result[i] = (float) fallback[i];
        return result;
    }

    // ========================================================================
    // Templates with @param annotations
    // ========================================================================

    private static final String TEMPLATE_SPHERE =
        "// Sphere — simplest possible DE\n" +
        "\n" +
        "uniform float radius; // @param min:0.1 max:3 default:1\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    trap.minDist = length(pos);\n" +
        "    trap.planeX = abs(pos.x);\n" +
        "    trap.planeY = abs(pos.y);\n" +
        "    trap.planeZ = abs(pos.z);\n" +
        "    trap.iterations = 1;\n" +
        "    return length(pos) - radius;\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    return length(pos) - radius;\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 0.8);\n" +
        "    float flow = (exp(-trap.planeX * 3.0) + exp(-trap.planeY * 3.0) + exp(-trap.planeZ * 3.0)) / 3.0;\n" +
        "    float iterNorm = 0.5;\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";

    private static final String TEMPLATE_TORUS =
        "// Torus — two radii SDE\n" +
        "\n" +
        "uniform float majorRadius; // @param min:0.1 max:2 default:0.75\n" +
        "uniform float minorRadius; // @param min:0.01 max:1 default:0.25\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float sdTorus(vec3 p, float R, float r) {\n" +
        "    vec2 q = vec2(length(p.xz) - R, p.y);\n" +
        "    return length(q) - r;\n" +
        "}\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    trap.minDist = length(pos);\n" +
        "    trap.planeX = abs(pos.x);\n" +
        "    trap.planeY = abs(pos.y);\n" +
        "    trap.planeZ = abs(pos.z);\n" +
        "    trap.iterations = 1;\n" +
        "    return sdTorus(pos, majorRadius, minorRadius);\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    return sdTorus(pos, majorRadius, minorRadius);\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 0.8);\n" +
        "    float flow = (exp(-trap.planeX * 3.0) + exp(-trap.planeY * 3.0) + exp(-trap.planeZ * 3.0)) / 3.0;\n" +
        "    float iterNorm = 0.5;\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";

    private static final String TEMPLATE_GYROID =
        "// Gyroid — triply-periodic minimal surface\n" +
        "// Beautiful organic structure, great for coloring\n" +
        "\n" +
        "uniform float scale;     // @param min:1 max:20 default:5\n" +
        "uniform float thickness; // @param min:0.01 max:0.5 default:0.05\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float sdGyroid(vec3 p, float s, float t) {\n" +
        "    p = p * s;\n" +
        "    float d = dot(sin(p), cos(p.zxy));\n" +
        "    return abs(d) / s - t;\n" +
        "}\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    trap.minDist = length(pos);\n" +
        "    trap.planeX = abs(pos.x);\n" +
        "    trap.planeY = abs(pos.y);\n" +
        "    trap.planeZ = abs(pos.z);\n" +
        "    trap.iterations = 1;\n" +
        "    // Intersect gyroid with a bounding sphere\n" +
        "    float gyroid = sdGyroid(pos, scale, thickness);\n" +
        "    float bound = length(pos) - 2.0;\n" +
        "    return max(gyroid, bound);\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    float gyroid = sdGyroid(pos, scale, thickness);\n" +
        "    float bound = length(pos) - 2.0;\n" +
        "    return max(gyroid, bound);\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 0.5);\n" +
        "    float flow = (exp(-trap.planeX * 2.0) + exp(-trap.planeY * 2.0) + exp(-trap.planeZ * 2.0)) / 3.0;\n" +
        "    float iterNorm = 0.5;\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";

    private static final String TEMPLATE_INFINITE_SPHERES =
        "// Infinite Spheres — mod() space repetition\n" +
        "// Fundamental SDF technique: tile a primitive infinitely\n" +
        "\n" +
        "uniform float spacing; // @param min:0.5 max:5 default:2\n" +
        "uniform float radius;  // @param min:0.05 max:1.5 default:0.4\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    // Fold space into repeating cells\n" +
        "    vec3 cell = floor(pos / spacing + 0.5);\n" +
        "    vec3 local = pos - cell * spacing;\n" +
        "    trap.minDist = length(local);\n" +
        "    trap.planeX = abs(cell.x);\n" +
        "    trap.planeY = abs(cell.y);\n" +
        "    trap.planeZ = abs(cell.z);\n" +
        "    trap.iterations = int(mod(cell.x + cell.y + cell.z, 4.0));\n" +
        "    return length(local) - radius;\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    vec3 local = pos - floor(pos / spacing + 0.5) * spacing;\n" +
        "    return length(local) - radius;\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 1.5);\n" +
        "    float flow = (exp(-trap.planeX * 0.5) + exp(-trap.planeY * 0.5) + exp(-trap.planeZ * 0.5)) / 3.0;\n" +
        "    float iterNorm = float(trap.iterations) / 4.0;\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";

    private static final String TEMPLATE_MENGER =
        "// Menger Sponge — IFS fold technique\n" +
        "// Repeated folding + box subtraction creates fractal holes\n" +
        "\n" +
        "uniform int maxIterations; // @param min:1 max:8 default:4\n" +
        "uniform float scale;       // @param min:1.5 max:4 default:3\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float sdBox(vec3 p, vec3 b) {\n" +
        "    vec3 d = abs(p) - b;\n" +
        "    return min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, 0.0));\n" +
        "}\n" +
        "\n" +
        "float sdCross(vec3 p) {\n" +
        "    float da = max(abs(p.x), abs(p.y));\n" +
        "    float db = max(abs(p.y), abs(p.z));\n" +
        "    float dc = max(abs(p.z), abs(p.x));\n" +
        "    return min(da, min(db, dc)) - 1.0;\n" +
        "}\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    float d = sdBox(pos, vec3(1.0));\n" +
        "    float s = 1.0;\n" +
        "    trap.minDist = 1e10;\n" +
        "    trap.planeX = 1e10;\n" +
        "    trap.planeY = 1e10;\n" +
        "    trap.planeZ = 1e10;\n" +
        "    trap.iterations = 0;\n" +
        "    for (int i = 0; i < maxIterations; i++) {\n" +
        "        vec3 a = mod(pos * s, 2.0) - 1.0;\n" +
        "        s = s * scale;\n" +
        "        vec3 r = abs(1.0 - 3.0 * abs(a));\n" +
        "        float c = sdCross(r) / s;\n" +
        "        d = max(d, -c);\n" +
        "        trap.minDist = min(trap.minDist, length(a));\n" +
        "        trap.planeX = min(trap.planeX, r.x);\n" +
        "        trap.planeY = min(trap.planeY, r.y);\n" +
        "        trap.planeZ = min(trap.planeZ, r.z);\n" +
        "        trap.iterations = i + 1;\n" +
        "    }\n" +
        "    return d;\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    float d = sdBox(pos, vec3(1.0));\n" +
        "    float s = 1.0;\n" +
        "    for (int i = 0; i < maxIterations; i++) {\n" +
        "        vec3 a = mod(pos * s, 2.0) - 1.0;\n" +
        "        s = s * scale;\n" +
        "        vec3 r = abs(1.0 - 3.0 * abs(a));\n" +
        "        float c = sdCross(r) / s;\n" +
        "        d = max(d, -c);\n" +
        "    }\n" +
        "    return d;\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 1.2);\n" +
        "    float flow = (exp(-trap.planeX * 2.0) + exp(-trap.planeY * 2.0) + exp(-trap.planeZ * 2.0)) / 3.0;\n" +
        "    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";

    private static final String TEMPLATE_MANDELBULB =
        "// Mandelbulb — power-N with full orbit traps\n" +
        "\n" +
        "uniform float power;      // @param min:2 max:16 default:8\n" +
        "uniform int maxIterations; // @param min:3 max:30 default:15\n" +
        "uniform float bailout;    // @param min:1 max:16 default:2\n" +
        "\n" +
        "struct OrbitTrap {\n" +
        "    float minDist;\n" +
        "    float planeX;\n" +
        "    float planeY;\n" +
        "    float planeZ;\n" +
        "    int iterations;\n" +
        "};\n" +
        "\n" +
        "float DE(vec3 pos, out OrbitTrap trap) {\n" +
        "    vec3 z = pos;\n" +
        "    float dr = 1.0;\n" +
        "    float r = 0.0;\n" +
        "\n" +
        "    trap.minDist = 1e10;\n" +
        "    trap.planeX = 1e10;\n" +
        "    trap.planeY = 1e10;\n" +
        "    trap.planeZ = 1e10;\n" +
        "    trap.iterations = 0;\n" +
        "\n" +
        "    for (int i = 0; i < maxIterations; i++) {\n" +
        "        r = length(z);\n" +
        "        if (r > bailout) break;\n" +
        "        float theta = acos(z.z / r);\n" +
        "        float phi = atan(z.y, z.x);\n" +
        "        dr = pow(r, power - 1.0) * power * dr + 1.0;\n" +
        "        float zr = pow(r, power);\n" +
        "        theta = theta * power;\n" +
        "        phi = phi * power;\n" +
        "        z = zr * vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta));\n" +
        "        z += pos;\n" +
        "        trap.minDist = min(trap.minDist, length(z));\n" +
        "        trap.planeX = min(trap.planeX, abs(z.x));\n" +
        "        trap.planeY = min(trap.planeY, abs(z.y));\n" +
        "        trap.planeZ = min(trap.planeZ, abs(z.z));\n" +
        "        trap.iterations = i + 1;\n" +
        "    }\n" +
        "    return 0.5 * log(r) * r / dr;\n" +
        "}\n" +
        "\n" +
        "float DE_simple(vec3 pos) {\n" +
        "    vec3 z = pos;\n" +
        "    float dr = 1.0;\n" +
        "    float r = 0.0;\n" +
        "    for (int i = 0; i < maxIterations; i++) {\n" +
        "        r = length(z);\n" +
        "        if (r > bailout) break;\n" +
        "        float theta = acos(z.z / r);\n" +
        "        float phi = atan(z.y, z.x);\n" +
        "        dr = pow(r, power - 1.0) * power * dr + 1.0;\n" +
        "        float zr = pow(r, power);\n" +
        "        theta = theta * power;\n" +
        "        phi = phi * power;\n" +
        "        z = zr * vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta));\n" +
        "        z += pos;\n" +
        "    }\n" +
        "    return 0.5 * log(r) * r / dr;\n" +
        "}\n" +
        "\n" +
        "vec3 getFactors(OrbitTrap trap) {\n" +
        "    float structural = 1.0 - exp(-trap.minDist * 0.8);\n" +
        "    float trapX = exp(-trap.planeX * 3.0);\n" +
        "    float trapY = exp(-trap.planeY * 3.0);\n" +
        "    float trapZ = exp(-trap.planeZ * 3.0);\n" +
        "    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;\n" +
        "    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));\n" +
        "    return vec3(structural, flow, iterNorm);\n" +
        "}\n";
}
