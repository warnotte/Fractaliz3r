package org.fractalizer.ui.components;

import javafx.application.Platform;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.FrameScorer;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridPresets;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The browser shows every chain in the library and every shipped preset, a click loads
 * the right one, and each chain has the thumbnail it promises — the last being the
 * guard that keeps the shipped pictures in step with the library: add a chain, rerun
 * {@code test/ThumbnailForge ... install}.
 */
class SceneBrowserTest {

    static final class HostStub implements SceneBrowser.Host {
        final List<String> loadedPresets = new ArrayList<>();
        final List<HybridPresets.Preset> loadedChains = new ArrayList<>();
        final NodeGraphParams params = new NodeGraphParams(FractalType.MANDELBULB);
        final List<Boolean> prospecting = new ArrayList<>();
        final List<String> loadedDiscoveries = new ArrayList<>();
        final List<NodeGraphParams> loadedScenes = new ArrayList<>();
        @Override public void loadPreset(String name) { loadedPresets.add(name); }
        @Override public void loadChain(HybridPresets.Preset preset) { loadedChains.add(preset); }
        @Override public AbstractFractalParams params() { return params; }
        @Override public void prospectingChanged(boolean on) { prospecting.add(on); }
        @Override public void loadDiscovery(NodeGraphParams scene, String label) { loadedScenes.add(scene); loadedDiscoveries.add(label); }
    }

    static Discovery discovery(HybridPresets.Preset chain, double score, String known) {
        HybridNode node = new HybridNode();
        HybridPresets.apply(node, chain);
        BufferedImage img = new BufferedImage(SceneBrowser.THUMB_W, SceneBrowser.THUMB_H, BufferedImage.TYPE_INT_RGB);
        return new Discovery(0, "mixed", node, new float[]{0.5f, 0.3f, -3f}, score,
                new FrameScorer.FrameScore(1000, 0.5, 0.1), 0.9, known, org.fractalizer.explore.Look.showcase(), img);
    }

    @Test
    void theDiscoveriesTabIsThereWithItsControls() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, null, host));
        assertEquals(3, browser.tabCount(), "presets, chains, discoveries");
        assertNotNull(UiWiringTest.findButton(browser.root(), "Prospect"));
        assertNotNull(UiWiringTest.findButton(browser.root(), "Breed"));
        assertNotNull(UiWiringTest.findButton(browser.root(), "Stop"));
        assertEquals(0, browser.tileCount(2));
    }

    @Test
    void aClickPicksTheFirstParentAndCtrlClickTheSecond() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, null, host));
        List<HybridPresets.Preset> lib = HybridPresets.all();
        UiWiringTest.onFxThread(() -> {
            browser.offer(discovery(lib.get(4), 10, null));
            browser.offer(discovery(lib.get(5), 30, null));
            return null;
        });
        assertNull(browser.primaryLabel(), "nothing picked yet");
        UiWiringTest.onFxThread(() -> { browser.clickTile(2, 1); return null; });   // the lower-scored one
        HybridNode low = new HybridNode();
        HybridPresets.apply(low, lib.get(4));
        assertEquals(low.describeChain(), browser.primaryLabel(), "the click made it the first parent");
        assertEquals(1, host.loadedDiscoveries.size(), "and the scene");
        UiWiringTest.onFxThread(() -> { browser.markSecondParent(0); return null; });   // the best one
        HybridNode best = new HybridNode();
        HybridPresets.apply(best, lib.get(5));
        assertEquals(best.describeChain(), browser.secondLabel());
        UiWiringTest.onFxThread(() -> { browser.markSecondParent(0); return null; });
        assertNull(browser.secondLabel(), "Ctrl+click again clears it");
        UiWiringTest.onFxThread(() -> { browser.markSecondParent(1); return null; });
        assertNull(browser.secondLabel(), "the first parent cannot be its own second");
        assertTrue(host.prospecting.isEmpty(), "selecting never pauses the host");
        // without a controller there is no GPU: Breed is a no-op, the host untouched
        UiWiringTest.onFxThread(() -> { browser.startBreeding(); return null; });
        assertTrue(host.prospecting.isEmpty());
    }

    @Test
    void discoveriesArriveBestFirstKnownOnesLeftOutAndAClickMakesOneTheScene() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, null, host));
        List<HybridPresets.Preset> lib = HybridPresets.all();
        UiWiringTest.onFxThread(() -> {
            browser.offer(discovery(lib.get(4), 10, null));
            browser.offer(discovery(lib.get(5), 30, null));
            browser.offer(discovery(lib.get(6), 99, "Buffalo"));   // known: counted, never shown
            return null;
        });
        assertEquals(2, browser.tileCount(2), "known families are left out");
        HybridNode best = new HybridNode();
        HybridPresets.apply(best, lib.get(5));
        assertEquals(best.describeChain(), browser.discoveryLabels().get(0), "best first");
        assertTrue(host.prospecting.isEmpty(), "offering results never pauses the host; only a search does");
        UiWiringTest.onFxThread(() -> { browser.clickTile(2, 0); return null; });
        assertEquals(List.of(best.describeChain()), host.loadedDiscoveries);
        NodeGraphParams scene = host.loadedScenes.get(0);
        assertInstanceOf(HybridNode.class, scene.getGraphRoot());
        assertEquals(best.describeChain(), ((HybridNode) scene.getGraphRoot()).describeChain());
        assertArrayEquals(new float[]{0.5f, 0.3f, -3f}, scene.getCamera().getPosition(), 1e-6f, "the camera the search settled on");
        assertFalse(scene.isPathTracingEnabled(), "classic shading, interactive");
        assertEquals(10, scene.getColoringMode(), "the discovery's own look");
    }

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // another test class started it in this JVM
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void everyChainHasATileAndClickingItLoadsThatChain() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, null, host));
        List<HybridPresets.Preset> library = HybridPresets.all();

        assertEquals(library.size(), browser.tileCount(1), "one tile per chain");
        UiWiringTest.onFxThread(() -> { browser.clickTile(1, 3); return null; });
        assertEquals(1, host.loadedChains.size());
        assertEquals(library.get(3).name(), host.loadedChains.get(0).name());
    }

    @Test
    void shippedPresetsAreListedAndClickingOneLoadsItByName() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, null, host));
        List<String> names = browser.presetNames();

        assertFalse(names.isEmpty(), "presets listed (from the shipped index, or presets/ on disk)");
        assertTrue(names.contains("HYBRID_BOXBULB"), names.toString());
        assertEquals(names.size(), browser.tileCount(0));
        UiWiringTest.onFxThread(() -> { browser.clickTile(0, 0); return null; });
        assertEquals(List.of(names.get(0)), host.loadedPresets);
    }

    @Test
    void everyChainInTheLibraryShipsWithItsThumbnail() {
        List<String> missing = new ArrayList<>();
        for (HybridPresets.Preset p : HybridPresets.all()) {
            String res = SceneBrowser.THUMBS + "chains/" + HybridPresets.key(p) + ".jpg";
            try (InputStream in = SceneBrowser.class.getResourceAsStream(res)) {
                if (in == null) missing.add(res);
            } catch (Exception e) {
                missing.add(res);
            }
        }
        assertTrue(missing.isEmpty(), "chains without a shipped thumbnail (run ThumbnailForge ... install): " + missing);
    }

    @Test
    void theShippedPresetIndexMatchesThePresetsFolder() throws Exception {
        try (InputStream in = SceneBrowser.class.getResourceAsStream(SceneBrowser.THUMBS + "presets/index.txt")) {
            assertNotNull(in, "presets index shipped under /thumbs/presets");
        }
        List<String> indexed = SceneBrowser.shippedPresets().stream().map(SceneBrowser.PresetEntry::name).toList();
        java.io.File[] onDisk = new java.io.File("presets").listFiles((d, n) -> n.endsWith(".frac"));
        assertNotNull(onDisk);
        List<String> disk = new ArrayList<>();
        for (java.io.File f : onDisk) disk.add(f.getName().replaceFirst("\\.frac$", ""));
        java.util.Collections.sort(disk);
        assertEquals(disk, new ArrayList<>(indexed), "index and presets/ folder disagree: rerun ThumbnailForge ... install");
    }
}
