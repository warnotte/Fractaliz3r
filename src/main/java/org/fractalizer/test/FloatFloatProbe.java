package org.fractalizer.test;

import org.fractalizer.engine.DeepSource;
import org.fractalizer.engine.FloatFloat;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Float-float (FloatFloat: a number as two floats) against double, on the GPU itself.
 *
 * <ol>
 * <li>Each function of the library over random arguments, against StrictMath: the worst error
 *     relative to max(|result|, 1). Float-float holds ~48 bits, so ~1e-14 is what it can do;
 *     a function far off that means the compiler did not keep the error terms.</li>
 * <li>A Mandelbulb estimator written three ways, float, double (the library DeepSource emits)
 *     and float-float, at points 1e-9..1e-3 from the surface (found by marching in Java): the
 *     error of each against the same estimator in Java, StrictMath, as the estimate those
 *     points get. What a deep march can trust.</li>
 * <li>"bench": what one estimate costs in each, every thread evaluating it in a chain.</li>
 * </ol>
 *
 * mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FloatFloatProbe" -Dexec.args="100000 bench"
 */
public class FloatFloatProbe {

    record Case(String name, String glsl, DoubleBinaryOperator ref, double ax, double bx, double ay, double by, double tol) {
        Case(String name, String glsl, DoubleBinaryOperator ref, double ax, double bx, double ay, double by) {
            this(name, glsl, ref, ax, bx, ay, by, 1e-13);
        }
    }

    static final Case[] CASES = {
            new Case("add", "ff_add(X, Y)", Double::sum, -3, 3, -3, 3),
            new Case("mul", "ff_mul(X, Y)", (x, y) -> x * y, -3, 3, -3, 3),
            new Case("div", "ff_div(X, Y)", (x, y) -> x / y, -3, 3, 0.2, 3),
            new Case("sqrt", "ff_sqrt(X)", (x, y) -> Math.sqrt(x), 1e-6, 9, 0, 0),
            new Case("sin", "ff_sin(X)", (x, y) -> StrictMath.sin(x), -30, 30, 0, 0),
            new Case("cos", "ff_cos(X)", (x, y) -> StrictMath.cos(x), -30, 30, 0, 0),
            new Case("tan", "ff_tan(X)", (x, y) -> StrictMath.tan(x), -1.4, 1.4, 0, 0),
            new Case("atan2", "ff_atan(X, Y)", StrictMath::atan2, -2, 2, -2, 2),
            new Case("atan", "ff_atan(X)", (x, y) -> StrictMath.atan(x), -50, 50, 0, 0),
            new Case("acos", "ff_acos(X)", (x, y) -> StrictMath.acos(x), -1, 1, 0, 0),
            new Case("asin", "ff_asin(X)", (x, y) -> StrictMath.asin(x), -1, 1, 0, 0),
            new Case("exp", "ff_exp(X)", (x, y) -> StrictMath.exp(x), -40, 40, 0, 0),
            new Case("log", "ff_log(X)", (x, y) -> StrictMath.log(x), 1e-4, 100, 0, 0),
            new Case("x^7", "ff_pow(X, 7.0)", (x, y) -> StrictMath.pow(x, 7), 0.01, 2, 0, 0),
            new Case("x^7.5", "ff_pow(X, 7.5)", (x, y) -> StrictMath.pow(x, 7.5), 0.01, 2, 0, 0),
            // exp(y log x): the error of y log x (up to ~14 here, ~1e-14 relative) is the result's
            new Case("x^y", "ff_pow(X, Y)", StrictMath::pow, 0.01, 3, -3, 3, 5e-13),
            new Case("floor", "ff_floor(X)", (x, y) -> Math.floor(x), -1000, 1000, 0, 0),
            new Case("mod", "ff_mod(X, Y)", (x, y) -> x - y * Math.floor(x / y), -10, 10, 0.3, 3),
            new Case("sinh", "ff_sinh(X)", (x, y) -> StrictMath.sinh(x), -5, 5, 0, 0),
            new Case("tanh", "ff_tanh(X)", (x, y) -> StrictMath.tanh(x), -5, 5, 0, 0),
    };

    static final int ITER = 15;

    static final String BULB = """
            uniform float power; uniform float bailout; uniform int maxIterations;
            double ff_tod(ff1 a) { return double(a.hi) + double(a.lo); }
            ff3 ff_vec3(ff1 a, ff1 b, ff1 c) { return ff3(vec3(a.hi, b.hi, c.hi), vec3(a.lo, b.lo, c.lo)); }
            // returns (r, dr) of the escaping orbit
            dvec2 bulb_f(dvec3 p0) {
                vec3 pos = vec3(p0); vec3 z = pos; float dr = 1.0; float r = 0.0;
                for (int i = 0; i < maxIterations; i++) {
                    r = length(z);
                    if (r > bailout) break;
                    float theta = acos(z.z / r);
                    float phi = atan(z.y, z.x);
                    dr = pow(r, power - 1.0) * power * dr + 1.0;
                    float zr = pow(r, power);
                    theta = theta * power; phi = phi * power;
                    z = zr * vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta));
                    z = z + pos;
                }
                return dvec2(r, dr);
            }
            dvec2 bulb_d(dvec3 pos) {
                dvec3 z = pos; double dr = 1.0LF; double r = 0.0LF;
                for (int i = 0; i < maxIterations; i++) {
                    r = length(z);
                    if (r > bailout) break;
                    double theta = acos_dp(z.z / r);
                    double phi = atan_dp(z.y, z.x);
                    dr = pow_dp(r, double(power) - 1.0LF) * double(power) * dr + 1.0LF;
                    double zr = pow_dp(r, double(power));
                    theta = theta * power; phi = phi * power;
                    z = zr * dvec3(sin_dp(theta) * cos_dp(phi), sin_dp(theta) * sin_dp(phi), cos_dp(theta));
                    z = z + pos;
                }
                return dvec2(r, dr);
            }
            // as the translator writes the float estimator: every float-family value a float-float
            dvec2 bulb_ff(dvec3 p0) {
                ff3 pos = ff_fromd(p0);
                ff3 z = pos;
                ff1 dr = ff_from(1.0);
                ff1 r = ff_from(0.0);
                for (int i = 0; i < maxIterations; i++) {
                    r = ff_length(z);
                    if (ff_gt(r, bailout)) break;
                    ff1 theta = ff_acos(ff_div(ff_z(z), r));
                    ff1 phi = ff_atan(ff_y(z), ff_x(z));
                    dr = ff_add(ff_mul(ff_mul(ff_pow(r, power - 1.0), power), dr), 1.0);
                    ff1 zr = ff_pow(r, power);
                    theta = ff_mul(theta, power);
                    phi = ff_mul(phi, power);
                    z = ff_mul(zr, ff_vec3(ff_mul(ff_sin(theta), ff_cos(phi)), ff_mul(ff_sin(theta), ff_sin(phi)), ff_cos(theta)));
                    z = ff_add(z, pos);
                }
                return dvec2(ff_tod(r), ff_tod(dr));
            }
            dvec2 bulb_ff_shared(dvec3 p0) {
                ff3 pos = ff_fromd(p0);
                ff3 z = pos;
                ff1 dr = ff_from(1.0);
                ff1 r = ff_from(0.0);
                for (int i = 0; i < maxIterations; i++) {
                    r = ff_length(z);
                    if (ff_gt(r, bailout)) break;
                    ff1 theta = ff_acos(ff_div(ff_z(z), r));
                    ff1 phi = ff_atan(ff_y(z), ff_x(z));
                    dr = ff_add(ff_mul(ff_mul(ff_pow(r, power - 1.0), power), dr), 1.0);
                    ff1 zr = ff_pow(r, power);
                    theta = ff_mul(theta, power);
                    phi = ff_mul(phi, power);
                    ff1 st, ct, sp, cp;
                    ff_sincos(theta, st, ct);
                    ff_sincos(phi, sp, cp);
                    z = ff_mul(zr, ff_vec3(ff_mul(st, cp), ff_mul(st, sp), ct));
                    z = ff_add(z, pos);
                }
                return dvec2(ff_tod(r), ff_tod(dr));
            }
            """;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        if (!glfwInit()) throw new IllegalStateException("GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "FloatFloatProbe", NULL, NULL);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GPU: " + glGetString(GL_RENDERER));
        String lib = FloatFloat.library() + DeepSource.library(Set.of("sin", "cos", "atan", "acos", "pow"));
        int[] ssbo = new int[3];
        glGenBuffers(ssbo);
        boolean ok = functions(lib, ssbo, n);
        ok &= estimator(lib, ssbo, Math.min(n, 20_000));
        if (args.length > 1 && args[1].equals("bench")) bench(lib, ssbo);
        if (args.length > 2 && args[1].equals("entries")) entries(args[2], ssbo);
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(ok ? 0 : 1);
    }

    /**
     * "entries kernel.glsl": the deep entries the program gets for a built-in kernel, the
     * float-float one (FloatFloatSource) and the double one (DeepSource), each with common.glsl
     * and the kernel in a compute shader of its own, timed per estimate as {@link #bench}
     * does, against the kernel's own float DE_simple.
     */
    static void entries(String kernel, int[] ssbo) {
        String common, scene;
        try {
            common = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/shaders/common.glsl"));
            scene = java.nio.file.Files.readString(java.nio.file.Path.of(kernel));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        String ff = org.fractalizer.engine.FloatFloatSource.build(common, scene);
        String dp = DeepSource.build(common, scene);
        if (ff == null) throw new IllegalStateException("float-float: " + org.fractalizer.engine.FloatFloatSource.lastError());
        // the same estimate called from one place, and from eight (as the raytracer calls it from
        // its march, refinement, normal, occlusion, shadow...): the compiler copies it into each
        String eight = "(k % 8 == 0 ? DE_deep(p) : k % 8 == 1 ? DE_deep(p + 1e-9LF) : k % 8 == 2 ? DE_deep(p + 2e-9LF) : k % 8 == 3 ? DE_deep(p + 3e-9LF)"
                + " : k % 8 == 4 ? DE_deep(p + 4e-9LF) : k % 8 == 5 ? DE_deep(p + 5e-9LF) : k % 8 == 6 ? DE_deep(p + 6e-9LF) : DE_deep(p + 7e-9LF))";
        // through a subroutine uniform: a call the driver cannot inline, one copy for every site
        String sub = "\nsubroutine float DeepFn(dvec3 p);\nsubroutine(DeepFn) float deepImpl(dvec3 p) { return DE_deep(p); }\nsubroutine uniform DeepFn deepFn;\n";
        String eightSub = eight.replace("DE_deep(", "deepFn(");
        String[][] cases = {{"float DE_simple", "", "DE_simple(vec3(p))"}, {"double DE_deep", dp, "DE_deep(p)"}, {"float-float DE_deep", ff, "DE_deep(p)"},
                {"double, 8 call sites", dp, eight}, {"float-float, 8 sites", ff, eight},
                {"double, 8, subroutine", dp + sub, eightSub}, {"ff, 8, subroutine", ff + sub, eightSub}, {"ff, 1, subroutine", ff + sub, "deepFn(p)"}};
        int n = 1 << 16;
        double[] xs = new double[n], ys = new double[n];
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) { xs[i] = -0.6 + 1.2 * rnd.nextDouble(); ys[i] = -0.6 + 1.2 * rnd.nextDouble(); }
        System.out.printf("%n%s: the program's own deep entries%n%-20s | %10s %10s%n", kernel, "estimate", "ns", "x float");
        double floatNs = 0;
        for (int c = 0; c < cases.length; c++) {
            String src = HEADER.replace("#version 430\n", "#version 430\n") + common + "\n" + scene + "\n" + cases[c][1]
                    + "\nuniform int reps;\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                    + "    dvec3 p = dvec3(inX[i], inY[i], 0.1LF); double acc = 0.0LF;\n"
                    + "    for (int k = 0; k < reps; k++) { float d = " + cases[c][2] + "; acc = acc + double(d); p.x = p.x + double(d) * 1.0e-30LF; }\n"
                    + "    outV[i] = acc;\n}\n";
            long tc = System.nanoTime();
            int prog = program(src);
            long compileMs = (System.nanoTime() - tc) / 1_000_000;
            glUseProgram(prog);
            glUniform1f(glGetUniformLocation(prog, "power"), 8f);
            glUniform1f(glGetUniformLocation(prog, "bailout"), 2f);
            glUniform1i(glGetUniformLocation(prog, "maxIterations"), ITER);
            DoubleMathProbe.upload(ssbo[0], 0, xs);
            DoubleMathProbe.upload(ssbo[1], 1, ys);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 8L * n, GL_DYNAMIC_READ);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo[2]);
            glUniform1i(glGetUniformLocation(prog, "count"), n);
            int subIndex = cases[c][1].contains("subroutine uniform") ? glGetSubroutineIndex(prog, GL_COMPUTE_SHADER, "deepImpl") : -1;
            int reps = 1;
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 7; round++) {
                glUniform1i(glGetUniformLocation(prog, "reps"), reps);
                if (subIndex >= 0) glUniformSubroutinesuiv(GL_COMPUTE_SHADER, new int[]{subIndex});
                glFinish();
                long t0 = System.nanoTime();
                glDispatchCompute(n / 64, 1, 1);
                glFinish();
                long dt = System.nanoTime() - t0;
                if (round < 2) {
                    if (dt < 25_000_000L && reps < 4096) reps = (int) Math.min(4096, reps * Math.max(2, 50_000_000L / Math.max(dt, 1)));
                    continue;
                }
                best = Math.min(best, (double) dt / ((double) n * reps));
            }
            if (c == 0) floatNs = best;
            System.out.printf(Locale.ROOT, "%-20s | %10.3f %10.1f   (compiled in %d ms)%n", cases[c][0], best, best / floatNs, compileMs);
        }
    }

    static int program(String src) {
        int cs = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(cs, src);
        glCompileShader(cs);
        if (glGetShaderi(cs, GL_COMPILE_STATUS) == GL_FALSE) {
            String[] lines = src.split("\n");
            for (int i = 0; i < lines.length; i++) System.out.printf("%4d %s%n", i + 1, lines[i]);
            throw new RuntimeException(glGetShaderInfoLog(cs));
        }
        int prog = glCreateProgram();
        glAttachShader(prog, cs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(prog));
        return prog;
    }

    static final String HEADER = "#version 430\nlayout(local_size_x = 64) in;\n"
            + "layout(std430, binding = 0) readonly buffer InX { double inX[]; };\n"
            + "layout(std430, binding = 1) readonly buffer InY { double inY[]; };\n"
            + "layout(std430, binding = 2) writeonly buffer Out { double outV[]; };\n"
            + "uniform int fn; uniform int count;\n";

    static boolean functions(String lib, int[] ssbo, int n) {
        StringBuilder sw = new StringBuilder();
        for (int c = 0; c < CASES.length; c++) sw.append("        case ").append(c).append(": r = ").append(CASES[c].glsl()).append("; break;\n");
        String src = HEADER + lib
                + "\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                + "    ff1 X = ff_fromd(inX[i]), Y = ff_fromd(inY[i]); ff1 r = ff_from(0.0);\n    switch (fn) {\n" + sw + "    }\n"
                + "    outV[i] = double(r.hi) + double(r.lo);\n}\n";
        int prog = program(src);
        glUseProgram(prog);
        Random rnd = new Random(1);
        System.out.printf("%n%-7s | %12s %28s%n", "ff", "worst rel", "at");
        boolean ok = true;
        for (int c = 0; c < CASES.length; c++) {
            Case k = CASES[c];
            double[] xs = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                // arguments a float-float can hold exactly: hi + lo of a random double
                xs[i] = k.ax() + (k.bx() - k.ax()) * rnd.nextDouble();
                ys[i] = k.ay() + (k.by() - k.ay()) * rnd.nextDouble();
                xs[i] = asFF(xs[i]);
                ys[i] = asFF(ys[i]);
            }
            double[] out = run(prog, ssbo, c, xs, ys, n, 8);
            double worst = 0;
            String at = "";
            int nan = 0;
            for (int i = 0; i < n; i++) {
                double ref = k.ref().applyAsDouble(xs[i], ys[i]);
                if (Double.isNaN(out[i])) { nan++; continue; }
                double e = Math.abs(out[i] - ref) / Math.max(Math.abs(ref), 1.0);
                if (e > worst) { worst = e; at = String.format(Locale.ROOT, "%.8g, %.8g", xs[i], ys[i]); }
            }
            System.out.printf(Locale.ROOT, "%-7s | %12.3g %28s%s%n", k.name(), worst, at, nan > 0 ? "   NaN x" + nan : "");
            if (worst > k.tol() || nan > 0) ok = false;
        }
        System.out.println(ok ? "functions: PASS (each within 1e-13 relative to max(|f|, 1), x^y 5e-13)" : "functions: FAIL");
        return ok;
    }

    /** The double nearest x that a float-float holds exactly (hi + lo, both floats). */
    static double asFF(double x) {
        float hi = (float) x;
        float lo = (float) (x - hi);
        return (double) hi + (double) lo;
    }

    static double[] run(int prog, int[] ssbo, int fn, double[] xs, double[] ys, int n, int outPer) {
        DoubleMathProbe.upload(ssbo[0], 0, xs);
        DoubleMathProbe.upload(ssbo[1], 1, ys);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) outPer * n, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo[2]);
        glUniform1i(glGetUniformLocation(prog, "fn"), fn);
        glUniform1i(glGetUniformLocation(prog, "count"), n);
        glDispatchCompute((n + 63) / 64, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        ByteBuffer out = BufferUtils.createByteBuffer(outPer * n).order(ByteOrder.nativeOrder());
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, out);
        DoubleBuffer o = out.asDoubleBuffer();
        double[] r = new double[o.remaining()];
        o.get(r);
        return r;
    }

    // ---- the estimator -----------------------------------------------------------------

    /** (r, dr) of the Mandelbulb orbit of p, power 8, as the shaders compute it, in StrictMath. */
    static double[] bulbRef(double x, double y, double z) {
        double zx = x, zy = y, zz = z, dr = 1, r = 0;
        for (int i = 0; i < ITER; i++) {
            r = Math.sqrt(zx * zx + zy * zy + zz * zz);
            if (r > 2.0) break;
            double th = StrictMath.acos(zz / r), ph = StrictMath.atan2(zy, zx);
            dr = StrictMath.pow(r, 7) * 8 * dr + 1;
            double zr = StrictMath.pow(r, 8);
            th *= 8;
            ph *= 8;
            zx = zr * StrictMath.sin(th) * StrictMath.cos(ph) + x;
            zy = zr * StrictMath.sin(th) * StrictMath.sin(ph) + y;
            zz = zr * StrictMath.cos(th) + z;
        }
        return new double[]{r, dr};
    }

    static double de(double r, double dr) { return 0.5 * Math.log(r) * r / dr; }

    static double deRef(double[] p) { double[] o = bulbRef(p[0], p[1], p[2]); return de(o[0], o[1]); }

    static boolean estimator(String lib, int[] ssbo, int n) {
        // Points near the surface: march in from a random direction (Java, double), then back
        // off by 1e-9..1e-3 along the ray.
        Random rnd = new Random(7);
        double[][] pts = new double[n][];
        double[] dist = new double[n];
        int found = 0;
        while (found < n) {
            double ux = rnd.nextGaussian(), uy = rnd.nextGaussian(), uz = rnd.nextGaussian();
            double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
            double[] d = {-ux / ul, -uy / ul, -uz / ul};
            double[] o = {1.6 * ux / ul, 1.6 * uy / ul, 1.6 * uz / ul};
            double t = 0;
            boolean hit = false;
            for (int s = 0; s < 400; s++) {
                double h = deRef(new double[]{o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t});
                if (h < 1e-12) { hit = true; break; }
                t += h * 0.9;
                if (t > 4) break;
            }
            if (!hit) continue;
            double back = Math.pow(10, -9 + 6 * rnd.nextDouble());
            double tt = t - back;
            double[] p = {asFF(o[0] + d[0] * tt), asFF(o[1] + d[1] * tt), asFF(o[2] + d[2] * tt)};
            pts[found] = p;
            dist[found] = back;
            found++;
        }
        String src = HEADER + lib + BULB
                + "layout(std430, binding = 3) readonly buffer InZ { double inZ[]; };\n"
                + "\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                + "    dvec3 p = dvec3(inX[i], inY[i], inZ[i]);\n"
                + "    dvec2 a = bulb_f(p), b = bulb_d(p), c = bulb_ff(p);\n"
                + "    outV[6 * i] = a.x; outV[6 * i + 1] = a.y; outV[6 * i + 2] = b.x; outV[6 * i + 3] = b.y;\n"
                + "    outV[6 * i + 4] = c.x; outV[6 * i + 5] = c.y;\n}\n";
        int prog = program(src);
        glUseProgram(prog);
        glUniform1f(glGetUniformLocation(prog, "power"), 8f);
        glUniform1f(glGetUniformLocation(prog, "bailout"), 2f);
        glUniform1i(glGetUniformLocation(prog, "maxIterations"), ITER);
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        for (int i = 0; i < n; i++) { xs[i] = pts[i][0]; ys[i] = pts[i][1]; zs[i] = pts[i][2]; }
        int zb = glGenBuffers();
        DoubleMathProbe.upload(zb, 3, zs);
        double[] out = run(prog, ssbo, 0, xs, ys, n, 48);

        // Error of each estimate against the reference, relative to the distance to the surface:
        // a deep march stepping with an estimate off by more than a fraction of itself goes wrong.
        String[] names = {"float", "double", "float-float"};
        System.out.printf("%nMandelbulb estimate at points 1e-9..1e-3 from the surface (%d points), |de - ref| / ref%n", n);
        System.out.printf("%-12s | %10s %10s %10s %10s | %s%n", "", "median", "p99", "worst", "> 1%", "by distance: median error at 1e-9, 1e-7, 1e-5, 1e-3");
        boolean ok = true;
        for (int v = 0; v < 3; v++) {
            double[] err = new double[n];
            double[][] band = new double[4][n];
            int[] bn = new int[4];
            int over = 0;
            for (int i = 0; i < n; i++) {
                double ref = deRef(pts[i]);
                double e = Math.abs(de(out[6 * i + 2 * v], out[6 * i + 2 * v + 1]) - ref) / Math.abs(ref);
                if (Double.isNaN(e)) e = Double.POSITIVE_INFINITY;
                err[i] = e;
                if (e > 0.01) over++;
                int b = (int) Math.max(0, Math.min(3, Math.round((Math.log10(dist[i]) + 9) / 2)));
                band[b][bn[b]++] = e;
            }
            Arrays.sort(err);
            StringBuilder bands = new StringBuilder();
            for (int b = 0; b < 4; b++) {
                double[] s = Arrays.copyOf(band[b], bn[b]);
                Arrays.sort(s);
                bands.append(String.format(Locale.ROOT, " %9.2g", s.length == 0 ? Double.NaN : s[s.length / 2]));
            }
            System.out.printf(Locale.ROOT, "%-12s | %10.2g %10.2g %10.2g %9.2f%% |%s%n", names[v], err[n / 2], err[(int) (n * 0.99)], err[n - 1],
                    100.0 * over / n, bands);
            if (v == 2 && err[(int) (n * 0.99)] > 0.01) ok = false;
        }
        System.out.println(ok ? "estimator: PASS (float-float within 1 % of the distance at 99 % of the points)" : "estimator: FAIL");
        return ok;
    }

    // ---- cost ----------------------------------------------------------------------------

    /** One estimate in each precision, every thread in a chain (each result nudges the next
     *  point, so nothing is hoisted), timed to the GPU's finish, best of five; dispatches far
     *  below the driver's two-second watchdog. */
    static void bench(String lib, int[] ssbo) {
        String[][] cases = {
                {"float", "bulb_f(p)"},
                {"double", "bulb_d(p)"},
                {"float-float", "bulb_ff(p)"},
                {"ff, sincos shared", "bulb_ff_shared(p)"},
        };
        StringBuilder sw = new StringBuilder();
        for (int c = 0; c < cases.length; c++) sw.append("            case ").append(c).append(": v = ").append(cases[c][1]).append("; break;\n");
        String src = HEADER + lib + BULB + "uniform int reps;\n"
                + "\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                + "    dvec3 p = dvec3(inX[i], inY[i], 0.1LF); double acc = 0.0LF;\n"
                + "    for (int k = 0; k < reps; k++) {\n        dvec2 v = dvec2(0.0LF);\n        switch (fn) {\n" + sw + "        }\n"
                + "        acc = acc + v.x + v.y;\n        p.x = p.x + v.x * 1.0e-30LF;\n    }\n    outV[i] = acc;\n}\n";
        int prog = program(src);
        glUseProgram(prog);
        glUniform1f(glGetUniformLocation(prog, "power"), 8f);
        glUniform1f(glGetUniformLocation(prog, "bailout"), 2f);
        glUniform1i(glGetUniformLocation(prog, "maxIterations"), ITER);
        int n = 1 << 16;
        double[] xs = new double[n], ys = new double[n];
        Random rnd = new Random(3);
        // points inside the bulb's body or near it, so most orbits run all their iterations
        for (int i = 0; i < n; i++) { xs[i] = -0.6 + 1.2 * rnd.nextDouble(); ys[i] = -0.6 + 1.2 * rnd.nextDouble(); }
        DoubleMathProbe.upload(ssbo[0], 0, xs);
        DoubleMathProbe.upload(ssbo[1], 1, ys);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 8L * n, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo[2]);
        glUniform1i(glGetUniformLocation(prog, "count"), n);
        System.out.printf("%n%-18s | %10s %10s%n", "estimate", "ns", "x float");
        double floatNs = 0;
        for (int c = 0; c < cases.length; c++) {
            glUniform1i(glGetUniformLocation(prog, "fn"), c);
            int reps = 1;
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 7; round++) {
                glUniform1i(glGetUniformLocation(prog, "reps"), reps);
                glFinish();
                long t0 = System.nanoTime();
                glDispatchCompute(n / 64, 1, 1);
                glFinish();
                long dt = System.nanoTime() - t0;
                if (round < 2) {                       // warm up, and size the chain to ~50 ms
                    if (dt < 25_000_000L && reps < 4096) reps = (int) Math.min(4096, reps * Math.max(2, 50_000_000L / Math.max(dt, 1)));
                    continue;
                }
                best = Math.min(best, (double) dt / ((double) n * reps));
            }
            if (c == 0) floatNs = best;
            System.out.printf(Locale.ROOT, "%-18s | %10.3f %10.1f%n", cases[c][0], best, best / floatNs);
        }
    }
}
