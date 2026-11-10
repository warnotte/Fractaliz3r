# Deep zoom — handoff

Read it before touching anything related to deep zoom, precision, DoF or navigation.

## 0. Where the work is (updated 2026-11-10, night)

- **`deep-zoom-wip`** (one commit, `c239d69`): the interactive session of 2026-11-08/09 as
  it was left, everything in § 2, untouched. It is the reference for "what worked": the
  double march on the Mandelbox and the KIFS, the renormalised KIFS, `PrecisionLab` and its
  numbers. Never merge it; lift from it.
- **`deep-zoom`** (from `main`): the rework by § 4. Step 1 is done, five commits:
  1. `658f471` the camera holds its position in double, `.frac` keeps `camera.positionD`
     (wins on load only while it still rounds to `position`, so a tool that edits
     `position` alone is obeyed), double uniforms, `camPosD`;
  2. `36c1518` the focus pick: depth from the variance image (the old read was **75 %
     short** with adaptive sampling, `FocusPickProbe`), `touchGLState()` in the read (a
     pick between two strips lost the rest of the sample, `BandedSampleProbe`), the
     Quality panel no longer writes the slider-clamped value back over the pick, the pick
     is asynchronous;
  3. `d195b94` **the open symptom of § 2, found and fixed**: the viewport's steps ran
     inline inside each other (`engine.post` is inline on the GL thread), so a whole
     refinement was one GL task and a pick waited for its end (~725 ms on a light scene,
     the whole refinement on a heavy one). Steps now go through `GLSLEngine.enqueue`; a
     pick comes back in 12-38 ms; navigation fluidity unchanged (docs/INTERACTIVE_RENDER.md);
  4. `05dfcbf` DoF at any scale: `gLensOffset` / `gPrimaryOrigin`, focal point as a vector,
     `aperture <= 0`, `surfaceBias` floor, logarithmic focal and aperture sliders
     (`EnhancedSlider.logarithmic`: the knob moves in log10, the label and the API speak the
     value, not the exponent the WIP showed);
  5. `1aafd66` move speed over twelve decades: wheel x1.25 a notch down to 1e-12,
     logarithmic slider, log HUD meter, position label to nine decimals.

  Every commit: `mvn test` green (147), `RenderRegression check` bit-exact on the global and
  the detail scenes (`out/nav/detail_scenes.txt`; those goldens were rewritten from `main`
  on 2026-11-09, the September ones are in `out/test_regression/golden_backup_2026-09`).
- **Step 2 (§ 4.1, the probe) is done on `deep-zoom`, two commits:**
  1. `b894617` **the probe.** `GLSLEngine.probe` runs the scene's own program on up to
     eight rays from the camera. A ray is the view's ray through a screen point or a world
     direction. The probe returns the distance along each ray and the scene's estimate at
     the camera.
     - Shader: `probeMode` takes the same path through `main()` as the view and leaves right
       after the primary march, so there is no second copy of the march or of the estimator
       (`ShaderCompileProbe`: no change). The estimate at the camera is the march's first
       one (`gMarchStartDE`).
     - The read is synchronous, about 0.3 ms. The GPU is idle between viewport steps, each
       of which awaits its fence, so the PBO and fence of the plan were not needed.
     - The focus pick goes through it: the ray through the clicked pixel, exact at its
       centre, before any image has accumulated, an edge included.
     - `FocusPickProbe`:
       - a sphere against the exact intersection: within 0.31 of a pixel's footprint,
         clearance exact to 4e-8;
       - a scene against its own image's depth: median 0.01 %;
       - a pick during a refinement: 18-35 ms.
     - `BandedSampleProbe`: a probe between two strips leaves the sample bit-identical.
     - `CpuDE` was never lifted, so nothing CPU-side knows any fractal.
  2. `e59c9be` **auto speed, measured on the scene.** `DiveSpeed` moves the camera
     each frame by the dial's fraction of the **free distance ahead along the move** (a
     probe ray in that direction), less the distance moved since the measure. A late
     measure only slows the camera down. A measure older than 250 ms, or taken more than 2°
     off the current move, is not trusted: the camera holds a frame. Inside the solid it
     backs out at the scale it came in at. It is not the WIP's estimate at the camera: that
     is a lower bound, measured 3800 times too low at the Mandelbox's default camera,
     where a speed tied to it froze the camera.
     - `DiveProbe` (headless, 60 frames a second, a still per decade): a decade every 60
       frames at a dial of 0.1 down to 1e-6 ahead on the Julia bulb, the Mandelbox (detail
       camera) and the Labyrinth, never crossing the surface. Below 1e-6 the float camera
       position stops following the double one and the dive slows, safely.
     - The stills show the float wall from 1e-4 down (blocky, then flat cells), as § 1
       measured.
     - Checkbox *Auto speed* in the Navigation panel, on by default as on the WIP; off, the
       dial is the step as before.

  Both commits were checked:
  - `mvn test` green (154);
  - `RenderRegression check` maxD 0 on the 9 global and the 6 detail scenes. The global
    goldens were rewritten from `deep-zoom` before the probe. The September
    `mandelbulb_pathtraced` was already 5/255 off on a few pixels at `main` and at this
    branch's step 1. The September goldens are in `golden_backup_2026-09`;
  - shader compile times unchanged: the 15 built-ins 163.4 s against 163.3 s (each one
    swings ±2 s from run to run); `ALBEDO_039` 15.3 s against 15.1 s; `LABYRINTH` 15.3 s
    against 15.9 s (measured before the direction rays; the final run hit the driver's
    cache);
  - the caustics' photon program still compiles;
  - the viewport probes, see `docs/INTERACTIVE_RENDER.md` § Proof.
- **Step 3 (§ 4.2 + § 4.5, double precision for any scene) is done on `deep-zoom`.** The
  commits are listed in `git log`; docs/RENDERING.md § "Deep precision: the float wall" has
  the full account and the numbers.
  - `DeepSource` (engine) derives the scene's double entry from its own float source, as the
    plan wanted but without macros in the kernels. It parses common.glsl and the scene, then
    copies `DE_simple` and every function and struct it reaches with float types made
    double, and redirects the calls. Transcendentals go through overloads: a float argument
    to the float built-in, a double one to a double library emitted with the block (sin, cos,
    atan2, acos, exp, log, pow at a few ulp of double), so the Mandelbulb family keeps its
    digits through its angles too (at 1e-6: blocks before, the surface after; ~5x the time
    of the float calls at depth). Compound
    assignments are written out (NVIDIA C9999). The kernels are not touched, so a program
    without deep precision is byte-for-byte what it was. 14 of 15 built-ins compile; the
    Polyhedral IFS crashes the driver's compiler and is shown in float with a message (the
    same fallback that catches a custom shader GLSL refuses in double).
  - The WIP's `rayMarchDeep`, `rayMarchSimpleDeep`, `refineSurfaceDeep` and the double
    normals, behind `HAS_DEEP_DE`. A camera ray goes float first and is redone in double when
    it lands where a pixel is under ~100 float steps, or came within 4 float steps of a
    surface. A view costs what it did until the dive gets there.
  - `PrecisionLab` is back, with the ladder placed by the probe (any scene, `CpuDE` gone), a
    `compile` mode, and the fallback case.
    - The double render is the oracle of the WIP (the whole orbit in double), so the bar of
      § 4.5 holds by construction.
    - What is measured is float against double: the Mandelbox 0.6° / 13° / 92° at
      1e-2 / 1e-3 / 1e-4, the KIFS flat in float from 1e-5.
    - The double renders keep the 1e-1 statistics down to 1e-8. Cost at depth: 2-6x float.
  - `presets/KIFS_DIVE.frac` with deep precision on (its `renormalize` dropped).
  - `DiveProbe`: seven decades down to 2.4e-10.
  - Report for the owner: https://claude.ai/artifact/P3gLuobAhACA19B3qSLdeA (private).
- **Still only on `deep-zoom-wip`, by design:** § 4.3, the renormalisation (`Renormalizer` +
  test, `DE_local` in kaleidoscopic.glsl, the SSBO, `rayMarchRenorm`), an optional
  optimisation of the KIFS on top of the double path, and the WIP's RENDERING.md narrative
  of it. `CpuDE` is not to be lifted.
- **Next, if wanted:**
  - the cost at depth (measured 2026-11-10: on the KIFS at 1e-6 the sun's shadow is ~45 %
    of the time, legitimate marching out of dense structure; the double march ~3x the float
    one). Generic shortcuts tried and dropped: a float estimate while far from the surface
    (~10 % faster) and no final refinement (normals 30-60° different). What would pay:
    float-float arithmetic (two floats per number, full-rate on a GeForce) in place of
    double, a large but generic change. Running only the orbit's first iterations in double
    is NOT safe in general (formulas that are not contractive early);
  - the rest of the secondary rays in double. The sun's shadow and AO at a deep hit, and the
    path tracer's shadow rays and bounces, are done (docs/RENDERING.md); reflections in
    classic shading, extra lights and SSS are still float;
  - the owner's `presets/PathtracerTest.frac` (the same scene as `ZoOM_ANDBUG`), too dark
    with 8 bounces: measured 2026-11-10, 8 / 16 / 32 bounces give the same image to the bit
    (Russian roulette ends every path on that dark albedo well before 8). The darkness is the
    Studio sky (its brightness is `cloudDensity`, 0.13 there: no fill light in the shadows),
    the palette's near-black first stop on the large wall, and the exposure (2.5, or the sun
    at 3, takes the share of pixels under 10/255 from 33 % to 5 %). Settings, not code;
  - `presets/MIRROR_GLASS.frac`, the reflection / refraction test scene (mirror ball, glass
    ball, Mandelbulb, slab). Rendered against `main` from afar and from 0.07 of the glass:
    the same images (classic bit-exact, path traced within sampling noise). It shows a
    limitation `main` already had: classic shading draws a metal MaterialNode as a dark ball
    and a glass one as an opaque ball; only the path tracer reflects and refracts them;
  - § 4.3.

The owner's wishes, in the owner's words and mine:

- **No code paths that depend on the fractal type.** The engine is a node-graph compiler;
  anything that only works for a "bare Mandelbox leaf" or "bare KIFS leaf" is a dead end
  (hybrids, CSG, transforms, custom shaders). "Il va falloir songer à faire un truc ultra
  robuste."
- **Depth of field stays manual.** An autofocus / f-number mode was tried and rejected
  (aiming at a hole moved the focus). Manual sliders, but usable at depth.
- **Test it yourself.** Build, run the harnesses, read the outputs, iterate; do not hand the
  owner `.bat` files to run. `mvn compile`, `mvn test` (no GPU), and the GPU harnesses in
  CLAUDE.md all work from the console on the owner's machine.
- Commit messages: no AI/Claude mentions (CLAUDE.md rule).

## 1. The problem, measured

A float position near |pos| ~ 1 is quantised to ~1e-7 world units. The pixel footprint at
clearance *c* is ~`c · 2·tan(fov/2) / height`, so below *c* ≈ 1e-4 (1080p, 60°) neighbouring
pixels round to the same point. `PrecisionLab` (new harness, § 3) measured it against an
**oracle** (the whole orbit in double): normal error of the float march on the Mandelbox is
3.8° at 1e-2 (8 % of pixels > 10° off), 7.8° at 1e-3 (27 %), 106° at 1e-4 (image flat). The
old `MIN_EPSILON = 1e-6` floor and `surfaceBias`'s 1e-5 floor are both larger than the whole
view at depth. Full write-up: `docs/RENDERING.md` § "Deep precision: the float wall" and
following sections (on the branch).

## 2. What was built (on the branch)

| Piece | Files | Verdict |
|---|---|---|
| Camera position in double, saved as `camera.positionD` in `.frac` | `engine/Camera.java`, `config/FractalConfig.java`, `CameraTest`, `ConfigRoundTripTest` | **keep** — necessary, type-independent |
| `camPosD` (dvec3) uniform, double[] uniform support | `GLSLEngine.java`, `common.glsl`, controller | **keep** |
| Double march of primary rays + normals (`rayMarchDeep`, `calcNormal` branch), `DEEP_MIN_EPSILON` | `raytracer.glsl` | **keep the mechanism**, redo the entry (§ 4.2) |
| `DE_deep(dvec3)` hand-written in `mandelbox.glsl` and `kaleidoscopic.glsl`, `GraphCompiler.generateDEDeep` on a bare leaf only | kernels, `GraphCompiler.java` | **replace** — per-kernel, bare-leaf only |
| `Renormalizer.java` + `RenormalizerTest` (4 tests, pure CPU): camera carried through its first k folds of a kaleidoscopic IFS in double, branch tree for straddled mirrors (≤ 16 leaves), cascade of shells, packing to an SSBO | `render/Renormalizer.java`, test | **keep** — correct, tested, and mathematically exact for affine IFS; it is an optimisation layered on top of the double path, never the core |
| Renormalised march in the shader (`rayMarchRenorm`, `DE_local` in `kaleidoscopic.glsl`, SSBO binding 13, `#define RENORMALIZED`/`HAS_RENORM`) | `raytracer.glsl`, `common.glsl`, `GLSLEngine.java`, controller | **keep**, re-hook through the compiler (§ 4.3). Measured on the KIFS against the oracle: 0.08° / 0.11° / 0.09° at 1e-2 / 1e-3 / 1e-4, 1.3° at 1e-6, at the cost of the float path (the oracle is 4–6× slower). Deeper rungs only measured on the symmetry axis (worst case, tree saturates) — the off-axis run never completed; `PrecisionLab` has since been fixed to aim off-axis (fov default, plane filter). |
| `CpuDE.java`: Mandelbox and KIFS estimators in Java, used for clearance (auto speed), the cascade's inner radius, and the focus pick | `render/CpuDE.java` | **delete** — the type-specific dead end; replace by a GPU probe (§ 4.1) |
| Navigation: wheel geometric to 1e-12, log10 speed slider, log HUD meter, *Auto speed* = `clearance × dial × 0.38` per frame with a floor | `NavigationController`, `FractalPanel`, `ViewportHUD` | **keep the UI**, feed clearance from the probe |
| DoF: lens offset carried as `gLensOffset` / `gPrimaryOrigin` (never an absolute origin), focal point as a vector, `aperture <= 0` instead of `< 0.0001`, log10 focal/aperture sliders | `common.glsl`, `QualityPanel` | **keep** — structural and type-independent |
| DoF "Follow zoom" (autofocus + f-number) | — | **already removed** |
| Focus pick: async GPU read; depth read from the variance image (adaptive sampling zeroes the accumulation's alpha for converged pixels, so `alpha / sampleCount` under-read, badly late in a refinement → focus far too close → everything blurred); CPU march for KIFS/Mandelbox | `GLSLEngine.readDepthAtAsync`, controller, `GLSLFractalizerApp` | **keep the variance-image fix** (real pre-existing bug); **delete the CPU path** |
| `PrecisionLab` harness + `report.html` | `test/PrecisionLab.java` | **keep**, generalise its ladder placement to the probe |
| `presets/KIFS_DIVE.frac` | — | keep |
| Deep/Renormalize checkboxes no longer greyed (the disabled state was computed once and outlived the scene) | `QualityPanel` | keep |

**Open symptom when the session ended:** the owner reports that a Ctrl+click focus pick
during a 64-sample refinement often does not take effect until the refinement completes,
while camera moves and slider changes preempt instantly. The scheduler (`ViewportScheduler`)
is preemptible at every step and `request()` posts a step, so by reading it should preempt;
the GPU-read path was the suspect (its result arrives via GL thread → `Platform.runLater` →
`requestRender`). Diagnose with `-Dfractalizer.debugRender=true` (prints every viewport step)
and the new `[focus] …` console line. The variance-image fix may already have been the whole
story (a wrong depth, not a late one).

**Resolved on `deep-zoom` (§ 0, commits 2 and 3):** it was both. The depth was wrong (75 %
short with adaptive sampling) *and* late: "by reading it should preempt" was the trap,
`engine.post` runs inline on the GL thread, so each step ran the next inside itself and a
whole refinement was one GL task; requests are checked at every step, a posted read is not.
`-Dfractalizer.debugRender=true` showed a pick waiting through two whole refinements.

## 3. Harnesses and numbers

- `mvn test` — green on the owner's machine with everything above (incl. `RenormalizerTest`).
- `PrecisionLab` (CLAUDE.md has the commands): camera ladder toward a surface point,
  float / double-or-renorm / oracle, per-pixel normal error against the oracle, `report.html`.
  Outputs of the session: `out/precision/` (Mandelbox) and `out/precision_kifs/` (KIFS).
- Shader compile times on the owner's NVIDIA: ~7 s plain, ~14–20 s with `RENORMALIZED`
  (the 16-way tree and SSBO indexing). NVIDIA refuses a uniform float array over 1024
  registers (C6020) — hence the SSBO.
- FP64 on the owner's GPU is cheap: the all-double oracle costs about the same as 6 double
  iterations (~1.7× the float path overall).

## 4. The robust design to build from `main`

### 4.1 One source of truth for the distance: a GPU probe
Anything that needs a distance (focus pick, auto speed, the renormalisation's inner radius,
`PrecisionLab`'s ladder) evaluates **the compiled scene program itself** on a handful of rays
(camera position, centre ray, picked pixel), one output pixel each, read back asynchronously
(PBO + fence, one frame of latency). Microseconds of GPU per frame, works for every graph,
hybrid, custom shader. Delete `CpuDE`. The probe should run the same precision path as the
view (double / renormalised when active) so it is right at depth.

*Done (§ 0, step 2).* The read turned out synchronous (the GPU is idle between steps); auto
speed follows the free distance along the move rather than the estimate at the camera; the
probe shares the view's path through `main()`, so it will follow § 4.2's double march.

### 4.2 Double precision generated by the compiler, not written per kernel
`GraphCompiler` already emits the composite DE. Make it emit a **second, double-typed
composite** mechanically: kernels written against generic type macros (`real`, `real3`,
literal suffix) and included twice with `#define real float` / `#define real double`;
transforms, CSG, effects and hybrid steps are emitted by the compiler and can be emitted in
double the same way. Kernels that use functions GLSL lacks in double (trig, `pow`) are
detected at compile (try the double variant, catch the failure, or a tiny static scan) and
the scene falls back to the float entry — automatically, not by a list. `rayMarchDeep` then
calls `DE_deep` for any scene. Secondary rays (shadows, AO, bounces) should move to local /
double coordinates too; the float floors (`surfaceBias` 1e-5, AO radius) go away with them.

*Done for the primary rays (§ 0, step 3)*, by translating the scene's float source rather
than rewriting the kernels with macros: same result, nothing to keep in step by hand, and the
float programs untouched. Secondary rays are still float.

### 4.3 Renormalisation as a compiler-recognised optimisation
Keep `Renormalizer` for affine IFS leaves and let the compiler hook it when a leaf *is* one
(KIFS today; Sierpinski and the fold-and-scale Menger variants next; the IQ-style
`menger.glsl` is not an iterated map and needs its own derivation; the Mandelbox's sphere
fold is an inversion — exact composition but rays stop being straight — later or never).
Never required: the double path (4.2) must carry every scene on its own.

### 4.4 Depth of field
Manual. Log10 sliders. Lens offset as a delta (done). Focus pick via the probe. Nothing
automatic.

### 4.5 Validate with the oracle, always
`PrecisionLab`'s oracle (whole orbit in double) is the ground truth; a change is accepted when
the measured normal error against it is at the 1e-4 level. Generalise the harness's ladder
placement to the probe so it runs on any scene.

## 5. Where things are
- Branch `deep-zoom-wip`: everything in § 2, as left. Branch `deep-zoom`: the rework (§ 0).
- `docs/RENDERING.md` on that branch: the full narrative (sections "Deep precision: the
  float wall", "Renormalisation", "What follows the zoom").
- `out/precision*/report.html`: the measurements with images.
