package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * Builds demo presets and renders a preview for each, so a candidate can be judged
 * before it is kept.
 *
 * Cameras come from FractalNavigator sweet spots (see out/nav/detail_scenes.txt and the
 * traveller logs) rather than default global framings — the point of a demo preset is
 * the fine detail, which default cameras do not show.
 *
 * Usage:
 *   -Dexec.args="&lt;outDir&gt; &lt;previewDir&gt; &lt;WxH&gt; &lt;samples&gt;"
 *   -Dexec.args="presets out/presets_preview 640x360 24"
 */
public class PresetForge {

    /** Julia Mandelbulb constant validated for deep-zoom detail (see docs/RENDERING.md). */
    private static final double JCX = 0.42, JCY = 0.18, JCZ = -0.31;

    // Palette gradients — {position, r, g, b}. The gradient is what colours the
    // fractal; paletteIndex does not feed the palette texture.
    private static final float[][] AMBER = {
        {0.00f, 0.00f, 0.00f, 0.04f}, {0.25f, 0.35f, 0.00f, 0.15f},
        {0.50f, 0.90f, 0.30f, 0.00f}, {0.75f, 1.00f, 0.85f, 0.20f}, {1.00f, 1.00f, 1.00f, 0.85f}};
    private static final float[][] ICE = {
        {0.00f, 0.01f, 0.03f, 0.09f}, {0.35f, 0.06f, 0.30f, 0.55f},
        {0.65f, 0.40f, 0.78f, 0.95f}, {1.00f, 0.92f, 0.99f, 1.00f}};
    private static final float[][] VIOLET = {
        {0.00f, 0.03f, 0.00f, 0.10f}, {0.30f, 0.32f, 0.04f, 0.45f},
        {0.60f, 0.85f, 0.25f, 0.62f}, {1.00f, 1.00f, 0.86f, 0.96f}};
    private static final float[][] SPECTRUM = {
        {0.00f, 0.08f, 0.12f, 0.47f}, {0.25f, 0.08f, 0.67f, 0.75f},
        {0.50f, 0.94f, 0.78f, 0.24f}, {0.75f, 0.86f, 0.24f, 0.24f}, {1.00f, 0.59f, 0.16f, 0.67f}};
    private static final float[][] EMERALD = {
        {0.00f, 0.01f, 0.05f, 0.03f}, {0.35f, 0.04f, 0.33f, 0.20f},
        {0.70f, 0.45f, 0.85f, 0.38f}, {1.00f, 0.95f, 1.00f, 0.78f}};

    // --- Hybrid chains: several formulas composed inside one iteration loop. No CSG
    // combination of two finished distance fields can reach these shapes. ---

    private static Step bulbStep(float power) {
        Step s = new Step(StepType.BULB);
        s.setPower(power);
        return s;
    }

    private static Step boxFoldStep(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.BOX_FOLD);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    private static Step rotateStep(float rx, float ry, float rz) {
        Step s = new Step(StepType.ROTATE);
        s.setRotX(rx); s.setRotY(ry); s.setRotZ(rz);
        return s;
    }

    static Map<String, Supplier<SceneBuilder>> presets() {
        Map<String, Supplier<SceneBuilder>> p = new LinkedHashMap<>();

        // --- Julia Mandelbulb: fractal at every scale, unlike the Mandelbrot form ---
        p.put("JULIA_BULB_OVERVIEW", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.30f, -2.30f).lookAt(0f, 0.30f, 0f).fov(45)
                .gradient(SPECTRUM).coloringMode(10)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.14f, 0.22f, 0.62f, 1.0f)
                .lightDir(2, 3, -2).lightIntensity(1.3f)
                .ambientColor(0.10f, 0.14f, 0.22f).ambientIntensity(0.35f)
                .palette(2).colorStrength(0.9f)
                .metalness(0.25f).roughness(0.45f));

        p.put("JULIA_BULB_SWEETSPOT", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.319f, -1.461f).lookAt(0f, 0.457f, -0.793f).fov(45)
                .gradient(VIOLET)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.10f, 0.48f, 0.50f, 1.0f)
                .lightDir(2.5f, 2.0f, -1.5f).lightIntensity(1.6f)
                .ambientColor(0.07f, 0.08f, 0.13f).ambientIntensity(0.18f)
                .palette(5).colorStrength(1.0f)
                .metalness(0.15f).roughness(0.45f));

        p.put("JULIA_BULB_ABYSS", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.450f, -0.827f).lookAt(0f, 0.457f, -0.793f).fov(45)
                .detailLOD(2f, 24)
                .gradient(SPECTRUM).coloringMode(9)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.18f, 0.16f, 0.60f, 1.0f)
                .lightDir(2f, 1.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.16f, 0.12f, 0.10f).ambientIntensity(0.45f)
                .palette(1).colorStrength(1.1f)
                .metalness(0.35f).roughness(0.30f));

        p.put("JULIA_BULB_CORAL", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20).param("power", 8.0)
                .param("juliaCx", -0.15).param("juliaCy", 0.55).param("juliaCz", 0.20)
                .camera(0f, 0f, -2.40f).lookAt(0f, 0f, 0f).fov(45)
                .gradient(ICE)
                .pathTracing(true).rimIntensity(0.03f).skyType(2)
                .lightDir(-2f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.10f, 0.16f, 0.20f).ambientIntensity(0.35f)
                .palette(4).colorStrength(0.8f)
                .metalness(0.4f).roughness(0.3f));

        // --- Found by JuliaProspector, not by hand: constants sitting on the Mandelbulb
        // boundary whose Julia sets branch instead of closing into a ball. ---
        p.put("JULIA_FOUND_BRANCH", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", -0.2422).param("juliaCy", 0.9995).param("juliaCz", 0.2219)
                .camera(1.20f, 0.85f, -1.85f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(SPECTRUM).coloringMode(9)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.42f, 0.14f, 0.52f, 1.0f)
                .lightDir(2f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.09f, 0.14f, 0.18f).ambientIntensity(0.32f)
                .colorStrength(0.9f).metalness(0.35f).roughness(0.35f));

        p.put("JULIA_FOUND_CLUSTER", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", 0.7126).param("juliaCy", 0.7796).param("juliaCz", 0.2602)
                .camera(1.20f, 0.85f, -1.85f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(SPECTRUM).coloringMode(9)
                .pathTracing(true).rimIntensity(0.03f).skyType(2)
                .lightDir(-1.8f, 2.2f, -2f).lightIntensity(1.4f)
                .ambientColor(0.12f, 0.10f, 0.18f).ambientIntensity(0.34f)
                .colorStrength(0.9f).metalness(0.3f).roughness(0.4f));

        p.put("HYBRID_BOXBULB", () -> SceneBuilder.nodeGraph(new HybridNode(
                    java.util.List.of(bulbStep(8f), boxFoldStep(1.2f, 0.5f, 1f, 1f),
                                      new Step(StepType.ADD_C)),
                    12, 8f, DEMode.LOG))
                .camera(1.26f, 0.90f, -2.55f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(SPECTRUM).coloringMode(10)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.16f, 0.20f, 0.72f, 1.0f)
                .lightDir(2f, 3f, -2f).lightIntensity(1.3f)
                .ambientColor(0.10f, 0.13f, 0.20f).ambientIntensity(0.33f)
                .colorStrength(1.0f).paletteOffset(0.05f)
                .metalness(0.35f).roughness(0.35f));

        p.put("HYBRID_ROTOBOX", () -> SceneBuilder.nodeGraph(new HybridNode(
                    java.util.List.of(bulbStep(6f), rotateStep(24f, 37f, 0f),
                                      boxFoldStep(1.3f, 0.5f, 1f, 1f), new Step(StepType.ADD_C)),
                    12, 8f, DEMode.LOG))
                .camera(1.26f, 0.90f, -2.55f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(SPECTRUM).coloringMode(11)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.10f, 0.52f, 0.55f, 1.0f)
                .lightDir(-1.8f, 2.4f, -2f).lightIntensity(1.4f)
                .ambientColor(0.09f, 0.13f, 0.20f).ambientIntensity(0.33f)
                .colorStrength(1.0f).paletteOffset(0.45f).metalness(0.4f).roughness(0.3f));

        // --- Mandelbox: self-similar, holds detail at any zoom; LOD earns its cost here ---
        p.put("MANDELBOX_DEEP", () -> SceneBuilder.mandelbox()
                .param("scale", 2.0).param("minRadius", 0.25)
                .camera(-1.4863f, 1.4465f, -6.0484f).lookAt(-1.45361f, 1.45941f, -6.01285f).fov(45)
                .detailLOD(2f, 24)
                .gradient(SPECTRUM).coloringMode(10)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.16f, 0.20f, 0.72f, 1.0f)
                .lightDir(2, 3, -1).lightIntensity(1.9f)
                .ambientColor(0.12f, 0.16f, 0.22f).ambientIntensity(0.55f)
                .palette(3).colorStrength(0.85f)
                .metalness(0.5f).roughness(0.25f));

        p.put("MANDELBOX_LEDGE", () -> SceneBuilder.mandelbox()
                .param("scale", 2.0).param("minRadius", 0.25)
                .camera(-1.5518f, 1.4207f, -6.1194f).lookAt(-1.45361f, 1.45941f, -6.01285f).fov(45)
                .detailLOD(2f, 24)
                .gradient(VIOLET)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.12f, 0.45f, 0.48f, 1.0f)
                .lightDir(-1.5f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.10f, 0.12f, 0.18f).ambientIntensity(0.30f)
                .palette(0).colorStrength(1.0f)
                .metalness(0.2f).roughness(0.5f));

        // --- Detail camera from the traveller manifest ---
        p.put("MENGER_WALL", () -> SceneBuilder.menger()
                .camera(-0.21993f, 1.03134f, -2.08363f).lookAt(-0.57876f, -0.08390f, -0.96397f).fov(50)
                .gradient(SPECTRUM).coloringMode(9)
                .pathTracing(true).rimIntensity(0.03f).skyType(1)
                .nebula(0.62f, 0.30f, 0.10f, 1.0f)
                .lightDir(2, 3, -2).lightIntensity(1.3f)
                .ambientColor(0.12f, 0.14f, 0.20f).ambientIntensity(0.35f)
                .palette(4).colorStrength(0.9f)
                .metalness(0.4f).roughness(0.35f));

        // --- The Labyrinth: a world to walk, not a view. An infinite stone maze (the
        // Menger sponge, from inside) whose central junction is Jareth's Escher room:
        // three staircases, each climbing under its own gravity, and the crystal ball.
        // Walk with the arrow keys; the lantern is on the camera.
        p.put("LABYRINTH", () -> SceneBuilder.nodeGraph(labyrinthWorld())
                .camera(0f, -0.21f, -0.92f).lookAt(0f, -0.14f, 0f).fov(62)
                .moveSpeed(0.02f)
                .maxRaySteps(400)
                .gradient(STONE).coloringMode(0)
                .pathTracing(true).rimIntensity(0.0f).skyType(1)
                .nebula(0.05f, 0.04f, 0.10f, 1.0f)
                .lightDir(0.4f, 1.0f, -0.3f).lightIntensity(0.35f)
                .ambientColor(0.42f, 0.36f, 0.30f).ambientIntensity(0.38f)
                .lantern(4.0f, 2.0f, 1.0f, 0.82f, 0.62f)
                .fog(0.10f).fogColor(0.30f, 0.24f, 0.18f)
                .colorStrength(0.8f).metalness(0.05f).roughness(0.8f));

        // --- Albedo 0.39: a world seen from orbit at dawn. The overview effect, the thing
        // astronauts come back with: one sphere, no borders. Continents are a sphere carved
        // by the engine's fractal noise, the sea a smooth sphere underneath, so every
        // coastline is an iso-line of fbm; snow above one radius, ice beyond one latitude;
        // a single low sun draws the terminator; the rim light is the atmosphere.
        p.put("ALBEDO_039", () -> SceneBuilder.nodeGraph(blueWorld())
                .camera(0f, 0.62f, -2.95f).lookAt(0.12f, -0.02f, 0f).fov(48)
                .moveSpeed(0.05f)
                .maxRaySteps(300)
                .gradient(SPECTRUM).coloringMode(0)
                .pathTracing(false).rimIntensity(0.45f).skyType(1)
                .nebula(0.05f, 0.07f, 0.16f, 0.7f)
                .lightDir(2.6f, 0.7f, 0.5f).lightColor(1.0f, 0.92f, 0.80f).lightIntensity(2.3f)
                .ambientColor(0.16f, 0.28f, 0.60f).ambientIntensity(0.07f)
                .glowIntensity(0.25f)
                .colorStrength(1.0f).metalness(0.0f).roughness(0.7f));

        return p;
    }

    /**
     * The blue world. Radii: sea 1.00; land 1.05 carved down by fractal noise, so the
     * basins fall below the sea and the plateaus stay above it; desert where the land is
     * still above 1.026; ice caps beyond |y| = 0.86. A moon, cratered by the same noise.
     * Two erosion passes on the land: thermal only (type 2, isotropic, low frequency)
     * for the continents, weathering only (type 3, fine, signed) for the mountains.
     * The plateaus the noise leaves highest are deserts, ochre; the poles are white.
     */
    static org.fractalizer.graph.GraphNode blueWorld() {
        // The graph is a tree, not a DAG: the ice branch and the temperate branch each get
        // their own globe. A node used twice is serialized twice anyway, and the compiler
        // would emit it twice.
        org.fractalizer.graph.GraphNode temperate = SceneBuilder.subtract(colouredGlobe(), polarSlabs());
        temperate.setName("Temperate");
        org.fractalizer.graph.GraphNode ice = material(SceneBuilder.intersect(globeShape(), polarSlabs()), 0.92f, 0.96f, 1.0f, 0.6f, 0f);
        ice.setName("Ice");
        org.fractalizer.graph.GraphNode planet = SceneBuilder.union(temperate, ice);
        planet.setName("Planet");

        org.fractalizer.graph.GraphNode moonBall = SceneBuilder.erode(sphere(0.27f), 0.7f, 4.0f, 0.4f, 3);
        org.fractalizer.graph.GraphNode moon = material(SceneBuilder.translate(moonBall, -2.3f, 1.05f, 1.6f), 0.55f, 0.53f, 0.50f, 0.95f, 0f);
        moon.setName("Moon");

        // No cloud shell: tried three times. A shell carved by fbm comes out as thick
        // white plates with smooth edges, ice floes rather than clouds, whatever the
        // scale. Clouds need soft edges, which a distance field does not give.
        // No glass atmosphere either: a sphere of glass around the planet turns it into
        // a black disc under path tracing and a white ball under classic shading. The
        // halo comes from the rim light.
        org.fractalizer.graph.GraphNode world = SceneBuilder.union(planet, moon);
        world.setName("Albedo 0.39");
        return world;
    }

    /** The carved land: radius 1.046, continents by thermal noise, mountains by weathering.
     *  Carve depth is fbm * K with K = 0.35 * time * strength * scale * 0.05 = 0.112, so the
     *  sea (radius 1) shows where fbm > 0.41 and the deserts stay where fbm < 0.18. */
    private static org.fractalizer.graph.GraphNode land() {
        org.fractalizer.graph.GraphNode land = SceneBuilder.erode(sphere(1.046f), 0.8f, 4.0f, 2.0f, 2);   // continents
        land = SceneBuilder.erode(land, 0.9f, 5.0f, 0.5f, 3);                                            // mountains
        land.setName("Land");
        return land;
    }

    /** Land and sea, bare geometry. */
    private static org.fractalizer.graph.GraphNode globeShape() {
        return SceneBuilder.union(land(), sphere(1.0f));
    }

    /** Land and sea with their colours: green lowland, ochre desert above 1.026, blue sea. */
    private static org.fractalizer.graph.GraphNode colouredGlobe() {
        org.fractalizer.graph.GraphNode lowland = material(SceneBuilder.intersect(land(), sphere(1.026f)), 0.19f, 0.40f, 0.15f, 0.85f, 0f);
        lowland.setName("Lowland");
        org.fractalizer.graph.GraphNode highland = material(SceneBuilder.subtract(land(), sphere(1.026f)), 0.78f, 0.66f, 0.42f, 0.9f, 0f);
        highland.setName("Highland");
        org.fractalizer.graph.GraphNode sea = material(sphere(1.0f), 0.03f, 0.12f, 0.36f, 0.12f, 0f);
        sea.setName("Ocean");
        return SceneBuilder.union(SceneBuilder.union(lowland, highland), sea);
    }

    /** Two slabs beyond |y| = 0.86: what they cut out of the globe is polar ice. */
    private static org.fractalizer.graph.GraphNode polarSlabs() {
        return SceneBuilder.union(
                SceneBuilder.translate(box(2.0f, 0.40f, 2.0f), 0f, 1.26f, 0f),
                SceneBuilder.translate(box(2.0f, 0.40f, 2.0f), 0f, -1.26f, 0f));
    }

    private static org.fractalizer.graph.PrimitiveNode sphere(float radius) {
        org.fractalizer.graph.PrimitiveNode s = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.SPHERE);
        s.setSizeX(radius);
        return s;
    }

    private static org.fractalizer.graph.PrimitiveNode box(float hx, float hy, float hz) {
        org.fractalizer.graph.PrimitiveNode b = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        b.setSizeX(hx); b.setSizeY(hy); b.setSizeZ(hz);
        return b;
    }

    /** Warm sandstone, for the parts of the labyrinth that keep the palette. */
    private static final float[][] STONE = {
        {0.00f, 0.22f, 0.17f, 0.12f}, {0.45f, 0.55f, 0.46f, 0.34f},
        {0.80f, 0.72f, 0.64f, 0.50f}, {1.00f, 0.86f, 0.80f, 0.66f}};

    /**
     * The labyrinth's node graph. The maze is a Menger sponge of side 2, so its
     * corridors are 2/3 wide and its central junction (|x|,|y|,|z| < 1/3) is a room with
     * six doorways. The staircases live in that room: each is a box step repeated along
     * a diagonal — a rotation, a 1D repetition along X, and the inverse rotation, so the
     * steps stay axis-aligned while their line climbs — clipped to the room by an
     * intersection. The same staircase turned about Z climbs under a sideways gravity,
     * turned about X under a third; that is the Escher room. The crystal is an emissive
     * sphere at the centre.
     */
    static org.fractalizer.graph.GraphNode labyrinthWorld() {
        org.fractalizer.graph.FractalNode menger = SceneBuilder.fractal(FractalType.MENGER_SPONGE);
        org.fractalizer.fractals.MengerSpongeParams mp = (org.fractalizer.fractals.MengerSpongeParams) menger.getFractalParams();
        mp.setMaxIterations(6);
        mp.setScale(3f);
        mp.setOffset(1f, 1f, 1f);
        org.fractalizer.graph.GraphNode walls = SceneBuilder.erode(menger, 0.02f, 1.5f, 2.5f);
        walls = material(walls, 0.56f, 0.47f, 0.36f, 0.85f, 0f);
        walls.setName("Stone maze");

        org.fractalizer.graph.GraphNode stairsA = staircase();                     // climbs +Y along +X
        stairsA = SceneBuilder.translate(stairsA, 0f, 0f, -0.16f);
        org.fractalizer.graph.GraphNode stairsB = SceneBuilder.rotate(staircase(), 0f, 0f, 90f);   // gravity +X
        stairsB = SceneBuilder.translate(stairsB, 0f, 0f, 0.16f);
        org.fractalizer.graph.GraphNode stairsC = SceneBuilder.rotate(staircase(), 90f, 0f, 0f);   // gravity -Z
        stairsC = SceneBuilder.translate(stairsC, 0.18f, 0f, 0f);
        org.fractalizer.graph.GraphNode stairs = SceneBuilder.union(SceneBuilder.union(stairsA, stairsB), stairsC);
        stairs = material(stairs, 0.74f, 0.68f, 0.56f, 0.7f, 0f);
        stairs.setName("Escher stairs");

        org.fractalizer.graph.PrimitiveNode orb = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.SPHERE);
        orb.setSizeX(0.07f);
        org.fractalizer.graph.GraphNode crystal = SceneBuilder.translate(orb, 0f, 0.10f, 0f);
        crystal = material(crystal, 1.0f, 0.93f, 0.80f, 0.2f, 9f);
        crystal.setName("Crystal");

        // The plain the labyrinth stands on: walk out of any corridor and the whole
        // building is there behind you, under the space sky. A plane sits at y = sizeX,
        // and sizeX cannot go below 0.01, so it is pushed down by a translation.
        org.fractalizer.graph.PrimitiveNode plain = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.PLANE);
        plain.setSizeX(0.01f);
        org.fractalizer.graph.GraphNode ground = SceneBuilder.translate(plain, 0f, -1.012f, 0f);
        ground = material(ground, 0.34f, 0.29f, 0.22f, 0.95f, 0f);
        ground.setName("Plain");

        org.fractalizer.graph.GraphNode world = SceneBuilder.union(
                SceneBuilder.union(SceneBuilder.union(walls, stairs), crystal), ground);
        world.setName("Labyrinth");
        return world;
    }

    /** Ten axis-aligned steps climbing the diagonal of the junction room, from its floor
     *  at y = -1/3 up to y = +0.27, 0.2 wide. */
    private static org.fractalizer.graph.GraphNode staircase() {
        float rise = 0.06f, run = 0.06f;
        float theta = (float) Math.toDegrees(Math.atan2(rise, run));
        float period = (float) Math.sqrt(rise * rise + run * run);
        org.fractalizer.graph.PrimitiveNode step = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        step.setSizeX(run * 0.5f);
        step.setSizeY(rise);          // twice the rise: each box overlaps the one below, a solid flight
        step.setSizeZ(0.13f);
        org.fractalizer.graph.GraphNode line = SceneBuilder.rotate(step, 0f, 0f, theta);          // inverse rotation: steps stay level
        line = SceneBuilder.repeat1D(line, 0, period);                                            // along X
        line = SceneBuilder.rotate(line, 0f, 0f, -theta);                                          // the line of steps climbs
        org.fractalizer.graph.PrimitiveNode region = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        region.setSizeX(0.30f);
        region.setSizeY(0.30f);
        region.setSizeZ(0.14f);
        org.fractalizer.graph.GraphNode clipped = SceneBuilder.intersect(SceneBuilder.translate(region, 0f, -0.03f, 0f), line);
        return clipped;
    }

    private static org.fractalizer.graph.MaterialNode material(org.fractalizer.graph.GraphNode child,
                                                               float r, float g, float b, float roughness, float emission) {
        org.fractalizer.graph.MaterialNode m = new org.fractalizer.graph.MaterialNode(child);
        m.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID);
        m.setColorR(r); m.setColorG(g); m.setColorB(b);
        m.setRoughness(roughness);
        m.setMetallic(0f);
        m.setEmission(emission);
        return m;
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "presets";
        String previewDir = args.length > 1 ? args[1] : "out/presets_preview";
        String[] res = (args.length > 2 ? args[2] : "640x360").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 24;
        // Optional 5th arg: only the presets whose name contains it are written and
        // rendered. Presets on disk may have been tuned by hand since they were forged;
        // rebuilding one should not silently rewrite the others.
        String only = args.length > 4 ? args[4] : null;

        new File(outDir).mkdirs();
        new File(previewDir).mkdirs();

        Map<String, Supplier<SceneBuilder>> specs = presets();
        List<String> written = new ArrayList<>();
        for (var e : specs.entrySet()) {
            if (only != null && !e.getKey().contains(only)) continue;
            e.getValue().get().writeTo(new File(outDir, e.getKey() + ".frac"));
            written.add(e.getKey());
        }
        System.out.printf("wrote %d presets to %s%n", written.size(), new File(outDir).getAbsolutePath());

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        System.out.printf("%n=== previews (%dx%d, %d spp) ===%n", W, H, samples);
        for (String name : written) {
            File frac = new File(outDir, name + ".frac");
            FractalConfig cfg = FractalConfigManager.load(frac);
            FractalType type = cfg.getFractalTypeEnum();
            controller.setFractalType(type);
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
            // The gradient only reaches the GPU through this call; without it every
            // render comes out monochrome regardless of the stops in the preset.
            controller.updatePaletteTexture(params.getCustomGradient());

            controller.setExportSize(W, H);
            long t0 = System.nanoTime();
            controller.exportToPNG(new File(previewDir, name + ".png"), samples, p -> {}, () -> false).get();
            System.out.printf("  %-22s %-22s %6d ms%n", name, type, (System.nanoTime() - t0) / 1_000_000);
            System.out.flush();
        }
        System.out.println();
        System.out.println("previews -> " + new File(previewDir).getAbsolutePath());
        System.exit(0);
    }
}
