package org.fractalizer.engine;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The float-float entry derived from a scene's float source. Whether GLSL accepts it and
 * what it computes is for the GPU to say (PrecisionLab compiles every built-in,
 * FloatFloatProbe checks the library against StrictMath); here: that everything a scene's
 * estimator reaches translates, that values from the position become float-floats and
 * uniforms do not, and that a computation repeated in a block is done once and redone after
 * what it reads changes.
 */
class FloatFloatSourceTest {

    private static String common() throws Exception {
        try (InputStream is = FloatFloatSourceTest.class.getResourceAsStream("/shaders/common.glsl")) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The translated body of one function. */
    private static String body(String block, String name) {
        Matcher def = Pattern.compile("(?m)^\\w+ " + name + FloatFloatSource.SUFFIX + "\\([^)]*\\) \\{").matcher(block);
        if (!def.find()) throw new AssertionError("no definition of " + name);
        int start = def.end() - 1;
        int depth = 0;
        for (int i = start; i < block.length(); i++) {
            if (block.charAt(i) == '{') depth++;
            else if (block.charAt(i) == '}' && --depth == 0) return block.substring(start, i + 1);
        }
        throw new AssertionError("no body for " + name);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    @Test
    void everyBuiltInFractalTranslates() throws Exception {
        String common = common();
        for (FractalType type : FractalType.values()) {
            if (type == FractalType.NODE_GRAPH || type == FractalType.CUSTOM_SHADER) continue;
            String glsl = new NodeGraphParams(type).recompile();
            String block = FloatFloatSource.build(common, glsl);
            assertNotNull(block, type + ": " + FloatFloatSource.lastError());
            assertTrue(block.contains("float DE_deep(dvec3 pos)"), type.toString());
            // no float type left in a copy's signature
            Matcher sig = Pattern.compile("(?m)^(\\w+) (\\w+_ff)\\(([^)]*)\\)( \\{|;)").matcher(block);
            int n = 0;
            while (sig.find()) {
                n++;
                assertFalse(Pattern.compile("\\b(float|vec[234]|mat2)\\b").matcher(sig.group(1) + " " + sig.group(3)).find(), type + ": " + sig.group());
            }
            assertTrue(n >= 1, type + ": nothing copied");
        }
    }

    @Test
    void everyHybridChainTranslates() throws Exception {
        String common = common();
        for (HybridPresets.Preset p : HybridPresets.all()) {
            NodeGraphParams ngp = HybridPresets.toFreshParams(p);
            String block = FloatFloatSource.build(common, ngp.recompile());
            assertNotNull(block, p.name() + ": " + FloatFloatSource.lastError());
        }
    }

    @Test
    void positionValuesBecomeFloatFloatsUniformsDoNot() {
        String scene = """
                uniform float radius;
                uniform vec3 offset;
                float DE_simple(vec3 pos) {
                    vec3 p = pos - offset;
                    return length(p) - radius;
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        String b = body(block, "DE_simple");
        assertTrue(b.contains("ff3 p"), b);
        assertTrue(b.contains("ff_sub(pos, offset)"), b);        // a float-float minus a plain vec3
        assertTrue(b.contains("ff_length("), b);
        assertTrue(b.contains(", radius)"), b);                  // the uniform stays a float operand
    }

    @Test
    void sinAndCosOfOneAngleShareOneSincos() {
        String scene = """
                float DE_simple(vec3 pos) {
                    float a = pos.x * 3.0;
                    vec3 z = vec3(sin(a) * cos(a), sin(a), cos(a));
                    return length(z) - 1.0;
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        String b = body(block, "DE_simple");
        assertEquals(1, count(b, "ff_sincos("), b);
        assertEquals(0, count(b, "ff_sin("), b);
    }

    @Test
    void aRepeatedComputationIsDoneOnceUntilWhatItReadsChanges() {
        String scene = """
                float DE_simple(vec3 pos) {
                    vec3 z = pos;
                    float a = length(z) + length(z);
                    z = z * 2.0;
                    float b = length(z);
                    return a + b;
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        String b = body(block, "DE_simple");
        // once before z changes, once after
        assertEquals(2, count(b, "ff_length(z)"), b);
    }

    @Test
    void aLoopDoesNotReuseWhatItChanges() {
        String scene = """
                float DE_simple(vec3 pos) {
                    vec3 z = pos;
                    float r = length(z);
                    for (int i = 0; i < 4; i++) {
                        r = length(z);
                        z = z * z.x;
                    }
                    return r;
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        String b = body(block, "DE_simple");
        // the length inside the loop is computed there, not taken from before the loop
        String loop = b.substring(b.indexOf("for ("));
        assertTrue(loop.contains("ff_length(z)"), b);
    }

    @Test
    void aSwizzleAssignmentWritesBothHalves() {
        String scene = """
                float DE_simple(vec3 pos) {
                    vec3 z = pos;
                    if (z.x + z.y < 0.0) z.xy = -z.yx;
                    z.y += 1.0;
                    return length(z);
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        String b = body(block, "DE_simple");
        assertTrue(b.contains("z.hi.xy = "), b);
        assertTrue(b.contains("z.lo.xy = "), b);
        assertTrue(b.contains("z.hi.y = "), b);
        assertTrue(block.contains("ff2 ff_sw_yx(ff3 a)"), block);
    }

    @Test
    void outParametersAndStructsCarryFloatFloats() {
        String scene = """
                struct Trap { float minDist; int it; };
                void fold(inout vec3 z, inout float dz, inout Trap t) {
                    z = abs(z) - 1.0;
                    dz = dz * 2.0;
                    t.minDist = min(t.minDist, length(z));
                    t.it = t.it + 1;
                }
                float DE_simple(vec3 pos) {
                    vec3 z = pos;
                    float dz = 1.0;
                    Trap t = Trap(1e10, 0);
                    fold(z, dz, t);
                    return length(z) / dz + t.minDist * 0.0;
                }
                """;
        String block = FloatFloatSource.build("", scene);
        assertNotNull(block, FloatFloatSource.lastError());
        assertTrue(block.contains("struct Trap_ff { ff1 minDist; int it; };"), block);
        assertTrue(block.contains("void fold_ff(inout ff3 z, inout ff1 dz, inout Trap_ff t)"), block);
        assertTrue(body(block, "DE_simple").contains("Trap_ff(ff_from(1e10), 0)"), block);
    }

    @Test
    void whatCannotBeTranslatedSaysWhy() {
        // a 3x3 matrix made from the position: float-float has 2x2 matrices only
        String scene = """
                float DE_simple(vec3 pos) {
                    mat3 m = mat3(pos, pos.yzx, pos.zxy);
                    return length(m * pos) - 1.0;
                }
                """;
        assertNull(FloatFloatSource.build("", scene));
        assertNotNull(FloatFloatSource.lastError());
        // the entry the program gets is then the double one
        String entry = DeepSource.entry("", scene);
        assertNotNull(entry);
        assertTrue(entry.contains("DE_simple_dp"), entry);
    }

    @Test
    void aSceneThatTranslatesGetsBothEntries() {
        String scene = """
                uniform float power;
                float DE_simple(vec3 pos) { return length(pos) - power; }
                """;
        String entry = DeepSource.entry("", scene);
        assertTrue(entry.startsWith("#define " + DeepSource.BOTH_DEFINE), entry);
        assertTrue(entry.contains("float DE_deep_ff(dvec3 pos)"), entry);
        assertTrue(entry.contains("float DE_deep_dp(dvec3 pos)"), entry);
        assertFalse(entry.contains("float DE_deep(dvec3 pos)"), entry);   // the raytracer picks one per frame
        assertEquals(1, count(entry, "#define " + DeepSource.DEFINE + "\n"), entry);
    }

    @Test
    void noDistanceEstimatorNoBlock() {
        assertNull(FloatFloatSource.build("", "float other(vec3 p) { return 1.0; }"));
    }
}
