package org.fractalizer.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renames all locally-defined symbols in a GLSL shader source with a prefix.
 * Used for Boolean Operations: the secondary fractal shader gets all its symbols
 * prefixed (e.g., "b_") so it can coexist with the primary fractal in one program.
 */
public final class ShaderPreprocessor {

    // GLSL built-in functions and types that must NOT be renamed
    private static final Set<String> GLSL_BUILTINS = Set.of(
        // Types
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
        "bvec2", "bvec3", "bvec4", "dvec2", "dvec3", "dvec4",
        "mat2", "mat3", "mat4", "mat2x3", "mat2x4", "mat3x2", "mat3x4", "mat4x2", "mat4x3",
        "sampler2D", "sampler3D", "samplerCube", "image2D",
        // Qualifiers
        "uniform", "in", "out", "inout", "const", "struct", "layout",
        "flat", "smooth", "noperspective", "centroid",
        // Control flow
        "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "discard",
        // Math functions
        "abs", "sign", "floor", "ceil", "fract", "mod", "min", "max", "clamp",
        "mix", "step", "smoothstep", "length", "distance", "dot", "cross",
        "normalize", "reflect", "refract", "pow", "exp", "exp2", "log", "log2",
        "sqrt", "inversesqrt", "sin", "cos", "tan", "asin", "acos", "atan",
        "radians", "degrees", "sinh", "cosh", "tanh",
        // Texture/image
        "texture", "textureLod", "textureGrad", "imageLoad", "imageStore",
        // Vector component access
        "x", "y", "z", "w", "r", "g", "b", "a", "s", "t", "p", "q",
        // Common GLSL variables
        "gl_FragCoord", "gl_FragColor", "gl_Position", "gl_VertexID",
        // Misc
        "main", "true", "false"
    );

    private ShaderPreprocessor() {}

    /**
     * Rename all locally-defined symbols in a GLSL source with the given prefix.
     * Collects uniform names, struct names, function names, and const names,
     * then replaces all occurrences with prefix + name.
     *
     * @param source the GLSL source code (already stripped of #version)
     * @param prefix the prefix to add (e.g., "b_")
     * @return the modified source with all local symbols renamed
     */
    public static String renameLocalSymbols(String source, String prefix) {
        // Phase 1: Collect locally-defined symbol names
        Set<String> symbols = new LinkedHashSet<>();

        // uniform TYPE NAME;  (also handles uniform TYPE NAME = ...)
        collectMatches(source, Pattern.compile("\\buniform\\s+\\w+\\s+(\\w+)\\s*[;=]"), symbols);

        // struct NAME {
        collectMatches(source, Pattern.compile("\\bstruct\\s+(\\w+)\\s*\\{"), symbols);

        // TYPE NAME( — function definitions (return type + name + open paren)
        // Match: word word( but exclude lines starting with layout/uniform/in/out
        collectMatches(source, Pattern.compile("(?m)^\\s*(?!uniform|layout|in\\b|out\\b)(\\w+)\\s+(\\w+)\\s*\\("), 2, symbols);

        // const TYPE NAME =
        collectMatches(source, Pattern.compile("\\bconst\\s+\\w+\\s+(\\w+)\\s*="), symbols);

        // Remove any GLSL builtins that were accidentally collected
        symbols.removeAll(GLSL_BUILTINS);

        if (symbols.isEmpty()) return source;

        // Phase 2: Sort by length descending (replace longer names first to avoid partial matches)
        List<String> sorted = new ArrayList<>(symbols);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));

        // Phase 3: Replace all occurrences using word boundary
        String result = source;
        for (String sym : sorted) {
            result = result.replaceAll("\\b" + Pattern.quote(sym) + "\\b", prefix + sym);
        }

        return result;
    }

    private static void collectMatches(String source, Pattern pattern, Set<String> symbols) {
        collectMatches(source, pattern, 1, symbols);
    }

    private static void collectMatches(String source, Pattern pattern, int group, Set<String> symbols) {
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            String name = m.group(group);
            if (name != null && !name.isEmpty() && !GLSL_BUILTINS.contains(name)) {
                symbols.add(name);
            }
        }
    }
}
