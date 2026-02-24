package org.fractalizer.test;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.graph.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone test for the graph compiler.
 * Prints generated GLSL for visual inspection and validates structure.
 *
 * Run: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.GraphCompilerTest"
 */
public class GraphCompilerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testSingleFractal();
        testCSGUnion();
        testCSGWithTransform();
        testThreeFractalsNested();
        testCollectUniformsStatic();
        testStoredFractalParams();
        testMirrorTransform();
        testTwistTransform();
        testRepetitionTransform();

        System.out.println("\n========================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /**
     * Test 1: Single FractalNode(MANDELBULB) — simplest case.
     * Should produce n0_ prefixed mandelbulb + trivial DE/DE_simple/getFactors.
     */
    private static void testSingleFractal() {
        System.out.println("\n=== Test 1: Single FractalNode(MANDELBULB) ===");
        try {
            GraphNode root = new FractalNode(FractalType.MANDELBULB);
            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);
            Map<String, Object> uniforms = compiler.getUniforms(root);

            assertContains(glsl, "n0_DE(", "Preprocessed DE function");
            assertContains(glsl, "n0_DE_simple(", "Preprocessed DE_simple function");
            assertContains(glsl, "n0_getFactors(", "Preprocessed getFactors function");
            assertContains(glsl, "n0_OrbitTrap", "Preprocessed OrbitTrap struct");
            assertContains(glsl, "struct OrbitTrap", "Composite OrbitTrap struct");
            assertContains(glsl, "float DE(vec3 pos, out OrbitTrap trap)", "Composite DE");
            assertContains(glsl, "float DE_simple(vec3 pos)", "Composite DE_simple");
            assertContains(glsl, "vec3 getFactors(OrbitTrap trap)", "Composite getFactors");
            assertNotContains(glsl, "smin_graph", "No smin needed for single fractal");

            assertTrue(uniforms.containsKey("n0_power"), "Uniform n0_power exists");
            assertTrue(uniforms.containsKey("n0_maxIterations"), "Uniform n0_maxIterations exists");
            assertTrue(uniforms.containsKey("n0_bailout"), "Uniform n0_bailout exists");

            printSummary(glsl, uniforms);
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 2: CSGNode(UNION, MANDELBULB, MENGER) — two fractals combined.
     */
    private static void testCSGUnion() {
        System.out.println("\n=== Test 2: CSGNode(UNION, MANDELBULB + MENGER, blend=0.1) ===");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new FractalNode(FractalType.MENGER_SPONGE),
                0.1f
            );
            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);
            Map<String, Object> uniforms = compiler.getUniforms(root);

            assertContains(glsl, "n0_DE(", "Mandelbulb DE with n0_ prefix");
            assertContains(glsl, "n1_DE(", "Menger DE with n1_ prefix");
            assertContains(glsl, "smin_graph(", "smin for UNION");
            assertContains(glsl, "n0_getFactors(", "n0 getFactors for coloring");
            assertContains(glsl, "n1_getFactors(", "n1 getFactors for coloring");
            assertContains(glsl, "c0_blend", "Blend as uniform reference");
            assertContains(glsl, "uniform float c0_blend", "Blend uniform declaration");

            assertTrue(uniforms.containsKey("n0_power"), "Mandelbulb uniform");
            assertTrue(uniforms.containsKey("n1_maxIterations"), "Menger uniform");
            assertTrue(uniforms.containsKey("n1_scale"), "Menger scale uniform");
            assertTrue(uniforms.containsKey("c0_blend"), "CSG blend uniform");
            assertTrue(((Number) uniforms.get("c0_blend")).floatValue() == 0.1f, "Blend value is 0.1");

            printSummary(glsl, uniforms);
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 3: CSG + TransformNode — offset one fractal.
     */
    private static void testCSGWithTransform() {
        System.out.println("\n=== Test 3: CSG(UNION, MANDELBULB, Transform(offset=[2,0,0], MENGER)) ===");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new TransformNode(
                    new FractalNode(FractalType.MENGER_SPONGE),
                    new float[]{2.0f, 0.0f, 0.0f},
                    new float[]{0, 0, 0},
                    1.0f
                ),
                0.1f
            );
            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);
            Map<String, Object> uniforms = compiler.getUniforms(root);

            assertContains(glsl, "applyTransform_t0(", "Transform function generated");
            assertContains(glsl, "t0_offset", "Transform offset uniform");
            assertContains(glsl, "t0_scale", "Transform scale uniform");
            assertContains(glsl, "pos_t0", "Transformed position variable");

            assertTrue(uniforms.containsKey("t0_offset"), "Transform offset uniform in map");
            assertTrue(uniforms.containsKey("t0_scale"), "Transform scale uniform in map");

            float[] offset = (float[]) uniforms.get("t0_offset");
            assertTrue(offset[0] == 2.0f && offset[1] == 0.0f && offset[2] == 0.0f,
                "Transform offset values correct");

            printSummary(glsl, uniforms);
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 4: Three fractals nested — CSG(SUBTRACT, CSG(UNION, A, B), C).
     */
    private static void testThreeFractalsNested() {
        System.out.println("\n=== Test 4: CSG(SUBTRACT, CSG(UNION, Mandelbulb, Menger), Sierpinski) ===");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.SUBTRACT,
                new CSGNode(
                    CSGNode.Op.UNION,
                    new FractalNode(FractalType.MANDELBULB),
                    new FractalNode(FractalType.MENGER_SPONGE),
                    0.05f
                ),
                new FractalNode(FractalType.SIERPINSKI),
                0.0f
            );
            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);
            Map<String, Object> uniforms = compiler.getUniforms(root);

            assertContains(glsl, "n0_DE(", "First fractal (n0)");
            assertContains(glsl, "n1_DE(", "Second fractal (n1)");
            assertContains(glsl, "n2_DE(", "Third fractal (n2)");
            assertContains(glsl, "smin_graph(", "Inner UNION");
            assertContains(glsl, "smax_graph(", "Outer SUBTRACT");
            assertContains(glsl, "-n2_d", "Negated right side for subtract");

            // Check winner propagation for 3 leaves
            assertContains(glsl, "== 0)", "Winner check for leaf 0");
            assertContains(glsl, "== 1)", "Winner check for leaf 1");

            assertTrue(uniforms.containsKey("n0_power"), "Mandelbulb uniform");
            assertTrue(uniforms.containsKey("n1_scale"), "Menger uniform");
            assertTrue(uniforms.containsKey("n2_maxIterations"), "Sierpinski uniform");

            printSummary(glsl, uniforms);
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 5: collectUniformsStatic — refresh uniforms without recompiling.
     */
    private static void testCollectUniformsStatic() {
        System.out.println("\n=== Test 5: collectUniformsStatic (parameter-only update) ===");
        try {
            CSGNode root = new CSGNode(
                CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new TransformNode(
                    new FractalNode(FractalType.MENGER_SPONGE),
                    new float[]{2.0f, 0.0f, 0.0f}
                ),
                0.1f
            );
            GraphCompiler compiler = new GraphCompiler();
            compiler.compile(root);  // assigns IDs

            // Now change blend without recompiling
            root.setBlend(0.5f);
            Map<String, Object> refreshed = GraphCompiler.collectUniformsStatic(root);

            assertTrue(refreshed.containsKey("c0_blend"), "c0_blend in static uniforms");
            assertTrue(((Number) refreshed.get("c0_blend")).floatValue() == 0.5f,
                "Updated blend value is 0.5");
            assertTrue(refreshed.containsKey("n0_power"), "n0_power still present");
            assertTrue(refreshed.containsKey("t0_offset"), "t0_offset still present");

            System.out.println("  Uniforms after update: " + refreshed.size());
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 6: Per-node fractal params — changing power on a FractalNode reflects in uniforms.
     */
    private static void testStoredFractalParams() {
        System.out.println("\n=== Test 6: Stored fractal params (per-node power) ===");
        try {
            FractalNode fn = new FractalNode(FractalType.MANDELBULB);
            ((MandelbulbParams) fn.getFractalParams()).setPower(12.0f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(fn);
            Map<String, Object> uniforms = compiler.getUniforms(fn);

            assertTrue(uniforms.containsKey("n0_power"), "n0_power exists");
            assertTrue(((Number) uniforms.get("n0_power")).floatValue() == 12.0f,
                "Power is 12 (not default 8)");

            // Verify collectUniformsStatic also picks up stored params
            ((MandelbulbParams) fn.getFractalParams()).setPower(5.0f);
            Map<String, Object> refreshed = GraphCompiler.collectUniformsStatic(fn);
            assertTrue(((Number) refreshed.get("n0_power")).floatValue() == 5.0f,
                "Static refresh picks up power=5");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 7: Mirror transform — generates mirror GLSL and correct uniforms.
     */
    private static void testMirrorTransform() {
        System.out.println("\n=== Test 7: Mirror transform ===");
        try {
            TransformNode mirror = new TransformNode(
                new FractalNode(FractalType.MANDELBULB),
                new float[]{0.5f, 0, 0}
            );
            mirror.setMode(TransformNode.Mode.MIRROR);
            mirror.setAxis(0); // X axis

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(mirror);
            Map<String, Object> uniforms = compiler.getUniforms(mirror);

            assertContains(glsl, "t0_mirrorAxis", "Mirror axis uniform");
            assertContains(glsl, "t0_mirrorOffset", "Mirror offset uniform");
            assertContains(glsl, "applyTransform_t0", "Transform function");
            assertNotContains(glsl, "t0_rotX", "No rotation for mirror");

            assertTrue(uniforms.containsKey("t0_mirrorAxis"), "mirrorAxis uniform");
            float[] axis = (float[]) uniforms.get("t0_mirrorAxis");
            assertTrue(axis[0] == 1.0f && axis[1] == 0.0f && axis[2] == 0.0f,
                "Mirror axis is X (1,0,0)");
            assertTrue(((Number) uniforms.get("t0_mirrorOffset")).floatValue() == 0.5f,
                "Mirror offset is 0.5");

            // Mirror has no scale correction
            assertContains(glsl, "float d_t0 = n0_d;", "No scale correction for mirror");

            printSummary(glsl, uniforms);
            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 8: Twist transform — generates Rodrigues rotation GLSL.
     */
    private static void testTwistTransform() {
        System.out.println("\n=== Test 8: Twist transform ===");
        try {
            TransformNode twist = new TransformNode(
                new FractalNode(FractalType.MENGER_SPONGE),
                new float[]{0, 0, 0}
            );
            twist.setMode(TransformNode.Mode.TWIST);
            twist.setAxis(1); // Y axis
            twist.setScale(0.8f); // twist strength

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(twist);
            Map<String, Object> uniforms = compiler.getUniforms(twist);

            assertContains(glsl, "t0_twistAxis", "Twist axis uniform");
            assertContains(glsl, "t0_twistStrength", "Twist strength uniform");
            assertContains(glsl, "cross(k, pos)", "Rodrigues rotation");

            float[] axis = (float[]) uniforms.get("t0_twistAxis");
            assertTrue(axis[1] == 1.0f, "Twist axis is Y");
            assertTrue(((Number) uniforms.get("t0_twistStrength")).floatValue() == 0.8f,
                "Twist strength is 0.8");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 9: Repetition transform — generates mod() GLSL.
     */
    private static void testRepetitionTransform() {
        System.out.println("\n=== Test 9: Repetition transform ===");
        try {
            TransformNode rep = new TransformNode(
                new FractalNode(FractalType.SIERPINSKI),
                new float[]{3, 3, 3}
            );
            rep.setMode(TransformNode.Mode.REPETITION);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(rep);
            Map<String, Object> uniforms = compiler.getUniforms(rep);

            assertContains(glsl, "t0_period", "Period uniform");
            assertContains(glsl, "mod(pos", "mod() for repetition");

            float[] period = (float[]) uniforms.get("t0_period");
            assertTrue(period[0] == 3.0f && period[1] == 3.0f && period[2] == 3.0f,
                "Period is (3,3,3)");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    // ========================================================================
    // Assertion helpers
    // ========================================================================

    private static void assertContains(String glsl, String expected, String desc) {
        if (!glsl.contains(expected)) {
            throw new AssertionError("Expected '" + expected + "' in GLSL output (" + desc + ")");
        }
    }

    private static void assertNotContains(String glsl, String unexpected, String desc) {
        if (glsl.contains(unexpected)) {
            throw new AssertionError("Did not expect '" + unexpected + "' in GLSL output (" + desc + ")");
        }
    }

    private static void assertTrue(boolean condition, String desc) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + desc);
        }
    }

    private static void printSummary(String glsl, Map<String, Object> uniforms) {
        System.out.println("  GLSL length: " + glsl.length() + " chars");
        System.out.println("  Uniforms: " + uniforms.size() + " entries");
        // Print first 60 lines of generated composite section (after preprocessed sources)
        String[] lines = glsl.split("\n");
        int compositeStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("Graph smin/smax") || lines[i].contains("Composite OrbitTrap")
                    || lines[i].contains("Composite DE")) {
                compositeStart = i;
                break;
            }
        }
        if (compositeStart >= 0) {
            System.out.println("  --- Composite GLSL (from line " + compositeStart + ") ---");
            for (int i = compositeStart; i < Math.min(lines.length, compositeStart + 60); i++) {
                System.out.println("  | " + lines[i]);
            }
            if (compositeStart + 60 < lines.length) {
                System.out.println("  | ... (" + (lines.length - compositeStart - 60) + " more lines)");
            }
        }
    }
}
