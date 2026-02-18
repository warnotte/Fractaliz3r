package org.fractalizer.fractals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameters for the Custom Shader fractal type.
 * Stores user-written GLSL shader source code and dynamic uniform values.
 */
public class CustomShaderParams extends AbstractFractalParams {

    public static final String DEFAULT_SHADER =
        "// === Custom Fractal Shader ===\n" +
        "// Define: OrbitTrap struct, DE(), DE_simple(), getFactors()\n" +
        "// common.glsl + raytracer.glsl are included automatically.\n" +
        "//\n" +
        "// Add @param to uniforms for automatic slider UI:\n" +
        "//   uniform float name; // @param min:0 max:10 default:1\n" +
        "//   uniform int name;   // @param min:1 max:30 default:15\n" +
        "//   uniform vec3 name;  // @param min:-1 max:1 default:0,0,0\n" +
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

    private String shaderSource;
    private final Map<String, Object> uniformValues = new LinkedHashMap<>();

    public CustomShaderParams() {
        super();
        this.shaderSource = DEFAULT_SHADER;
    }

    @Override
    public FractalType getType() {
        return FractalType.CUSTOM_SHADER;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        CustomShaderParams reduced = new CustomShaderParams();
        copyCommonParams(reduced);
        reduced.shaderSource = this.shaderSource;
        reduced.uniformValues.putAll(this.uniformValues);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public String getShaderSource() { return shaderSource; }
    public void setShaderSource(String shaderSource) { this.shaderSource = shaderSource; }

    public Map<String, Object> getUniformValues() { return uniformValues; }

    public void setUniformValue(String name, Object value) { uniformValues.put(name, value); }

    public void clearUniformValues() { uniformValues.clear(); }
}
