# Export & VR Features

Detailed documentation of all export capabilities in Fractaliz3r.

---

## VR & 360 Export

- **360 Equirectangular Projection**: Render full spherical panoramas compatible with VR headsets.
- **Automatic Metadata Injection**:
    - **Photos**: Injects Google Photo Sphere (XMP) tags into JPEG and PNG files via `ImageWriterHelper`.
    - **Videos**:
        - Uses **ExifTool** (if available in PATH) to automatically inject spherical metadata into MP4 files.
        - Provides a fallback guidance system with links to official Google tools if ExifTool is missing.
        - Includes a silent audio track to ensure YouTube/Facebook VR processing.
- **Multi-format Export**: Support for high-quality PNG and JPEG (95% quality).
- **Standard VR Presets**: 2048x1024 (2K) and 4096x2048 (4K) 2:1 aspect ratio presets.

---

## Tiled Rendering

Automatic tile-based rendering for exports exceeding 4096px in any dimension. The image is split into MAX_TILE_SIZE (4096px) tiles, each rendered independently with proper UV remapping via `tileOffset`, `tileScale`, and `fullResolution` shader uniforms, then assembled CPU-side. Transparent to the user — same export UI, progress bar spans all tiles linearly. Presets up to 16K (15360x8640) and 360 8K (8192x4096). Bloom may have minor seams at tile boundaries (invisible at 4096px tiles).

---

## Depth/Normal AOV Export

Export auxiliary render passes (AOVs) for compositing in After Effects, Nuke, etc.

- **Render modes** already exist in shader: `RENDER_MODE_NORMALS` (1) and `RENDER_MODE_DEPTH` (2).
- **postprocess.glsl**: Early return for `renderMode != 0` — raw data passes through without any post-processing.
- **Export**: `RenderController.exportAOV(File, int renderMode)` — 1 sample only (deterministic), supports tiled rendering.
  - Depth (mode 2): 16-bit grayscale PNG (`TYPE_USHORT_GRAY`)
  - Normals (mode 1): 8-bit RGB PNG
- **UI**: "Depth Map" and "Normal Map" checkboxes in ExportPanel. Files saved as `{name}_depth.png` / `{name}_normal.png`.
- **Animation**: Per-frame AOV passes exported alongside beauty frames (`frame_00000_depth.png`, etc.).

---

## GPU-Accelerated 3D Mesh Export

Export fractal geometry as 3D meshes (OBJ, glTF/GLB, PLY) using GPU-accelerated distance field evaluation. Uses the same GLSL fractal shaders as the renderer — 100% fidelity, zero CPU DE code.

- **Architecture**: `evaluator.glsl` renders each Z-slice as a fullscreen quad, outputting `vec4(color, distance)` per grid point. Colors are computed GPU-side via `applyMaterial(getFactors(trap))` — same palette/coloring pipeline as the renderer. `MarchingCubes.java` processes slices via a `SliceProvider` functional interface.
- **Grid alignment**: `evaluator.glsl` uses `gl_FragCoord` integer coordinates with a `gridResolution` uniform to map pixels to exact grid positions (`i/(gridResolution-1)`), matching the Marching Cubes grid formula.
- **Normals from distance grid**: Computed via central differences on already-evaluated distance values (4-slice sliding window: z-1, z, z+1, z+2). No CPU DE calls.
- **Colors**: Vertex colors computed entirely GPU-side (palette lookup + coloring modes in `evaluator.glsl`). Interpolated between cube corners in `MarchingCubes.java`. Zero CPU coloring code.
- **Formats**: OBJ (vertex colors), glTF 2.0 binary (.glb), PLY binary (point cloud, 28 bytes/vertex).
- **Adding a new fractal**: GPU mesh export works automatically — no Java code needed. `FractalEvaluator.java` has been deleted.

---

## Video Encoding

Automatic MP4 creation via FFmpeg (H.265 HEVC) with `+faststart` for web optimization.

---

## Visual Regression Test

A headless rendering test (`TiledRenderTest.java`) that exports the same Mandelbulb scene at multiple resolutions. **Run this after any shader or rendering pipeline change** to verify no visual regression.

```bash
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"
```

Outputs PNGs to `test_output/` (gitignored). After running, **read the images with the Read tool** to visually verify:
- `01_1920x1080_reference.png` — baseline, no tiling
- `02_4096x4096_boundary.png` — boundary case (exactly MAX_TILE_SIZE)
- `03_5000x2000_2x1tiles.png` — 2x1 tiles (X exceeds threshold)
- `04_8192x4096_2x1tiles.png` — 2x1 tiles (360 8K preset size)
- `05_7680x4320_8K.png` — 2x2 tiles (8K preset)

What to check: fractal centered, correct aspect ratio, no tile seams, no Y-flip, background continuous across tiles.

---

## Building a Release (jlink)

```bash
mvn clean javafx:jlink package -DskipTests
```

This creates a self-contained runtime in `target/image/` with launcher script `bin/fractaliz3r.bat`. The `package` phase automatically extracts LWJGL native DLLs (glfw.dll, lwjgl.dll, etc.) into `bin/` and patches the launcher to set `-Dorg.lwjgl.librarypath`. Without this step, the app crashes on machines that never ran LWJGL before (no native cache in temp).

Distribute: zip `target/image/` — no JDK needed on the target machine.
