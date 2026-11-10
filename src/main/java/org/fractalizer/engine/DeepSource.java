package org.fractalizer.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The double-precision entry of a scene, derived from its float source: {@code float
 * DE_deep(dvec3 pos)}, the scene's {@code DE_simple} with the point and the orbit in double.
 *
 * <p>Nothing is written per fractal. The scene's source (common.glsl and whatever the graph
 * compiler emitted: kernels, primitives, hybrid chains, transforms, CSG, effects, a custom
 * shader) is parsed for its functions, and every function {@code DE_simple} reaches is copied
 * with its float types made double ({@code float} to {@code double}, {@code vecN} to
 * {@code dvecN}, {@code matN} to {@code dmatN}) and its calls redirected to the copies.
 * Uniforms, constants and literals stay float; GLSL converts them to double where they meet
 * a double. The float source is not touched: a program without this block is exactly what it
 * was.
 *
 * <p>GLSL has no double trigonometry, exponentials or powers. Those calls go through small
 * overloads that evaluate them in float ({@code sin_dp} and so on). Where such a function only
 * sees a parameter ({@code cos(angle)}, {@code pow(scale, -n)}) nothing is lost. Where it sees
 * the position itself (the Mandelbulb's angles), the orbit drops back to float precision at
 * that point, which is no worse than the float path. Anything else GLSL refuses in double makes
 * the compile fail; the caller then keeps the float program.
 *
 * <p>The structs those functions use (an orbit trap filled from the orbit) are copied the same
 * way. The copies keep the preprocessor conditions they were defined under, and functions are
 * declared before they are defined, so their order does not matter.
 */
public final class DeepSource {

    /** The entry the raytracer's double march calls. */
    public static final String ENTRY = "DE_deep";
    /** Defined by the block, tested by raytracer.glsl. */
    public static final String DEFINE = "HAS_DEEP_DE";

    static final String SUFFIX = "_dp";
    private static final String ROOT = "DE_simple";

    private static final List<String> FLOAT_ONLY = List.of(
            "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
            "exp", "log", "exp2", "log2", "radians", "degrees");
    private static final List<String> FLOAT_ONLY_2 = List.of("pow", "atan");
    private static final Map<String, String> BITS = Map.of("floatBitsToInt", "int", "floatBitsToUint", "uint");

    private static final Pattern FUNCTION = Pattern.compile(
            "[ \\t]*(?:(?:highp|mediump|lowp|precise)\\s+)?([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*\\(([^(){};]*)\\)\\s*\\{");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern STRUCT = Pattern.compile("[ \\t]*struct\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Set<String> NOT_TYPES = Set.of("return", "else", "if", "for", "while", "switch", "case", "do", "struct");

    record Function(String returnType, String name, String params, String body, List<List<String>> conditions) {}
    record Struct(String name, String body, List<List<String>> conditions) {}
    record Parsed(List<Function> functions, List<Struct> structs) {}

    private DeepSource() {}

    /**
     * The double entry for a scene, as a block to append after the scene's source, or null
     * when the source has no {@code DE_simple}.
     *
     * @param common the common.glsl the program is built with (helpers the kernels call)
     * @param scene  the scene's own source (graph compiler output or kernel)
     */
    public static String build(String common, String scene) {
        Parsed parsed = parse(stripComments(common) + "\n" + stripComments(scene));
        List<Function> functions = parsed.functions();
        Map<String, List<Function>> byName = new LinkedHashMap<>();
        for (Function f : functions) byName.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f);
        if (!byName.containsKey(ROOT)) return null;

        // Everything DE_simple reaches.
        Set<String> reach = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(ROOT));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!reach.add(name)) continue;
            for (Function f : byName.get(name)) {
                Matcher m = CALL.matcher(f.body());
                while (m.find()) {
                    String callee = m.group(1);
                    if (byName.containsKey(callee) && !reach.contains(callee)) todo.push(callee);
                }
            }
        }

        List<Function> copies = new ArrayList<>();
        for (Function f : functions) if (reach.contains(f.name())) copies.add(f);

        // The structs the copies use (an orbit trap filled from the orbit), copied with their
        // float fields made double too, and the structs those use in turn.
        Map<String, Struct> structByName = new LinkedHashMap<>();
        for (Struct st : parsed.structs()) structByName.putIfAbsent(st.name(), st);
        Set<String> structs = new LinkedHashSet<>();
        StringBuilder scan = new StringBuilder();
        for (Function f : copies) scan.append(f.returnType()).append(' ').append(f.params()).append(' ').append(f.body()).append('\n');
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Struct st : structByName.values()) {
                if (!structs.contains(st.name()) && Pattern.compile("\\b" + Pattern.quote(st.name()) + "\\b").matcher(scan).find()) {
                    structs.add(st.name());
                    scan.append(st.body()).append('\n');
                    grew = true;
                }
            }
        }

        StringBuilder typeDefs = new StringBuilder(), decls = new StringBuilder(), defs = new StringBuilder();
        for (Struct st : parsed.structs()) {
            if (structs.contains(st.name())) {
                wrap(typeDefs, st.conditions(), "struct " + st.name() + SUFFIX + " {" + structNames(types(st.body()), structs) + "};\n");
            }
        }
        Set<String> shimsUsed = new TreeSet<>();
        for (Function f : copies) {
            String signature = structNames(types(f.returnType()) + " " + f.name() + SUFFIX + "(" + types(f.params()) + ")", structs);
            String body = structNames(calls(expandCompound(types(f.body())), reach, shimsUsed), structs);
            wrap(decls, f.conditions(), signature + ";\n");
            wrap(defs, f.conditions(), signature + " {" + body + "}\n");
        }

        StringBuilder out = new StringBuilder();
        out.append("// === Double entry of the scene (DeepSource: DE_simple and what it calls, in double) ===\n");
        out.append("#define ").append(DEFINE).append('\n');
        out.append(shims(shimsUsed));
        out.append(typeDefs);
        out.append(decls);
        out.append(defs);
        out.append("float ").append(ENTRY).append("(dvec3 pos) { return float(").append(ROOT).append(SUFFIX).append("(pos)); }\n");
        return out.toString();
    }

    // ---- parsing ---------------------------------------------------------------------

    static String stripComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) if (s.charAt(k) == '\n') b.append('\n');   // keep line structure
                i = stop;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** Top-level function and struct definitions, in order, each with the preprocessor
     *  conditions it sits in. */
    static Parsed parse(String text) {
        List<Function> out = new ArrayList<>();
        List<Struct> structs = new ArrayList<>();
        Deque<List<String>> conditions = new ArrayDeque<>();
        int i = 0, n = text.length(), depth = 0;
        while (i < n) {
            int eol = text.indexOf('\n', i);
            if (eol < 0) eol = n;
            String line = text.substring(i, eol).trim();
            if (depth == 0 && line.startsWith("#")) {
                String d = line.substring(1).trim();
                if (d.startsWith("if")) conditions.push(new ArrayList<>(List.of(line)));
                else if ((d.startsWith("elif") || d.startsWith("else")) && !conditions.isEmpty()) conditions.peek().add(line);
                else if (d.startsWith("endif") && !conditions.isEmpty()) conditions.pop();
                i = eol + 1;
                continue;
            }
            if (depth == 0) {
                Matcher m = FUNCTION.matcher(text);
                m.region(i, n);
                if (m.lookingAt() && !NOT_TYPES.contains(m.group(1))) {
                    int open = m.end() - 1;
                    int close = matchingBrace(text, open);
                    if (close < 0) break;
                    out.add(new Function(m.group(1), m.group(2), m.group(3), text.substring(open + 1, close), conditionsNow(conditions)));
                    i = close + 1;
                    continue;
                }
                Matcher st = STRUCT.matcher(text);
                st.region(i, n);
                if (st.lookingAt()) {
                    int open = st.end() - 1;
                    int close = matchingBrace(text, open);
                    if (close < 0) break;
                    structs.add(new Struct(st.group(1), text.substring(open + 1, close), conditionsNow(conditions)));
                    int semi = text.indexOf(';', close);
                    i = semi < 0 ? close + 1 : semi + 1;
                    continue;
                }
            }
            for (int k = i; k < eol; k++) {
                char c = text.charAt(k);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            i = eol + 1;
        }
        return new Parsed(out, structs);
    }

    private static List<List<String>> conditionsNow(Deque<List<String>> conditions) {
        List<List<String>> snapshot = new ArrayList<>();
        for (Iterator<List<String>> it = conditions.descendingIterator(); it.hasNext(); ) snapshot.add(List.copyOf(it.next()));
        return snapshot;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k;
        }
        return -1;
    }

    // ---- translation -----------------------------------------------------------------

    /** Float types made double; precision qualifiers dropped (they do not apply to double). */
    static String types(String s) {
        s = s.replaceAll("\\b(?:highp|mediump|lowp)\\s+", "");
        s = s.replaceAll("\\bfloat\\b", "double");
        s = s.replaceAll("\\bvec([234])\\b", "dvec$1");
        s = s.replaceAll("\\bmat([234](?:x[234])?)\\b", "dmat$1");
        return s;
    }

    private static final Pattern COMPOUND = Pattern.compile(
            "(?<![\\w.\\]])([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*|\\[[^\\]+\\-]*\\])*)\\s*([-+*/])=\\s*([^;{}]+);");

    /**
     * {@code a += b} written {@code a = a + (b)}. NVIDIA's compiler dies on some compound
     * assignments in double (C9999 "Unhandled expr op assign+", the Polyhedral IFS's orbit
     * trap), as it does on swizzled ones in float; the expanded form means the same.
     */
    static String expandCompound(String body) {
        Matcher m = COMPOUND.matcher(body);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + " = " + m.group(1) + " " + m.group(2) + " (" + m.group(3).trim() + ");"));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** In a copy, the copied structs' names refer to their copies. */
    private static String structNames(String s, Set<String> structs) {
        for (String name : structs) s = s.replaceAll("\\b" + Pattern.quote(name) + "\\b", name + SUFFIX);
        return s;
    }

    /** Calls to copied functions go to the copies, calls GLSL lacks in double to the shims. */
    private static String calls(String body, Set<String> copied, Set<String> shimsUsed) {
        Matcher m = CALL.matcher(body);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String to = null;
            if (copied.contains(name)) to = name + SUFFIX;
            else if (FLOAT_ONLY.contains(name) || FLOAT_ONLY_2.contains(name) || BITS.containsKey(name)) { to = name + SUFFIX; shimsUsed.add(name); }
            m.appendReplacement(b, Matcher.quoteReplacement(to == null ? m.group() : to + "("));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static void wrap(StringBuilder b, List<List<String>> conditions, String code) {
        for (List<String> frame : conditions) for (String line : frame) b.append(line).append('\n');
        b.append(code);
        for (int k = 0; k < conditions.size(); k++) b.append("#endif\n");
    }

    /** Overloads that evaluate a float-only built-in on float or double arguments. */
    static String shims(Set<String> used) {
        StringBuilder b = new StringBuilder();
        String[] f = {"float", "vec2", "vec3", "vec4"}, d = {"double", "dvec2", "dvec3", "dvec4"};
        for (String name : used) {
            if (BITS.containsKey(name)) {
                String[] r = BITS.get(name).equals("int") ? new String[]{"int", "ivec2", "ivec3", "ivec4"} : new String[]{"uint", "uvec2", "uvec3", "uvec4"};
                for (int k = 0; k < 4; k++) {
                    b.append(r[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" x) { return ").append(name).append("(x); }\n");
                    b.append(r[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" x) { return ").append(name).append('(').append(f[k]).append("(x)); }\n");
                }
                continue;
            }
            for (int k = 0; k < 4; k++) {
                if (FLOAT_ONLY.contains(name)) {
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" x) { return ").append(name).append("(x); }\n");
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" x) { return ").append(name).append('(').append(f[k]).append("(x)); }\n");
                }
                if (FLOAT_ONLY_2.contains(name)) {
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" a, ").append(f[k]).append(" b) { return ").append(name).append("(a, b); }\n");
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" a, ").append(d[k]).append(" b) { return ")
                     .append(name).append('(').append(f[k]).append("(a), ").append(f[k]).append("(b)); }\n");
                }
            }
        }
        return b.toString();
    }
}
