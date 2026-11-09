# Deep zoom — handoff

Read it before touching anything related to deep zoom, precision, DoF or navigation.

## 0. Where the work is (updated 2026-11-09, night)

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
- **Still only on `deep-zoom-wip`, by design:** auto speed (`NavigationController
  .effectiveStep`, the checkbox) waits for the probe (§ 4.1) to feed it a clearance; Deep
  Precision / Renormalize (params, `.frac` fields, Quality panel checkboxes, `rayMarchDeep`,
  the per-kernel `DE_deep` / `DE_local`, `Renormalizer` + test, the SSBO), `PrecisionLab`,
  `presets/KIFS_DIVE.frac` and the docs/RENDERING.md narrative belong to § 4.2-4.3 and § 4.5;
  `CpuDE` is to be deleted.
- **Next: § 4.1, the GPU probe.**

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
