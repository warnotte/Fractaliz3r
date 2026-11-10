package org.fractalizer.engine;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The double entry derived from a scene's float source. Whether GLSL accepts it is for the
 * GPU to say (PrecisionLab compiles every built-in); here: that what DE_simple reaches is
 * copied, typed in double, called through the copies, and nothing else.
 */
class DeepSourceTest {

    private static String common() throws Exception {
        try (InputStream is = DeepSourceTest.class.getResourceAsStream("/shaders/common.glsl")) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void copiesWhatTheEstimatorReachesInDouble() {
        String scene = """
                uniform float scale;
                uniform mat3 rot;
                float unrelated(vec3 p) { return p.x; }
                void fold(inout vec3 z, inout float dz) {
                    float r2 = dot(z, z);   // a comment with vec3 and { a brace
                    if (r2 < 0.25) { z *= 4.0; dz *= 4.0; }
                }
                #ifdef SOME_FLAG
                float shaped(vec3 z) { return length(z) - 1.0; }
                #else
                float shaped(vec3 z) { return max(abs(z.x), max(abs(z.y), abs(z.z))) - 1.0; }
                #endif
                float DE_simple(vec3 pos) {
                    vec3 z = rot * pos;
                    float dz = 1.0;
                    for (int i = 0; i < 8; i++) { fold(z, dz); z = z * scale + pos; dz = dz * abs(scale) + 1.0; }
                    return shaped(z) * pow(scale, -8.0) + 0.0 * sin(z.x);
                }
                """;
        String block = DeepSource.build("", scene);
        assertNotNull(block);
        assertTrue(block.contains("#define " + DeepSource.DEFINE));
        assertTrue(block.contains("void fold_dp(inout dvec3 z, inout double dz)"), block);
        assertTrue(block.contains("double DE_simple_dp(dvec3 pos)"), block);
        assertTrue(block.contains("fold_dp(z, dz)"), "calls go to the copies");
        assertTrue(block.contains("dvec3 z = rot * pos"), "uniforms stay float, GLSL widens them");
        assertTrue(block.contains("pow_dp(scale"), "pow has no double overload: the shim");
        assertTrue(block.contains("sin_dp(z.x)"));
        assertTrue(block.contains("float pow_dp(double a, double b) { return pow(float(a), float(b)); }"), block);
        assertTrue(block.contains("float " + DeepSource.ENTRY + "(dvec3 pos) { return float(DE_simple_dp(pos)); }"));
        assertFalse(block.contains("unrelated"), "only what DE_simple reaches");
        assertFalse(block.contains("comment"), "comments are not copied");
        // Both definitions of shaped, each under its own branch of the condition.
        assertTrue(block.contains("#ifdef SOME_FLAG\ndouble shaped_dp(dvec3 z);\n#endif"), block);
        assertTrue(block.contains("#ifdef SOME_FLAG\n#else\ndouble shaped_dp(dvec3 z);\n#endif"), block);
    }

    @Test
    void aStructTheEstimatorFillsIsCopiedInDouble() {
        String scene = """
                struct Trap { float minDist; vec3 plane; int iterations; };
                struct Unused { float x; };
                float orbit(vec3 p, out Trap trap) {
                    trap.minDist = 1e10; trap.plane = vec3(0.0);
                    for (int i = 0; i < 4; i++) { p = abs(p) * 2.0 - 1.0; trap.minDist = min(trap.minDist, dot(p, p)); trap.plane += abs(p); }
                    trap.iterations = 4;
                    return length(p) * 0.0625;
                }
                float DE_simple(vec3 pos) { Trap trap; return orbit(pos, trap); }
                """;
        String block = DeepSource.build("", scene);
        assertTrue(block.contains("struct Trap_dp { double minDist; dvec3 plane; int iterations; };"), block);
        assertTrue(block.contains("double orbit_dp(dvec3 p, out Trap_dp trap)"), block);
        assertTrue(block.contains("Trap_dp trap; return orbit_dp(pos, trap);"), block);
        assertFalse(block.contains("Unused"), "only the structs the copies use");
    }

    @Test
    void compoundAssignmentsAreWrittenOut() {
        assertEquals("trap.plane = trap.plane + (abs(w) / k);", DeepSource.expandCompound("trap.plane += abs(w) / k;"));
        assertEquals("w = w * (s);  d[2] = d[2] - (1.0);", DeepSource.expandCompound("w *= s;  d[2] -= 1.0;"));
        assertEquals("for (int i = 0; i < 4; i += 1) { z = z / (2.0); }", DeepSource.expandCompound("for (int i = 0; i < 4; i += 1) { z /= 2.0; }"),
                "a loop's increment, which no semicolon ends, is left alone");
        assertEquals("a[i++] += 1.0;", DeepSource.expandCompound("a[i++] += 1.0;"), "an index with a side effect is not duplicated");
        assertEquals("x == y; a <= b; c >= d; e != f;", DeepSource.expandCompound("x == y; a <= b; c >= d; e != f;"));
    }

    @Test
    void noDistanceEstimatorNoBlock() {
        assertNull(DeepSource.build("", "float other(vec3 p) { return 1.0; }"));
    }

    private static final Pattern FLOAT_TYPE = Pattern.compile("\\b(float|vec[234]|mat[234])\\b");

    @Test
    void everyBuiltInFractalTranslates() throws Exception {
        String common = common();
        for (FractalType type : FractalType.values()) {
            if (type == FractalType.NODE_GRAPH || type == FractalType.CUSTOM_SHADER) continue;
            String glsl = new NodeGraphParams(type).recompile();
            String block = DeepSource.build(common, glsl);
            assertNotNull(block, type + ": no DE_simple found");
            String copies = block.substring(block.indexOf("_dp("));     // past the shims' header
            // In the copies' signatures every float type has become double.
            Matcher sig = Pattern.compile("(?m)^(\\w+) (\\w+_dp)\\(([^)]*)\\)( \\{|;)").matcher(block);
            int n = 0;
            while (sig.find()) {
                if (sig.group(2).matches("(sin|cos|tan|asin|acos|atan|sinh|cosh|tanh|asinh|acosh|atanh|exp|log|exp2|log2|radians|degrees|pow|floatBitsToInt|floatBitsToUint)_dp")) continue;
                n++;
                assertFalse(FLOAT_TYPE.matcher(sig.group(1) + " " + sig.group(3)).find(), type + ": " + sig.group());
            }
            assertTrue(n >= 1, type + ": nothing copied");
            assertTrue(copies.contains("DE_simple_dp"), type.toString());
        }
    }
}
