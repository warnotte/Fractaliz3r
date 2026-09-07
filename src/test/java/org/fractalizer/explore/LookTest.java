package org.fractalizer.explore;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A discovery's look: drawn from the seed (so a run is reproducible), varied enough that
 * a sheet does not read as one material photographed twelve times, bright enough that
 * the detail score stays comparable, and written whole into a scene.
 */
class LookTest {

    @Test
    void theSameSeedDrawsTheSameLook() {
        Look a = Look.draw(new Random(21)), b = Look.draw(new Random(21));
        assertEquals(a.name(), b.name());
        assertEquals(a.paletteOffset(), b.paletteOffset());
        assertArrayEquals(a.lightDir(), b.lightDir());
        assertEquals(a.metalness(), b.metalness());
    }

    @Test
    void aHundredDrawsWearManyPalettesLightsAndSkies() {
        Random rnd = new Random(3);
        Set<String> palettes = new HashSet<>(), lights = new HashSet<>(), skies = new HashSet<>(), modes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Look l = Look.draw(rnd);
            palettes.add(l.palette()); lights.add(l.lighting()); skies.add(l.sky()); modes.add(String.valueOf(l.coloringMode()));
            assertTrue(l.lightIntensity() > 1.0f && l.lightIntensity() < 1.7f, "a key light within the band: " + l.lightIntensity());
            assertTrue(l.ambientIntensity() >= 0.3f && l.ambientIntensity() <= 0.45f, "ambient within the band");
            assertTrue(l.coloringMode() >= 9 && l.coloringMode() <= 12, "a multi-hue colouring mode");
            assertTrue(l.stops().length >= 3);
            assertTrue(l.metalness() <= 0.6f && l.roughness() >= 0.2f);
        }
        assertTrue(palettes.size() >= 12, "palettes seen: " + palettes);
        assertTrue(lights.size() >= 6, "lightings seen: " + lights);
        assertTrue(skies.size() >= 3, "skies seen: " + skies);
        assertTrue(modes.size() >= 3, "modes seen: " + modes);
    }

    @Test
    void applyingALookWritesAllOfItIntoTheScene() {
        Look l = Look.draw(new Random(8));
        NodeGraphParams p = new NodeGraphParams(FractalType.MANDELBULB);
        p.setPathTracingEnabled(true);
        l.apply(p);
        assertEquals(l.coloringMode(), p.getColoringMode());
        assertEquals(l.skyType(), p.getSkyType());
        assertEquals(l.stops().length, p.getCustomGradient().getStops().size());
        assertEquals(l.lightIntensity(), p.getLightIntensity(), 1e-6f);
        assertEquals(l.metalness(), p.getMetalness(), 1e-6f);
        assertEquals(l.roughness(), p.getRoughness(), 1e-6f);
        assertEquals(l.rimIntensity(), p.getRimIntensity(), 1e-6f);
        assertFalse(p.isPathTracingEnabled(), "classic shading, so it stays interactive");
    }

    @Test
    void theShowcaseLookIsTheLibrarysAndNamesItself() {
        Look s = Look.showcase();
        assertEquals("Spectrum, showcase light, space", s.name());
        assertEquals(10, s.coloringMode());
        assertEquals(1, s.skyType());
    }
}
