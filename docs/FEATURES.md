# Features Reference

Detailed documentation of advanced features in Fractaliz3r.

---

## Advanced UI Features

### EnhancedSlider Component

A standard component used for **all** numeric parameters across the application:
- **Mouse Wheel Support**: Scroll over any slider to adjust value.
- **Modifier Keys** (Required for value adjustment to avoid ScrollPane conflicts):
  - `ALT + Scroll`: Normal speed adjustment.
  - `SHIFT + Scroll`: Fine/Precision control (10x slower).
  - `CTRL + Scroll`: Fast movement (10x faster).
- **Automatic Labels**: Displays title and value with configurable precision (default 3 decimals).
- **Inline Value Entry**: Double-click on the label or slider track to open an inline text field. Shows the real (unrounded) value. Enter to confirm, Escape to cancel, focus-loss auto-commits. Value is clamped to slider min/max. Styled via `.inline-edit-field` CSS class.
- **Lock Button**: Each slider has a lock toggle that protects it from the dice randomizer.
- **Tooltip Help**: Explains shortcuts when hovering.

### Dice Randomizer (FractalPanel)

A parameter randomization system with undo/redo history:
- Randomizes all unlocked fractal-specific sliders and ComboBoxes using their own min/max ranges.
- Navigate through dice history (up to 50 snapshots).
- **Auto-discovery**: Uses recursive tree traversal of the visible fractal controls VBox — adding a new slider or fractal type requires zero changes to the randomizer.
- **Lock integration**: Locked sliders are preserved during randomization.
- **Snapshots**: Captured via `IdentityHashMap<Object, Object>` before each roll; restored by setting slider/combo values directly (no suppressRender, so param callbacks fire correctly).

### Morph Crossfade (FractalPanel)

Smooth interpolation between two parameter snapshots:
- **Set A / Set B**: Capture current fractal params as morph endpoints.
- **Morph Slider** (0→1): Linearly interpolates all slider values between A and B. ComboBoxes snap at 0.5.
- **Type safety**: Morph is disabled (slider grayed out) if A and B are different fractal types, with a warning label.
- Uses the same recursive `IdentityHashMap` snapshot mechanism as the dice history.

### Albedo 0.39: a planet built from nodes (`presets/ALBEDO_039.frac`)

A world seen from orbit at dawn, built by `PresetForge.blueWorld()` (rebuild: `PresetForge
presets - 640x360 24 ALBEDO` writes the file, `ShaderCompileProbe --render 1280x720 48 out/x
presets/ALBEDO_039.frac` renders it in 15 s). Radii and thresholds are the whole design:

- **Land** is a sphere of radius 1.022 carved by two erosion passes. The first is thermal
  only (type 2: isotropic fBm at low frequency, noise scale 2) and makes the continents; the
  carve depth is `fbm × K` with `K = 0.35 × time × strength × scale × 0.05 = 0.056`. The second
  is weathering only (type 3: fine, signed, scale 0.5) and makes the mountains. This is half
  the relief of the first two passes: at 0.112 the coasts were 280 km cliffs at Earth scale
  and drew themselves, as black shadow bands on the sea at dawn and as grey rim-lit lines
  on the night side. Halving K and the land radius offset together keeps every coastline.
- **Sea** is a smooth sphere of radius 1.0. Where the carve exceeds 0.022 the land falls
  below it — about half the globe — so every coastline is an fBm iso-line, fractal at every
  zoom. The land/sea ratio is extremely sensitive to that offset (at the old relief, 0.05
  gave three quarters land, 0.04 a water world). **Shallows**: the sea intersected with the
  land raised 0.0007 is turquoise, the rest deep blue, so every coast has a fringe of
  shallow water; the fringe's width is that depth over the coastal slope.
- **Altitude bands**: the same eroded sphere cut by spheres into green (to 1.013), brown
  (to 1.019) and snow, by CSG intersections and subtractions, so no two bands overlap.
- **Ice** is whatever the globe is beyond the polar slabs: an intersection with them, white,
  and the complementary subtraction for the temperate globe. The slabs' faces start at
  |y| = 0.80 and are carved by the thermal noise at scale 0.5, up to 0.15 deep, plus a fine
  weathering pass, so the edge of each cap wanders between |y| = 0.80 and 0.95 in about nine
  lobes; a flat slab gave a lid, and a 0.06 carve still read as a straight line from orbit.
- **City lights** on the night side: a skin 0.003 thick on the land (the land raised 0.003
  minus the land) kept where three masks agree. The fine mask is the land carved by the
  signed weathering noise at scale 0.10: where that noise is below its zero the mask rises
  above the land and the skin survives, as blobs with the outlines of towns, about four
  tenths of the ground (a lattice of small spheres tried first read as a grid). The coarse
  mask is a sphere of radius 1.05 carved 0.10 deep by the thermal noise at scale 0.7:
  clusters, denser in the lowlands. The third is the half-space facing away from the sun, a
  box turned onto the sun direction. Above the sea, emissive, sodium orange.
- **The moon** is a sphere of radius 1.35 cratered by the weathering noise, on the same line
  of sight as the small near moon of the first passes but five times as far: the silhouette
  glow has a fixed width in world units, so a near moon wore an atmosphere.
- **Light**: a single low sun from the side (`lightDir (2.6, 0.7, 0.5)`), so the terminator
  crosses the disc; a faint blue ambient for the night side; **the rim light at 0.45 is the
  atmosphere**, with the silhouette glow. The palette is on no surface (every material is
  solid): it colours the glow, pale blue at the limb fading to deep blue, and the night
  sky's nebula. This is why the preset ships with classic shading: the path tracer has no
  rim term, and the halo is most of the beauty.

Two things that did not work, kept out on purpose: a glass sphere as atmosphere (a black disc
under path tracing, a white ball under classic shading), and a cloud layer as an fBm-carved
shell (thick white plates with smooth edges — ice floes, not clouds; a distance field gives no
soft edge). One engine bug the planet exposed: in the node graph, the erosion displacement used
by shadows, AO and normals was a cheaper noise than the one the eye ray hit, off by as much as
the land relief, which read as a golf-ball ocean and dark fringes along the coasts near the
terminator. `GraphCompiler` now emits the same displacement in both, with the erosion type
baked in as a literal so the shader does not grow (see docs/RENDERING.md § Surface Effects).
`BlueWorldTest` compiles the graph, round-trips it, and checks the shipped preset is in orbit
with the atmosphere on.

### The Labyrinth: a world built from nodes (`presets/LABYRINTH.frac`)

A place to walk rather than a view, and a demonstration of what the node graph composes.
Built by `PresetForge.labyrinthWorld()` (rebuild it with `PresetForge presets
out/presets_preview 640x360 48 LABYRINTH` — the 5th argument limits the forge to one preset,
so the hand-tuned ones on disk are left alone):

- **The maze** is a Menger sponge of side 2 (scale 3, six iterations), seen from inside. Its
  corridors are 2/3 wide with a floor at y = −1/3, its doorways repeat at every scale, and the
  central junction (|x|, |y|, |z| < 1/3) is a room with six exits. A light erosion effect
  weathers the stone; a solid sandstone material replaces the palette.
- **The Escher room** is three staircases in that junction. A staircase is one box step
  repeated along a diagonal: rotate space by −θ about Z, repeat along X with period
  √(rise² + run²), rotate back by +θ — the inner rotation undoes the outer one for each cell,
  so every step stays axis-aligned while the line of steps climbs. An intersection with the
  room's box clips the flight to ten steps. The same flight turned 90° about Z climbs under a
  sideways gravity, turned 90° about X under a third.
- **The crystal** is an emissive sphere at the centre (material emission 9), and lights the
  hall under path tracing.
- **The plain** is a plane at y = −1 under the whole building, for the walk outside.
- **Light** is the lantern: a point light attached to the camera (`SceneBuilder.lantern`),
  since no directional light reaches a corridor; a warm ambient; low volumetric dust.
  The camera starts in the corridor at (0, −0.21, −0.92) facing the junction, move speed
  0.02 per key press (`SceneBuilder.moveSpeed`).

`LabyrinthWorldTest` compiles the graph, round-trips it through a save, checks the staircase
repetition has a real period (the `repeat1D` helper once set it on the wrong field, which
stacked every step on the first), and checks the shipped file starts inside the maze.

### Presets & Chains browser (status bar button, View › Presets & Chains…, Ctrl+B)

Every shipped scene as a picture. Two tabs: the `presets/*.frac` files, rendered as File › Load
would show them, and the hybrid chain library (`HybridPresets`), each framed at its
`previewDist`. A click loads the preset (from the checkout's `presets/` folder when it is
there, else from the copy bundled in the jar — the pom ships `presets/*.frac` as resources)
or makes the chain the scene's node graph, camera placed as in its thumbnail, with the Node
Graph editor showing its steps.

The thumbnails are not rendered on open — thirty scenes with a shader compile each is not
something to wait for. `test/ThumbnailForge` renders them (320×180, 16 spp) under `out/thumbs`
and, with `install` as its last argument, copies them into `src/main/resources/thumbs`
together with `presets/index.txt`, the list the browser reads (a jar cannot list its own
resources). Two JUnit tests keep the shipped set honest: every chain in the library must have
its thumbnail, and the index must match the `presets/` folder — add a chain or a preset and
the suite says "rerun ThumbnailForge … install" until you do. A missing picture degrades to a
dark tile with the name on it, never to a broken browser.

### Discoveries (third tab of Presets & Chains, *Discover…* status bar button)

The prospector in the app. *Prospect* draws that many chain structures (ten by default, about
two minutes), each a random composition of the 28 hybrid steps under one of four recipes, and
for each: compiles it (about ten seconds on the dev machine, once per machine — the driver
caches shaders), sweeps eight parameter draws through it as uniforms at a few milliseconds a
frame, frames each draw on the depth AOV, scores it, and hands it over. New finds appear as
tiles best first while the search runs; known families (a single power map or fold however
it is turned, or the shape steps of a library chain) are counted in the status line and left
out. A click makes a discovery the scene, a fresh node graph with the chain at its root in
the showcase look, camera where the search settled; File › Save keeps it. *Stop* ends the
run after the current draw and keeps what was found; clicking a tile during a run stops it,
then loads.

The search drives the GPU controller from a worker thread on a throw-away scene: the host
pauses its preview loop as for Explore, the renderer makes the throw-away scene current on
its first chain, and the user's scene is marked dirty and put back when the run ends,
whatever happened — recompiled from the driver's cache in a moment. `test/ProspectSwapProbe`
renders the user's scene before and after a search and requires the two frames identical
pixel for pixel. The algorithm is `explore/ChainProspector` (see docs/NODE_GRAPH.md,
"Prospecting"); `test/HybridProspector` is its command line, with contact sheets, a ranking
file and `FOUND_NN.frac` output, which is how it was tuned before it got a tab.

### Explore (status bar button, View › Explore…, Ctrl+E)

Two ways of asking the app what else there is to see, in one window.

**Views.** The app looks for detailed views of the scene from where the camera is, and shows
them as scored thumbnails. Click one and the camera flies there (an eased 1.2 s flight,
`CameraFlight`: smoothstep on position and field of view, spherical interpolation on the
orientation along the shorter arc; any key press lands it); explore again from there to go
deeper. It is the `FractalNavigator` traveller (see [RENDERING.md](RENDERING.md) § Test
Harnesses) running inside the app, on the GPU at thumbnail size, with one change: it keeps
several targets instead of diving on the best one, so the answer is a set of framings rather
than a single camera.

**Variations.** The camera stays; the scene's parameters are nudged instead. The tab lists
the scene's numeric knobs (`ParamKnobs`: every fractal leaf's `@Animatable` floats — power,
Julia constant, radiolaria — and, for a hybrid chain, its Julia constant plus the parameters
each step actually reads; integer counts are not knobs, and bailouts are unticked by
default). Each variant nudges every ticked knob by a Gaussian of *Amplitude %* × the knob's
own scale (its magnitude, or half a unit near zero), renders it from the current camera and
scores it like a view; the unchanged scene is always the first result, so the ranking says
whether any variation beats what is there. Click one to make it the scene; *Restore
original* puts the values back. The search is `ParamExplorer`, the prospector's
render-score-rank loop applied to whatever the scene is.

What a Views run does, in order (`org.fractalizer.explore.CameraExplorer`):
1. **Pivot** — the surface point under the centre of the view; if the centre looks at
   nothing, the origin.
2. **Auto-frame** — back off along the pivot→eye axis while the view is inside or overflowing
   (surface coverage above 85%, or the centre closer than the depth encoding can tell), move
   in while it is sparse (below 25%). The result is the *Global view* thumbnail.
3. **Aim scan** — nine aim points on a 3×3 grid across the view plane; each that hits a
   surface gets a quick render and a detail score. The most detailed few become targets (the
   *Targets* setting). This is what keeps it out of the Menger sponge's hollow core.
4. **Dive** — for each target, walk the camera toward its surface point in shrinking steps
   (*Steps* × *Shrink*), scoring every step. The sweet spot is usually a middle step: too
   close, a surface goes smooth and dark.

Every thumbnail carries the composed score (`FrameScorer.aesthetic()`: Laplacian-variance
detail × a coverage band peaking at 55% × how central the detail is), the raw detail, the
surface coverage and the camera distance. The grid is kept sorted best first as results
arrive. Thumbnails are 320×180 at a few samples; a run is a few seconds on a warm shader.

While it runs the search owns the scene camera and the engine size, so the preview loop and
keyboard navigation stand down, and the thumbnails are not clickable. When it ends — done,
cancelled, or failed — the camera the user had is put back. Flying somewhere is always a
click, never a side effect of searching.

The decisions above are unit-tested against an analytic sphere on the CPU
(`CameraExplorerTest`, `ParamExplorerTest`): backing off from inside, the pivot fallback, the
ladder geometry, cancellation, variants bounded by the amplitude and every knob put back.
`test/ExploreProbe` runs the same code on the GPU from any fractal or `.frac` and writes the
scored sheet, best first, with the time per view; with `variations` as its 5th argument it
runs the Variations tab instead.

### Parameter Persistence

The `GLSLFractalizerController` maintains a cache of fractal parameters. Switching between fractal types preserves your specific settings for each fractal, while common settings (Path Tracing, Palette, Lighting) are synchronized across all types.

---

## Boolean Operations (Legacy/Core)

Constructive Solid Geometry between fractal distance fields. This logic now powers the `CSGNode` in the node graph.

---

## Custom Shader Editor

Write custom fractal DE shaders and compile them on the fly. Built on the same runtime GLSL compilation pipeline as Boolean Operations.

- **User writes fractal part only** — `common.glsl` + `raytracer.glsl` are assembled automatically by `GLSLEngine.loadCustomFractalShader()`.
- **Shader contract**: User must define `OrbitTrap` struct, `DE()`, `DE_simple()`, `getFactors()` — same as any built-in fractal shader.
- **`@param` annotations** for automatic slider UI:
  ```glsl
  uniform float power;      // @param min:2 max:16 default:8
  uniform int maxIterations; // @param min:3 max:30 default:15
  uniform vec3 offset;       // @param min:-2 max:2 default:0,0,0
  ```
  Supported types: `float`, `int`, `vec2`, `vec3`, `vec4`. Uniforms without `@param` get no slider (hardcoded in GLSL).
- **Dynamic sliders**: `CustomShaderEditor` (in `ui/components/`) parses `@param` annotations on successful compile and generates `EnhancedSlider` controls. Values stored in `CustomShaderParams.uniformValues` map, emitted via `buildUniforms()`.
- **Async compilation**: Runs on a daemon thread to avoid blocking the FX thread. UI shows "Compiling..." state with disabled controls.
- **6 templates**: Sphere, Torus, Gyroid, Infinite Spheres, Menger Sponge, Mandelbulb (Simple) — each demonstrates a different SDF technique.
- **Auto-compile on load**: When loading a `.frac` file with a custom shader, `loadParams()` triggers compilation via `Platform.runLater` so saved shaders render immediately.
- **Serialization**: `shaderSource` (String) + `uniformValues` (Map) in `FractalConfig`. Gson `List<Double>` → `float[]` conversion handled in `applyFractalParams()`.
- **Not usable as boolean secondary** — excluded from the boolean ops combo alongside `TEST_SCENE`, `CORNELL_BOX`, `FRACTAL_TERRAIN`.
- **Files**: `CustomShaderParams.java` (model), `CustomShaderEditor.java` (UI component in `ui/components/`), `FractalType.CUSTOM_SHADER` enum value.

---

## Multi-Fractal Nesting (Experimental)

Tiles a secondary fractal as micro-geometry on the surface of a primary fractal. E.g., a Menger Sponge covered in mini-Mandelbulbs. Extends the Boolean Operations system with `boolOp == 4` ("Nesting").

- **Reuses 100% of the boolean pipeline**: `ShaderPreprocessor` + `loadBooleanFractalShader` + `#ifdef BOOLEAN_OPS`. No new shader files or compilation paths.
- **raytracer.glsl**: `nestEvalCell()` (shared by `boolDE` and `boolDE_simple`) implements the nesting logic:
  1. Early-out when `d1 > nestThreshold` (far from primary surface — skip secondary eval)
  2. **Domain warp**: 3x `fbmLow` displace position before tiling — breaks regular grid, organic cell boundaries
  3. `fract()` tiles warped space into repeating cells
  4. **Edge fade**: `smoothstep(0.6, 1.0, edgeDist)` fades micro-fractals to zero at cell boundaries — eliminates visible seams
  5. Per-cell random rotation via `nestCellRotate()`: hashes `cellId` to produce a unique axis + angle per cell (Rodrigues rotation). `nestRotation` controls max amplitude (0 = all aligned, 360 = fully random).
  6. Evaluate `b_DE_simple(cellPos)` in the tiled/rotated cell, scale result back to world space
  7. Final blend: `smoothstep(threshold proximity) * fade * nestMix` — combines surface proximity, edge fade, and global mix
- **All 8 existing boolean DE call sites** (calcNormal, calcShadow, calcAO, calcSSS, rayMarch, rayMarchSimple, 2x glass) work automatically — they already dispatch through `boolDE`/`boolDE_simple`.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `nestThreshold` (float, 0.01-1.0, default 0.1) — shell thickness
  - `nestRepeatScale` (float, 0.5-50, default 5.0) — repetition density
  - `nestRotation` (float, 0-360, default 0) — max per-cell random rotation
  - `nestMix` (float, 0-1, default 1) — crossfade: 0 = pure primary, 1 = full nesting
  - Also uses existing `boolScale` for secondary fractal size within each cell
- **UI**: "Nesting" option in the Boolean Operations operation ComboBox. Mix, Threshold, Repeat Scale, and Rotation sliders appear only when Nesting is selected.
- **Zero overhead when not Nesting**: `boolOp != 4` takes the normal boolean path with zero extra cost.
