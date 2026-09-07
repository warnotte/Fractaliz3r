package org.fractalizer.explore;

import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.GradientPalette;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A look for a discovery: palette, colouring mode, light, ambient, sky, material. Drawn
 * once per candidate from the same seed as its chain, applied before its thumbnail is
 * rendered and saved with it, so what the tile shows is what the click loads. Every value
 * is a uniform or a texture upload: a look never costs a shader compile.
 *
 * <p>Without it every find wore the chain library's showcase look — one palette, one sun,
 * one nebula, one metal — and a sheet of discoveries read as one material photographed
 * twelve times. The draw combines a curated palette, a lighting scheme, a sky and a
 * material within a luminance band, so the detail score stays comparable across looks.
 */
public record Look(String palette, float[][] stops, float paletteOffset, int coloringMode, float colorStrength,
                   String lighting, float[] lightDir, float[] lightColor, float lightIntensity,
                   float[] ambientColor, float ambientIntensity, float rimIntensity, float glowIntensity,
                   String sky, int skyType, float[] nebulaColor, float nebulaTint,
                   float metalness, float roughness, float specularIntensity, float specularPower) {

    /** "Ember, dawn light, space": what the tile says under the chain. */
    public String name() { return palette + ", " + lighting + ", " + sky; }

    // ------------------------------------------------------------- palettes

    record Palette(String name, float[][] stops) {}

    /** Curated gradients, each {position, r, g, b}. Dark-to-light or a hue walk; none of
     *  them so dark that a scene under them loses its detail score. */
    static final Palette[] PALETTES = {
        new Palette("Spectrum", new float[][]{{0f, .08f, .12f, .47f}, {.25f, .08f, .67f, .75f}, {.5f, .94f, .78f, .24f}, {.75f, .86f, .24f, .24f}, {1f, .59f, .16f, .67f}}),
        new Palette("Ember", new float[][]{{0f, .05f, .02f, .02f}, {.3f, .55f, .08f, .03f}, {.6f, .95f, .45f, .08f}, {.85f, 1f, .85f, .35f}, {1f, 1f, 1f, .9f}}),
        new Palette("Glacier", new float[][]{{0f, .02f, .08f, .25f}, {.4f, .10f, .45f, .75f}, {.75f, .55f, .85f, .95f}, {1f, .97f, .99f, 1f}}),
        new Palette("Verdigris", new float[][]{{0f, .03f, .12f, .12f}, {.35f, .10f, .45f, .40f}, {.7f, .45f, .72f, .55f}, {1f, .90f, .85f, .65f}}),
        new Palette("Nacre", new float[][]{{0f, .55f, .50f, .65f}, {.3f, .80f, .70f, .85f}, {.6f, .85f, .90f, .95f}, {1f, .98f, .92f, .85f}}),
        new Palette("Obsidian and gold", new float[][]{{0f, .03f, .03f, .04f}, {.45f, .20f, .16f, .12f}, {.75f, .85f, .65f, .25f}, {1f, 1f, .92f, .60f}}),
        new Palette("Coral", new float[][]{{0f, .10f, .05f, .20f}, {.35f, .70f, .15f, .35f}, {.7f, 1f, .45f, .35f}, {1f, 1f, .85f, .70f}}),
        new Palette("Plasma", new float[][]{{0f, .05f, .03f, .53f}, {.25f, .49f, .01f, .66f}, {.5f, .80f, .28f, .47f}, {.75f, .98f, .65f, .14f}, {1f, .94f, .98f, .13f}}),
        new Palette("Sandstone", new float[][]{{0f, .22f, .17f, .12f}, {.45f, .55f, .46f, .34f}, {.8f, .72f, .64f, .50f}, {1f, .86f, .80f, .66f}}),
        new Palette("Bismuth", new float[][]{{0f, .15f, .05f, .25f}, {.3f, .85f, .15f, .55f}, {.55f, .10f, .75f, .85f}, {.8f, .95f, .85f, .20f}, {1f, .90f, .95f, .95f}}),
        new Palette("Ultraviolet", new float[][]{{0f, .02f, 0f, .08f}, {.4f, .30f, .05f, .60f}, {.75f, .70f, .30f, .95f}, {1f, .95f, .85f, 1f}}),
        new Palette("Copper oxide", new float[][]{{0f, .10f, .06f, .04f}, {.4f, .60f, .30f, .15f}, {.7f, .20f, .60f, .55f}, {1f, .80f, .95f, .85f}}),
        new Palette("Ivory", new float[][]{{0f, .15f, .14f, .13f}, {.5f, .70f, .66f, .60f}, {1f, .98f, .96f, .92f}}),
        new Palette("Lagoon", new float[][]{{0f, .01f, .10f, .18f}, {.4f, .05f, .45f, .55f}, {.75f, .40f, .85f, .75f}, {1f, .95f, 1f, .90f}}),
        new Palette("Autumn", new float[][]{{0f, .12f, .06f, .03f}, {.35f, .55f, .20f, .05f}, {.65f, .90f, .50f, .10f}, {.85f, .95f, .80f, .30f}, {1f, .60f, .75f, .30f}}),
        new Palette("Rose quartz", new float[][]{{0f, .30f, .15f, .22f}, {.45f, .80f, .45f, .55f}, {.8f, .98f, .80f, .85f}, {1f, 1f, .97f, .95f}}),
    };

    // ------------------------------------------------------------- lighting

    record Lighting(String name, float[] dir, float[] colour, float intensity, float[] ambient, float ambientIntensity, float rim) {}

    /** One key light and an ambient, within a band of brightness: a moonlit scene is bluer
     *  and softer than a noon one, not darker by half. */
    static final Lighting[] LIGHTINGS = {
        new Lighting("dawn light", new float[]{2.5f, 1.0f, -2.0f}, new float[]{1.0f, .85f, .65f}, 1.4f, new float[]{.10f, .14f, .28f}, .35f, .10f),
        new Lighting("noon light", new float[]{0.5f, 3.0f, -1.0f}, new float[]{1f, 1f, 1f}, 1.3f, new float[]{.25f, .27f, .30f}, .30f, .04f),
        new Lighting("studio light", new float[]{1.5f, 2.5f, -3.0f}, new float[]{1f, .97f, .92f}, 1.2f, new float[]{.30f, .30f, .32f}, .45f, .06f),
        new Lighting("moonlight", new float[]{-2.0f, 2.0f, -1.5f}, new float[]{.65f, .75f, 1.0f}, 1.15f, new float[]{.12f, .10f, .25f}, .40f, .15f),
        new Lighting("backlight", new float[]{-1.0f, 1.5f, 3.0f}, new float[]{1f, .9f, .8f}, 1.5f, new float[]{.20f, .18f, .22f}, .40f, .30f),
        new Lighting("underwater light", new float[]{0.3f, 3.0f, 0.5f}, new float[]{.55f, .85f, .90f}, 1.25f, new float[]{.02f, .15f, .20f}, .45f, .08f),
        new Lighting("furnace light", new float[]{0f, -1.5f, -2.5f}, new float[]{1f, .6f, .3f}, 1.3f, new float[]{.25f, .08f, .05f}, .35f, .12f),
    };

    // ------------------------------------------------------------- skies

    record Sky(String name, int type) {}

    static final Sky[] SKIES = {
        new Sky("space", 1), new Sky("space", 1), new Sky("space", 1), new Sky("space", 1),
        new Sky("deep space", 4), new Sky("deep space", 4),
        new Sky("studio backdrop", 3), new Sky("studio backdrop", 3), new Sky("studio backdrop", 3),
        new Sky("daylight", 0),
    };

    static final float[][] NEBULAE = {
        {.16f, .20f, .72f}, {.55f, .15f, .35f}, {.10f, .40f, .45f}, {.45f, .30f, .10f}, {.30f, .10f, .50f}};

    /** Colouring modes that put more than one hue on an object (docs/RENDERING.md:
     *  modes 0-8 walk one hue, 9-12 several). Triplanar twice: it reads best on most. */
    static final int[] MODES = {9, 10, 10, 11, 12};

    private static float uni(Random rnd, float lo, float hi) { return lo + rnd.nextFloat() * (hi - lo); }

    /** A look from the seed's stream: same seed, same look. */
    public static Look draw(Random rnd) {
        Palette pal = PALETTES[rnd.nextInt(PALETTES.length)];
        Lighting li = LIGHTINGS[rnd.nextInt(LIGHTINGS.length)];
        Sky sky = SKIES[rnd.nextInt(SKIES.length)];
        float[] dir = {li.dir()[0] * uni(rnd, .8f, 1.2f), li.dir()[1] * uni(rnd, .8f, 1.2f), li.dir()[2] * uni(rnd, .8f, 1.2f)};
        return new Look(pal.name(), pal.stops(), uni(rnd, 0f, 1f), MODES[rnd.nextInt(MODES.length)], uni(rnd, .8f, 1.2f),
                li.name(), dir, li.colour(), li.intensity() * uni(rnd, .9f, 1.1f),
                li.ambient(), li.ambientIntensity(), li.rim(), uni(rnd, 0f, .15f),
                sky.name(), sky.type(), NEBULAE[rnd.nextInt(NEBULAE.length)], 1.0f,
                uni(rnd, 0f, .6f), uni(rnd, .2f, .7f), uni(rnd, .3f, 1.0f), uni(rnd, 20f, 90f));
    }

    /** The look a scene wears now, so a chain loaded from anywhere can be bred with the
     *  palette and light it has. Its parts are named for what they are. */
    public static Look of(AbstractFractalParams p) {
        List<GradientPalette.ColorStop> cs = p.getCustomGradient() == null ? List.of() : p.getCustomGradient().getStops();
        float[][] st = new float[Math.max(2, cs.size())][];
        if (cs.size() < 2) {
            st[0] = new float[]{0f, .1f, .1f, .1f};
            st[1] = new float[]{1f, .9f, .9f, .9f};
        } else {
            for (int i = 0; i < cs.size(); i++) {
                GradientPalette.ColorStop c = cs.get(i);
                st[i] = new float[]{(float) c.position(), (float) c.color().getRed(), (float) c.color().getGreen(), (float) c.color().getBlue()};
            }
        }
        return new Look("scene palette", st, p.getPaletteOffset(), p.getColoringMode(), p.getColorStrength(),
                "scene light", new float[]{p.getLightX(), p.getLightY(), p.getLightZ()},
                new float[]{p.getLightR(), p.getLightG(), p.getLightB()}, p.getLightIntensity(),
                new float[]{p.getAmbientR(), p.getAmbientG(), p.getAmbientB()}, p.getAmbientIntensity(),
                p.getRimIntensity(), p.getGlowIntensity(),
                "scene sky", p.getSkyType(), p.getNebulaColor(), p.getNebulaTint(),
                p.getMetalness(), p.getRoughness(), p.getSpecularIntensity(), p.getSpecularPower());
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** A child's look: the parent's, nudged. The palette scrolls a little, the light moves
     *  a little, the metal and the roughness drift; one time in seven the palette is a new
     *  one, one in seven the light, one in ten the sky or the colouring mode. */
    public Look mutate(Random rnd) {
        String pal = palette; float[][] st = stops;
        if (rnd.nextInt(7) == 0) { Palette np = PALETTES[rnd.nextInt(PALETTES.length)]; pal = np.name(); st = np.stops(); }
        String li = lighting; float[] dir = lightDir.clone(); float[] lc = lightColor; float lint = lightIntensity;
        float[] amb = ambientColor; float ambI = ambientIntensity; float rim = rimIntensity;
        if (rnd.nextInt(7) == 0) {
            Lighting nl = LIGHTINGS[rnd.nextInt(LIGHTINGS.length)];
            li = nl.name(); dir = nl.dir().clone(); lc = nl.colour(); lint = nl.intensity(); amb = nl.ambient(); ambI = nl.ambientIntensity(); rim = nl.rim();
        }
        for (int i = 0; i < 3; i++) dir[i] *= uni(rnd, .85f, 1.15f);
        lint = clamp(lint * uni(rnd, .95f, 1.05f), 1.0f, 1.7f);
        String skyName = sky; int skyT = skyType; float[] neb = nebulaColor;
        if (rnd.nextInt(10) == 0) { Sky ns = SKIES[rnd.nextInt(SKIES.length)]; skyName = ns.name(); skyT = ns.type(); neb = NEBULAE[rnd.nextInt(NEBULAE.length)]; }
        int mode = rnd.nextInt(10) == 0 ? MODES[rnd.nextInt(MODES.length)] : coloringMode;
        float off = paletteOffset + (float) (rnd.nextGaussian() * 0.1);
        off -= (float) Math.floor(off);
        return new Look(pal, st, off, mode, clamp(colorStrength * uni(rnd, .95f, 1.05f), .8f, 1.2f),
                li, dir, lc, lint, amb, ambI, rim, clamp(glowIntensity + (float) (rnd.nextGaussian() * 0.03), 0f, .15f),
                skyName, skyT, neb, nebulaTint,
                clamp(metalness + (float) (rnd.nextGaussian() * 0.1), 0f, .6f), clamp(roughness + (float) (rnd.nextGaussian() * 0.1), .2f, .7f),
                clamp(specularIntensity * uni(rnd, .9f, 1.1f), .3f, 1f), clamp(specularPower * uni(rnd, .9f, 1.1f), 20f, 90f));
    }

    /** The chain library's showcase look, as {@code HybridPresets.showcaseLook} sets it. */
    public static Look showcase() {
        return new Look("Spectrum", PALETTES[0].stops(), .05f, 10, 1.0f,
                "showcase light", new float[]{2f, 3f, -2f}, new float[]{1f, 1f, 1f}, 1.3f,
                new float[]{.10f, .13f, .20f}, .33f, .03f, 0f,
                "space", 1, new float[]{.16f, .20f, .72f}, 1.0f,
                .35f, .35f, 1.0f, 32f);
    }

    /** Write the look into a scene. Classic shading, so it stays interactive and thumbnails
     *  render in milliseconds. */
    public void apply(AbstractFractalParams p) {
        List<GradientPalette.ColorStop> cs = new ArrayList<>();
        for (float[] s : stops) cs.add(new GradientPalette.ColorStop(s[0], Color.color(s[1], s[2], s[3])));
        p.setCustomGradient(new GradientPalette(cs));
        p.setColoringMode(coloringMode);
        p.setColorStrength(colorStrength);
        p.setPaletteOffset(paletteOffset);
        p.setRimIntensity(rimIntensity);
        p.setGlowIntensity(glowIntensity);
        p.setSkyType(skyType);
        p.setNebulaColor(nebulaColor[0], nebulaColor[1], nebulaColor[2]);
        p.setNebulaTint(nebulaTint);
        p.setLightDirection(lightDir[0], lightDir[1], lightDir[2]);
        p.setLightColor(lightColor[0], lightColor[1], lightColor[2]);
        p.setLightIntensity(lightIntensity);
        p.setAmbientColor(ambientColor[0], ambientColor[1], ambientColor[2]);
        p.setAmbientIntensity(ambientIntensity);
        p.setMaterialType(0);
        p.setMetalness(metalness);
        p.setRoughness(roughness);
        p.setSpecularIntensity(specularIntensity);
        p.setSpecularPower(specularPower);
        p.setPathTracingEnabled(false);
    }
}
