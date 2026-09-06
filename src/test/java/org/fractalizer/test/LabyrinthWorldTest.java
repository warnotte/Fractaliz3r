package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.PrimitiveNode;
import org.fractalizer.graph.TransformNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Labyrinth is a world built from nodes — a fractal maze, three Escher staircases,
 * a crystal, a plain. This checks that the graph compiles, that it survives a save and a
 * load, that the staircase is really a repetition (the helper once set the period on the
 * wrong field, which left every step on top of the previous one), and that the shipped
 * preset is that graph with the camera inside the maze.
 */
class LabyrinthWorldTest {

    @Test
    void theWorldCompilesToOneShaderWithEveryPart() {
        GraphNode world = PresetForge.labyrinthWorld();
        NodeGraphParams ngp = new NodeGraphParams();
        ngp.setGraphRoot(world);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = assertDoesNotThrow(() -> compiler.compile(world));
        assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"));
        Map<String, Object> uniforms = compiler.getUniforms(world);
        assertTrue(uniforms.keySet().stream().anyMatch(k -> k.endsWith("_period")), "a repetition for the steps: " + uniforms.keySet());
        assertTrue(uniforms.keySet().stream().anyMatch(k -> k.endsWith("_maxIterations")), "the Menger maze");
        assertEquals(4, countMaterials(world), "walls, stairs, crystal, plain");
    }

    @Test
    void theStaircaseIsARepetitionWithARealPeriod() {
        PrimitiveNode step = new PrimitiveNode(PrimitiveNode.PrimitiveType.BOX);
        TransformNode rep = SceneBuilder.repeat1D(step, 0, 0.085f);
        assertEquals(TransformNode.Mode.REPETITION_1D, rep.getMode());
        assertEquals(0, rep.getAxis());
        assertEquals(0.085f, rep.getOffset()[0], 1e-6f, "the compiler reads the period from offset[axis]");
        Map<String, Object> u = new GraphCompiler().getUniforms(rep);
        Object period = u.entrySet().stream().filter(e -> e.getKey().endsWith("_period")).findFirst().orElseThrow().getValue();
        assertEquals(0.085f, ((Number) period).floatValue(), 1e-6f);
    }

    @Test
    void theWorldSurvivesASaveAndALoad() {
        GraphNode world = PresetForge.labyrinthWorld();
        Map<String, Object> map = FractalConfig.serializeGraphNode(world);
        GraphNode back = FractalConfig.deserializeGraphNode(map);
        assertNotNull(back);
        assertEquals(countNodes(world), countNodes(back), "same number of nodes after the round trip");
        assertEquals(countMaterials(world), countMaterials(back));
        String a = new GraphCompiler().compile(world), b = new GraphCompiler().compile(back);
        assertEquals(a, b, "the same shader comes out of the loaded graph");
    }

    @Test
    void theShippedPresetStartsInsideTheMazeLookingAtTheJunction() throws Exception {
        File f = new File("presets/LABYRINTH.frac");
        assertTrue(f.isFile(), "presets/LABYRINTH.frac is shipped (PresetForge ... LABYRINTH)");
        FractalConfig cfg = FractalConfigManager.load(f);
        NodeGraphParams fresh = cfg.toFreshParams();
        assertNotNull(fresh.getGraphRoot());
        assertEquals(countNodes(PresetForge.labyrinthWorld()), countNodes(fresh.getGraphRoot()), "the file is the forged world");
        float[] eye = fresh.getCamera().getPosition();
        assertTrue(Math.abs(eye[0]) < 1f / 3 && Math.abs(eye[1]) < 1f / 3 && Math.abs(eye[2]) < 1f,
                "the camera stands inside the central corridor of the Menger maze: " + eye[0] + "," + eye[1] + "," + eye[2]);
        assertTrue(eye[1] > -1f / 3, "above the corridor floor");
        assertTrue(fresh.getCamera().getForwardVector()[2] > 0.9f, "looking down the corridor toward the junction");
        assertEquals(org.fractalizer.fractals.AbstractFractalParams.EXTRA_LIGHT_POINT, fresh.getExtraLightType(), "the lantern");
        assertTrue(fresh.isVolumetricFogEnabled(), "dust in the air");
    }

    static int countNodes(GraphNode n) {
        int c = 1;
        for (GraphNode ch : n.getChildren()) c += countNodes(ch);
        return c;
    }

    static int countMaterials(GraphNode n) {
        int c = (n instanceof org.fractalizer.graph.MaterialNode) ? 1 : 0;
        for (GraphNode ch : n.getChildren()) c += countMaterials(ch);
        return c;
    }
}
