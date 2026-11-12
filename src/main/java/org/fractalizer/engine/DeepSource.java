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
 * <p>GLSL has no double trigonometry, exponentials or powers. Those calls go through overloads
 * ({@code sin_dp} and so on): a float argument (a uniform, {@code cos(angle)}) to the float
 * built-in, the same instruction as the float program; a double argument (the Mandelbulb's
 * angles, taken of the position itself) to a small double library emitted with the block, so
 * the orbit keeps its digits through the formula. Anything else GLSL refuses in double makes
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

    /** {@code -Dfractalizer.deep=double} keeps the double entry alone, {@code =ff} the
     *  float-float one alone (to compare them); unset, both with the relay below. */
    public static final String MODE_PROPERTY = "fractalizer.deep";
    /** Defined by a block holding both entries; the raytracer then picks one per frame. */
    public static final String BOTH_DEFINE = "DEEP_BOTH";

    /**
     * The deep entry of a scene, {@code DE_deep(dvec3)}. When the scene translates to
     * float-float (FloatFloatSource), the block holds that entry and the double one
     * ({@link #build}) as {@code DE_deep_ff} and {@code DE_deep_dp} under {@link #BOTH_DEFINE},
     * and the raytracer takes the double one for a frame where the two disagree at the
     * camera. Float-float
     * holds ~48 bits against 53 and costs a GeForce several times less, but how far down that
     * is enough depends on the formula: the Mandelbox matched double to 1e-12, the Mandelbulb
     * broke between 1e-10 and 1e-11 (PrecisionLab), its orbit amplifying the last digits.
     * A scene that does not translate gets the double entry alone.
     */
    public static String entry(String common, String scene) {
        String mode = System.getProperty(MODE_PROPERTY, "");
        if (!mode.equals("double")) {
            String ff = FloatFloatSource.build(common, scene);
            if (ff != null && mode.equals("ff")) return ff;
            if (ff != null) {
                String dp = build(common, scene);
                if (dp == null) return ff;
                // both entries; raytracer.glsl calls each through a subroutine of its own and
                // picks one per frame (gDeepDouble, main)
                return "#define " + BOTH_DEFINE + "\n"
                        + ff.replace("float " + ENTRY + "(dvec3 pos)", "float " + ENTRY + "_ff(dvec3 pos)")
                        + dp.replace("#define " + DEFINE + "\n", "").replace("float " + ENTRY + "(dvec3 pos)", "float " + ENTRY + "_dp(dvec3 pos)");
            }
            if (FloatFloatSource.lastError() != null)
                System.err.println("Deep precision in double for this scene (float-float: " + FloatFloatSource.lastError() + ")");
        }
        return build(common, scene);
    }

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

    /**
     * Overloads of a float-only built-in: a float argument goes to the built-in itself (the
     * same instruction as the float program), a double argument to the double library below.
     */
    static String shims(Set<String> used) {
        StringBuilder b = new StringBuilder();
        String[] f = {"float", "vec2", "vec3", "vec4"}, d = {"double", "dvec2", "dvec3", "dvec4"};
        String[] comp = {"x", "y", "z", "w"};
        Set<String> blocks = new LinkedHashSet<>();
        for (String name : used) {
            if (BITS.containsKey(name)) {
                String[] r = BITS.get(name).equals("int") ? new String[]{"int", "ivec2", "ivec3", "ivec4"} : new String[]{"uint", "uvec2", "uvec3", "uvec4"};
                for (int k = 0; k < 4; k++) {
                    b.append(r[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" x) { return ").append(name).append("(x); }\n");
                    b.append(r[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" x) { return ").append(name).append('(').append(f[k]).append("(x)); }\n");
                }
                continue;
            }
            blocks.addAll(LIBRARY_NEEDS.get(name));
            for (int k = 0; k < 4; k++) {
                if (FLOAT_ONLY.contains(name)) {
                    String lib = "dp_" + name;
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" x) { return ").append(name).append("(x); }\n");
                    b.append(d[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" x) { return ");
                    if (k == 0) b.append(lib).append("(x)");
                    else {
                        b.append(d[k]).append('(');
                        for (int c = 0; c <= k; c++) b.append(c > 0 ? ", " : "").append(lib).append("(x.").append(comp[c]).append(')');
                        b.append(')');
                    }
                    b.append("; }\n");
                }
                if (FLOAT_ONLY_2.contains(name)) {
                    String lib = name.equals("atan") ? "dp_atan2" : "dp_" + name;
                    b.append(f[k]).append(' ').append(name).append(SUFFIX).append('(').append(f[k]).append(" a, ").append(f[k]).append(" b) { return ").append(name).append("(a, b); }\n");
                    b.append(d[k]).append(' ').append(name).append(SUFFIX).append('(').append(d[k]).append(" a, ").append(d[k]).append(" b) { return ");
                    if (k == 0) b.append(lib).append("(a, b)");
                    else {
                        b.append(d[k]).append('(');
                        for (int c = 0; c <= k; c++) b.append(c > 0 ? ", " : "").append(lib).append("(a.").append(comp[c]).append(", b.").append(comp[c]).append(')');
                        b.append(')');
                    }
                    b.append("; }\n");
                }
            }
        }
        StringBuilder lib = new StringBuilder();
        if (!blocks.isEmpty()) lib.append(LIBRARY_HEADER);
        for (String block : List.of("trig", "exp", "log", "pow", "misc")) if (blocks.contains(block)) lib.append(LIBRARY.get(block));
        return lib.append(b).toString();
    }

    /** The double library and the overloads for these built-ins, as a scene's block carries
     *  them (DoubleMathProbe evaluates them on the GPU against the reference library). */
    public static String library(Set<String> builtins) {
        return shims(builtins);
    }

    // ---- double elementary functions --------------------------------------------------------
    //
    // GLSL has sin, exp, log and pow in float only. A double argument rounded to float would
    // lose the digits the double march carries (the Mandelbulb's angles are taken of the
    // position itself), so these are evaluated in double: Cody-Waite range reduction and fdlibm
    // kernels for sin and cos, Taylor on |r| <= ln2/2 for exp, atanh series on [sqrt(1/2),
    // sqrt(2)) for log, and atan2 as one Newton step from the float answer on x sin t - y cos t
    // (= r sin(t - answer): the error cubes, 1e-6 becomes 1e-19). pow with a whole exponent up
    // to 64 (the Mandelbulb's r^7 and r^8) is repeated squaring, otherwise exp(y log x). sin and
    // cos compute both kernels and pick, without a branch, so sin(a) and cos(a) side by side
    // share their reduction and kernels once inlined.
    //
    // DoubleMathProbe runs them on the GPU against StrictMath: every one within 1e-13 (relative
    // above 1, absolute below), most within 2 to 5 ulp, where the float built-ins are off by
    // 5e-6 (sin, cos up to |x| = 30) and 7e-5 (acos near +-1). What they cost on a GeForce,
    // where a double operation runs at 1/64 of a float one, in double fused multiply-adds:
    // sin 9, sin and cos of one angle 10, atan2 18, acos 27, pow 5.5 with a whole exponent
    // (25 otherwise), exp 10, log 16; a division is 6, a square root 9. A Mandelbulb iteration
    // is ~105 of them against ~45 with the float functions: the double estimator with them
    // costs 2.5 to 3 times what it did (PrecisionLab at 1e-6: 1.43 s against 0.50 s for 4
    // samples at 640x360). Repeated squaring and the shared sin/cos took 12 % off that.

    private static final String LIBRARY_HEADER =
            "// Double elementary functions (GLSL has them in float only): Cody-Waite reduction, fdlibm kernels.\n";

    /** Which library blocks each built-in needs. */
    private static final Map<String, List<String>> LIBRARY_NEEDS = Map.ofEntries(
            Map.entry("sin", List.of("trig")), Map.entry("cos", List.of("trig")), Map.entry("tan", List.of("trig")),
            Map.entry("asin", List.of("trig")), Map.entry("acos", List.of("trig")), Map.entry("atan", List.of("trig")),
            Map.entry("exp", List.of("exp")), Map.entry("exp2", List.of("exp")),
            Map.entry("sinh", List.of("exp")), Map.entry("cosh", List.of("exp")), Map.entry("tanh", List.of("exp")),
            Map.entry("log", List.of("log")), Map.entry("log2", List.of("log")),
            Map.entry("asinh", List.of("log")), Map.entry("acosh", List.of("log")), Map.entry("atanh", List.of("log")),
            Map.entry("pow", List.of("exp", "log", "pow")),
            Map.entry("radians", List.of("misc")), Map.entry("degrees", List.of("misc")));

    private static final Map<String, String> LIBRARY = Map.of(
            "trig", """
                    double dp_reduce(double x, out int q) {
                        double k = floor(x * 0.6366197723675814LF + 0.5LF);
                        q = int(k) & 3;
                        return (x - k * 1.5707963267341256LF) - k * 6.077100506506192e-11LF;
                    }
                    double dp_ksin(double x) {
                        double z = x * x;
                        return x + x * z * (-1.66666666666666324348e-01LF + z * (8.33333333332248946124e-03LF + z * (-1.98412698298579493134e-04LF
                             + z * (2.75573137070700676789e-06LF + z * (-2.50507602534068634195e-08LF + z * 1.58969099521155010221e-10LF)))));
                    }
                    double dp_kcos(double x) {
                        double z = x * x;
                        return 1.0LF - 0.5LF * z + z * z * (4.16666666666666019037e-02LF + z * (-1.38888888888741095749e-03LF + z * (2.48015872894767294178e-05LF
                             + z * (-2.75573143513906633035e-07LF + z * (2.08757232129817482790e-09LF + z * -1.13596475577881948265e-11LF)))));
                    }
                    double dp_sin(double x) {
                        int q; double r = dp_reduce(x, q);
                        double s = dp_ksin(r), c = dp_kcos(r);
                        double v = (q & 1) == 0 ? s : c;
                        return (q & 2) == 0 ? v : -v;
                    }
                    double dp_cos(double x) {
                        int q; double r = dp_reduce(x, q);
                        double s = dp_ksin(r), c = dp_kcos(r);
                        double v = (q & 1) == 0 ? c : s;
                        return ((q + 1) & 2) == 0 ? v : -v;
                    }
                    void dp_sincos(double x, out double s, out double c) {
                        int q; double r = dp_reduce(x, q);
                        double a = dp_ksin(r), b = dp_kcos(r);
                        s = (q & 1) == 0 ? a : b;
                        c = (q & 1) == 0 ? b : a;
                        if ((q & 2) != 0) s = -s;
                        if (((q + 1) & 2) != 0) c = -c;
                    }
                    double dp_tan(double x) { double s, c; dp_sincos(x, s, c); return s / c; }
                    double dp_atan2(double y, double x) {
                        double t = double(atan(float(y), float(x)));
                        double s, c; dp_sincos(t, s, c);
                        double slope = x * c + y * s;
                        return slope == 0.0LF ? t : t - (x * s - y * c) / slope;
                    }
                    double dp_atan(double x) { return dp_atan2(x, 1.0LF); }
                    double dp_asin(double x) { return dp_atan2(x, sqrt(max((1.0LF - x) * (1.0LF + x), 0.0LF))); }
                    double dp_acos(double x) { return dp_atan2(sqrt(max((1.0LF - x) * (1.0LF + x), 0.0LF)), x); }
                    """,
            "exp", """
                    double dp_exp(double x) {
                        if (x < -708.0LF) return 0.0LF;
                        double xc = min(x, 709.0LF);
                        double k = floor(xc * 1.4426950408889634LF + 0.5LF);
                        double r = (xc - k * 0.6931471803691238LF) - k * 1.9082149292705877e-10LF;
                        double p = 1.6059043836821613e-10LF;
                        p = p * r + 2.08767569878681e-09LF;
                        p = p * r + 2.505210838544172e-08LF;
                        p = p * r + 2.755731922398589e-07LF;
                        p = p * r + 2.7557319223985893e-06LF;
                        p = p * r + 2.48015873015873e-05LF;
                        p = p * r + 0.0001984126984126984LF;
                        p = p * r + 0.001388888888888889LF;
                        p = p * r + 0.008333333333333333LF;
                        p = p * r + 0.041666666666666664LF;
                        p = p * r + 0.16666666666666666LF;
                        p = p * r + 0.5LF;
                        p = p * r + 1.0LF;
                        p = p * r + 1.0LF;
                        return ldexp(p, int(k));
                    }
                    double dp_exp2(double x) { return dp_exp(x * 0.6931471805599453LF); }
                    double dp_sinh(double x) { double e = dp_exp(clamp(x, -700.0LF, 700.0LF)); return 0.5LF * (e - 1.0LF / e); }
                    double dp_cosh(double x) { double e = dp_exp(clamp(x, -700.0LF, 700.0LF)); return 0.5LF * (e + 1.0LF / e); }
                    double dp_tanh(double x) { double e = dp_exp(2.0LF * clamp(x, -20.0LF, 20.0LF)); return (e - 1.0LF) / (e + 1.0LF); }
                    """,
            "log", """
                    double dp_log(double x) {
                        if (!(x > 0.0LF)) return double(log(float(x)));
                        int e;
                        double m = frexp(x, e);
                        if (m < 0.7071067811865476LF) { m = m + m; e = e - 1; }
                        double f = m - 1.0LF;
                        double s = f / (2.0LF + f);
                        double z = s * s;
                        double p = 0.047619047619047616LF;
                        p = p * z + 0.05263157894736842LF;
                        p = p * z + 0.058823529411764705LF;
                        p = p * z + 0.06666666666666667LF;
                        p = p * z + 0.07692307692307693LF;
                        p = p * z + 0.09090909090909091LF;
                        p = p * z + 0.1111111111111111LF;
                        p = p * z + 0.14285714285714285LF;
                        p = p * z + 0.2LF;
                        p = p * z + 0.3333333333333333LF;
                        p = p * z + 1.0LF;
                        double de = double(e);
                        return de * 0.6931471803691238LF + (2.0LF * s * p + de * 1.9082149292705877e-10LF);
                    }
                    double dp_log2(double x) { return dp_log(x) * 1.4426950408889634LF; }
                    double dp_asinh(double x) { double a = abs(x); double v = dp_log(a + sqrt(a * a + 1.0LF)); return x < 0.0LF ? -v : v; }
                    double dp_acosh(double x) { return dp_log(x + sqrt(max(x * x - 1.0LF, 0.0LF))); }
                    double dp_atanh(double x) { return 0.5LF * dp_log((1.0LF + x) / (1.0LF - x)); }
                    """,
            "pow", """
                    double dp_powi(double x, int n) {
                        double r = 1.0LF;
                        double b = x;
                        while (n > 0) {
                            if ((n & 1) != 0) r = r * b;
                            b = b * b;
                            n = n >> 1;
                        }
                        return r;
                    }
                    double dp_pow(double x, double y) {
                        if (!(x > 0.0LF)) return double(pow(float(x), float(y)));
                        if (y == floor(y) && abs(y) <= 64.0LF) {
                            double p = dp_powi(x, int(abs(y)));
                            return y < 0.0LF ? 1.0LF / p : p;
                        }
                        return dp_exp(y * dp_log(x));
                    }
                    """,
            "misc", """
                    double dp_radians(double x) { return x * 0.017453292519943295LF; }
                    double dp_degrees(double x) { return x * 57.29577951308232LF; }
                    """);
}
