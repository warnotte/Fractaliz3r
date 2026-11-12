package org.fractalizer.engine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deep entry of a scene in float-float: {@code float DE_deep(dvec3 pos)}, the scene's
 * {@code DE_simple} and everything it reaches with every float, vector and 2x2 matrix that
 * holds a value made from the position held as two floats (FloatFloat). Nothing is written
 * per fractal: the functions are parsed, typed and rewritten, an operator at a time
 * ({@code a * b} on float-floats becomes {@code ff_mul(a, b)}), as DeepSource rewrites types
 * for its double entry. Uniforms, constants and literals stay float; a value is a float-float
 * only where it comes from the position.
 *
 * <p>The compiler does not merge two identical float-float computations ({@code sin(theta)}
 * twice cost a Mandelbulb 40 %, FloatFloatProbe), so the translation does: every float-float
 * call evaluated unconditionally in a statement is written to a temporary before it, and the
 * same call later in the block reuses it until one of the variables it reads is assigned;
 * {@code sin(x)} and {@code cos(x)} of one {@code x} share one {@code ff_sincos}.
 *
 * <p>What it cannot translate (a construct it does not know, a 3x3 matrix made from the
 * position) makes {@link #build} return null with the reason in {@link #lastError()}; the
 * caller then uses the double entry.
 */
public final class FloatFloatSource {

    public static final String SUFFIX = "_ff";
    private static final String ROOT = "DE_simple";

    private static volatile String lastError;

    private FloatFloatSource() {}

    /** Why the last {@link #build} returned null. */
    public static String lastError() { return lastError; }

    static final class Unsupported extends RuntimeException {
        Unsupported(String m) { super(m); }
    }

    /**
     * The float-float entry for a scene, as a block to append after the scene's source, or
     * null when the source has no {@code DE_simple} or something in it cannot be translated.
     */
    public static String build(String common, String scene) {
        try {
            lastError = null;
            return new FloatFloatSource.Job(common, scene).run();
        } catch (Unsupported | IllegalStateException | IndexOutOfBoundsException e) {
            lastError = e.getMessage();
            return null;
        }
    }

    // =====================================================================================
    // Lexer
    // =====================================================================================

    enum K { ID, NUM, OP, PP, EOF }

    record Tok(K k, String s) {
        boolean is(String t) { return (k == K.OP || k == K.ID) && s.equals(t); }
        @Override public String toString() { return s; }
    }

    private static final String[] OPS = {"<<=", ">>=", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "==", "!=", "<=", ">=", "&&", "||", "^^", "<<", ">>"};
    private static final Pattern NUMBER = Pattern.compile(
            "(?:\\d+\\.\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)(?:lf|LF|f|F)?|0[xX][0-9a-fA-F]+[uU]?|\\d+[uU]?");

    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = src.length();
        boolean lineStart = true;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { lineStart = true; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#' && lineStart) {
                int e = src.indexOf('\n', i);
                if (e < 0) e = n;
                // a directive continued with a backslash
                while (e > i && e < n && src.charAt(e - 1) == '\\') {
                    int next = src.indexOf('\n', e + 1);
                    e = next < 0 ? n : next;
                }
                out.add(new Tok(K.PP, src.substring(i, e).trim()));
                i = e;
                continue;
            }
            lineStart = false;
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                out.add(new Tok(K.ID, src.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                Matcher m = NUMBER.matcher(src);
                m.region(i, n);
                if (!m.lookingAt()) throw new Unsupported("number at " + src.substring(i, Math.min(n, i + 12)));
                out.add(new Tok(K.NUM, m.group()));
                i = m.end();
                continue;
            }
            String op = null;
            for (String o : OPS) if (src.startsWith(o, i)) { op = o; break; }
            if (op == null) {
                if ("+-*/%<>=!~&|^?:;,.(){}[]".indexOf(c) < 0) throw new Unsupported("character '" + c + "'");
                op = String.valueOf(c);
            }
            out.add(new Tok(K.OP, op));
            i += op.length();
        }
        out.add(new Tok(K.EOF, ""));
        return out;
    }

    // =====================================================================================
    // Syntax
    // =====================================================================================

    sealed interface Ex permits Lit, Id, Un, Post, Bin, Tern, Asg, Call, Member, Index, Comma {}
    record Lit(String text) implements Ex {}
    record Id(String name) implements Ex {}
    record Un(String op, Ex e) implements Ex {}
    record Post(String op, Ex e) implements Ex {}
    record Bin(String op, Ex a, Ex b) implements Ex {}
    record Tern(Ex c, Ex a, Ex b) implements Ex {}
    record Asg(String op, Ex lhs, Ex rhs) implements Ex {}
    record Call(String name, List<Ex> args) implements Ex {}
    record Member(Ex e, String field) implements Ex {}
    record Index(Ex e, Ex i) implements Ex {}
    record Comma(List<Ex> es) implements Ex {}

    sealed interface St permits Block, Decl, ExprSt, If, For, While, DoWhile, Switch, CaseLabel, Ret, Jump, PP, Empty {}
    record Block(List<St> body) implements St {}
    record Dcl(String name, Ex size, boolean sized, Ex init) {}
    record Decl(List<String> quals, String type, List<Dcl> vars) implements St {}
    record ExprSt(Ex e) implements St {}
    record If(Ex c, St a, St b) implements St {}
    record For(St init, Ex cond, Ex incr, St body) implements St {}
    record While(Ex c, St body) implements St {}
    record DoWhile(St body, Ex c) implements St {}
    record Switch(Ex e, Block body) implements St {}
    record CaseLabel(Ex label) implements St {}
    record Ret(Ex e) implements St {}
    record Jump(String kw) implements St {}
    record PP(String line) implements St {}
    record Empty() implements St {}

    static final Set<String> QUALS = Set.of("const", "highp", "mediump", "lowp", "precise", "in", "out", "inout", "flat", "smooth", "uniform", "invariant");
    static final Set<String> BUILTIN_TYPES = new HashSet<>(List.of("void", "bool", "int", "uint", "float", "double",
            "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
            "dvec2", "dvec3", "dvec4", "mat2", "mat3", "mat4", "dmat2", "dmat3", "dmat4",
            "mat2x2", "mat3x3", "mat4x4", "mat2x3", "mat2x4", "mat3x2", "mat3x4", "mat4x2", "mat4x3"));
    static final Map<String, Integer> PREC = Map.ofEntries(
            Map.entry("||", 1), Map.entry("^^", 2), Map.entry("&&", 3), Map.entry("|", 4), Map.entry("^", 5), Map.entry("&", 6),
            Map.entry("==", 7), Map.entry("!=", 7), Map.entry("<", 8), Map.entry(">", 8), Map.entry("<=", 8), Map.entry(">=", 8),
            Map.entry("<<", 9), Map.entry(">>", 9), Map.entry("+", 10), Map.entry("-", 10), Map.entry("*", 11), Map.entry("/", 11), Map.entry("%", 11));
    static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=");

    static final class Parser {
        final List<Tok> t;
        final Set<String> structs;
        int p;

        Parser(List<Tok> t, Set<String> structs) { this.t = t; this.structs = structs; }

        Tok peek() { return t.get(p); }
        Tok peek(int k) { return t.get(Math.min(t.size() - 1, p + k)); }
        Tok next() { return t.get(p++); }
        boolean at(String s) { return peek().is(s); }
        boolean eat(String s) { if (at(s)) { p++; return true; } return false; }
        void expect(String s) { if (!eat(s)) throw new Unsupported("expected '" + s + "' at '" + peek() + "' (" + context() + ")"); }
        String context() {
            StringBuilder b = new StringBuilder();
            for (int k = Math.max(0, p - 6); k < Math.min(t.size(), p + 6); k++) b.append(t.get(k).s()).append(' ');
            return b.toString().trim();
        }

        boolean isType(Tok k) { return k.k() == K.ID && (BUILTIN_TYPES.contains(k.s()) || structs.contains(k.s())); }

        /** A declaration starts here: qualifiers, a type, a name. */
        boolean declStarts() {
            int k = p;
            while (t.get(k).k() == K.ID && QUALS.contains(t.get(k).s())) k++;
            return isType(t.get(k)) && t.get(k + 1).k() == K.ID;
        }

        List<St> statements() {
            List<St> out = new ArrayList<>();
            while (peek().k() != K.EOF && !at("}")) out.add(statement());
            return out;
        }

        St statement() {
            Tok k = peek();
            if (k.k() == K.PP) { p++; return new PP(k.s()); }
            if (eat("{")) { List<St> b = statements(); expect("}"); return new Block(b); }
            if (eat(";")) return new Empty();
            if (eat("if")) {
                expect("(");
                Ex c = expr();
                expect(")");
                St a = statement();
                St b = eat("else") ? statement() : null;
                return new If(c, a, b);
            }
            if (eat("for")) {
                expect("(");
                St init;
                if (eat(";")) init = new Empty();
                else if (declStarts()) init = declaration();
                else { init = new ExprSt(expr()); expect(";"); }
                Ex cond = at(";") ? null : expr();
                expect(";");
                Ex incr = at(")") ? null : expr();
                expect(")");
                return new For(init, cond, incr, statement());
            }
            if (eat("while")) { expect("("); Ex c = expr(); expect(")"); return new While(c, statement()); }
            if (eat("do")) {
                St body = statement();
                expect("while"); expect("(");
                Ex c = expr();
                expect(")"); expect(";");
                return new DoWhile(body, c);
            }
            if (eat("switch")) {
                expect("(");
                Ex e = expr();
                expect(")");
                expect("{");
                List<St> b = statements();
                expect("}");
                return new Switch(e, new Block(b));
            }
            if (eat("case")) { Ex l = ternary(); expect(":"); return new CaseLabel(l); }
            if (eat("default")) { expect(":"); return new CaseLabel(null); }
            if (eat("return")) { Ex e = at(";") ? null : expr(); expect(";"); return new Ret(e); }
            if (at("break") || at("continue") || at("discard")) { String w = next().s(); expect(";"); return new Jump(w); }
            if (declStarts()) return declaration();
            Ex e = expr();
            expect(";");
            return new ExprSt(e);
        }

        Decl declaration() {
            List<String> quals = new ArrayList<>();
            while (peek().k() == K.ID && QUALS.contains(peek().s())) quals.add(next().s());
            String type = next().s();
            if (at("[")) throw new Unsupported("array type syntax " + type + "[]");
            List<Dcl> vars = new ArrayList<>();
            do {
                String name = next().s();
                Ex size = null;
                boolean sized = false;
                if (eat("[")) { sized = true; if (!at("]")) size = expr(); expect("]"); }
                Ex init = eat("=") ? assign() : null;
                vars.add(new Dcl(name, size, sized, init));
            } while (eat(","));
            expect(";");
            return new Decl(quals, type, vars);
        }

        Ex expr() {
            Ex e = assign();
            if (!at(",")) return e;
            List<Ex> es = new ArrayList<>(List.of(e));
            while (eat(",")) es.add(assign());
            return new Comma(es);
        }

        Ex assign() {
            Ex lhs = ternary();
            if (peek().k() == K.OP && ASSIGN.contains(peek().s())) {
                String op = next().s();
                return new Asg(op, lhs, assign());
            }
            return lhs;
        }

        Ex ternary() {
            Ex c = binary(1);
            if (eat("?")) {
                Ex a = assign();
                expect(":");
                Ex b = assign();
                return new Tern(c, a, b);
            }
            return c;
        }

        Ex binary(int min) {
            Ex a = unary();
            while (true) {
                Tok k = peek();
                Integer pr = k.k() == K.OP ? PREC.get(k.s()) : null;
                if (pr == null || pr < min) return a;
                p++;
                a = new Bin(k.s(), a, binary(pr + 1));
            }
        }

        Ex unary() {
            if (at("-") || at("+") || at("!") || at("~") || at("++") || at("--")) {
                String op = next().s();
                return new Un(op, unary());
            }
            return postfix();
        }

        Ex postfix() {
            Ex e = primary();
            while (true) {
                if (eat("[")) { Ex i = expr(); expect("]"); e = new Index(e, i); }
                else if (eat(".")) {
                    String f = next().s();
                    if (at("(")) throw new Unsupported("method call ." + f + "()");
                    e = new Member(e, f);
                }
                else if (at("++") || at("--")) e = new Post(next().s(), e);
                else return e;
            }
        }

        Ex primary() {
            Tok k = next();
            if (k.k() == K.NUM) return new Lit(k.s());
            if (k.k() == K.ID) {
                if (at("(")) {
                    p++;
                    List<Ex> args = new ArrayList<>();
                    if (!at(")")) {
                        if (at("void") && peek(1).is(")")) p++;
                        else do { args.add(assign()); } while (eat(","));
                    }
                    expect(")");
                    return new Call(k.s(), args);
                }
                if (at("[") && isType(k)) throw new Unsupported("array constructor " + k.s() + "[]");
                return new Id(k.s());
            }
            if (k.is("(")) { Ex e = expr(); expect(")"); return e; }
            throw new Unsupported("unexpected '" + k.s() + "' (" + context() + ")");
        }
    }

    // =====================================================================================
    // Types
    // =====================================================================================

    /**
     * A GLSL type: base "float", "int", "uint", "bool", "void" or a struct name; n the vector
     * size (1 for a scalar); mat the matrix size (0 when not a matrix); ff whether the value
     * is held in float-float; arr the array length (0 none, -1 unsized).
     */
    record Ty(String base, int n, int mat, boolean ff, int arr) {
        static Ty scalar(String b) { return new Ty(b, 1, 0, false, 0); }
        boolean isFloat() { return base.equals("float"); }
        boolean isNum() { return base.equals("float") || base.equals("int") || base.equals("uint"); }
        boolean isStruct() { return !List.of("float", "int", "uint", "bool", "void").contains(base); }
        Ty withFF(boolean f) { return new Ty(base, n, mat, f, arr); }
        Ty elem() { return new Ty(base, n, mat, ff, 0); }
        Ty withN(int k) { return new Ty(base, k, 0, ff, 0); }

        /** The GLSL type this is written as. */
        String glsl() {
            if (isStruct()) return ff ? base + SUFFIX : base;
            if (mat > 0) return ff ? "ffm" + mat : "mat" + mat;
            if (ff) return "ff" + n;
            if (n == 1) return base;
            return switch (base) { case "float" -> "vec"; case "int" -> "ivec"; case "uint" -> "uvec"; case "bool" -> "bvec"; default -> base; } + n;
        }
    }

    static Ty parseType(String name, Set<String> structs) {
        switch (name) {
            case "void": return Ty.scalar("void");
            case "float": return Ty.scalar("float");
            case "int": return Ty.scalar("int");
            case "uint": return Ty.scalar("uint");
            case "bool": return Ty.scalar("bool");
            case "mat2": case "mat2x2": return new Ty("float", 2, 2, false, 0);
            case "mat3": case "mat3x3": return new Ty("float", 3, 3, false, 0);
            case "mat4": case "mat4x4": return new Ty("float", 4, 4, false, 0);
            default:
        }
        Matcher m = Pattern.compile("(i|u|b)?vec([234])").matcher(name);
        if (m.matches()) {
            String b = m.group(1) == null ? "float" : switch (m.group(1)) { case "i" -> "int"; case "u" -> "uint"; default -> "bool"; };
            return new Ty(b, Integer.parseInt(m.group(2)), 0, false, 0);
        }
        if (structs.contains(name)) return Ty.scalar(name);
        throw new Unsupported("type " + name);
    }

    /** What a float-family declaration becomes in the copy: a float-float (2x2 matrices too). */
    static Ty translated(Ty t, Set<String> ffStructs) {
        if (t.isStruct()) return ffStructs.contains(t.base()) ? t.withFF(true) : t;
        if (!t.isFloat()) return t;
        if (t.mat() == 3 || t.mat() == 4) return t;      // kept float; one made from the position fails at its use
        return t.withFF(true);
    }

    // =====================================================================================
    // The translation of one scene
    // =====================================================================================

    record Fn(DeepSource.Function src, Ty ret, List<Param> params) {}
    record Param(String qual, Ty ty, String name) {}
    record Val(String code, Ty ty, boolean pure) {}

    static final class Job {
        final List<DeepSource.Function> functions;
        final Map<String, List<Fn>> byName = new LinkedHashMap<>();
        final Map<String, DeepSource.Struct> structByName = new LinkedHashMap<>();
        final Set<String> structNames = new HashSet<>();
        final Set<String> ffStructs = new LinkedHashSet<>();
        final Map<String, Map<String, Ty>> fields = new HashMap<>();
        final Map<String, Ty> globals = new HashMap<>();
        final Set<String> functionMacros = new HashSet<>();
        final Map<String, String> helpers = new LinkedHashMap<>();
        final Set<String> reach = new LinkedHashSet<>();
        final String text;

        Job(String common, String scene) {
            text = DeepSource.stripComments(common) + "\n" + DeepSource.stripComments(scene);
            DeepSource.Parsed parsed = DeepSource.parse(text);
            functions = parsed.functions();
            for (DeepSource.Struct s : parsed.structs()) { structByName.putIfAbsent(s.name(), s); structNames.add(s.name()); }
        }

        String run() {
            Map<String, List<DeepSource.Function>> raw = new LinkedHashMap<>();
            for (DeepSource.Function f : functions) raw.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f);
            if (!raw.containsKey(ROOT)) return null;

            // Everything DE_simple reaches (as DeepSource reckons it).
            Pattern call = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
            Deque<String> todo = new ArrayDeque<>(List.of(ROOT));
            while (!todo.isEmpty()) {
                String name = todo.pop();
                if (!reach.add(name)) continue;
                for (DeepSource.Function f : raw.get(name)) {
                    Matcher m = call.matcher(f.body());
                    while (m.find()) if (raw.containsKey(m.group(1)) && !reach.contains(m.group(1))) todo.push(m.group(1));
                }
            }

            // Structs: the ones the reached functions use, and their fields. A struct with a
            // float-family field gets a float-float copy.
            for (DeepSource.Struct s : structByName.values()) {
                Map<String, Ty> f = new LinkedHashMap<>();
                Parser ps = new Parser(lex(s.body()), structNames);
                for (St st : ps.statements()) {
                    if (!(st instanceof Decl d)) throw new Unsupported("struct " + s.name() + " body");
                    for (Dcl v : d.vars()) {
                        Ty t = parseType(d.type(), structNames);
                        if (v.sized()) t = new Ty(t.base(), t.n(), t.mat(), t.ff(), arrayLength(v.size()));
                        f.put(v.name(), t);
                    }
                }
                fields.put(s.name(), f);
            }
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Map.Entry<String, Map<String, Ty>> e : fields.entrySet()) {
                    if (ffStructs.contains(e.getKey())) continue;
                    for (Ty t : e.getValue().values()) {
                        if ((t.isFloat() && t.mat() <= 2) || ffStructs.contains(t.base())) { ffStructs.add(e.getKey()); grew = true; break; }
                    }
                }
            }

            scanGlobals();

            for (String name : reach) {
                for (DeepSource.Function f : raw.get(name)) {
                    Ty ret = parseType(f.returnType(), structNames);
                    List<Param> params = new ArrayList<>();
                    String ps = f.params().trim();
                    if (!ps.isEmpty() && !ps.equals("void")) {
                        for (String part : splitTop(ps)) {
                            List<Tok> tk = lex(part);
                            int k = 0;
                            String qual = "in";
                            while (tk.get(k).k() == K.ID && QUALS.contains(tk.get(k).s())) {
                                String q = tk.get(k).s();
                                if (q.equals("out") || q.equals("inout")) qual = q;
                                k++;
                            }
                            Ty t = parseType(tk.get(k).s(), structNames);
                            String pname = tk.get(k + 1).s();
                            if (tk.get(k + 2).is("[")) {
                                Ex sz = new Parser(tk.subList(k + 3, tk.size()), structNames).expr();
                                t = new Ty(t.base(), t.n(), t.mat(), t.ff(), arrayLength(sz));
                            }
                            params.add(new Param(qual, t, pname));
                        }
                    }
                    byName.computeIfAbsent(name, x -> new ArrayList<>()).add(new Fn(f, ret, params));
                }
            }

            StringBuilder typeDefs = new StringBuilder(), decls = new StringBuilder(), defs = new StringBuilder();
            for (DeepSource.Struct s : structByName.values()) {
                if (!ffStructs.contains(s.name())) continue;
                StringBuilder b = new StringBuilder("struct ").append(s.name()).append(SUFFIX).append(" {");
                for (Map.Entry<String, Ty> e : fields.get(s.name()).entrySet()) {
                    Ty t = translated(e.getValue(), ffStructs);
                    b.append(' ').append(t.glsl()).append(' ').append(e.getKey());
                    if (t.arr() != 0) b.append('[').append(t.arr() > 0 ? String.valueOf(t.arr()) : "").append(']');
                    b.append(';');
                }
                b.append(" };\n");
                wrap(typeDefs, s.conditions(), b.toString());
            }
            // Definitions callees first. A call to a function only declared so far, made from the
            // deep estimate's subroutine, crashes NVIDIA's compiler (C9999 "exception during
            // compilation"; the same functions defined in this order compile).
            List<String> order = new ArrayList<>();
            calleesFirst(ROOT, raw, new HashSet<>(), order);
            for (String name : order) {
                for (Fn f : byName.get(name)) {
                    String sig = signature(f);
                    wrap(decls, f.src().conditions(), sig + ";\n");
                    wrap(defs, f.src().conditions(), sig + " {\n" + new FnJob(this, f).body() + "}\n");
                }
            }
            StringBuilder out = new StringBuilder();
            out.append("// === Deep entry of the scene in float-float (FloatFloatSource: DE_simple and what it calls) ===\n");
            out.append("#define ").append(DeepSource.DEFINE).append('\n');
            out.append(FloatFloat.library());
            out.append(typeDefs);
            for (String h : helpers.values()) out.append(h);
            out.append(decls);
            out.append(defs);
            Fn root = byName.get(ROOT).get(0);
            if (root.params().size() != 1 || root.params().get(0).ty().n() != 3) throw new Unsupported("DE_simple(vec3)");
            // A float-float has the float's exponent range: a derivative that overflows inside
            // the set (where double still has a tiny distance and float divides by infinity
            // to 0) leaves NaN in the low halves. That is a point at the surface or in it.
            out.append("float ").append(DeepSource.ENTRY).append("(dvec3 pos) { float d = ff_tofloat(").append(ROOT).append(SUFFIX)
                    .append("(ff_fromd(pos))); return isnan(d) ? 0.0 : d; }\n");
            return out.toString();
        }

        /** Post-order over the call graph: every function after the ones it calls. */
        void calleesFirst(String name, Map<String, List<DeepSource.Function>> raw, Set<String> seen, List<String> out) {
            if (!seen.add(name)) return;
            Pattern call = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
            for (DeepSource.Function f : raw.get(name)) {
                Matcher m = call.matcher(f.body());
                while (m.find()) if (reach.contains(m.group(1)) && !m.group(1).equals(name)) calleesFirst(m.group(1), raw, seen, out);
            }
            out.add(name);
        }

        String signature(Fn f) {
            StringBuilder b = new StringBuilder();
            b.append(translated(f.ret(), ffStructs).glsl()).append(' ').append(f.src().name()).append(SUFFIX).append('(');
            for (int i = 0; i < f.params().size(); i++) {
                Param p = f.params().get(i);
                Ty t = translated(p.ty(), ffStructs);
                if (i > 0) b.append(", ");
                if (!p.qual().equals("in")) b.append(p.qual()).append(' ');
                b.append(t.glsl()).append(' ').append(p.name());
                if (t.arr() != 0) b.append('[').append(t.arr() > 0 ? String.valueOf(t.arr()) : "").append(']');
            }
            return b.append(')').toString();
        }

        int arrayLength(Ex size) {
            if (size == null) return -1;
            if (size instanceof Lit l) return Integer.parseInt(l.text().replaceAll("[uU]", ""));
            if (size instanceof Id id && globals.containsKey(id.name())) {
                String v = constValues.get(id.name());
                if (v != null) return Integer.parseInt(v.trim());
            }
            throw new Unsupported("array size " + size);
        }

        final Map<String, String> constValues = new HashMap<>();

        /** Uniforms, constants, globals and object-like macros of the program, with their types. */
        void scanGlobals() {
            // Top-level text: function and struct bodies removed.
            StringBuilder top = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') { depth++; continue; }
                if (c == '}') { depth--; if (depth == 0) top.append(';'); continue; }
                if (depth == 0) top.append(c);
            }
            for (String line : top.toString().split("\n")) {
                String l = line.trim();
                if (l.startsWith("#define")) {
                    Matcher m = Pattern.compile("#define\\s+([A-Za-z_]\\w*)(\\()?\\s*(.*)").matcher(l);
                    if (!m.matches()) continue;
                    if (m.group(2) != null) { functionMacros.add(m.group(1)); continue; }
                    String body = m.group(3).trim();
                    if (body.isEmpty()) continue;
                    try {
                        Ex e = new Parser(lex(body), structNames).expr();
                        Ty t = new FnJob(this, null).ex(e, false).ty();
                        globals.put(m.group(1), t);
                        constValues.put(m.group(1), body);
                    } catch (RuntimeException ignored) {
                        // a macro that is not an expression; used, it fails there
                    }
                }
            }
            String noPP = top.toString().replaceAll("(?m)^\\s*#.*$", "");
            noPP = noPP.replaceAll("layout\\s*\\([^)]*\\)", " ");
            for (String stmt : noPP.split(";")) {
                String s = stmt.trim();
                if (s.isEmpty()) continue;
                List<Tok> tk;
                try { tk = lex(s); } catch (Unsupported e) { continue; }
                int k = 0;
                boolean isConst = false;
                while (tk.get(k).k() == K.ID && QUALS.contains(tk.get(k).s())) { if (tk.get(k).s().equals("const")) isConst = true; k++; }
                if (tk.get(k).k() != K.ID || !(BUILTIN_TYPES.contains(tk.get(k).s()) || structNames.contains(tk.get(k).s()))) continue;
                if (tk.get(k + 1).k() != K.ID) continue;
                if (tk.get(k + 2).is("(")) continue;                      // a prototype
                Ty base;
                try { base = parseType(tk.get(k).s(), structNames); } catch (Unsupported e) { continue; }
                // declarators: name [ [size] ] [= init], ...
                int j = k + 1;
                while (j < tk.size() && tk.get(j).k() == K.ID) {
                    String name = tk.get(j).s();
                    j++;
                    Ty t = base;
                    if (tk.get(j).is("[")) {
                        int close = j;
                        while (!tk.get(close).is("]")) close++;
                        int len = -1;
                        if (close == j + 2 && tk.get(j + 1).k() == K.NUM) len = Integer.parseInt(tk.get(j + 1).s().replaceAll("[uU]", ""));
                        else if (close == j + 2 && constValues.containsKey(tk.get(j + 1).s())) len = Integer.parseInt(constValues.get(tk.get(j + 1).s()).trim());
                        t = new Ty(t.base(), t.n(), t.mat(), false, len);
                        j = close + 1;
                    }
                    globals.put(name, t);
                    if (tk.get(j).is("=")) {
                        int start = j + 1, d = 0;
                        j++;
                        while (j < tk.size() && tk.get(j).k() != K.EOF && !(d == 0 && tk.get(j).is(","))) {
                            if (tk.get(j).is("(")) d++;
                            if (tk.get(j).is(")")) d--;
                            j++;
                        }
                        if (isConst) {
                            StringBuilder v = new StringBuilder();
                            for (int q = start; q < j; q++) v.append(tk.get(q).s()).append(' ');
                            constValues.put(name, v.toString().trim());
                        }
                    }
                    if (tk.get(j).is(",")) j++;
                    else break;
                }
            }
        }
    }

    static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i).trim()); start = i + 1; }
        }
        out.add(s.substring(start).trim());
        return out;
    }

    static void wrap(StringBuilder b, List<List<String>> conditions, String code) {
        for (List<String> frame : conditions) for (String line : frame) b.append(line).append('\n');
        b.append(code);
        for (int k = 0; k < conditions.size(); k++) b.append("#endif\n");
    }

    // =====================================================================================
    // One function
    // =====================================================================================

    static final Set<String> UNARY = Set.of(FloatFloat.UNARY);
    static final Set<String> GEN_BINARY = Set.of("min", "max", "mod", "step", "pow", "atan");
    static final Set<String> GEN_TERNARY = Set.of("clamp", "mix", "smoothstep");
    static final Pattern IDENT = Pattern.compile("\\b[A-Za-z_]\\w*\\b");

    /** A temporary holding a float-float result, and the variables it was computed from. */
    record Held(String name, Set<String> reads) {}

    static final class FnJob {
        final Job job;
        final Fn fn;
        final Deque<Map<String, Ty>> scopes = new ArrayDeque<>();
        // common subexpressions: code -> temporary, valid until a variable it reads is assigned
        final Map<String, Held> cache = new HashMap<>();
        final Deque<List<String>> cacheLevels = new ArrayDeque<>();
        List<String> pre;          // statements to write before the one being translated; null: none allowed
        int tmp;
        final StringBuilder out = new StringBuilder();
        int indent = 1;

        FnJob(Job job, Fn fn) {
            this.job = job;
            this.fn = fn;
            scopes.push(new HashMap<>());
            cacheLevels.push(new ArrayList<>());
            if (fn != null) for (Param p : fn.params()) scopes.peek().put(p.name(), translated(p.ty(), job.ffStructs));
        }

        String body() {
            Parser ps = new Parser(lex(fn.src().body()), job.structNames);
            List<St> sts = ps.statements();
            if (ps.peek().k() != K.EOF) throw new Unsupported("trailing '" + ps.peek() + "' in " + fn.src().name());
            for (St s : sts) stmt(s);
            return out.toString();
        }

        // ---- scopes and the cache --------------------------------------------------------

        Ty lookup(String name) {
            for (Map<String, Ty> s : scopes) if (s.containsKey(name)) return s.get(name);
            Ty g = job.globals.get(name);
            if (g != null) return g;
            if (name.equals("true") || name.equals("false")) return Ty.scalar("bool");
            if (job.functionMacros.contains(name)) throw new Unsupported("function-like macro " + name);
            throw new Unsupported("unknown identifier " + name + (fn == null ? "" : " in " + fn.src().name()));
        }

        void open() { scopes.push(new HashMap<>()); cacheLevels.push(new ArrayList<>()); }
        void close() {
            scopes.pop();
            for (String k : cacheLevels.pop()) cache.remove(k);
        }

        void invalidate(String var) {
            cache.entrySet().removeIf(e -> e.getValue().reads().contains(var));
        }

        void invalidateAll() { cache.clear(); }

        Set<String> reads(String code) {
            Set<String> r = new HashSet<>();
            Matcher m = IDENT.matcher(code);
            while (m.find()) {
                String id = m.group();
                Held h = heldByName.get(id);
                if (h != null) r.addAll(h.reads());
                else r.add(id);
            }
            return r;
        }

        final Map<String, Held> heldByName = new HashMap<>();

        /** A float-float value as a temporary when it may be hoisted, reused when already held. */
        Val held(String code, Ty ty, boolean pure, boolean hoist) {
            if (!hoist || pre == null || !pure) return new Val(code, ty, pure);
            Held h = cache.get(code);
            if (h != null) return new Val(h.name(), ty, true);
            String name = "_t" + (tmp++);
            pre.add(ty.glsl() + " " + name + " = " + code + ";");
            h = new Held(name, reads(code));
            cache.put(code, h);
            heldByName.put(name, h);
            cacheLevels.peek().add(code);
            return new Val(name, ty, true);
        }

        // ---- statements ------------------------------------------------------------------

        void line(String s) {
            for (int i = 0; i < indent; i++) out.append("    ");
            out.append(s).append('\n');
        }

        /** Translate a statement whose expressions may hoist into temporaries written before it. */
        void stmt(St s) {
            switch (s) {
                case Block b -> {
                    line("{");
                    indent++;
                    open();
                    for (St x : b.body()) stmt(x);
                    close();
                    indent--;
                    line("}");
                }
                case PP pp -> {
                    invalidateAll();
                    out.append(pp.line()).append('\n');
                }
                case Empty e -> { }
                case Decl d -> {
                    for (Dcl v : d.vars()) declare(d, v);
                }
                case ExprSt es -> exprStatement(es.e());
                case If i -> {
                    List<String> p = begin();
                    String c = to(ex(i.c(), true), Ty.scalar("bool")).code;
                    flush(p);
                    line("if (" + c + ")");
                    branch(i.a());
                    if (i.b() != null) {
                        line("else");
                        branch(i.b());
                    }
                }
                case For f -> {
                    line("{");
                    indent++;
                    open();
                    invalidateAssignedIn(f);
                    String init;
                    if (f.init() instanceof Decl d) {
                        if (d.vars().size() != 1) throw new Unsupported("for with several declarations");
                        Dcl v = d.vars().get(0);
                        Ty t = translated(parseType(d.type(), job.structNames), job.ffStructs);
                        List<String> p = begin();
                        String initCode = v.init() == null ? null : to(ex(v.init(), true), t).code;
                        flush(p);
                        scopes.peek().put(v.name(), t);
                        init = t.glsl() + " " + v.name() + (initCode == null ? "" : " = " + initCode);
                    } else if (f.init() instanceof ExprSt es) {
                        List<String> p = begin();
                        init = expression(es.e());
                        flush(p);
                    } else init = "";
                    invalidateAssignedIn(f);
                    List<String> saved = pre;
                    pre = null;
                    String cond = f.cond() == null ? "" : to(ex(f.cond(), false), Ty.scalar("bool")).code;
                    String incr = f.incr() == null ? "" : expression(f.incr());
                    pre = saved;
                    line("for (" + init + "; " + cond + "; " + incr + ")");
                    branch(f.body());
                    invalidateAssignedIn(f);
                    close();
                    indent--;
                    line("}");
                }
                case While w -> {
                    invalidateAssignedIn(w);
                    List<String> saved = pre;
                    pre = null;
                    String c = to(ex(w.c(), false), Ty.scalar("bool")).code;
                    pre = saved;
                    line("while (" + c + ")");
                    branch(w.body());
                    invalidateAssignedIn(w);
                }
                case DoWhile d -> {
                    invalidateAssignedIn(d);
                    line("do");
                    branch(d.body());
                    List<String> saved = pre;
                    pre = null;
                    String c = to(ex(d.c(), false), Ty.scalar("bool")).code;
                    pre = saved;
                    line("while (" + c + ");");
                    invalidateAssignedIn(d);
                }
                case Switch sw -> {
                    List<String> p = begin();
                    Val e = ex(sw.e(), true);
                    if (e.ty().ff()) throw new Unsupported("switch on a float");
                    flush(p);
                    line("switch (" + e.code() + ")");
                    stmt(sw.body());
                    invalidateAll();
                }
                case CaseLabel c -> {
                    invalidateAll();
                    if (c.label() == null) line("default:");
                    else line("case " + ex(c.label(), false).code() + ":");
                }
                case Ret r -> {
                    if (r.e() == null) { line("return;"); return; }
                    Ty rt = translated(fn.ret(), job.ffStructs);
                    List<String> p = begin();
                    String c = to(ex(r.e(), true), rt).code;
                    flush(p);
                    line("return " + c + ";");
                }
                case Jump j -> line(j.kw() + ";");
            }
        }

        /** The body of an if, a loop: always a block, so temporaries stay inside it. */
        void branch(St s) {
            if (s instanceof Block) { stmt(s); return; }
            line("{");
            indent++;
            open();
            stmt(s);
            close();
            indent--;
            line("}");
        }

        List<String> begin() { List<String> p = new ArrayList<>(); pre = p; return p; }

        void flush(List<String> p) {
            for (String s : p) line(s);
            pre = null;
        }

        void declare(Decl d, Dcl v) {
            Ty t = translated(parseType(d.type(), job.structNames), job.ffStructs);
            if (v.sized()) t = new Ty(t.base(), t.n(), t.mat(), t.ff(), job.arrayLength(v.size()));
            List<String> p = begin();
            String init = null;
            if (v.init() != null) {
                Val iv = ex(v.init(), true);
                if (t.mat() == 2 && !iv.ty().ff()) t = t.withFF(false);       // a 2x2 matrix of uniforms stays float
                init = to(iv, t).code;
            } else if (t.mat() == 2) {
                throw new Unsupported("2x2 matrix declared without a value");
            }
            flush(p);
            scopes.peek().put(v.name(), t);
            invalidate(v.name());
            boolean keepConst = d.quals().contains("const") && !t.ff();
            String arr = t.arr() != 0 ? "[" + (t.arr() > 0 ? t.arr() : "") + "]" : "";
            Ty decl = t.arr() != 0 ? t.elem() : t;
            line((keepConst ? "const " : "") + decl.glsl() + " " + v.name() + arr + (init == null ? "" : " = " + init) + ";");
        }

        void exprStatement(Ex e) {
            if (e instanceof Comma c) { for (Ex x : c.es()) exprStatement(x); return; }
            List<String> p = begin();
            String code = expressionStatement(e);
            flush(p);
            if (code != null) line(code + ";");
        }

        /** An expression evaluated for its effect: assignments to a float-float swizzle are
         *  written out here, as statements. Null when everything went to the temporaries. */
        String expressionStatement(Ex e) {
            if (e instanceof Asg a) {
                Ex rhs = a.op().equals("=") ? a.rhs() : new Bin(a.op().substring(0, a.op().length() - 1), a.lhs(), a.rhs());
                if (a.lhs() instanceof Member m && isFFSwizzle(m)) {
                    Val base = lvalue(m.e());
                    String sw = swizzle(m.field());
                    Ty t = base.ty().withN(sw.length());
                    Val r = to(ex(rhs, true), t);
                    String name = "_t" + (tmp++);
                    pre.add(t.glsl() + " " + name + " = " + r.code() + ";");
                    pre.add(base.code() + ".hi." + sw + " = " + name + ".hi;");
                    pre.add(base.code() + ".lo." + sw + " = " + name + ".lo;");
                    invalidate(root(a.lhs()));
                    return null;
                }
                if (a.lhs() instanceof Index ix && lvalueType(ix.e()).ff() && lvalueType(ix.e()).arr() == 0 && lvalueType(ix.e()).n() > 1) {
                    Val base = lvalue(ix.e());
                    Val i = ex(ix.i(), true);
                    Val r = to(ex(rhs, true), Ty.scalar("float").withFF(true));
                    String name = "_t" + (tmp++);
                    pre.add("ff1 " + name + " = " + r.code() + ";");
                    pre.add(base.code() + ".hi[" + i.code() + "] = " + name + ".hi;");
                    pre.add(base.code() + ".lo[" + i.code() + "] = " + name + ".lo;");
                    invalidate(root(a.lhs()));
                    return null;
                }
            }
            if ((e instanceof Post po && lvalueType(po.e()).ff()) || (e instanceof Un u && (u.op().equals("++") || u.op().equals("--")) && lvalueType(u.e()).ff())) {
                Ex target = e instanceof Post po ? po.e() : ((Un) e).e();
                String op = (e instanceof Post po ? po.op() : ((Un) e).op()).equals("++") ? "+" : "-";
                return expressionStatement(new Asg("=", target, new Bin(op, target, new Lit("1.0"))));
            }
            return expression(e);
        }

        /** An expression as code, temporaries allowed where pre is set. */
        String expression(Ex e) {
            if (e instanceof Comma c) {
                StringBuilder b = new StringBuilder();
                for (Ex x : c.es()) { if (b.length() > 0) b.append(", "); b.append(expression(x)); }
                return b.toString();
            }
            if (e instanceof Asg a && !a.op().equals("=")) {
                return expression(new Asg("=", a.lhs(), new Bin(a.op().substring(0, a.op().length() - 1), a.lhs(), a.rhs())));
            }
            return ex(e, pre != null).code();
        }

        /** Every variable assigned somewhere in a loop is stale for the cache inside it. */
        void invalidateAssignedIn(St s) {
            Set<String> a = new HashSet<>();
            assignedIn(s, a);
            for (String v : a) invalidate(v);
        }

        void assignedIn(Object node, Set<String> out) {
            switch (node) {
                case null -> { }
                case Asg a -> { out.add(root(a.lhs())); assignedIn(a.rhs(), out); assignedIn(a.lhs(), out); }
                case Post p -> out.add(root(p.e()));
                case Un u -> { if (u.op().equals("++") || u.op().equals("--")) out.add(root(u.e())); assignedIn(u.e(), out); }
                case Bin b -> { assignedIn(b.a(), out); assignedIn(b.b(), out); }
                case Tern t -> { assignedIn(t.c(), out); assignedIn(t.a(), out); assignedIn(t.b(), out); }
                case Call c -> {
                    for (Ex x : c.args()) {
                        assignedIn(x, out);
                        // an out or inout argument of a copied function
                        if (job.byName.containsKey(c.name())) {
                            String r = rootOrNull(x);
                            if (r != null) out.add(r);
                        }
                    }
                }
                case Member m -> assignedIn(m.e(), out);
                case Index i -> { assignedIn(i.e(), out); assignedIn(i.i(), out); }
                case Comma c -> { for (Ex x : c.es()) assignedIn(x, out); }
                case Block b -> { for (St x : b.body()) assignedIn(x, out); }
                case Decl d -> { for (Dcl v : d.vars()) { out.add(v.name()); assignedIn(v.init(), out); } }
                case ExprSt es -> assignedIn(es.e(), out);
                case If i -> { assignedIn(i.c(), out); assignedIn(i.a(), out); assignedIn(i.b(), out); }
                case For f -> { assignedIn(f.init(), out); assignedIn(f.cond(), out); assignedIn(f.incr(), out); assignedIn(f.body(), out); }
                case While w -> { assignedIn(w.c(), out); assignedIn(w.body(), out); }
                case DoWhile d -> { assignedIn(d.body(), out); assignedIn(d.c(), out); }
                case Switch sw -> { assignedIn(sw.e(), out); assignedIn(sw.body(), out); }
                case Ret r -> assignedIn(r.e(), out);
                default -> { }
            }
        }

        static String root(Ex e) {
            String r = rootOrNull(e);
            if (r == null) throw new Unsupported("assignment to " + e);
            return r;
        }

        static String rootOrNull(Ex e) {
            return switch (e) {
                case Id i -> i.name();
                case Member m -> rootOrNull(m.e());
                case Index i -> rootOrNull(i.e());
                default -> null;
            };
        }

        // ---- expressions -----------------------------------------------------------------

        static final Pattern SWIZZLE = Pattern.compile("[xyzw]{1,4}|[rgba]{1,4}|[stpq]{1,4}");

        static String swizzle(String f) {
            StringBuilder b = new StringBuilder();
            for (char c : f.toCharArray()) {
                int k = "xyzw".indexOf(c);
                if (k < 0) k = "rgba".indexOf(c);
                if (k < 0) k = "stpq".indexOf(c);
                b.append("xyzw".charAt(k));
            }
            return b.toString();
        }

        boolean isFFSwizzle(Member m) {
            Ty t = lvalueType(m.e());
            return t.ff() && !t.isStruct() && t.mat() == 0 && t.arr() == 0 && SWIZZLE.matcher(m.field()).matches();
        }

        Ty lvalueType(Ex e) {
            List<String> saved = pre;
            pre = null;
            try { return ex(e, false).ty(); } finally { pre = saved; }
        }

        /** An assignable expression as code (never a temporary). */
        Val lvalue(Ex e) {
            List<String> saved = pre;
            pre = null;
            try {
                Val v = ex(e, false);
                if (!v.pure()) throw new Unsupported("assignment through a call");
                return v;
            } finally { pre = saved; }
        }

        Val ex(Ex e, boolean hoist) {
            return switch (e) {
                case Lit l -> literal(l.text());
                case Id i -> new Val(i.name(), lookup(i.name()), true);
                case Un u -> unary(u, hoist);
                case Post p -> {
                    Val v = ex(p.e(), false);
                    if (v.ty().ff()) throw new Unsupported("++ on a float inside an expression");
                    invalidate(root(p.e()));
                    yield new Val(v.code() + p.op(), v.ty(), false);
                }
                case Bin b -> binary(b, hoist);
                case Tern t -> ternary(t, hoist);
                case Asg a -> assignment(a, hoist);
                case Call c -> call(c, hoist);
                case Member m -> member(m, hoist);
                case Index i -> index(i, hoist);
                case Comma c -> throw new Unsupported("comma inside an expression");
            };
        }

        Val literal(String text) {
            String t = text;
            if (t.endsWith("lf") || t.endsWith("LF")) throw new Unsupported("double literal");
            if (t.endsWith("f") || t.endsWith("F")) t = t.substring(0, t.length() - 1);
            if (t.startsWith("0x") || t.startsWith("0X")) return new Val(text, Ty.scalar(text.endsWith("u") || text.endsWith("U") ? "uint" : "int"), true);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return new Val(t, Ty.scalar("float"), true);
            if (t.endsWith("u") || t.endsWith("U")) return new Val(t, Ty.scalar("uint"), true);
            return new Val(t, Ty.scalar("int"), true);
        }

        Val unary(Un u, boolean hoist) {
            if (u.op().equals("++") || u.op().equals("--")) {
                Val v = ex(u.e(), false);
                if (v.ty().ff()) throw new Unsupported("++ on a float inside an expression");
                invalidate(root(u.e()));
                return new Val(u.op() + v.code(), v.ty(), false);
            }
            Val v = ex(u.e(), hoist);
            if (u.op().equals("+")) return v;
            if (u.op().equals("-") && v.ty().ff()) {
                if (v.ty().mat() > 0) throw new Unsupported("negated matrix");
                return held("ff_neg(" + v.code() + ")", v.ty(), v.pure(), hoist);
            }
            if (v.ty().ff()) throw new Unsupported(u.op() + " on a float-float");
            return new Val("(" + u.op() + v.code() + ")", v.ty(), v.pure());
        }

        Val binary(Bin b, boolean hoist) {
            String op = b.op();
            if (op.equals("&&") || op.equals("||") || op.equals("^^")) {
                Val x = ex(b.a(), hoist);
                Val y = ex(b.b(), op.equals("^^") && hoist);   // the right side of && and || may not run
                return new Val("(" + x.code() + " " + op + " " + y.code() + ")", Ty.scalar("bool"), x.pure() && y.pure());
            }
            Val x = ex(b.a(), hoist), y = ex(b.b(), hoist);
            boolean pure = x.pure() && y.pure();
            Ty tx = x.ty(), ty = y.ty();
            if (!tx.ff() && !ty.ff()) return new Val("(" + x.code() + " " + op + " " + y.code() + ")", arithType(op, tx, ty), pure);
            switch (op) {
                case "<", ">", "<=", ">=" -> {
                    if (tx.n() != 1 || ty.n() != 1 || tx.mat() > 0 || ty.mat() > 0) throw new Unsupported("vector comparison " + op);
                    String f = switch (op) { case "<" -> "ff_lt"; case ">" -> "ff_gt"; case "<=" -> "ff_le"; default -> "ff_ge"; };
                    return new Val(f + "(" + scalarArg(x) + ", " + scalarArg(y) + ")", Ty.scalar("bool"), pure);
                }
                case "==", "!=" -> {
                    Ty t = unify(tx, ty);
                    String f = op.equals("==") ? "ff_eq" : "ff_ne";
                    return new Val(f + "(" + to(x, t).code() + ", " + to(y, t).code() + ")", Ty.scalar("bool"), pure);
                }
                case "+", "-", "*", "/" -> { return arith(op, x, y, hoist); }
                default -> throw new Unsupported("operator " + op + " on a float-float");
            }
        }

        /** A scalar operand of a float-float comparison: ff1 or a float. */
        String scalarArg(Val v) {
            if (v.ty().ff()) return v.code();
            if (v.ty().base().equals("float")) return v.code();
            return "float(" + v.code() + ")";
        }

        Val arith(String op, Val x, Val y, boolean hoist) {
            String f = switch (op) { case "+" -> "ff_add"; case "-" -> "ff_sub"; case "*" -> "ff_mul"; default -> "ff_div"; };
            Ty tx = x.ty(), ty = y.ty();
            boolean pure = x.pure() && y.pure();
            // matrices
            if (tx.mat() > 0 || ty.mat() > 0) {
                if (!op.equals("*")) throw new Unsupported("matrix " + op);
                if (tx.mat() > 0 && ty.mat() == 0 && ty.n() == tx.mat()) {
                    if (tx.ff() && tx.mat() != 2) throw new Unsupported("float-float matrix " + tx.mat());
                    Ty r = Ty.scalar("float").withN(tx.mat()).withFF(true);
                    return held(f + "(" + x.code() + ", " + to(y, tx.ff() ? r : (ty.ff() ? ty : r)).code() + ")", r, pure, hoist);
                }
                if (ty.mat() > 0 && tx.mat() == 0 && tx.n() == ty.mat()) {
                    if (ty.ff() && ty.mat() != 2) throw new Unsupported("float-float matrix " + ty.mat());
                    Ty r = Ty.scalar("float").withN(ty.mat()).withFF(true);
                    return held(f + "(" + to(x, r).code() + ", " + y.code() + ")", r, pure, hoist);
                }
                throw new Unsupported("matrix product " + tx.glsl() + " * " + ty.glsl());
            }
            if (tx.isStruct() || ty.isStruct() || tx.arr() != 0 || ty.arr() != 0) throw new Unsupported("arithmetic on " + tx.glsl());
            String a = floatCode(x), b = floatCode(y);
            int n = Math.max(tx.n(), ty.n());
            if (tx.n() != ty.n() && tx.n() != 1 && ty.n() != 1) throw new Unsupported("vector sizes " + tx.n() + ", " + ty.n());
            Ty r = Ty.scalar("float").withN(n).withFF(true);
            return held(f + "(" + a + ", " + b + ")", r, pure, hoist);
        }

        /** An operand as a float-family code: ints converted. */
        static String floatCode(Val v) {
            if (v.ty().ff() || v.ty().isFloat()) return v.code();
            if (v.ty().base().equals("int") || v.ty().base().equals("uint")) return (v.ty().n() == 1 ? "float" : "vec" + v.ty().n()) + "(" + v.code() + ")";
            throw new Unsupported("arithmetic on " + v.ty().glsl());
        }

        static Ty arithType(String op, Ty a, Ty b) {
            switch (op) {
                case "<", ">", "<=", ">=", "==", "!=": return Ty.scalar("bool");
                default:
            }
            String base = a.base().equals("float") || b.base().equals("float") ? "float" : a.base();
            if (a.mat() > 0 && b.mat() > 0) return a;
            if (a.mat() > 0) return b.n() > 1 ? new Ty(base, a.mat(), 0, false, 0) : a;
            if (b.mat() > 0) return a.n() > 1 ? new Ty(base, b.mat(), 0, false, 0) : b;
            return new Ty(base, Math.max(a.n(), b.n()), 0, false, 0);
        }

        /** The common type of the two sides of a ternary, of an equality. */
        static Ty unify(Ty a, Ty b) {
            if (a.isStruct() || b.isStruct()) {
                if (!a.base().equals(b.base())) throw new Unsupported("ternary of " + a.base() + " and " + b.base());
                return a.ff() || b.ff() ? a.withFF(true) : a;
            }
            if (a.base().equals("bool") || b.base().equals("bool")) return a;
            int n = Math.max(a.n(), b.n());
            int mat = Math.max(a.mat(), b.mat());
            String base = a.base().equals("float") || b.base().equals("float") ? "float" : a.base();
            return new Ty(base, n, mat, a.ff() || b.ff(), 0);
        }

        Val ternary(Tern t, boolean hoist) {
            Val c = to(ex(t.c(), hoist), Ty.scalar("bool"));
            Val a = ex(t.a(), false), b = ex(t.b(), false);
            Ty u = unify(a.ty(), b.ty());
            return new Val("(" + c.code() + " ? " + to(a, u).code() + " : " + to(b, u).code() + ")", u, c.pure() && a.pure() && b.pure());
        }

        Val assignment(Asg a, boolean hoist) {
            if (!a.op().equals("=")) return assignment(new Asg("=", a.lhs(), new Bin(a.op().substring(0, a.op().length() - 1), a.lhs(), a.rhs())), hoist);
            if (a.lhs() instanceof Member m && isFFSwizzle(m)) throw new Unsupported("assignment to a float-float swizzle inside an expression");
            Val l = lvalue(a.lhs());
            if (l.ty().ff() && a.lhs() instanceof Index ix && lvalueType(ix.e()).arr() == 0) throw new Unsupported("indexed assignment inside an expression");
            Val r = to(ex(a.rhs(), hoist), l.ty());
            invalidate(root(a.lhs()));
            return new Val("(" + l.code() + " = " + r.code() + ")", l.ty(), false);
        }

        Val member(Member m, boolean hoist) {
            Val b = ex(m.e(), hoist);
            Ty t = b.ty();
            if (t.isStruct()) {
                Map<String, Ty> f = job.fields.get(t.base());
                if (f == null || !f.containsKey(m.field())) throw new Unsupported("field " + m.field() + " of " + t.base());
                Ty ft = f.get(m.field());
                if (t.ff()) ft = translated(ft, job.ffStructs);
                return new Val(b.code() + "." + m.field(), ft, b.pure());
            }
            if (!SWIZZLE.matcher(m.field()).matches()) throw new Unsupported("member " + m.field());
            String sw = swizzle(m.field());
            if (t.mat() > 0) throw new Unsupported("swizzle of a matrix");
            if (!t.ff()) return new Val(b.code() + "." + m.field(), new Ty(t.base(), sw.length(), 0, false, 0), b.pure());
            if (t.n() == 1) {
                if (!sw.equals("x")) throw new Unsupported("swizzle ." + sw + " of a scalar");
                return b;
            }
            if (sw.length() == 1) return held("ff_" + sw + "(" + b.code() + ")", Ty.scalar("float").withFF(true), b.pure(), hoist);
            String name = "ff_sw_" + sw;
            String key = name + "/" + t.n();
            int k = sw.length();
            job.helpers.computeIfAbsent(key, x -> "ff" + k + " " + name + "(ff" + t.n() + " a) { return ff" + k + "(a.hi." + sw + ", a.lo." + sw + "); }\n");
            return held(name + "(" + b.code() + ")", Ty.scalar("float").withN(k).withFF(true), b.pure(), hoist);
        }

        Val index(Index i, boolean hoist) {
            Val b = ex(i.e(), hoist);
            Val k = ex(i.i(), hoist);
            Ty t = b.ty();
            if (t.arr() != 0) return new Val(b.code() + "[" + k.code() + "]", t.elem(), b.pure() && k.pure());
            if (t.mat() > 0) {
                if (t.ff()) return new Val("ff2(" + b.code() + ".hi[" + k.code() + "], " + b.code() + ".lo[" + k.code() + "])", Ty.scalar("float").withN(2).withFF(true), b.pure() && k.pure());
                return new Val(b.code() + "[" + k.code() + "]", new Ty("float", t.mat(), 0, false, 0), b.pure() && k.pure());
            }
            if (t.n() > 1) {
                if (t.ff()) return new Val("ff1(" + b.code() + ".hi[" + k.code() + "], " + b.code() + ".lo[" + k.code() + "])", Ty.scalar("float").withFF(true), b.pure() && k.pure());
                return new Val(b.code() + "[" + k.code() + "]", Ty.scalar(t.base()), b.pure() && k.pure());
            }
            throw new Unsupported("index of " + t.glsl());
        }

        // ---- conversions -----------------------------------------------------------------

        /** {@code v} as type {@code t}: promoted to float-float, splatted, ints made float. */
        Val to(Val v, Ty t) {
            Ty s = v.ty();
            if (s.equals(t)) return v;
            if (t.isStruct() || s.isStruct()) {
                if (s.base().equals(t.base()) && s.ff() == t.ff()) return v;
                throw new Unsupported("struct " + s.glsl() + " as " + t.glsl());
            }
            if (t.arr() != 0 || s.arr() != 0) {
                if (s.base().equals(t.base()) && s.n() == t.n() && s.ff() == t.ff()) return v;
                throw new Unsupported("array " + s.glsl() + " as " + t.glsl());
            }
            if (t.base().equals("bool")) {
                if (s.base().equals("bool") && s.n() == t.n()) return v;
                throw new Unsupported(s.glsl() + " as bool");
            }
            if (t.mat() > 0 || s.mat() > 0) {
                if (s.mat() == t.mat() && !s.ff() && t.ff() && t.mat() == 2) {
                    String c = v.code();
                    return new Val("ffm2(" + c + ", mat2(0.0))", t, v.pure());
                }
                if (s.mat() == t.mat() && s.ff() == t.ff()) return v;
                throw new Unsupported(s.glsl() + " as " + t.glsl());
            }
            if (!t.isFloat()) {
                // int or uint wanted: GLSL converts int to uint only explicitly; keep what was written
                if (!s.ff() && s.isNum()) return v;
                throw new Unsupported(s.glsl() + " as " + t.glsl());
            }
            String c = v.code();
            if (!t.ff()) {
                if (s.ff()) {
                    if (s.n() != t.n()) throw new Unsupported(s.glsl() + " as " + t.glsl());
                    return new Val("ff_tofloat(" + c + ")", t, v.pure());
                }
                if (s.isFloat()) {
                    if (s.n() == t.n()) return v;
                    if (s.n() == 1) return new Val("vec" + t.n() + "(" + c + ")", t, v.pure());
                }
                if (s.base().equals("int") || s.base().equals("uint")) return new Val((t.n() == 1 ? "float" : "vec" + t.n()) + "(" + c + ")", t, v.pure());
                throw new Unsupported(s.glsl() + " as " + t.glsl());
            }
            // a float-float wanted
            if (s.ff()) {
                if (s.n() == t.n()) return v;
                if (s.n() == 1) return new Val("ff_splat" + t.n() + "(" + c + ")", t, v.pure());
                throw new Unsupported(s.glsl() + " as " + t.glsl());
            }
            if (s.base().equals("int") || s.base().equals("uint")) c = (s.n() == 1 ? "float" : "vec" + s.n()) + "(" + c + ")";
            else if (!s.isFloat()) throw new Unsupported(s.glsl() + " as " + t.glsl());
            if (s.n() == t.n()) return new Val("ff_from(" + c + ")", t, v.pure());
            if (s.n() == 1) return new Val("ff_from(vec" + t.n() + "(" + c + "))", t, v.pure());
            throw new Unsupported(s.glsl() + " as " + t.glsl());
        }

        // ---- calls -------------------------------------------------------------------------

        Val call(Call c, boolean hoist) {
            String name = c.name();
            if (job.functionMacros.contains(name)) throw new Unsupported("function-like macro " + name);
            if (job.byName.containsKey(name)) return userCall(c, hoist);
            if (BUILTIN_TYPES.contains(name)) return constructor(name, c.args(), hoist);
            if (job.structNames.contains(name)) return structConstructor(name, c.args(), hoist);
            List<Val> args = new ArrayList<>();
            for (Ex a : c.args()) args.add(ex(a, hoist));
            boolean anyFF = args.stream().anyMatch(v -> v.ty().ff());
            boolean pure = args.stream().allMatch(Val::pure);
            if (!anyFF) return new Val(name + "(" + join(args) + ")", builtinType(name, args), pure);
            return ffBuiltin(name, args, pure, hoist);
        }

        static String join(List<Val> args) {
            StringBuilder b = new StringBuilder();
            for (Val v : args) { if (b.length() > 0) b.append(", "); b.append(v.code()); }
            return b.toString();
        }

        Val userCall(Call c, boolean hoist) {
            List<Fn> cands = job.byName.get(c.name());
            List<Val> args = new ArrayList<>();
            for (Ex a : c.args()) args.add(ex(a, hoist));
            Fn chosen = null;
            int best = -1;
            for (Fn f : cands) {
                if (f.params().size() != args.size()) continue;
                int score = 0;
                boolean ok = true;
                for (int i = 0; i < args.size() && ok; i++) {
                    Ty p = f.params().get(i).ty(), a = args.get(i).ty();
                    if (p.isStruct() || a.isStruct()) { ok = p.base().equals(a.base()); score += 2; continue; }
                    if (p.n() != a.n() || p.mat() != a.mat() || (p.arr() == 0) != (a.arr() == 0)) { ok = false; continue; }
                    if (p.base().equals(a.base())) score += 2;
                    else if (p.isFloat() && (a.base().equals("int") || a.base().equals("uint"))) score += 1;
                    else ok = false;
                }
                if (ok && score > best) { best = score; chosen = f; }
            }
            if (chosen == null) throw new Unsupported("no overload of " + c.name() + " for these arguments");
            StringBuilder b = new StringBuilder(c.name()).append(SUFFIX).append('(');
            for (int i = 0; i < args.size(); i++) {
                Param p = chosen.params().get(i);
                Ty pt = translated(p.ty(), job.ffStructs);
                if (i > 0) b.append(", ");
                if (p.qual().equals("in")) b.append(to(args.get(i), pt).code());
                else {
                    Val a = args.get(i);
                    if (!a.ty().equals(pt) && !(a.ty().arr() != 0 && a.ty().elem().equals(pt.elem())))
                        throw new Unsupported(p.qual() + " argument " + a.ty().glsl() + " for " + pt.glsl() + " of " + c.name());
                    b.append(a.code());
                }
            }
            b.append(')');
            for (int i = 0; i < args.size(); i++) {
                if (!chosen.params().get(i).qual().equals("in")) {
                    String r = rootOrNull(c.args().get(i));
                    if (r == null) throw new Unsupported("out argument that is not a variable");
                    invalidate(r);
                }
            }
            return new Val(b.toString(), translated(chosen.ret(), job.ffStructs), false);
        }

        Val constructor(String name, List<Ex> argEx, boolean hoist) {
            Ty t = parseType(name, job.structNames);
            List<Val> args = new ArrayList<>();
            for (Ex a : argEx) args.add(ex(a, hoist));
            boolean pure = args.stream().allMatch(Val::pure);
            boolean anyFF = args.stream().anyMatch(v -> v.ty().ff());
            if (!anyFF) return new Val(name + "(" + join(args) + ")", t, pure);
            if (t.mat() > 0) {
                if (t.mat() != 2 || args.size() != 4) throw new Unsupported("float-float " + name + " of " + args.size() + " arguments");
                StringBuilder b = new StringBuilder("ff_mat2(");
                for (int i = 0; i < 4; i++) {
                    if (args.get(i).ty().n() != 1) throw new Unsupported("mat2 of vectors");
                    b.append(i > 0 ? ", " : "").append(to(args.get(i), Ty.scalar("float").withFF(true)).code());
                }
                return held(b.append(')').toString(), t.withFF(true), pure, hoist);
            }
            if (!t.isFloat()) {
                // int(x), uint(x), bool(x) of a float-float: of its value, truncated
                if (args.size() != 1) throw new Unsupported(name + " of several float-floats");
                Val a = args.get(0);
                if (t.base().equals("bool")) return new Val(name + "(" + a.code() + ".hi)", t, pure);
                return new Val(name + "(ff_tofloat(ff_trunc(" + a.code() + ")))", t, pure);
            }
            if (t.n() == 1) {
                if (args.size() != 1) throw new Unsupported("float of several values");
                Val a = args.get(0);
                if (a.ty().n() == 1) return a;
                return held("ff_x(" + a.code() + ")", Ty.scalar("float").withFF(true), pure, hoist);
            }
            // vecN of float-floats: every argument made a float-float, put together by a helper
            List<String> codes = new ArrayList<>();
            List<Integer> dims = new ArrayList<>();
            for (Val a : args) {
                Ty at = a.ty();
                if (at.mat() > 0 || at.isStruct() || at.base().equals("bool")) throw new Unsupported("vec of " + at.glsl());
                Ty want = Ty.scalar("float").withN(at.n()).withFF(true);
                codes.add(to(a, want).code());
                dims.add(at.n());
            }
            StringBuilder sig = new StringBuilder(), hi = new StringBuilder(), lo = new StringBuilder();
            for (int i = 0; i < dims.size(); i++) {
                if (i > 0) { sig.append(", "); hi.append(", "); lo.append(", "); }
                sig.append("ff").append(dims.get(i)).append(" a").append(i);
                hi.append('a').append(i).append(".hi");
                lo.append('a').append(i).append(".lo");
            }
            String fname = "ff_vec" + t.n();
            String key = fname + "(" + sig + ")";
            String vt = "vec" + t.n();
            job.helpers.computeIfAbsent(key, x -> "ff" + t.n() + " " + fname + "(" + sig + ") { return ff" + t.n() + "(" + vt + "(" + hi + "), " + vt + "(" + lo + ")); }\n");
            return held(fname + "(" + String.join(", ", codes) + ")", t.withFF(true), pure, hoist);
        }

        Val structConstructor(String name, List<Ex> argEx, boolean hoist) {
            Map<String, Ty> f = job.fields.get(name);
            List<Val> args = new ArrayList<>();
            for (Ex a : argEx) args.add(ex(a, hoist));
            boolean pure = args.stream().allMatch(Val::pure);
            boolean ffStruct = job.ffStructs.contains(name);
            if (args.size() != f.size()) throw new Unsupported("constructor of " + name);
            List<String> codes = new ArrayList<>();
            int i = 0;
            for (Ty ft : f.values()) {
                Ty want = ffStruct ? translated(ft, job.ffStructs) : ft;
                codes.add(to(args.get(i++), want).code());
            }
            return new Val((ffStruct ? name + SUFFIX : name) + "(" + String.join(", ", codes) + ")", Ty.scalar(name).withFF(ffStruct), pure);
        }

        /** A built-in with a float-float argument. */
        Val ffBuiltin(String name, List<Val> args, boolean pure, boolean hoist) {
            int n = 1;
            for (Val a : args) if (a.ty().ff() || a.ty().isFloat()) n = Math.max(n, a.ty().n());
            Ty r = Ty.scalar("float").withN(n).withFF(true);
            Ty r1 = Ty.scalar("float").withFF(true);
            switch (name) {
                case "sin", "cos" -> {
                    if (args.size() != 1) break;
                    Val a = args.get(0);
                    if (a.ty().n() == 1 && hoist && pre != null && a.pure()) {
                        String key = "ff_sincos(" + a.code() + ")";
                        Held h = cache.get(key);
                        if (h == null) {
                            String s = "_t" + (tmp++), co = "_t" + (tmp++);
                            pre.add("ff1 " + s + ", " + co + ";");
                            pre.add("ff_sincos(" + a.code() + ", " + s + ", " + co + ");");
                            h = new Held(s + "," + co, reads(a.code()));
                            cache.put(key, h);
                            heldByName.put(s, h);
                            heldByName.put(co, h);
                            cacheLevels.peek().add(key);
                        }
                        String[] two = h.name().split(",");
                        return new Val(name.equals("sin") ? two[0] : two[1], r1, true);
                    }
                    return held("ff_" + name + "(" + a.code() + ")", a.ty(), pure, hoist);
                }
                case "length", "distance", "dot" -> {
                    List<String> c = new ArrayList<>();
                    for (Val a : args) c.add(to(a, r).code());
                    return held("ff_" + name + "(" + String.join(", ", c) + ")", r1, pure, hoist);
                }
                case "normalize", "reflect" -> {
                    List<String> c = new ArrayList<>();
                    for (Val a : args) c.add(to(a, r).code());
                    return held("ff_" + name + "(" + String.join(", ", c) + ")", r, pure, hoist);
                }
                case "cross" -> {
                    Ty r3 = Ty.scalar("float").withN(3).withFF(true);
                    return held("ff_cross(" + to(args.get(0), r3).code() + ", " + to(args.get(1), r3).code() + ")", r3, pure, hoist);
                }
                case "fma" -> {
                    Val p = arith("*", args.get(0), args.get(1), hoist);
                    return arith("+", p, args.get(2), hoist);
                }
                case "floatBitsToInt", "floatBitsToUint" -> {
                    Val a = args.get(0);
                    String rb = name.equals("floatBitsToInt") ? "int" : "uint";
                    return new Val(name + "(ff_tofloat(" + a.code() + "))", new Ty(rb, a.ty().n(), 0, false, 0), pure);
                }
                case "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual" -> {
                    Ty fl = Ty.scalar("float").withN(n);
                    return new Val(name + "(" + to(args.get(0), fl).code() + ", " + to(args.get(1), fl).code() + ")", new Ty("bool", n, 0, false, 0), pure);
                }
                case "isnan", "isinf" -> {
                    Val a = args.get(0);
                    return new Val(name + "(" + a.code() + ".hi)", new Ty("bool", a.ty().n(), 0, false, 0), pure);
                }
                case "mix" -> {
                    if (args.size() == 3 && args.get(2).ty().base().equals("bool")) {
                        if (args.get(2).ty().n() != 1) throw new Unsupported("mix with a bvec");
                        Ty u = unify(args.get(0).ty(), args.get(1).ty()).withFF(true);
                        return new Val("(" + args.get(2).code() + " ? " + to(args.get(1), u).code() + " : " + to(args.get(0), u).code() + ")", u, pure);
                    }
                }
                default -> { }
            }
            if (UNARY.contains(name) && args.size() == 1) {
                Val a = args.get(0);
                return held("ff_" + name + "(" + to(a, r).code() + ")", r, pure, hoist);
            }
            if (GEN_BINARY.contains(name) && args.size() == 2) {
                Val a = args.get(0), b = args.get(1);
                if (name.equals("pow") && !b.ty().ff() && b.ty().isFloat() && b.ty().n() == a.ty().n())
                    return held("ff_pow(" + to(a, r).code() + ", " + b.code() + ")", r, pure, hoist);
                if (name.equals("pow") && !b.ty().ff() && b.ty().isFloat() && b.ty().n() == 1 && n == 1)
                    return held("ff_pow(" + to(a, r).code() + ", " + b.code() + ")", r, pure, hoist);
                return held("ff_" + name + "(" + to(a, r).code() + ", " + to(b, r).code() + ")", r, pure, hoist);
            }
            if (GEN_TERNARY.contains(name) && args.size() == 3) {
                return held("ff_" + name + "(" + to(args.get(0), r).code() + ", " + to(args.get(1), r).code() + ", " + to(args.get(2), r).code() + ")", r, pure, hoist);
            }
            throw new Unsupported("built-in " + name + " on a float-float");
        }

        /** The type of a built-in call on plain values. */
        Ty builtinType(String name, List<Val> args) {
            Ty first = args.isEmpty() ? Ty.scalar("float") : args.get(0).ty();
            int n = 1;
            String base = first.base();
            for (Val a : args) {
                if (a.ty().mat() == 0) n = Math.max(n, a.ty().n());
                if (a.ty().isFloat()) base = "float";
            }
            switch (name) {
                case "length", "distance", "dot", "determinant": return Ty.scalar("float");
                case "cross": return new Ty("float", 3, 0, false, 0);
                case "normalize", "reflect", "refract", "faceforward", "transpose", "inverse", "matrixCompMult", "dFdx", "dFdy", "fwidth": return first;
                case "any", "all": return Ty.scalar("bool");
                case "not": return first;
                case "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual", "isnan", "isinf":
                    return new Ty("bool", first.n(), 0, false, 0);
                case "floatBitsToInt": return new Ty("int", first.n(), 0, false, 0);
                case "floatBitsToUint": return new Ty("uint", first.n(), 0, false, 0);
                case "intBitsToFloat", "uintBitsToFloat": return new Ty("float", first.n(), 0, false, 0);
                case "texture", "textureLod", "texelFetch", "textureGrad": return new Ty("float", 4, 0, false, 0);
                case "step": return args.get(1).ty();
                case "smoothstep": return args.get(2).ty();
                case "bitCount", "findLSB", "findMSB": return new Ty("int", first.n(), 0, false, 0);
                default:
            }
            if (UNARY.contains(name) || GEN_BINARY.contains(name) || GEN_TERNARY.contains(name) || name.equals("fma") || name.equals("roundEven")
                    || name.equals("bitfieldExtract") || name.equals("bitfieldInsert") || name.equals("bitfieldReverse")) {
                return new Ty(base, n, 0, false, 0);
            }
            throw new Unsupported("built-in " + name);
        }
    }
}
