package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Albedo 0.39, the blue world: a planet whose coastlines are iso-lines of the engine's
 * fractal noise. The graph must compile, survive a save, and the shipped preset must put
 * the viewer in orbit with the atmosphere (the rim light) on.
 */
class BlueWorldTest {

    @Test
    void theWorldCompilesWithItsSixMaterials() {
        GraphNode world = PresetForge.blueWorld();
        String glsl = assertDoesNotThrow(() -> new GraphCompiler().compile(world));
        assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"));
        // lowland, highland, ocean, ice, moon
        assertEquals(5, LabyrinthWorldTest.countMaterials(world), "lowland, desert, ocean, ice, moon");
    }

    @Test
    void theWorldSurvivesASaveAndALoad() {
        GraphNode world = PresetForge.blueWorld();
        GraphNode back = FractalConfig.deserializeGraphNode(FractalConfig.serializeGraphNode(world));
        assertNotNull(back);
        assertEquals(LabyrinthWorldTest.countNodes(world), LabyrinthWorldTest.countNodes(back));
        assertEquals(new GraphCompiler().compile(world), new GraphCompiler().compile(back));
    }

    @Test
    void theShippedPresetIsInOrbitAtDawnWithTheAtmosphereOn() throws Exception {
        File f = new File("presets/ALBEDO_039.frac");
        assertTrue(f.isFile(), "presets/ALBEDO_039.frac is shipped (PresetForge ... ALBEDO)");
        FractalConfig cfg = FractalConfigManager.load(f);
        NodeGraphParams fresh = cfg.toFreshParams();
        assertEquals(LabyrinthWorldTest.countNodes(PresetForge.blueWorld()), LabyrinthWorldTest.countNodes(fresh.getGraphRoot()));
        float[] eye = fresh.getCamera().getPosition();
        double r = Math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]);
        assertTrue(r > 2.5 && r < 4.0, "in orbit, a few radii out: " + r);
        assertTrue(fresh.getRimIntensity() > 0.3f, "the rim light is the atmosphere");
        assertFalse(fresh.isPathTracingEnabled(), "classic shading: that is where the halo lives");
        float[] sun = cfg.lighting.direction;
        assertTrue(sun[0] > 0 && Math.abs(sun[0]) > Math.abs(sun[2]), "a low sun from the side, so the terminator crosses the disc");
        assertTrue(fresh instanceof AbstractFractalParams);
    }
}
