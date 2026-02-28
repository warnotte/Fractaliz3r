package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.*;

import java.util.Map;

/**
 * Standalone tests for the SSBO material system.
 * Validates MaterialNode, GraphCompiler SSBO output, collectMaterialSSBOData(),
 * FractalConfig serialization, and CSG material propagation.
 *
 * Run: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.MaterialSSBOTest"
 */
public class MaterialSSBOTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // MaterialNode unit tests
        testMaterialNodeDefaults();
        testMaterialNodeColorModeClamping();
        testMaterialNodeFieldClamping();

        // GraphCompiler SSBO tests
        testSSBODefineEmitted();
        testSSBODeclarationStructure();
        testOrbitTrapHasMatId();
        testSingleMatIdLocal();
        testNoFatMaterialUniforms();
        testMaterialIndexAssignment();

        // CSG + Material tests
        testCSGMaterialSaveResetPick();
        testCSGMorphMaterialSnap();
        testNestedMaterialsMultipleIndices();

        // collectMaterialSSBOData tests
        testSSBODataLayout();
        testSSBODataNullWithoutMaterials();
        testSSBODataMultipleMaterials();

        // FractalConfig serialization tests
        testSerializeColorMode();
        testDeserializeColorMode();
        testDeserializeBackwardCompat();

        // No-material path (regression)
        testNoMaterialNoSSBO();

        System.out.println("\n========================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ========================================================================
    // MaterialNode unit tests
    // ========================================================================

    private static void testMaterialNodeDefaults() {
        System.out.println("\n=== MaterialNode: defaults ===");
        try {
            MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));

            assertTrue(mn.getMaterialType() == MaterialNode.TYPE_GLOBAL, "Default materialType is -1 (global)");
            assertTrue(mn.getColorMode() == MaterialNode.COLOR_PALETTE, "Default colorMode is 0 (palette)");
            assertTrue(mn.getColorR() == 1.0f, "Default colorR is 1.0");
            assertTrue(mn.getColorG() == 1.0f, "Default colorG is 1.0");
            assertTrue(mn.getColorB() == 1.0f, "Default colorB is 1.0");
            assertTrue(mn.getRoughness() == -1.0f, "Default roughness is -1 (sentinel)");
            assertTrue(mn.getMetallic() == -1.0f, "Default metallic is -1 (sentinel)");
            assertTrue(mn.getIor() == -1.0f, "Default ior is -1 (sentinel)");
            assertTrue(mn.getEmission() == -1.0f, "Default emission is -1 (sentinel)");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testMaterialNodeColorModeClamping() {
        System.out.println("\n=== MaterialNode: colorMode clamping ===");
        try {
            MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));

            mn.setColorMode(0);
            assertTrue(mn.getColorMode() == 0, "colorMode 0 accepted");

            mn.setColorMode(1);
            assertTrue(mn.getColorMode() == 1, "colorMode 1 accepted");

            mn.setColorMode(2);
            assertTrue(mn.getColorMode() == 2, "colorMode 2 accepted");

            mn.setColorMode(-1);
            assertTrue(mn.getColorMode() == 0, "colorMode -1 clamped to 0");

            mn.setColorMode(5);
            assertTrue(mn.getColorMode() == 2, "colorMode 5 clamped to 2");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testMaterialNodeFieldClamping() {
        System.out.println("\n=== MaterialNode: field clamping ===");
        try {
            MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));

            mn.setMaterialType(-2);
            assertTrue(mn.getMaterialType() == -1, "materialType clamped to -1 min");
            mn.setMaterialType(5);
            assertTrue(mn.getMaterialType() == 2, "materialType clamped to 2 max");

            mn.setColorR(-0.5f);
            assertTrue(mn.getColorR() == 0.0f, "colorR clamped to 0 min");
            mn.setColorR(1.5f);
            assertTrue(mn.getColorR() == 1.0f, "colorR clamped to 1 max");

            mn.setIor(5.0f);
            assertTrue(mn.getIor() == 3.0f, "ior clamped to 3 max");

            mn.setEmission(100f);
            assertTrue(mn.getEmission() == 50f, "emission clamped to 50 max");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // GraphCompiler SSBO tests
    // ========================================================================

    private static void testSSBODefineEmitted() {
        System.out.println("\n=== GraphCompiler: #define HAS_MATERIALS emitted ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            root.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertContains(glsl, "#define HAS_MATERIALS", "HAS_MATERIALS define present");
            assertNotContains(glsl, "#define NODE_GRAPH_MATERIALS", "Old NODE_GRAPH_MATERIALS not used");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testSSBODeclarationStructure() {
        System.out.println("\n=== GraphCompiler: SSBO declaration structure ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            root.setMaterialType(MaterialNode.TYPE_METALLIC);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertContains(glsl, "struct MaterialData", "MaterialData struct declared");
            assertContains(glsl, "float type;", "type field in MaterialData");
            assertContains(glsl, "float colorMode;", "colorMode field in MaterialData");
            assertContains(glsl, "float albedoR, albedoG, albedoB;", "albedo fields in MaterialData");
            assertContains(glsl, "float roughness, metallic, ior, emission;", "PBR fields in MaterialData");
            assertContains(glsl, "layout(std430, binding = 6)", "SSBO binding point 6");
            assertContains(glsl, "readonly buffer MaterialBuffer", "MaterialBuffer declaration");
            assertContains(glsl, "MaterialData materials[]", "materials array in buffer");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testOrbitTrapHasMatId() {
        System.out.println("\n=== GraphCompiler: OrbitTrap has matId field ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertContains(glsl, "int matId;", "matId field in OrbitTrap");
            assertContains(glsl, "struct OrbitTrap", "OrbitTrap struct exists");

            // Should NOT have the old fat fields
            assertNotContains(glsl, "int matType;", "No old matType field in OrbitTrap");
            assertNotContains(glsl, "float matColorR;", "No old matColorR field in OrbitTrap");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testSingleMatIdLocal() {
        System.out.println("\n=== GraphCompiler: single _matId local in DE ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertContains(glsl, "int _matId = -1;", "Single _matId local declared");
            // Should NOT have old fat locals
            assertNotContains(glsl, "int _matType =", "No old _matType local");
            assertNotContains(glsl, "float _matColorR =", "No old _matColorR local");
            assertNotContains(glsl, "float _matRoughness =", "No old _matRoughness local");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testNoFatMaterialUniforms() {
        System.out.println("\n=== GraphCompiler: no per-node material uniforms ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            root.setMaterialType(MaterialNode.TYPE_GLASS);
            root.setRoughness(0.5f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);
            Map<String, Object> uniforms = compiler.getUniforms(root);

            // No material uniforms should be emitted (data flows through SSBO)
            assertNotContains(glsl, "uniform int m0_matType", "No m0_matType uniform in GLSL");
            assertNotContains(glsl, "uniform float m0_matColorR", "No m0_matColorR uniform in GLSL");
            assertNotContains(glsl, "uniform float m0_roughness", "No m0_roughness uniform in GLSL");

            for (String key : uniforms.keySet()) {
                assertTrue(!key.startsWith("m0_"), "No m0_ uniform key: " + key);
            }

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testMaterialIndexAssignment() {
        System.out.println("\n=== GraphCompiler: material index assignment in DE ===");
        try {
            MaterialNode root = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            root.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertContains(glsl, "_matId = 0;", "Material index 0 assigned");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // CSG + Material tests
    // ========================================================================

    private static void testCSGMaterialSaveResetPick() {
        System.out.println("\n=== CSG + Material: save/reset/pick ===");
        try {
            // Material(Mandelbulb) UNION Material(Menger)
            MaterialNode leftMat = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            leftMat.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);
            leftMat.setColorMode(MaterialNode.COLOR_SOLID);
            leftMat.setColorR(1.0f); leftMat.setColorG(0.0f); leftMat.setColorB(0.0f);

            MaterialNode rightMat = new MaterialNode(new FractalNode(FractalType.MENGER_SPONGE));
            rightMat.setMaterialType(MaterialNode.TYPE_METALLIC);
            rightMat.setColorMode(MaterialNode.COLOR_SOLID);
            rightMat.setColorR(0.0f); rightMat.setColorG(1.0f); rightMat.setColorB(0.0f);

            CSGNode root = new CSGNode(CSGNode.Op.UNION, leftMat, rightMat, 0.0f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            // Save left matId before right subtree
            assertContains(glsl, "_matId_L_c0", "Left matId saved for CSG c0");
            // Reset before right subtree
            assertContains(glsl, "_matId = -1;", "matId reset before right subtree");
            // Pick based on winner
            assertContains(glsl, "_matId = _matId_L_c0", "matId pick from saved left");

            // Should NOT have 6+ save/reset variables
            assertNotContains(glsl, "_matType_L_", "No old _matType_L_ save");
            assertNotContains(glsl, "_matColorR_L_", "No old _matColorR_L_ save");

            // Both materials get indices
            assertContains(glsl, "_matId = 0;", "Left material index 0");
            assertContains(glsl, "_matId = 1;", "Right material index 1");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testCSGMorphMaterialSnap() {
        System.out.println("\n=== CSG Morph: material snap at 0.5 ===");
        try {
            MaterialNode leftMat = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            leftMat.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);

            MaterialNode rightMat = new MaterialNode(new FractalNode(FractalType.MENGER_SPONGE));
            rightMat.setMaterialType(MaterialNode.TYPE_GLASS);

            CSGNode root = new CSGNode(CSGNode.Op.MORPH, leftMat, rightMat, 0.3f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            // Morph snaps matId at 0.5 blend threshold
            assertContains(glsl, "< 0.5) ? _matId_L_c0 : _matId", "Morph material snap at 0.5");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testNestedMaterialsMultipleIndices() {
        System.out.println("\n=== Nested materials: multiple SSBO indices ===");
        try {
            // Create 3 materials at different positions in tree
            MaterialNode mat0 = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            mat0.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);

            MaterialNode mat1 = new MaterialNode(new FractalNode(FractalType.MENGER_SPONGE));
            mat1.setMaterialType(MaterialNode.TYPE_METALLIC);

            MaterialNode mat2 = new MaterialNode(new FractalNode(FractalType.SIERPINSKI));
            mat2.setMaterialType(MaterialNode.TYPE_GLASS);

            CSGNode inner = new CSGNode(CSGNode.Op.UNION, mat0, mat1, 0.0f);
            CSGNode root = new CSGNode(CSGNode.Op.SUBTRACT, inner, mat2, 0.0f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            // Three distinct material indices
            assertContains(glsl, "_matId = 0;", "Material index 0");
            assertContains(glsl, "_matId = 1;", "Material index 1");
            assertContains(glsl, "_matId = 2;", "Material index 2");

            // SSBO data should have 3 entries
            float[] data = GraphCompiler.collectMaterialSSBOData(root);
            assertTrue(data != null, "SSBO data not null");
            assertTrue(data.length == 36, "3 materials * 12 floats = 36");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // collectMaterialSSBOData tests
    // ========================================================================

    private static void testSSBODataLayout() {
        System.out.println("\n=== collectMaterialSSBOData: layout verification ===");
        try {
            MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            mn.setMaterialType(MaterialNode.TYPE_GLASS);      // 2
            mn.setColorMode(MaterialNode.COLOR_SOLID);         // 1
            mn.setColorR(0.85f);
            mn.setColorG(0.08f);
            mn.setColorB(0.08f);
            mn.setRoughness(0.3f);
            mn.setMetallic(0.7f);
            mn.setIor(1.5f);
            mn.setEmission(5.0f);

            float[] data = GraphCompiler.collectMaterialSSBOData(mn);

            assertTrue(data != null, "Data not null");
            assertTrue(data.length == 12, "Single material = 12 floats");

            // Check layout: [type, colorMode, albedoR, albedoG, albedoB, roughness, metallic, ior, emission, pad, pad, pad]
            assertTrue(data[0] == 2.0f, "data[0] = type (Glass=2)");
            assertTrue(data[1] == 1.0f, "data[1] = colorMode (Solid=1)");
            assertTrue(floatEq(data[2], 0.85f), "data[2] = albedoR");
            assertTrue(floatEq(data[3], 0.08f), "data[3] = albedoG");
            assertTrue(floatEq(data[4], 0.08f), "data[4] = albedoB");
            assertTrue(floatEq(data[5], 0.3f), "data[5] = roughness");
            assertTrue(floatEq(data[6], 0.7f), "data[6] = metallic");
            assertTrue(floatEq(data[7], 1.5f), "data[7] = ior");
            assertTrue(floatEq(data[8], 5.0f), "data[8] = emission");
            assertTrue(data[9] == 0.0f, "data[9] = padding 0");
            assertTrue(data[10] == 0.0f, "data[10] = padding 0");
            assertTrue(data[11] == 0.0f, "data[11] = padding 0");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testSSBODataNullWithoutMaterials() {
        System.out.println("\n=== collectMaterialSSBOData: null without materials ===");
        try {
            GraphNode root = new FractalNode(FractalType.MANDELBULB);
            float[] data = GraphCompiler.collectMaterialSSBOData(root);
            assertTrue(data == null, "No materials → null data");

            // Also test with CSG but no MaterialNode
            CSGNode csg = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new FractalNode(FractalType.MENGER_SPONGE), 0.1f);
            float[] data2 = GraphCompiler.collectMaterialSSBOData(csg);
            assertTrue(data2 == null, "CSG without materials → null data");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testSSBODataMultipleMaterials() {
        System.out.println("\n=== collectMaterialSSBOData: multiple materials correct order ===");
        try {
            // Left: Lambertian red, Solid
            MaterialNode mat0 = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            mat0.setMaterialType(MaterialNode.TYPE_LAMBERTIAN);
            mat0.setColorMode(MaterialNode.COLOR_SOLID);
            mat0.setColorR(1.0f); mat0.setColorG(0.0f); mat0.setColorB(0.0f);
            mat0.setRoughness(0.5f);

            // Right: Glass green, Tint
            MaterialNode mat1 = new MaterialNode(new FractalNode(FractalType.MENGER_SPONGE));
            mat1.setMaterialType(MaterialNode.TYPE_GLASS);
            mat1.setColorMode(MaterialNode.COLOR_TINT);
            mat1.setColorR(0.0f); mat1.setColorG(1.0f); mat1.setColorB(0.0f);
            mat1.setIor(1.45f);

            CSGNode root = new CSGNode(CSGNode.Op.UNION, mat0, mat1, 0.0f);

            float[] data = GraphCompiler.collectMaterialSSBOData(root);
            assertTrue(data != null, "Data not null");
            assertTrue(data.length == 24, "2 materials * 12 = 24 floats");

            // First material (index 0): Lambertian, Solid, red
            assertTrue(data[0] == 0.0f, "mat0 type = Lambertian (0)");
            assertTrue(data[1] == 1.0f, "mat0 colorMode = Solid (1)");
            assertTrue(data[2] == 1.0f, "mat0 albedoR = 1.0");
            assertTrue(data[3] == 0.0f, "mat0 albedoG = 0.0");
            assertTrue(data[4] == 0.0f, "mat0 albedoB = 0.0");
            assertTrue(floatEq(data[5], 0.5f), "mat0 roughness = 0.5");

            // Second material (index 1): Glass, Tint, green
            assertTrue(data[12] == 2.0f, "mat1 type = Glass (2)");
            assertTrue(data[13] == 2.0f, "mat1 colorMode = Tint (2)");
            assertTrue(data[14] == 0.0f, "mat1 albedoR = 0.0");
            assertTrue(data[15] == 1.0f, "mat1 albedoG = 1.0");
            assertTrue(data[16] == 0.0f, "mat1 albedoB = 0.0");
            assertTrue(floatEq(data[19], 1.45f), "mat1 ior = 1.45");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // FractalConfig serialization tests
    // ========================================================================

    private static void testSerializeColorMode() {
        System.out.println("\n=== FractalConfig: serialize colorMode ===");
        try {
            MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            mn.setMaterialType(MaterialNode.TYPE_METALLIC);
            mn.setColorMode(MaterialNode.COLOR_SOLID);
            mn.setColorR(0.15f);
            mn.setRoughness(0.185f);

            Map<String, Object> map = FractalConfig.serializeGraphNode(mn);

            assertTrue("material".equals(map.get("type")), "Serialized type is 'material'");
            assertTrue(((Number) map.get("materialType")).intValue() == 1, "materialType = 1 (Metallic)");
            assertTrue(((Number) map.get("colorMode")).intValue() == 1, "colorMode = 1 (Solid)");
            assertTrue(floatEq(((Number) map.get("colorR")).floatValue(), 0.15f), "colorR = 0.15");
            assertTrue(floatEq(((Number) map.get("roughness")).floatValue(), 0.185f), "roughness = 0.185");

            // Check that child is serialized
            assertTrue(map.containsKey("child"), "child key present");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testDeserializeColorMode() {
        System.out.println("\n=== FractalConfig: deserialize colorMode round-trip ===");
        try {
            // Create, serialize, deserialize
            MaterialNode original = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
            original.setMaterialType(MaterialNode.TYPE_GLASS);
            original.setColorMode(MaterialNode.COLOR_TINT);
            original.setColorR(0.5f); original.setColorG(0.7f); original.setColorB(0.3f);
            original.setRoughness(0.05f);
            original.setIor(1.5f);
            original.setEmission(10.0f);

            Map<String, Object> map = FractalConfig.serializeGraphNode(original);

            @SuppressWarnings("unchecked")
            GraphNode deserialized = FractalConfig.deserializeGraphNode(map);
            assertTrue(deserialized instanceof MaterialNode, "Deserialized is MaterialNode");

            MaterialNode mn = (MaterialNode) deserialized;
            assertTrue(mn.getMaterialType() == MaterialNode.TYPE_GLASS, "materialType round-trips (Glass)");
            assertTrue(mn.getColorMode() == MaterialNode.COLOR_TINT, "colorMode round-trips (Tint)");
            assertTrue(floatEq(mn.getColorR(), 0.5f), "colorR round-trips");
            assertTrue(floatEq(mn.getColorG(), 0.7f), "colorG round-trips");
            assertTrue(floatEq(mn.getColorB(), 0.3f), "colorB round-trips");
            assertTrue(floatEq(mn.getRoughness(), 0.05f), "roughness round-trips");
            assertTrue(floatEq(mn.getIor(), 1.5f), "ior round-trips");
            assertTrue(floatEq(mn.getEmission(), 10.0f), "emission round-trips");

            // Child should be a FractalNode(MANDELBULB)
            assertTrue(mn.getChild() instanceof FractalNode, "Child is FractalNode");
            assertTrue(((FractalNode) mn.getChild()).getFractalType() == FractalType.MANDELBULB,
                "Child fractal type preserved");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    private static void testDeserializeBackwardCompat() {
        System.out.println("\n=== FractalConfig: backward compat (missing colorMode → TINT) ===");
        try {
            // Simulate old save format: no colorMode key
            Map<String, Object> oldMap = new java.util.LinkedHashMap<>();
            oldMap.put("type", "material");
            oldMap.put("materialType", 0);
            oldMap.put("colorR", 0.8);
            oldMap.put("colorG", 0.2);
            oldMap.put("colorB", 0.2);
            oldMap.put("roughness", 0.5);
            oldMap.put("metallic", -1.0);
            oldMap.put("ior", -1.0);
            oldMap.put("emission", -1.0);

            // Child fractal
            Map<String, Object> childMap = new java.util.LinkedHashMap<>();
            childMap.put("type", "fractal");
            childMap.put("fractalType", "MANDELBULB");
            oldMap.put("child", childMap);

            // No "colorMode" key — old format

            @SuppressWarnings("unchecked")
            GraphNode deserialized = FractalConfig.deserializeGraphNode(oldMap);
            assertTrue(deserialized instanceof MaterialNode, "Deserialized is MaterialNode");

            MaterialNode mn = (MaterialNode) deserialized;
            assertTrue(mn.getColorMode() == MaterialNode.COLOR_TINT,
                "Missing colorMode defaults to TINT (backward compat)");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // Regression: no-material path
    // ========================================================================

    private static void testNoMaterialNoSSBO() {
        System.out.println("\n=== No material: no SSBO emitted ===");
        try {
            // Plain CSG without any MaterialNode
            CSGNode root = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new FractalNode(FractalType.MENGER_SPONGE),
                0.1f);

            GraphCompiler compiler = new GraphCompiler();
            String glsl = compiler.compile(root);

            assertNotContains(glsl, "#define HAS_MATERIALS", "No HAS_MATERIALS without MaterialNode");
            assertNotContains(glsl, "struct MaterialData", "No MaterialData without MaterialNode");
            assertNotContains(glsl, "MaterialBuffer", "No MaterialBuffer without MaterialNode");
            assertNotContains(glsl, "_matId", "No _matId without MaterialNode");
            assertNotContains(glsl, "int matId;", "No matId in OrbitTrap without MaterialNode");

            // OrbitTrap should be 5 fields (no matId)
            assertContains(glsl, "struct OrbitTrap", "OrbitTrap still exists");

            System.out.println("  PASSED");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            failed++;
        }
    }

    // ========================================================================
    // Assertion helpers
    // ========================================================================

    private static void assertContains(String glsl, String expected, String desc) {
        if (!glsl.contains(expected)) {
            throw new AssertionError("Expected '" + expected + "' in output (" + desc + ")");
        }
    }

    private static void assertNotContains(String glsl, String unexpected, String desc) {
        if (glsl.contains(unexpected)) {
            throw new AssertionError("Did not expect '" + unexpected + "' in output (" + desc + ")");
        }
    }

    private static void assertTrue(boolean condition, String desc) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + desc);
        }
    }

    private static boolean floatEq(float a, float b) {
        return Math.abs(a - b) < 0.001f;
    }
}
