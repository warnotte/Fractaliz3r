package org.fractalizer.test;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.*;

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
            assertContains(glsl, "0.1", "Blend value in smin call");

            assertTrue(uniforms.containsKey("n0_power"), "Mandelbulb uniform");
            assertTrue(uniforms.containsKey("n1_maxIterations"), "Menger uniform");
            assertTrue(uniforms.containsKey("n1_scale"), "Menger scale uniform");

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
