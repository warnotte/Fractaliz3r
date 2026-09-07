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

- **Architecture**: `evaluator.glsl` renders each Z-slice as a fullscreen quad, outputting `vec4(color, distance)` per grid point. Colors are computed GPU-side via `applyMaterial(factors, pos, normal, rayDir)` — same palette/coloring pipeline as the renderer (all 13 coloring modes available; geometry-based modes 9–12 use dummy normal/ray in evaluator context). `MarchingCubes.java` processes slices via a `SliceProvider` functional interface.
- **Grid alignment**: `evaluator.glsl` uses `gl_FragCoord` integer coordinates with a `gridResolution` uniform to map pixels to exact grid positions (`i/(gridResolution-1)`), matching the Marching Cubes grid formula.
- **Normals from distance grid**: Computed via central differences on already-evaluated distance values (4-slice sliding window: z-1, z, z+1, z+2). No CPU DE calls.
- **Colors**: Vertex colors computed entirely GPU-side (palette lookup + coloring modes in `evaluator.glsl`). Interpolated between cube corners in `MarchingCubes.java`. Zero CPU coloring code.
- **Formats**: OBJ (vertex colors), glTF 2.0 binary (.glb), PLY binary (point cloud, 28 bytes/vertex).
- **Adding a new fractal**: GPU mesh export works automatically — no Java code needed. `FractalEvaluator.java` has been deleted.

---

## Video Encoding

Automatic MP4 creation via FFmpeg (H.265 HEVC) with `+faststart` for web optimization.

---

## Export Progress (why a bar can lie)

`engine.renderSamples()` issues GL commands and returns; `runOnGLThread` waits for them to
be **issued**, not for the GPU to finish. Left alone, an export loop pushes every batch into
the driver queue far faster than it drains, so progress reports submissions rather than
work. Measured on a 2600x1600, 128-sample export: the bar reached 100% after **0.07 s of a
26 s render** and sat full for the rest.

All six sample loops therefore wait on the GPU before reporting — the four batch loops
(`exportSingleToPNG`, `exportTiledToPNG`, `exportAnimationFrameSingle`,
`exportAnimationFrameTiled`) and the two motion-blur loops, which render sample by sample
through `renderSample`. 100% now arrives at 22.4 s of a 23.0 s export. The two remaining
`renderSample` callers are AOV exports: one sample followed immediately by `readImage()`,
which waits by itself and has no progress bar to be wrong.

**The wait is not free everywhere.** A batch of eight samples takes ~1.6 s at 2600x1600,
where draining the pipeline is noise, and ~28 ms at 480x270, where syncing every batch cost
~18% on the benchmark. Timing the first batch to decide does **not** work — it carries the
program activation and buffer reset and always looks slow. The gate is therefore
`width x height x samples > SYNC_MIN_WORK`, known before the loop starts. It ignores how
expensive the scene is, so an unusually slow small export gets a coarse bar; those finish in
about a second regardless.

`test/ExportProgressProbe` measures the gap between the first 100% report and the moment the
export actually completes, for both the still and the animation-frame paths.

---

## Visual Regression Test

A headless rendering test (`TiledRenderTest.java`) that exports the same Mandelbulb scene at multiple resolutions. **Run this after any shader or rendering pipeline change** to verify no visual regression.

```bash
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"
```

Outputs PNGs to `out/test_output/` (gitignored). After running, **read the images with the Read tool** to visually verify:
- `01_1920x1080_reference.png` — baseline, no tiling
- `02_4096x4096_boundary.png` — boundary case (exactly MAX_TILE_SIZE)
- `03_5000x2000_2x1tiles.png` — 2x1 tiles (X exceeds threshold)
- `04_8192x4096_2x1tiles.png` — 2x1 tiles (360 8K preset size)
- `05_7680x4320_8K.png` — 2x2 tiles (8K preset)

What to check: fractal centered, correct aspect ratio, no tile seams, no Y-flip, background continuous across tiles.

---

## Building a Release

Three layers, each on top of the previous one.

### 1. jlink image

```bash
mvn -Prelease clean javafx:jlink package -DskipTests
```

Creates a self-contained runtime in `target/image/` with the launcher script `bin/fractaliz3r.bat`.
The `release` profile adds two steps to `package`: it extracts the LWJGL native DLLs (glfw.dll,
lwjgl.dll, lwjgl_opengl.dll, lwjgl_stb.dll) from their classifier jars into `bin/`, and patches the
launcher to pass `-Dorg.lwjgl.librarypath`. Without them the image works on the build machine
(LWJGL finds its DLLs in the temp cache left by earlier runs) and crashes on any machine that never
ran LWJGL. The steps live in a profile because they assume the image exists: bound unconditionally,
a plain `mvn package` failed on the missing launcher script.

Zipping `target/image/` is already a portable distribution; no Java needed on the target.

### 2. jpackage: installer and portable folder

`jpackage` (part of the JDK) wraps the image in a native launcher with the icon, a version, and
either an `.msi` or a plain folder:

```bash
# Portable folder: target/installer/Fractaliz3r/Fractaliz3r.exe — no extra tools needed
jpackage --type app-image --dest target/installer \
  --name Fractaliz3r --app-version 3.2.0 --vendor "Renaud Warnotte" \
  --description "Real-time cinematic 3D fractal renderer" \
  --runtime-image target/image --module Fractaliz3r/org.fractalizer.Launcher \
  --icon src/main/resources/icons/fractaliz3r.ico \
  --java-options '-Dorg.lwjgl.librarypath=$ROOTDIR\runtime\bin'

# Installer: same command with --type msi and the Windows options; needs WiX 3 (candle/light) on the PATH
#   --win-menu --win-shortcut --win-dir-chooser --win-per-user-install --license-file LICENSE
```

Two things to know about the result:

- The runtime image is copied whole into `runtime/`, DLLs included, so the LWJGL library path
  must point there. `$ROOTDIR` is expanded by the launcher to the installation directory; the
  launcher script's `%~dp0` trick from step 1 does not apply because the `.exe` never runs it.
- The launcher reads `app/Fractaliz3r.cfg`; if a start-up option ever needs changing after the
  fact, that file is where it lives.

### 3. GitHub release

`.github/workflows/release.yml` runs the two steps above on a Windows runner when a `v*` tag is
pushed (WiX is installed with Chocolatey), then attaches `Fractaliz3r-<version>.msi` and
`Fractaliz3r-<version>-windows.zip` to the release for that tag:

```bash
git tag v3.2.0
git push origin v3.2.0
```

The tag is the version. A manual run of the workflow (`workflow_dispatch`) builds the same two
files and keeps them as a workflow artifact instead of publishing a release.
