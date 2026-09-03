package org.fractalizer.graph;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.MandelbulbParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graph compiler turns a node tree into one GLSL distance estimator. These check the
 * shape of what it emits (prefixes, composite functions, CSG operators, transform
 * corrections) and the uniform map that must accompany it, without touching a GPU.
 */
class GraphCompilerTest {

    static float f(Map<String, Object> u, String key) {
        assertTrue(u.containsKey(key), "uniform " + key + " missing; have " + u.keySet());
        return ((Number) u.get(key)).floatValue();
    }

    @Test
    void singleFractalGetsPrefixedSourceAndCompositeEntryPoints() {
        GraphNode root = new FractalNode(FractalType.MANDELBULB);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        Map<String, Object> uniforms = compiler.getUniforms(root);

        assertTrue(glsl.contains("n0_DE("), "preprocessed DE");
        assertTrue(glsl.contains("n0_DE_simple("), "preprocessed DE_simple");
        assertTrue(glsl.contains("n0_getFactors("), "preprocessed getFactors");
        assertTrue(glsl.contains("n0_OrbitTrap"), "preprocessed OrbitTrap");
        assertTrue(glsl.contains("struct OrbitTrap"), "composite OrbitTrap");
        assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"), "composite DE");
        assertTrue(glsl.contains("float DE_simple(vec3 pos)"), "composite DE_simple");
        assertTrue(glsl.contains("vec3 getFactors(OrbitTrap trap)"), "composite getFactors");
        assertFalse(glsl.contains("smin_graph"), "no smooth-min for a single leaf");

        assertTrue(uniforms.containsKey("n0_power"));
        assertTrue(uniforms.containsKey("n0_maxIterations"));
        assertTrue(uniforms.containsKey("n0_bailout"));
    }

    @Test
    void unionOfTwoFractalsUsesSmoothMinAndABlendUniform() {
        GraphNode root = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new FractalNode(FractalType.MENGER_SPONGE), 0.1f);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        Map<String, Object> uniforms = compiler.getUniforms(root);

        assertTrue(glsl.contains("n0_DE("));
        assertTrue(glsl.contains("n1_DE("));
        assertTrue(glsl.contains("smin_graph("), "smooth min for UNION");
        assertTrue(glsl.contains("n0_getFactors(") && glsl.contains("n1_getFactors("));
        assertTrue(glsl.contains("uniform float c0_blend"), "blend is a uniform, not a literal");

        assertTrue(uniforms.containsKey("n0_power"));
        assertTrue(uniforms.containsKey("n1_maxIterations"));
        assertTrue(uniforms.containsKey("n1_scale"));
        assertEquals(0.1f, f(uniforms, "c0_blend"));
    }

    @Test
    void transformNodeEmitsAFunctionAndItsUniforms() {
        GraphNode root = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new TransformNode(new FractalNode(FractalType.MENGER_SPONGE),
                        new float[]{2.0f, 0.0f, 0.0f}, new float[]{0, 0, 0}, 1.0f),
                0.1f);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        Map<String, Object> uniforms = compiler.getUniforms(root);

        assertTrue(glsl.contains("applyTransform_t0("), "transform function");
        assertTrue(glsl.contains("t0_offset") && glsl.contains("t0_scale"));
        assertTrue(glsl.contains("pos_t0"), "transformed position variable");

        assertArrayEquals(new float[]{2, 0, 0}, (float[]) uniforms.get("t0_offset"));
        assertEquals(1.0f, f(uniforms, "t0_scale"));
    }

    @Test
    void nestedCsgSubtractsWithNegatedRightSideAndTracksTheWinner() {
        GraphNode root = new CSGNode(CSGNode.Op.SUBTRACT,
                new CSGNode(CSGNode.Op.UNION,
                        new FractalNode(FractalType.MANDELBULB),
                        new FractalNode(FractalType.MENGER_SPONGE), 0.05f),
                new FractalNode(FractalType.SIERPINSKI), 0.0f);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        Map<String, Object> uniforms = compiler.getUniforms(root);

        assertTrue(glsl.contains("n0_DE(") && glsl.contains("n1_DE(") && glsl.contains("n2_DE("));
        assertTrue(glsl.contains("smin_graph("), "inner UNION");
        assertTrue(glsl.contains("smax_graph("), "outer SUBTRACT");
        assertTrue(glsl.contains("-n2_d"), "subtract negates the right side");
        assertTrue(glsl.contains("== 0)") && glsl.contains("== 1)"), "winner checks per leaf");

        assertTrue(uniforms.containsKey("n0_power"));
        assertTrue(uniforms.containsKey("n1_scale"));
        assertTrue(uniforms.containsKey("n2_maxIterations"));
    }

    @Test
    void uniformsCanBeRefreshedWithoutRecompiling() {
        CSGNode root = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new TransformNode(new FractalNode(FractalType.MENGER_SPONGE), new float[]{2, 0, 0}),
                0.1f);
        new GraphCompiler().compile(root);   // assigns ids

        root.setBlend(0.5f);
        Map<String, Object> refreshed = GraphCompiler.collectUniformsStatic(root);
        assertEquals(0.5f, f(refreshed, "c0_blend"));
        assertTrue(refreshed.containsKey("n0_power"));
        assertTrue(refreshed.containsKey("t0_offset"));
    }

    @Test
    void perNodeFractalParamsReachTheUniforms() {
        FractalNode fn = new FractalNode(FractalType.MANDELBULB);
        ((MandelbulbParams) fn.getFractalParams()).setPower(12.0f);
        GraphCompiler compiler = new GraphCompiler();
        compiler.compile(fn);
        assertEquals(12.0f, f(compiler.getUniforms(fn), "n0_power"));

        ((MandelbulbParams) fn.getFractalParams()).setPower(5.0f);
        assertEquals(5.0f, f(GraphCompiler.collectUniformsStatic(fn), "n0_power"));
    }

    @Test
    void mirrorIsIsometricAndCarriesNoRotation() {
        TransformNode mirror = new TransformNode(new FractalNode(FractalType.MANDELBULB), new float[]{0.5f, 0, 0});
        mirror.setMode(TransformNode.Mode.MIRROR);
        mirror.setAxis(0);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(mirror);
        Map<String, Object> uniforms = compiler.getUniforms(mirror);

        assertTrue(glsl.contains("t0_mirrorAxis") && glsl.contains("t0_mirrorOffset"));
        assertTrue(glsl.contains("applyTransform_t0"));
        assertFalse(glsl.contains("t0_rotX"), "no rotation uniforms for a mirror");
        assertTrue(glsl.contains("float d_t0 = n0_d;"), "no distance correction: a mirror is isometric");

        assertArrayEquals(new float[]{1, 0, 0}, (float[]) uniforms.get("t0_mirrorAxis"));
        assertEquals(0.5f, f(uniforms, "t0_mirrorOffset"));
    }

    @Test
    void twistIsNonIsometricAndGetsADistanceCorrection() {
        TransformNode twist = new TransformNode(new FractalNode(FractalType.MENGER_SPONGE), new float[]{0, 0, 0});
        twist.setMode(TransformNode.Mode.TWIST);
        twist.setAxis(1);
        twist.setScale(0.8f);   // strength
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(twist);
        Map<String, Object> uniforms = compiler.getUniforms(twist);

        assertTrue(glsl.contains("uniform int t0_axis;"), "twist axis uniform");
        assertTrue(glsl.contains("uniform float t0_strength;"), "twist strength uniform");
        assertTrue(glsl.contains("uniform float t0_frequency;"), "twist frequency uniform");
        assertTrue(glsl.contains("float deCorr_t0(vec3 pos)"), "a distance correction function is emitted");
        assertTrue(glsl.contains("float d_t0 = n0_d * corr_t0;"), "the child's distance is scaled by it");

        assertEquals(1, ((Number) uniforms.get("t0_axis")).intValue());
        assertEquals(0.8f, f(uniforms, "t0_strength"));
    }

    @Test
    void repetitionUsesModAndAPeriodVector() {
        TransformNode rep = new TransformNode(new FractalNode(FractalType.SIERPINSKI), new float[]{3, 3, 3});
        rep.setMode(TransformNode.Mode.REPETITION);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(rep);
        Map<String, Object> uniforms = compiler.getUniforms(rep);

        assertTrue(glsl.contains("t0_period"));
        assertTrue(glsl.contains("mod(pos"), "domain repetition is a mod()");
        assertArrayEquals(new float[]{3, 3, 3}, (float[]) uniforms.get("t0_period"));
    }

    @Test
    void everyFractalTypeWithAShaderCompilesAsALeaf() {
        for (FractalType type : FractalType.values()) {
            if (type == FractalType.NODE_GRAPH || type == FractalType.CUSTOM_SHADER) continue;
            if (GraphCompilerTest.class.getResourceAsStream("/shaders/fractals/" + type.getKernelName() + ".glsl") == null) continue;
            FractalNode leaf = new FractalNode(type);
            GraphCompiler compiler = new GraphCompiler();
            String glsl = assertDoesNotThrow(() -> compiler.compile(leaf), type.name());
            assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"), type + " has a composite DE");
            assertFalse(compiler.getUniforms(leaf).isEmpty(), type + " exposes uniforms");
        }
    }
}
