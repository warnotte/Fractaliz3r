package org.fractalizer.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The preprocessor lets two fractal shaders coexist in one program by prefixing every
 * locally defined symbol of the secondary one. Getting a symbol wrong either breaks the
 * link (undefined function) or, worse, silently binds the primary's uniform in the
 * secondary's code. These pin down what is renamed and what must stay untouched.
 */
class ShaderPreprocessorTest {

    static final String SOURCE = """
            uniform float power;
            uniform int maxIterations = 12;
            const float BAILOUT = 4.0;

            struct OrbitTrap {
                float minDist;
                int iterations;
            };

            float DE_simple(vec3 pos) {
                return length(pos) - BAILOUT;
            }

            float DE(vec3 pos, out OrbitTrap trap) {
                trap.minDist = DE_simple(pos);
                return pow(power, 2.0) * float(maxIterations);
            }
            """;

    @Test
    void uniformsStructsFunctionsAndConstsArePrefixed() {
        String out = ShaderPreprocessor.renameLocalSymbols(SOURCE, "b_");
        assertTrue(out.contains("uniform float b_power;"), "uniform renamed at declaration");
        assertTrue(out.contains("uniform int b_maxIterations = 12;"), "initialised uniform renamed");
        assertTrue(out.contains("const float b_BAILOUT = 4.0;"), "const renamed");
        assertTrue(out.contains("struct b_OrbitTrap {"), "struct renamed");
        assertTrue(out.contains("float b_DE_simple(vec3 pos)"), "function renamed");
        assertTrue(out.contains("float b_DE(vec3 pos, out b_OrbitTrap trap)"), "function and struct param renamed");
    }

    @Test
    void usesAreRenamedTogetherWithDeclarations() {
        String out = ShaderPreprocessor.renameLocalSymbols(SOURCE, "b_");
        assertTrue(out.contains("trap.minDist = b_DE_simple(pos);"), "call site follows the definition");
        assertTrue(out.contains("pow(b_power, 2.0) * float(b_maxIterations)"), "uniform uses renamed");
        assertTrue(out.contains("length(pos) - b_BAILOUT"), "const use renamed");
    }

    @Test
    void builtinsAndFieldsAreLeftAlone() {
        String out = ShaderPreprocessor.renameLocalSymbols(SOURCE, "b_");
        assertTrue(out.contains("length(pos)"), "length() is a builtin");
        assertTrue(out.contains("pow("), "pow() is a builtin");
        assertTrue(out.contains("vec3 pos"), "types are builtins");
        assertTrue(out.contains("trap.minDist"), "struct fields are not symbols");
        assertFalse(out.contains("b_float"), "no type got prefixed");
        assertFalse(out.contains("b_length"), "no builtin got prefixed");
    }

    @Test
    void aSymbolThatPrefixesAnotherIsNotDoublePrefixed() {
        // DE is a prefix of DE_simple; longer names are replaced first and the word
        // boundary must then keep "DE" from matching inside "b_DE_simple".
        String out = ShaderPreprocessor.renameLocalSymbols(SOURCE, "b_");
        assertFalse(out.contains("b_b_"), "a symbol was prefixed twice");
        assertFalse(out.contains("b_DE_b_simple"), "the shorter symbol matched inside the longer one");
    }

    @Test
    void sourceWithoutLocalSymbolsIsReturnedUnchanged() {
        String src = "void main() { gl_FragColor = vec4(1.0); }\n";
        assertSame(src, ShaderPreprocessor.renameLocalSymbols(src, "b_"));
    }

    @Test
    void twoPrefixesGiveTwoIndependentCopies() {
        String a = ShaderPreprocessor.renameLocalSymbols(SOURCE, "n0_");
        String b = ShaderPreprocessor.renameLocalSymbols(SOURCE, "n1_");
        assertTrue(a.contains("n0_DE(") && !a.contains("n1_"));
        assertTrue(b.contains("n1_DE(") && !b.contains("n0_"));
    }
}
