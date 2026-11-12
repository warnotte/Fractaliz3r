package org.fractalizer.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Float-float arithmetic for the shaders: a number held as two floats, {@code hi + lo} with
 * {@code |lo| <= ulp(hi) / 2}, about 48 bits of mantissa (a double has 53). On a GeForce a
 * double operation costs as much as 64 float ones; a float-float addition is about 20 float
 * operations and a multiplication about 7. FloatFloatProbe measured a Mandelbulb estimate at
 * 16.4 ns in double and 1.85 ns in float-float on an RTX 5070 Ti (0.097 ns in float).
 *
 * <p>Types {@code ff1 ff2 ff3 ff4} (struct of {@code hi}, {@code lo}: float, vec2, vec3, vec4)
 * and {@code ffm2} (a 2x2 matrix). Every function is overloaded on them: arithmetic
 * {@code ff_add ff_sub ff_mul ff_div ff_neg} (also against a plain float operand of the same
 * shape, and a vector against a scalar), comparisons {@code ff_lt ff_gt ff_le ff_ge ff_eq
 * ff_ne}, and the GLSL built-ins as {@code ff_<name>}. {@code ff_from} makes one from floats,
 * {@code ff_fromd} from doubles, {@code ff_tofloat} rounds one back.
 *
 * <p>The error-free transformations it rests on (the rounding error of a sum, of a product
 * through {@code fma}) hold only if the compiler evaluates them as written: every such step
 * is {@code precise}, which forbids reassociating and contracting it. Whether the compiler
 * honours that is checked on the GPU by {@code FloatFloatProbe}, every function against
 * StrictMath.
 */
public final class FloatFloat {

    private FloatFloat() {}

    private static final MathContext MC = new MathContext(60);
    private static final BigDecimal PI = new BigDecimal(
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899863");
    private static final BigDecimal LN2 = new BigDecimal(
            "0.69314718055994530941723212145817656807550013436025525412068000949339362196969471560586");

    static final String[] T = {"", "float", "vec2", "vec3", "vec4"};
    static final String[] XYZW = {"x", "y", "z", "w"};

    /** A float literal GLSL reads back as exactly this float. */
    static String lit(float f) {
        if (f == 0f) return "0.0";
        if (Float.isInfinite(f)) return f > 0 ? "3.4028235e38" : "-3.4028235e38";
        String s = Float.toString(f);
        return s.replace("E", "e");
    }

    static float[] split(BigDecimal v) {
        float hi = v.floatValue();
        float lo = v.subtract(new BigDecimal(hi), MC).floatValue();
        return new float[]{hi, lo};
    }

    /** An ff1 constant. */
    static String c1(BigDecimal v) {
        float[] s = split(v);
        return "ff1(" + lit(s[0]) + ", " + lit(s[1]) + ")";
    }

    static BigDecimal inv(long n) { return BigDecimal.ONE.divide(BigDecimal.valueOf(n), MC); }

    static float[] three(BigDecimal v) {
        float a = v.floatValue();
        BigDecimal r = v.subtract(new BigDecimal(a), MC);
        float b = r.floatValue();
        float c = r.subtract(new BigDecimal(b), MC).floatValue();
        return new float[]{a, b, c};
    }

    /** The whole library. */
    public static String library() {
        StringBuilder b = new StringBuilder();
        b.append("// ==== float-float arithmetic (FloatFloat): x = hi + lo, two floats, ~48 bits ====\n");
        for (int n = 1; n <= 4; n++) b.append("struct ff").append(n).append(" { ").append(T[n]).append(" hi; ").append(T[n]).append(" lo; };\n");
        b.append("struct ffm2 { mat2 hi; mat2 lo; };\n");
        for (int n = 1; n <= 4; n++) b.append(core(n));
        for (int n = 2; n <= 4; n++) b.append(vectorScalar(n));
        b.append(scalarFunctions());
        for (int n = 2; n <= 4; n++) b.append(vectorFunctions(n));
        b.append(geometry());
        return b.toString();
    }

    /** Arithmetic on ffN, and ffN against a plain vecN of the same shape. */
    static String core(int n) {
        String s = "ff" + n, t = T[n];
        String src = """
                S ff_from(T a) { return S(a, T(0.0)); }
                S ff_fromd(DT a) { T h = T(a); return S(h, T(a - DT(h))); }
                T ff_tofloat(S a) { return a.hi + a.lo; }
                S ff_qts(T a, T b) { precise T s = a + b; precise T e = b - (s - a); return S(s, e); }
                S ff_ts(T a, T b) {
                    precise T s = a + b; precise T v = s - a; precise T e = (a - (s - v)) + (b - v);
                    return S(s, e);
                }
                S ff_tp(T a, T b) { precise T p = a * b; precise T e = fma(a, b, -p); return S(p, e); }
                S ff_neg(S a) { return S(-a.hi, -a.lo); }
                S ff_add(S a, S b) {
                    S s = ff_ts(a.hi, b.hi);
                    S t = ff_ts(a.lo, b.lo);
                    precise T u = s.lo + t.hi;
                    s = ff_qts(s.hi, u);
                    precise T w = s.lo + t.lo;
                    return ff_qts(s.hi, w);
                }
                S ff_add(S a, T b) { S s = ff_ts(a.hi, b); precise T u = s.lo + a.lo; return ff_qts(s.hi, u); }
                S ff_add(T a, S b) { return ff_add(b, a); }
                S ff_sub(S a, S b) { return ff_add(a, ff_neg(b)); }
                S ff_sub(S a, T b) { return ff_add(a, -b); }
                S ff_sub(T a, S b) { return ff_add(ff_neg(b), a); }
                S ff_mul(S a, S b) {
                    S p = ff_tp(a.hi, b.hi);
                    precise T e = fma(a.hi, b.lo, fma(a.lo, b.hi, p.lo));
                    return ff_qts(p.hi, e);
                }
                S ff_mul(S a, T b) { S p = ff_tp(a.hi, b); precise T e = fma(a.lo, b, p.lo); return ff_qts(p.hi, e); }
                S ff_mul(T a, S b) { return ff_mul(b, a); }
                S ff_sqr(S a) { S p = ff_tp(a.hi, a.hi); precise T e = fma(a.hi + a.hi, a.lo, p.lo); return ff_qts(p.hi, e); }
                S ff_div(S a, S b) {
                    T inv = 1.0 / b.hi;
                    T q1 = a.hi * inv;
                    S r = ff_sub(a, ff_mul(b, q1));
                    T q2 = r.hi * inv;
                    r = ff_sub(r, ff_mul(b, q2));
                    T q3 = r.hi * inv;
                    return ff_add(ff_qts(q1, q2), q3);
                }
                S ff_div(S a, T b) { return ff_div(a, ff_from(b)); }
                S ff_div(T a, S b) { return ff_div(ff_from(a), b); }
                bool ff_eq(S a, S b) { return a.hi == b.hi && a.lo == b.lo; }
                bool ff_ne(S a, S b) { return !ff_eq(a, b); }
                """;
        // S and T are the only capitals in the template
        return src.replace("DT", n == 1 ? "double" : "dvec" + n).replace("S", s).replace("T", t);
    }

    /** ffN against a scalar (ff1 or float), both orders, and the splat. */
    static String vectorScalar(int n) {
        String s = "ff" + n, t = T[n];
        StringBuilder b = new StringBuilder();
        b.append(s).append(" ff_splat").append(n).append("(ff1 a) { return ").append(s).append('(').append(t).append("(a.hi), ").append(t).append("(a.lo)); }\n");
        for (String op : new String[]{"add", "sub", "mul", "div"}) {
            b.append(s).append(" ff_").append(op).append('(').append(s).append(" a, ff1 b) { return ff_").append(op).append("(a, ff_splat").append(n).append("(b)); }\n");
            b.append(s).append(" ff_").append(op).append("(ff1 a, ").append(s).append(" b) { return ff_").append(op).append("(ff_splat").append(n).append("(a), b); }\n");
            b.append(s).append(" ff_").append(op).append('(').append(s).append(" a, float b) { return ff_").append(op).append("(a, ").append(t).append("(b)); }\n");
            b.append(s).append(" ff_").append(op).append("(float a, ").append(s).append(" b) { return ff_").append(op).append('(').append(t).append("(a), b); }\n");
            b.append(s).append(" ff_").append(op).append('(').append(t).append(" a, ff1 b) { return ff_").append(op).append("(ff_from(a), ff_splat").append(n).append("(b)); }\n");
            b.append(s).append(" ff_").append(op).append("(ff1 a, ").append(t).append(" b) { return ff_").append(op).append("(ff_splat").append(n).append("(a), ff_from(b)); }\n");
        }
        for (int k = 0; k < n; k++) {
            b.append("ff1 ff_").append(XYZW[k]).append('(').append(s).append(" a) { return ff1(a.hi.").append(XYZW[k]).append(", a.lo.").append(XYZW[k]).append("); }\n");
        }
        return b.toString();
    }

    static String scalarFunctions() {
        BigDecimal pio2 = PI.divide(BigDecimal.valueOf(2), MC);
        float[] p = three(pio2);
        float twoOverPi = BigDecimal.valueOf(2).divide(PI, MC).floatValue();
        float[] l = three(LN2);
        float invLn2 = BigDecimal.ONE.divide(LN2, MC).floatValue();
        long[] sinDen = {-6L, 120L, -5040L, 362880L, -39916800L, 6227020800L, -1307674368000L, 355687428096000L};
        long[] cosDen = {24L, -720L, 40320L, -3628800L, 479001600L, -87178291200L, 20922789888000L};
        long[] fact = new long[14];
        fact[0] = 1;
        for (int i = 1; i < 14; i++) fact[i] = fact[i - 1] * i;

        StringBuilder b = new StringBuilder();
        b.append("""
                bool ff_lt(ff1 a, ff1 b) { return a.hi < b.hi || (a.hi == b.hi && a.lo < b.lo); }
                bool ff_lt(ff1 a, float b) { return a.hi < b || (a.hi == b && a.lo < 0.0); }
                bool ff_lt(float a, ff1 b) { return a < b.hi || (a == b.hi && 0.0 < b.lo); }
                bool ff_gt(ff1 a, ff1 b) { return ff_lt(b, a); }
                bool ff_gt(ff1 a, float b) { return ff_lt(b, a); }
                bool ff_gt(float a, ff1 b) { return ff_lt(b, a); }
                bool ff_le(ff1 a, ff1 b) { return !ff_lt(b, a); }
                bool ff_le(ff1 a, float b) { return !ff_lt(b, a); }
                bool ff_le(float a, ff1 b) { return !ff_lt(b, a); }
                bool ff_ge(ff1 a, ff1 b) { return !ff_lt(a, b); }
                bool ff_ge(ff1 a, float b) { return !ff_lt(a, b); }
                bool ff_ge(float a, ff1 b) { return !ff_lt(a, b); }
                bool ff_eq(ff1 a, float b) { return a.hi == b && a.lo == 0.0; }
                bool ff_eq(float a, ff1 b) { return ff_eq(b, a); }
                bool ff_ne(ff1 a, float b) { return !ff_eq(a, b); }
                bool ff_ne(float a, ff1 b) { return !ff_eq(b, a); }
                ff1 ff_abs(ff1 a) { return a.hi < 0.0 ? ff_neg(a) : a; }
                ff1 ff_sign(ff1 a) { return ff_from(a.hi > 0.0 ? 1.0 : (a.hi < 0.0 ? -1.0 : 0.0)); }
                ff1 ff_floor(ff1 a) {
                    float f = floor(a.hi);
                    if (f == a.hi) return ff_qts(f, floor(a.lo));
                    return ff1(f, 0.0);
                }
                ff1 ff_ceil(ff1 a) { return ff_neg(ff_floor(ff_neg(a))); }
                ff1 ff_trunc(ff1 a) { return a.hi < 0.0 ? ff_ceil(a) : ff_floor(a); }
                ff1 ff_round(ff1 a) { return ff_floor(ff_add(a, 0.5)); }
                ff1 ff_fract(ff1 a) { return ff_sub(a, ff_floor(a)); }
                ff1 ff_mod(ff1 a, ff1 b) { return ff_sub(a, ff_mul(b, ff_floor(ff_div(a, b)))); }
                ff1 ff_min(ff1 a, ff1 b) { return ff_lt(b, a) ? b : a; }
                ff1 ff_max(ff1 a, ff1 b) { return ff_lt(a, b) ? b : a; }
                ff1 ff_clamp(ff1 x, ff1 lo, ff1 hi) { return ff_min(ff_max(x, lo), hi); }
                ff1 ff_mix(ff1 a, ff1 b, ff1 t) { return ff_add(a, ff_mul(ff_sub(b, a), t)); }
                ff1 ff_step(ff1 e, ff1 x) { return ff_from(ff_lt(x, e) ? 0.0 : 1.0); }
                ff1 ff_smoothstep(ff1 e0, ff1 e1, ff1 x) {
                    ff1 t = ff_clamp(ff_div(ff_sub(x, e0), ff_sub(e1, e0)), ff_from(0.0), ff_from(1.0));
                    return ff_mul(ff_sqr(t), ff_sub(3.0, ff_mul(t, 2.0)));
                }
                ff1 ff_sqrt(ff1 a) {
                    if (!(a.hi > 0.0)) return ff_from(0.0);
                    float s = sqrt(a.hi);
                    ff1 r = ff_sub(a, ff_tp(s, s));
                    return ff_qts(s, r.hi * 0.5 / s);
                }
                ff1 ff_inversesqrt(ff1 a) { return ff_div(1.0, ff_sqrt(a)); }
                ff1 ff_powi(ff1 x, int n) {
                    ff1 r = ff_from(1.0);
                    ff1 b = x;
                    while (n > 0) {
                        if ((n & 1) != 0) r = ff_mul(r, b);
                        b = ff_sqr(b);
                        n = n >> 1;
                    }
                    return r;
                }
                """);
        // sin r = r + r s P(s), cos r = 1 - s/2 + s^2 Q(s), s = r^2, |r| <= pi/4: the leading
        // coefficients in float-float, the tail in float where its error stays under 1e-16.
        b.append(String.format(Locale.ROOT, """
                void ff_sincos(ff1 x, out ff1 sx, out ff1 cx) {
                    float k = floor(x.hi * %s + 0.5);
                    ff1 r = ff_sub(x, ff_tp(k, %s));
                    r = ff_sub(r, ff_tp(k, %s));
                    r = ff_sub(r, k * %s);
                    ff1 s = ff_sqr(r);
                    float ts = %s + s.hi * (%s + s.hi * (%s + s.hi * %s));
                    ff1 p = ff_add(%s, ff_mul(s, ts));
                    p = ff_add(%s, ff_mul(s, p));
                    p = ff_add(%s, ff_mul(s, p));
                    p = ff_add(%s, ff_mul(s, p));
                    ff1 sn = ff_add(r, ff_mul(ff_mul(r, s), p));
                    float tc = %s + s.hi * (%s + s.hi * (%s + s.hi * %s));
                    ff1 q = ff_add(%s, ff_mul(s, tc));
                    q = ff_add(%s, ff_mul(s, q));
                    q = ff_add(%s, ff_mul(s, q));
                    ff1 cs = ff_add(ff_sub(1.0, ff1(s.hi * 0.5, s.lo * 0.5)), ff_mul(ff_sqr(s), q));
                    int quadrant = int(k) & 3;
                    ff1 a = (quadrant & 1) == 0 ? sn : cs;
                    ff1 c = (quadrant & 1) == 0 ? cs : sn;
                    sx = (quadrant & 2) == 0 ? a : ff_neg(a);
                    cx = ((quadrant + 1) & 2) == 0 ? c : ff_neg(c);
                }
                ff1 ff_sin(ff1 x) { ff1 s, c; ff_sincos(x, s, c); return s; }
                ff1 ff_cos(ff1 x) { ff1 s, c; ff_sincos(x, s, c); return c; }
                ff1 ff_tan(ff1 x) { ff1 s, c; ff_sincos(x, s, c); return ff_div(s, c); }
                """,
                lit(twoOverPi), lit(p[0]), lit(p[1]), lit(p[2]),
                lit(inv(sinDen[4]).floatValue()), lit(inv(sinDen[5]).floatValue()), lit(inv(sinDen[6]).floatValue()), lit(inv(sinDen[7]).floatValue()),
                c1(inv(sinDen[3])), c1(inv(sinDen[2])), c1(inv(sinDen[1])), c1(inv(sinDen[0])),
                lit(inv(cosDen[3]).floatValue()), lit(inv(cosDen[4]).floatValue()), lit(inv(cosDen[5]).floatValue()), lit(inv(cosDen[6]).floatValue()),
                c1(inv(cosDen[2])), c1(inv(cosDen[1])), c1(inv(cosDen[0]))));
        // exp: x = k ln2 + r, |r| <= ln2/2; exp r by its series, 1/0!..1/7! in float-float, the
        // rest in float (r^8/8! <= 5e-9); then scaled by 2^k exactly.
        StringBuilder horner = new StringBuilder();
        for (int i = 7; i >= 0; i--) horner.append("    p = ff_add(").append(c1(inv(fact[i]))).append(", ff_mul(r, p));\n");
        b.append(String.format(Locale.ROOT, """
                ff1 ff_exp(ff1 x) {
                    if (x.hi > 88.0) return ff_from(3.4028235e38);
                    if (x.hi < -87.0) return ff_from(0.0);
                    float k = floor(x.hi * %s + 0.5);
                    ff1 r = ff_sub(x, ff_tp(k, %s));
                    r = ff_sub(r, ff_tp(k, %s));
                    r = ff_sub(r, k * %s);
                    float t = %s + r.hi * (%s + r.hi * (%s + r.hi * (%s + r.hi * (%s + r.hi * %s))));
                    ff1 p = ff_from(t);
                %s    int e = int(k);
                    return ff1(ldexp(p.hi, e), ldexp(p.lo, e));
                }
                """,
                lit(invLn2), lit(l[0]), lit(l[1]), lit(l[2]),
                lit(inv(fact[8]).floatValue()), lit(inv(fact[9]).floatValue()), lit(inv(fact[10]).floatValue()),
                lit(inv(fact[11]).floatValue()), lit(inv(fact[12]).floatValue()), lit(inv(fact[13]).floatValue()),
                horner));
        BigDecimal pi180 = PI.divide(BigDecimal.valueOf(180), MC), d180 = BigDecimal.valueOf(180).divide(PI, MC);
        BigDecimal invLn2Big = BigDecimal.ONE.divide(LN2, MC);
        b.append(String.format(Locale.ROOT, """
                // log: the float answer y0, then y0 + log(1 + d), d = x e^-y0 - 1 ~ 1e-7, log(1 + d) = d - d^2/2 to 1e-21
                ff1 ff_log(ff1 x) {
                    if (!(x.hi > 0.0)) return ff_from(log(x.hi));
                    float y0 = log(x.hi);
                    ff1 d = ff_sub(ff_mul(x, ff_exp(ff_from(-y0))), 1.0);
                    return ff_add(ff_sub(d, ff_mul(ff_sqr(d), 0.5)), y0);
                }
                ff1 ff_exp2(ff1 x) { return ff_exp(ff_mul(x, %s)); }
                ff1 ff_log2(ff1 x) { return ff_mul(ff_log(x), %s); }
                ff1 ff_pow(ff1 x, float y) {
                    if (y == floor(y) && abs(y) <= 64.0) {
                        ff1 p = ff_powi(x, int(abs(y)));
                        return y < 0.0 ? ff_div(1.0, p) : p;
                    }
                    if (!(x.hi > 0.0)) return ff_from(pow(x.hi, y));
                    return ff_exp(ff_mul(ff_log(x), y));
                }
                ff1 ff_pow(ff1 x, ff1 y) {
                    if (y.lo == 0.0) return ff_pow(x, y.hi);
                    if (!(x.hi > 0.0)) return ff_from(pow(x.hi, y.hi));
                    return ff_exp(ff_mul(ff_log(x), y));
                }
                ff1 ff_sinh(ff1 x) { ff1 e = ff_exp(x); return ff_mul(ff_sub(e, ff_div(1.0, e)), 0.5); }
                ff1 ff_cosh(ff1 x) { ff1 e = ff_exp(x); return ff_mul(ff_add(e, ff_div(1.0, e)), 0.5); }
                ff1 ff_tanh(ff1 x) {
                    if (abs(x.hi) > 20.0) return ff_from(sign(x.hi));
                    ff1 e = ff_exp(ff_mul(x, 2.0));
                    return ff_div(ff_sub(e, 1.0), ff_add(e, 1.0));
                }
                ff1 ff_radians(ff1 x) { return ff_mul(x, %s); }
                ff1 ff_degrees(ff1 x) { return ff_mul(x, %s); }
                // atan2: the float answer t0, then t0 + atan(u), u = (y cos t0 - x sin t0) / (x cos t0 + y sin t0)
                // = tan(t - t0) ~ 1e-7, where atan(u) = u to 1e-21
                ff1 ff_atan(ff1 y, ff1 x) {
                    if (x.hi == 0.0 && y.hi == 0.0) return ff_from(0.0);
                    float t0 = atan(y.hi, x.hi);
                    ff1 s, c;
                    ff_sincos(ff_from(t0), s, c);
                    ff1 num = ff_sub(ff_mul(y, c), ff_mul(x, s));
                    ff1 den = ff_add(ff_mul(x, c), ff_mul(y, s));
                    return ff_add(ff_div(num, den), t0);
                }
                ff1 ff_atan(ff1 x) { return ff_atan(x, ff_from(1.0)); }
                // 1 - x^2 as (1 - x)(1 + x), which keeps its digits near +-1
                ff1 ff_acos(ff1 x) { return ff_atan(ff_sqrt(ff_mul(ff_sub(1.0, x), ff_add(1.0, x))), x); }
                ff1 ff_asin(ff1 x) { return ff_atan(x, ff_sqrt(ff_mul(ff_sub(1.0, x), ff_add(1.0, x)))); }
                ff1 ff_asinh(ff1 x) {
                    ff1 a = ff_abs(x);
                    ff1 r = ff_log(ff_add(a, ff_sqrt(ff_add(ff_sqr(a), 1.0))));
                    return x.hi < 0.0 ? ff_neg(r) : r;
                }
                ff1 ff_acosh(ff1 x) { return ff_log(ff_add(x, ff_sqrt(ff_sub(ff_sqr(x), 1.0)))); }
                ff1 ff_atanh(ff1 x) { return ff_mul(ff_log(ff_div(ff_add(1.0, x), ff_sub(1.0, x))), 0.5); }
                """, c1(LN2), c1(invLn2Big), c1(pi180), c1(d180)));
        return b.toString();
    }

    static final String[] UNARY = {"abs", "sign", "floor", "ceil", "trunc", "round", "fract", "sqrt", "inversesqrt",
            "exp", "log", "exp2", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
            "asinh", "acosh", "atanh", "radians", "degrees"};
    static final String[] BINARY = {"min", "max", "mod", "step", "pow", "atan"};
    static final String[] TERNARY = {"clamp", "mix", "smoothstep"};

    /** The built-ins on ffN, a component at a time. */
    static String vectorFunctions(int n) {
        String s = "ff" + n, t = T[n];
        StringBuilder b = new StringBuilder();
        java.util.function.Function<String[], String> assemble = parts -> {
            StringBuilder h = new StringBuilder(), l = new StringBuilder();
            for (int k = 0; k < n; k++) {
                h.append(k > 0 ? ", " : "").append(parts[k]).append(".hi");
                l.append(k > 0 ? ", " : "").append(parts[k]).append(".lo");
            }
            return s + "(" + t + "(" + h + "), " + t + "(" + l + "))";
        };
        for (String f : UNARY) {
            String[] parts = new String[n];
            StringBuilder body = new StringBuilder();
            for (int k = 0; k < n; k++) {
                parts[k] = "r" + k;
                body.append(" ff1 r").append(k).append(" = ff_").append(f).append("(ff_").append(XYZW[k]).append("(a));");
            }
            b.append(s).append(" ff_").append(f).append('(').append(s).append(" a) {").append(body).append(" return ").append(assemble.apply(parts)).append("; }\n");
        }
        for (String f : BINARY) {
            String[] parts = new String[n];
            StringBuilder body = new StringBuilder();
            for (int k = 0; k < n; k++) {
                parts[k] = "r" + k;
                body.append(" ff1 r").append(k).append(" = ff_").append(f).append("(ff_").append(XYZW[k]).append("(a), ff_").append(XYZW[k]).append("(b));");
            }
            b.append(s).append(" ff_").append(f).append('(').append(s).append(" a, ").append(s).append(" b) {").append(body).append(" return ").append(assemble.apply(parts)).append("; }\n");
        }
        for (String f : TERNARY) {
            String[] parts = new String[n];
            StringBuilder body = new StringBuilder();
            for (int k = 0; k < n; k++) {
                parts[k] = "r" + k;
                body.append(" ff1 r").append(k).append(" = ff_").append(f).append("(ff_").append(XYZW[k]).append("(a), ff_").append(XYZW[k]).append("(b), ff_").append(XYZW[k]).append("(c));");
            }
            b.append(s).append(" ff_").append(f).append('(').append(s).append(" a, ").append(s).append(" b, ").append(s).append(" c) {").append(body).append(" return ").append(assemble.apply(parts)).append("; }\n");
        }
        // pow with a plain float exponent per component (a uniform exponent, the common case)
        {
            String[] parts = new String[n];
            StringBuilder body = new StringBuilder();
            for (int k = 0; k < n; k++) {
                parts[k] = "r" + k;
                body.append(" ff1 r").append(k).append(" = ff_pow(ff_").append(XYZW[k]).append("(a), b.").append(XYZW[k]).append(");");
            }
            b.append(s).append(" ff_pow(").append(s).append(" a, ").append(t).append(" b) {").append(body).append(" return ").append(assemble.apply(parts)).append("; }\n");
        }
        // dot, length, distance, normalize
        StringBuilder dot = new StringBuilder("ff_mul(ff_x(a), ff_x(b))");
        for (int k = 1; k < n; k++) dot = new StringBuilder("ff_add(" + dot + ", ff_mul(ff_" + XYZW[k] + "(a), ff_" + XYZW[k] + "(b)))");
        b.append("ff1 ff_dot(").append(s).append(" a, ").append(s).append(" b) { return ").append(dot).append("; }\n");
        b.append("ff1 ff_length(").append(s).append(" a) { return ff_sqrt(ff_dot(a, a)); }\n");
        b.append("ff1 ff_distance(").append(s).append(" a, ").append(s).append(" b) { return ff_length(ff_sub(a, b)); }\n");
        b.append(s).append(" ff_normalize(").append(s).append(" a) { return ff_mul(a, ff_div(1.0, ff_length(a))); }\n");
        b.append(s).append(" ff_reflect(").append(s).append(" i, ").append(s).append(" nn) { return ff_sub(i, ff_mul(nn, ff_mul(ff_dot(nn, i), 2.0))); }\n");
        // a plain float matrix times a float-float vector, both ways
        String m = "mat" + n;
        if (n >= 2) {
            StringBuilder mv = new StringBuilder("ff_mul(m[0], ff_x(v))");
            for (int k = 1; k < n; k++) mv = new StringBuilder("ff_add(" + mv + ", ff_mul(m[" + k + "], ff_" + XYZW[k] + "(v)))");
            b.append(s).append(" ff_mul(").append(m).append(" m, ").append(s).append(" v) { return ").append(mv).append("; }\n");
            StringBuilder vm = new StringBuilder();
            String[] parts = new String[n];
            for (int k = 0; k < n; k++) {
                parts[k] = "r" + k;
                vm.append(" ff1 r").append(k).append(" = ff_dot(v, ff_from(m[").append(k).append("]));");
            }
            b.append(s).append(" ff_mul(").append(s).append(" v, ").append(m).append(" m) {").append(vm).append(" return ").append(assemble.apply(parts)).append("; }\n");
        }
        return b.toString();
    }

    static String geometry() {
        return """
                ff1 ff_dot(ff1 a, ff1 b) { return ff_mul(a, b); }
                ff1 ff_length(ff1 a) { return ff_abs(a); }
                ff1 ff_distance(ff1 a, ff1 b) { return ff_abs(ff_sub(a, b)); }
                ff1 ff_normalize(ff1 a) { return ff_sign(a); }
                ff1 ff_pow(ff1 a, int b) { return ff_pow(a, float(b)); }
                ff3 ff_cross(ff3 a, ff3 b) {
                    ff1 x = ff_sub(ff_mul(ff_y(a), ff_z(b)), ff_mul(ff_z(a), ff_y(b)));
                    ff1 y = ff_sub(ff_mul(ff_z(a), ff_x(b)), ff_mul(ff_x(a), ff_z(b)));
                    ff1 z = ff_sub(ff_mul(ff_x(a), ff_y(b)), ff_mul(ff_y(a), ff_x(b)));
                    return ff3(vec3(x.hi, y.hi, z.hi), vec3(x.lo, y.lo, z.lo));
                }
                ffm2 ff_mat2(ff1 a, ff1 b, ff1 c, ff1 d) { return ffm2(mat2(a.hi, b.hi, c.hi, d.hi), mat2(a.lo, b.lo, c.lo, d.lo)); }
                ff2 ff_mul(ffm2 m, ff2 v) {
                    ff2 c0 = ff2(m.hi[0], m.lo[0]), c1 = ff2(m.hi[1], m.lo[1]);
                    return ff_add(ff_mul(c0, ff_x(v)), ff_mul(c1, ff_y(v)));
                }
                ff2 ff_mul(ffm2 m, vec2 v) { return ff_mul(m, ff_from(v)); }
                ff2 ff_mul(ff2 v, ffm2 m) {
                    ff2 c0 = ff2(m.hi[0], m.lo[0]), c1 = ff2(m.hi[1], m.lo[1]);
                    ff1 a = ff_dot(v, c0), b = ff_dot(v, c1);
                    return ff2(vec2(a.hi, b.hi), vec2(a.lo, b.lo));
                }
                """;
    }
}
