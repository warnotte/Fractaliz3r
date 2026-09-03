package org.fractalizer.config;

import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.MandelbulbParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every setting a .frac is supposed to carry, written and read back.
 *
 * Two settings had silently failed to persist (coloringMode, the whole post-processing
 * chain): the value lived somewhere the serializer never looked, so choosing it in the UI
 * and saving the scene lost it with no warning. Nothing in the source makes that visible,
 * the field is simply absent from the file, so it is checked here rather than by reading.
 * No GPU is involved: this is purely about what reaches the JSON and comes back.
 */
class ConfigRoundTripTest {

    static MandelbulbParams written;
    static MandelbulbParams reloaded;
    static GLSLEngine.PostProcessParams ppWritten;
    static GLSLEngine.PostProcessParams ppReloaded;

    @BeforeAll
    static void saveAndReload() throws Exception {
        written = new MandelbulbParams();
        // Deliberately non-default everywhere, so a value that fails to round-trip cannot
        // be mistaken for one that happened to match the default.
        written.setRimIntensity(0.037f);
        written.setColoringMode(10);
        written.setTrapMode(3);
        written.setDetailLOD(2.5f);
        written.setDetailLODMax(19);
        written.setPreviewScale(0.31f);
        written.setPreviewFastShading(false);
        written.setNebulaColor(0.11f, 0.22f, 0.33f);
        written.setNebulaTint(0.77f);
        written.setJuliaCx(0.41f);
        written.setJuliaCy(-0.19f);
        written.setJuliaCz(0.28f);

        FractalConfig config = FractalConfig.fromParams(written);

        ppWritten = new GLSLEngine.PostProcessParams();
        ppWritten.toneMapMode = 2;
        ppWritten.exposure = 1.37f;
        ppWritten.saturation = 1.42f;
        ppWritten.bloomEnabled = true;
        ppWritten.bloomIntensity = 0.61f;
        ppWritten.bloomThreshold = 0.83f;
        ppWritten.bloomRadius = 5;
        ppWritten.vignetteEnabled = true;
        ppWritten.vignetteIntensity = 0.29f;
        ppWritten.filmGrainEnabled = true;
        ppWritten.sharpenEnabled = true;
        ppWritten.sharpenIntensity = 0.23f;
        ppWritten.colorGradingMode = 4;
        ppWritten.colorGradingIntensity = 0.66f;
        ppWritten.audioDeltaExposure = 999f;   // transient: must NOT reach the file
        config.postProcess = ppWritten;

        File tmp = File.createTempFile("roundtrip_", ".frac");
        try {
            FractalConfigManager.save(config, tmp);
            FractalConfig back = FractalConfigManager.load(tmp);
            reloaded = new MandelbulbParams();
            back.applyTo(reloaded);
            ppReloaded = back.postProcess;
        } finally {
            tmp.delete();
        }
    }

    @Test
    void renderingSettingsSurvive() {
        assertEquals(written.getRimIntensity(), reloaded.getRimIntensity(), "rimIntensity");
        assertEquals(written.getColoringMode(), reloaded.getColoringMode(), "coloringMode");
        assertEquals(written.getTrapMode(), reloaded.getTrapMode(), "trapMode");
        assertEquals(written.getDetailLOD(), reloaded.getDetailLOD(), "detailLOD");
        assertEquals(written.getDetailLODMax(), reloaded.getDetailLODMax(), "detailLODMax");
        assertEquals(written.getPreviewScale(), reloaded.getPreviewScale(), "previewScale");
        assertEquals(written.isPreviewFastShading(), reloaded.isPreviewFastShading(), "previewFastShading");
    }

    @Test
    void environmentAndJuliaSettingsSurvive() {
        assertEquals(written.getNebulaTint(), reloaded.getNebulaTint(), "nebulaTint");
        assertArrayEquals(written.getNebulaColor(), reloaded.getNebulaColor(), "nebulaColor");
        assertEquals(written.getJuliaCx(), reloaded.getJuliaCx(), "juliaCx");
        assertEquals(written.getJuliaCy(), reloaded.getJuliaCy(), "juliaCy");
        assertEquals(written.getJuliaCz(), reloaded.getJuliaCz(), "juliaCz");
    }

    @Test
    void postProcessingChainSurvives() {
        assertNotNull(ppReloaded, "the post-processing chain is absent from the file");
        assertEquals(ppWritten.toneMapMode, ppReloaded.toneMapMode, "toneMapMode");
        assertEquals(ppWritten.exposure, ppReloaded.exposure, "exposure");
        assertEquals(ppWritten.saturation, ppReloaded.saturation, "saturation");
        assertEquals(ppWritten.bloomEnabled, ppReloaded.bloomEnabled, "bloomEnabled");
        assertEquals(ppWritten.bloomIntensity, ppReloaded.bloomIntensity, "bloomIntensity");
        assertEquals(ppWritten.bloomThreshold, ppReloaded.bloomThreshold, "bloomThreshold");
        assertEquals(ppWritten.bloomRadius, ppReloaded.bloomRadius, "bloomRadius");
        assertEquals(ppWritten.vignetteEnabled, ppReloaded.vignetteEnabled, "vignetteEnabled");
        assertEquals(ppWritten.vignetteIntensity, ppReloaded.vignetteIntensity, "vignetteIntensity");
        assertEquals(ppWritten.filmGrainEnabled, ppReloaded.filmGrainEnabled, "filmGrainEnabled");
        assertEquals(ppWritten.sharpenEnabled, ppReloaded.sharpenEnabled, "sharpenEnabled");
        assertEquals(ppWritten.sharpenIntensity, ppReloaded.sharpenIntensity, "sharpenIntensity");
        assertEquals(ppWritten.colorGradingMode, ppReloaded.colorGradingMode, "colorGradingMode");
        assertEquals(ppWritten.colorGradingIntensity, ppReloaded.colorGradingIntensity, "colorGradingIntensity");
    }

    @Test
    void liveAudioStateDoesNotReachTheFile() {
        assertNotNull(ppReloaded);
        // Session state, not scene content: it must come back at the default.
        assertEquals(0f, ppReloaded.audioDeltaExposure, "audioDeltaExposure");
    }

    @Test
    void jsonFormIsStableAcrossOneMoreCycle() throws Exception {
        FractalConfig config = FractalConfig.fromParams(written);
        String json1 = FractalConfigManager.toJson(config);
        FractalConfig again = FractalConfigManager.fromJson(json1);
        MandelbulbParams p2 = new MandelbulbParams();
        again.applyTo(p2);
        String json2 = FractalConfigManager.toJson(FractalConfig.fromParams(p2));
        // The save timestamp is the one field that legitimately differs between two saves.
        json1 = json1.replaceAll("\"timestamp\": \"[^\"]*\"", "\"timestamp\": \"-\"");
        json2 = json2.replaceAll("\"timestamp\": \"[^\"]*\"", "\"timestamp\": \"-\"");
        if (!json1.equals(json2)) {
            // Leave both forms on disk so the drifting key can be read off a diff.
            java.nio.file.Path dir = java.nio.file.Path.of("target", "roundtrip");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("first_save.json"), json1);
            java.nio.file.Files.writeString(dir.resolve("second_save.json"), json2);
            fail("a second save of a reloaded scene is not byte-identical; see target/roundtrip/");
        }
    }
}
