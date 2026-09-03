package org.fractalizer.graph;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.fractals.FractalType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-node materials travel to the GPU as one SSBO of 12 floats per material and a single
 * int index carried through the distance estimator. These pin down the layout, the
 * define that gates the feature, how CSG saves and picks the index, and serialization.
 */
class MaterialSSBOTest {

    static final float TOL = 0.001f;

    static MaterialNode mat(FractalType type, int materialType) {
        MaterialNode mn = new MaterialNode(new FractalNode(type));
        mn.setMaterialType(materialType);
        return mn;
    }

    // ---------------------------------------------------------------- MaterialNode

    @Test
    void defaultsAreSentinelsThatMeanGlobal() {
        MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
        assertEquals(MaterialNode.TYPE_GLOBAL, mn.getMaterialType());
        assertEquals(MaterialNode.COLOR_PALETTE, mn.getColorMode());
        assertEquals(1.0f, mn.getColorR());
        assertEquals(1.0f, mn.getColorG());
        assertEquals(1.0f, mn.getColorB());
        assertEquals(-1.0f, mn.getRoughness());
        assertEquals(-1.0f, mn.getMetallic());
        assertEquals(-1.0f, mn.getIor());
        assertEquals(-1.0f, mn.getEmission());
    }

    @Test
    void fieldsAreClampedToTheirRanges() {
        MaterialNode mn = new MaterialNode(new FractalNode(FractalType.MANDELBULB));
        mn.setColorMode(-1);  assertEquals(0, mn.getColorMode());
        mn.setColorMode(5);   assertEquals(2, mn.getColorMode());
        mn.setColorMode(1);   assertEquals(1, mn.getColorMode());
        mn.setMaterialType(-2); assertEquals(-1, mn.getMaterialType());
        mn.setMaterialType(5);  assertEquals(2, mn.getMaterialType());
        mn.setColorR(-0.5f);  assertEquals(0.0f, mn.getColorR());
        mn.setColorR(1.5f);   assertEquals(1.0f, mn.getColorR());
        mn.setIor(5.0f);      assertEquals(3.0f, mn.getIor());
        mn.setEmission(100f); assertEquals(50f, mn.getEmission());
    }

    // ---------------------------------------------------------------- compiler output

    @Test
    void materialNodeGatesTheFeatureWithADefineAndAnSsbo() {
        String glsl = new GraphCompiler().compile(mat(FractalType.MANDELBULB, MaterialNode.TYPE_METALLIC));
        assertTrue(glsl.contains("#define HAS_MATERIALS"));
        assertFalse(glsl.contains("#define NODE_GRAPH_MATERIALS"), "old define must not come back");
        assertTrue(glsl.contains("struct MaterialData"));
        assertTrue(glsl.contains("float type;"));
        assertTrue(glsl.contains("float colorMode;"));
        assertTrue(glsl.contains("float albedoR, albedoG, albedoB;"));
        assertTrue(glsl.contains("float roughness, metallic, ior, emission;"));
        assertTrue(glsl.contains("layout(std430, binding = 6)"), "binding point 6");
        assertTrue(glsl.contains("readonly buffer MaterialBuffer"));
        assertTrue(glsl.contains("MaterialData materials[]"));
    }

    @Test
    void orbitTrapCarriesASingleIndexNotFatFields() {
        MaterialNode root = mat(FractalType.MANDELBULB, MaterialNode.TYPE_LAMBERTIAN);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        assertTrue(glsl.contains("int matId;"), "matId in OrbitTrap");
        assertTrue(glsl.contains("int _matId = -1;"), "single local in DE");
        assertTrue(glsl.contains("_matId = 0;"), "index 0 assigned");
        for (String old : new String[]{"int matType;", "float matColorR;", "int _matType =", "float _matColorR =", "float _matRoughness ="}) {
            assertFalse(glsl.contains(old), "old fat field present: " + old);
        }
    }

    @Test
    void noMaterialUniformsAreEmitted() {
        MaterialNode root = mat(FractalType.MANDELBULB, MaterialNode.TYPE_GLASS);
        root.setRoughness(0.5f);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        assertFalse(glsl.contains("uniform int m0_matType"));
        assertFalse(glsl.contains("uniform float m0_roughness"));
        for (String key : compiler.getUniforms(root).keySet()) {
            assertFalse(key.startsWith("m0_"), "material data must flow through the SSBO, not uniform " + key);
        }
    }

    @Test
    void withoutAMaterialNodeNothingMaterialRelatedIsEmitted() {
        CSGNode root = new CSGNode(CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB), new FractalNode(FractalType.MENGER_SPONGE), 0.1f);
        String glsl = new GraphCompiler().compile(root);
        assertFalse(glsl.contains("#define HAS_MATERIALS"));
        assertFalse(glsl.contains("struct MaterialData"));
        assertFalse(glsl.contains("MaterialBuffer"));
        assertFalse(glsl.contains("_matId"));
        assertFalse(glsl.contains("int matId;"));
        assertTrue(glsl.contains("struct OrbitTrap"));
        assertNull(GraphCompiler.collectMaterialSSBOData(root), "no SSBO data without materials");
    }

    // ---------------------------------------------------------------- CSG

    @Test
    void csgSavesResetsAndPicksTheIndexAcrossItsTwoSides() {
        MaterialNode left = mat(FractalType.MANDELBULB, MaterialNode.TYPE_LAMBERTIAN);
        left.setColorMode(MaterialNode.COLOR_SOLID);
        MaterialNode right = mat(FractalType.MENGER_SPONGE, MaterialNode.TYPE_METALLIC);
        right.setColorMode(MaterialNode.COLOR_SOLID);
        String glsl = new GraphCompiler().compile(new CSGNode(CSGNode.Op.UNION, left, right, 0.0f));

        assertTrue(glsl.contains("_matId_L_c0"), "left index saved before the right subtree");
        assertTrue(glsl.contains("_matId = -1;"), "reset before the right subtree");
        assertTrue(glsl.contains("_matId = _matId_L_c0"), "picked back from the saved left value");
        assertTrue(glsl.contains("_matId = 0;") && glsl.contains("_matId = 1;"), "both sides indexed");
        assertFalse(glsl.contains("_matType_L_") || glsl.contains("_matColorR_L_"), "old per-field save");
    }

    @Test
    void morphSnapsTheMaterialAtHalfBlend() {
        MaterialNode left = mat(FractalType.MANDELBULB, MaterialNode.TYPE_LAMBERTIAN);
        MaterialNode right = mat(FractalType.MENGER_SPONGE, MaterialNode.TYPE_GLASS);
        String glsl = new GraphCompiler().compile(new CSGNode(CSGNode.Op.MORPH, left, right, 0.3f));
        assertTrue(glsl.contains("< 0.5) ? _matId_L_c0 : _matId"));
    }

    @Test
    void nestedMaterialsGetDistinctIndicesAndOneSsboEntryEach() {
        MaterialNode m0 = mat(FractalType.MANDELBULB, MaterialNode.TYPE_LAMBERTIAN);
        MaterialNode m1 = mat(FractalType.MENGER_SPONGE, MaterialNode.TYPE_METALLIC);
        MaterialNode m2 = mat(FractalType.SIERPINSKI, MaterialNode.TYPE_GLASS);
        CSGNode root = new CSGNode(CSGNode.Op.SUBTRACT, new CSGNode(CSGNode.Op.UNION, m0, m1, 0f), m2, 0f);
        String glsl = new GraphCompiler().compile(root);
        assertTrue(glsl.contains("_matId = 0;") && glsl.contains("_matId = 1;") && glsl.contains("_matId = 2;"));
        float[] data = GraphCompiler.collectMaterialSSBOData(root);
        assertNotNull(data);
        assertEquals(36, data.length, "3 materials x 12 floats");
    }

    // ---------------------------------------------------------------- SSBO layout

    @Test
    void ssboLayoutIsTypeModeAlbedoPbrThenPadding() {
        MaterialNode mn = mat(FractalType.MANDELBULB, MaterialNode.TYPE_GLASS);
        mn.setColorMode(MaterialNode.COLOR_SOLID);
        mn.setColorR(0.85f); mn.setColorG(0.08f); mn.setColorB(0.08f);
        mn.setRoughness(0.3f); mn.setMetallic(0.7f); mn.setIor(1.5f); mn.setEmission(5.0f);

        float[] d = GraphCompiler.collectMaterialSSBOData(mn);
        assertNotNull(d);
        assertEquals(12, d.length);
        assertEquals(2.0f, d[0], "type");
        assertEquals(1.0f, d[1], "colorMode");
        assertEquals(0.85f, d[2], TOL); assertEquals(0.08f, d[3], TOL); assertEquals(0.08f, d[4], TOL);
        assertEquals(0.3f, d[5], TOL); assertEquals(0.7f, d[6], TOL); assertEquals(1.5f, d[7], TOL); assertEquals(5.0f, d[8], TOL);
        assertEquals(0f, d[9]); assertEquals(0f, d[10]); assertEquals(0f, d[11]);
    }

    @Test
    void ssboEntriesFollowTreeOrder() {
        MaterialNode m0 = mat(FractalType.MANDELBULB, MaterialNode.TYPE_LAMBERTIAN);
        m0.setColorMode(MaterialNode.COLOR_SOLID);
        m0.setColorR(1f); m0.setColorG(0f); m0.setColorB(0f); m0.setRoughness(0.5f);
        MaterialNode m1 = mat(FractalType.MENGER_SPONGE, MaterialNode.TYPE_GLASS);
        m1.setColorMode(MaterialNode.COLOR_TINT);
        m1.setColorR(0f); m1.setColorG(1f); m1.setColorB(0f); m1.setIor(1.45f);

        float[] d = GraphCompiler.collectMaterialSSBOData(new CSGNode(CSGNode.Op.UNION, m0, m1, 0f));
        assertNotNull(d);
        assertEquals(24, d.length);
        assertEquals(0f, d[0]); assertEquals(1f, d[1]); assertEquals(1f, d[2]); assertEquals(0f, d[3]); assertEquals(0.5f, d[5], TOL);
        assertEquals(2f, d[12]); assertEquals(2f, d[13]); assertEquals(0f, d[14]); assertEquals(1f, d[15]); assertEquals(1.45f, d[19], TOL);
    }

    // ---------------------------------------------------------------- serialization

    @Test
    void materialNodeSerializesAndDeserializes() {
        MaterialNode original = mat(FractalType.MANDELBULB, MaterialNode.TYPE_GLASS);
        original.setColorMode(MaterialNode.COLOR_TINT);
        original.setColorR(0.5f); original.setColorG(0.7f); original.setColorB(0.3f);
        original.setRoughness(0.05f); original.setIor(1.5f); original.setEmission(10.0f);

        Map<String, Object> map = FractalConfig.serializeGraphNode(original);
        assertEquals("material", map.get("type"));
        assertEquals(2, ((Number) map.get("materialType")).intValue());
        assertEquals(2, ((Number) map.get("colorMode")).intValue());
        assertTrue(map.containsKey("child"));

        GraphNode back = FractalConfig.deserializeGraphNode(map);
        MaterialNode mn = assertInstanceOf(MaterialNode.class, back);
        assertEquals(MaterialNode.TYPE_GLASS, mn.getMaterialType());
        assertEquals(MaterialNode.COLOR_TINT, mn.getColorMode());
        assertEquals(0.5f, mn.getColorR(), TOL); assertEquals(0.7f, mn.getColorG(), TOL); assertEquals(0.3f, mn.getColorB(), TOL);
        assertEquals(0.05f, mn.getRoughness(), TOL); assertEquals(1.5f, mn.getIor(), TOL); assertEquals(10.0f, mn.getEmission(), TOL);
        FractalNode child = assertInstanceOf(FractalNode.class, mn.getChild());
        assertEquals(FractalType.MANDELBULB, child.getFractalType());
    }

    @Test
    void aSaveWithoutColorModeLoadsAsTint() {
        // Files written before colorMode existed carried only a colour; they tinted.
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("type", "material");
        old.put("materialType", 0);
        old.put("colorR", 0.8); old.put("colorG", 0.2); old.put("colorB", 0.2);
        old.put("roughness", 0.5); old.put("metallic", -1.0); old.put("ior", -1.0); old.put("emission", -1.0);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("type", "fractal");
        child.put("fractalType", "MANDELBULB");
        old.put("child", child);

        MaterialNode mn = assertInstanceOf(MaterialNode.class, FractalConfig.deserializeGraphNode(old));
        assertEquals(MaterialNode.COLOR_TINT, mn.getColorMode());
    }
}
