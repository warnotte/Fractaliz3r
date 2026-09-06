package org.fractalizer.ui.components;

import javafx.application.Platform;
import org.fractalizer.graph.HybridPresets;
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
        @Override public void loadPreset(String name) { loadedPresets.add(name); }
        @Override public void loadChain(HybridPresets.Preset preset) { loadedChains.add(preset); }
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
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, host));
        List<HybridPresets.Preset> library = HybridPresets.all();

        assertEquals(library.size(), browser.tileCount(1), "one tile per chain");
        UiWiringTest.onFxThread(() -> { browser.clickTile(1, 3); return null; });
        assertEquals(1, host.loadedChains.size());
        assertEquals(library.get(3).name(), host.loadedChains.get(0).name());
    }

    @Test
    void shippedPresetsAreListedAndClickingOneLoadsItByName() throws Exception {
        HostStub host = new HostStub();
        SceneBrowser browser = UiWiringTest.onFxThread(() -> new SceneBrowser(null, host));
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
