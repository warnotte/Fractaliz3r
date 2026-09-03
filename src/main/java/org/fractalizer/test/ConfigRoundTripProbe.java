package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.MandelbulbParams;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a setting survives save and reload.
 *
 * Two settings had silently failed to: coloringMode, and the whole post-processing chain.
 * In both cases the value lived somewhere the serializer never looked, so choosing it in
 * the UI and saving the scene lost it with no warning. Nothing about the code makes that
 * visible — the field is simply absent from the file — so it needs a test rather than a
 * reading. No GPU is involved: this is purely about what reaches the JSON and comes back.
 *
 * Usage:  -Dexec.args=""   (no arguments)
 */
public class ConfigRoundTripProbe {

    static final List<String> failures = new ArrayList<>();

    static void check(String name, Object written, Object read) {
        boolean ok = (written == null) ? read == null : written.equals(read);
        System.out.printf("  %-26s %-14s -> %-14s  %s%n", name, written, read, ok ? "ok" : "LOST");
        if (!ok) failures.add(name);
    }

    public static void main(String[] args) throws Exception {
        MandelbulbParams params = new MandelbulbParams();

        // Deliberately non-default everywhere, so a value that fails to round-trip cannot
        // be mistaken for one that happened to match the default.
        params.setRimIntensity(0.037f);
        params.setColoringMode(10);
        params.setTrapMode(3);
        params.setDetailLOD(2.5f);
        params.setDetailLODMax(19);
        params.setPreviewScale(0.31f);
        params.setPreviewFastShading(false);
        params.setNebulaColor(0.11f, 0.22f, 0.33f);
        params.setNebulaTint(0.77f);
        params.setJuliaCx(0.41f);
        params.setJuliaCy(-0.19f);
        params.setJuliaCz(0.28f);

        FractalConfig config = FractalConfig.fromParams(params);

        GLSLEngine.PostProcessParams pp = new GLSLEngine.PostProcessParams();
        pp.toneMapMode = 2;
        pp.exposure = 1.37f;
        pp.saturation = 1.42f;
        pp.bloomEnabled = true;
        pp.bloomIntensity = 0.61f;
        pp.bloomThreshold = 0.83f;
        pp.bloomRadius = 5;
        pp.vignetteEnabled = true;
        pp.vignetteIntensity = 0.29f;
        pp.filmGrainEnabled = true;
        pp.sharpenEnabled = true;
        pp.sharpenIntensity = 0.23f;
        pp.colorGradingMode = 4;
        pp.colorGradingIntensity = 0.66f;
        pp.audioDeltaExposure = 999f;   // transient: must NOT reach the file
        config.postProcess = pp;

        File tmp = File.createTempFile("roundtrip_", ".frac");
        FractalConfigManager.save(config, tmp);

        FractalConfig back = FractalConfigManager.load(tmp);
        MandelbulbParams reloaded = new MandelbulbParams();
        back.applyTo(reloaded);

        System.out.println("=== ConfigRoundTripProbe ===");
        System.out.printf("  %-26s %-14s    %-14s%n", "setting", "written", "read back");

        check("rimIntensity", params.getRimIntensity(), reloaded.getRimIntensity());
        check("coloringMode", params.getColoringMode(), reloaded.getColoringMode());
        check("trapMode", params.getTrapMode(), reloaded.getTrapMode());
        check("detailLOD", params.getDetailLOD(), reloaded.getDetailLOD());
        check("detailLODMax", params.getDetailLODMax(), reloaded.getDetailLODMax());
        check("previewScale", params.getPreviewScale(), reloaded.getPreviewScale());
        check("previewFastShading", params.isPreviewFastShading(), reloaded.isPreviewFastShading());
        check("nebulaTint", params.getNebulaTint(), reloaded.getNebulaTint());
        check("nebulaColor.r", params.getNebulaColor()[0], reloaded.getNebulaColor()[0]);
        check("juliaCx", params.getJuliaCx(), reloaded.getJuliaCx());
        check("juliaCy", params.getJuliaCy(), reloaded.getJuliaCy());
        check("juliaCz", params.getJuliaCz(), reloaded.getJuliaCz());

        GLSLEngine.PostProcessParams rp = back.postProcess;
        if (rp == null) {
            System.out.println("  postProcess                (absent)       -> null           LOST");
            failures.add("postProcess");
        } else {
            check("pp.toneMapMode", pp.toneMapMode, rp.toneMapMode);
            check("pp.exposure", pp.exposure, rp.exposure);
            check("pp.saturation", pp.saturation, rp.saturation);
            check("pp.bloomEnabled", pp.bloomEnabled, rp.bloomEnabled);
            check("pp.bloomIntensity", pp.bloomIntensity, rp.bloomIntensity);
            check("pp.bloomRadius", pp.bloomRadius, rp.bloomRadius);
            check("pp.vignetteIntensity", pp.vignetteIntensity, rp.vignetteIntensity);
            check("pp.sharpenIntensity", pp.sharpenIntensity, rp.sharpenIntensity);
            check("pp.colorGradingMode", pp.colorGradingMode, rp.colorGradingMode);
            check("pp.colorGradingIntensity", pp.colorGradingIntensity, rp.colorGradingIntensity);
            // Live session state, not scene content: it must come back at the default.
            check("pp.audioDeltaExposure", 0f, rp.audioDeltaExposure);
        }

        tmp.delete();
        System.out.println();
        System.out.println(failures.isEmpty()
                ? "RESULT: every setting survives save and reload"
                : "RESULT: LOST ON RELOAD -> " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
