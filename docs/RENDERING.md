# Rendering & Coloring Pipeline

Detailed documentation of the rendering, coloring, and quality systems in Fractaliz3r.

---

## Visual Gradient Editor

The coloring system uses a GPU-based 1D texture (256x1, RGB32F) driven by a visual gradient editor with draggable color stops.

**Architecture**: `GradientPalette` (model) → `toTextureData()` → `GLSLEngine.updatePaletteTexture()` (GL_TEXTURE1) → shader sampling via `getPresetPalette(t)` (fract, cyclic) and `getSmoothPalette(t)` (clamp, for environments).

**10 Built-in Presets**: Magma, Ice, Forest, Neon, Spectral, Sunset, Ocean, Aurora, Pastel, Monochrome.

**Parameters**:
- `Color Strength`: Multiplier for color intensity and contrast.
- `Palette Shift`: Global offset to slide colors across the fractal structure.

## Coloring Modes

13 coloring modes in `applyMaterial()` (MaterialPanel "Coloring" ComboBox). Modes 0–8 are orbit-trap-based; modes 9–12 are **geometry-based** (scale-invariant — colors derive from surface properties, not iteration history).

### Orbit-Trap Modes (0–8)

| Mode | Name | Description |
|------|------|-------------|
| 0 | **Standard** | Flow + depth (default) |
| 1 | **Iteration Bands** | Sharp discrete color bands by iteration count |
| 2 | **Distance** | Structural/proximity based |
| 3 | **Angular** | atan2 spiral patterns |
| 4 | **Blend** | Equal mix of structural + flow + depth |
| 5 | **Contour** | High-frequency sine stripes (topographic) |
| 6 | **HSV Direct** | Factors → H/S/V independently (no palette) |
| 7 | **Dual Palette** | Two palette lookups blended by depth |
| 8 | **Neon** | Sharp hue bands, high saturation glow |

### Scale-Invariant Modes (9–12)

These bypass orbit traps entirely. `applyMaterial()` receives `hitPos`, `normal`, and `rayDir` to compute colors from geometry.

| Mode | Name | How It Works | Why Scale-Invariant |
|------|------|-------------|---------------------|
| 9 | **Normal Map** | `dot(normal, lightVec) * 0.5 + 0.5` → palette | Normals are unit vectors, always [0,1] |
| 10 | **Triplanar** | Dual-octave `triplanarNoise()` blended by normal weights → palette + edge darkening | Position-proportional detail at every scale |
| 11 | **Curvature** | Laplacian of DE via 6 `DE_simple` samples, eps scaled by camera distance → palette | Normalized by adaptive epsilon |
| 12 | **Fresnel** | `pow(1 - abs(dot(normal, rayDir)), 2)` → palette | Dot product always [0,1] |

**Parameters**: `Color Strength` adjusts intensity for all modes (also controls noise frequency for Triplanar). `Palette Shift` offsets the palette lookup.

## Improved Orbit Traps

Major fractals use cumulative "Plane Traps" (weighted sum of absolute coordinates) to ensure rich, non-uniform coloring that reacts dynamically to fractal parameters.

## Sample Controls

Three separate sample counts control rendering quality at different stages:
- **Preview Samples** (QualityPanel, 16-4096, default 64): Controls `fullSamples` in the controller — the number of iterations for Auto Full Quality preview rendering.
- **Export Samples** (ExportPanel Image section, 16-1024, default 128): Passed directly to `exportToPNG(file, samples, progress)` for single image export.
- **Animation Samples** (ExportPanel Animation section, 1-128, default 16): Per-frame samples for animation sequence export.

---

## Blue Noise Sampling

AA jitter and DoF aperture sampling use a 64x64 blue noise texture instead of PCG white noise. Blue noise is spatially uniform — at equal sample counts, the image looks 2-3x cleaner to the human eye (no random clumping).

- **Texture**: 64x64 RG32F, generated at startup by `BlueNoiseGenerator` (Mitchell's best-candidate algorithm). Bound to `GL_TEXTURE2` during raytracer pass. `GL_NEAREST` filter, `GL_REPEAT` wrap (seamless tiling).
- **Temporal animation**: `fract(bn + sampleIndex * φ)` (golden ratio) decorrelates each frame while preserving the blue noise spectrum.
- **AA jitter**: `texelFetch(blueNoiseTex, ivec2(gl_FragCoord.xy) % 64, 0).rg` replaces `random2(seed)`.
- **DoF aperture**: Same texture, offset texel `(+37, +17)` for decorrelation from jitter. Drives the disk sample (`r`, `theta`) with polygon bokeh shaping preserved.
- **PCG seed consistency**: 2 dummy `random(seed)` calls at each replacement site keep the downstream PCG chain identical — zero regression on path tracing, volumetric fog, soft shadows.
- **Not used for**: path tracing bounces, volumetric fog, SSS, GGX — per-bounce randomness doesn't benefit from spatial blue noise.

---

## Raymarcher Improvements (Cone Tracing)

Four independently toggleable raymarcher enhancements in QualityPanel "Raymarcher" TitledPane. Each feature gates on its own condition — zero overhead when disabled.

- **Cone Tracing** (`pixelRadius`): Pixel-aware adaptive epsilon. `epsilon = max(MIN_EPSILON, pixelRadius * distance)` where `pixelRadius = tan(fov/2) / (height/2)`. Replaces legacy `computeAdaptiveEpsilon()` in `rayMarch`, `rayMarchSimple`, and `calcNormal`. Tiled export paths override `pixelRadius` using full image height (not tile height).
- **Fudge Factor** (0.1–2.0, default 1.0): DE step multiplier. `step *= fudgeFactor`. Applied in `rayMarch`, `rayMarchSimple`, and `calcShadow`. Values >1 = faster but riskier, <1 = more conservative.
- **Surface Refinement** (0–8 steps, default 4): Bisection of the last step interval after hit. `refineSurface()` detects **SDF vs fractal DE** at the hit point: for true SDFs (negative distance inside), binary search for zero-crossing; for fractal DEs (always non-negative), **ternary search** to minimize DE along the ray (converges to the true surface). After refinement, when cone tracing is active, **6 extra sphere-tracing steps** converge from the cone epsilon (~0.003) to `MIN_EPSILON` — each step reduces DE by ~10x. Re-evaluates full `sceneDE` at the final position for correct orbit traps and coloring.
- **Step Relaxation** (0.0–1.0, default 0.0): Keinert 2014 over-relaxation. `omega = 1 + stepRelaxation`. On overshoot (`prevD + d < candidateStep`): backstep, reset to conservative stepping. Applied in `rayMarch` and `rayMarchSimple`.

**Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`): `coneTracingEnabled` (bool, default true), `fudgeFactor` (float), `refinementSteps` (int), `stepRelaxation` (float).

### Known consequence: dark rim at silhouettes (path tracing only)

Cone tracing accepts a hit as soon as the ray comes within one pixel footprint of the surface (`d < pixelRadius * dist`) — that is what antialiases the silhouette. At the outline the ray only *grazes*, so the accepted point sits up to a full pixel footprint **beside** the geometry, in empty space, and everything downstream is evaluated there:

- the tetrahedron normal (gradient step `e = pixelRadius * dist * 0.5`) is ill-conditioned at a near-tangent, off-surface point;
- the shadow / NEE ray leaves from that near-tangent point, re-enters the object and reports occlusion;
- in the path tracer most bounce directions from a tangent point immediately re-hit the fractal, so every bounce loses energy.

The 6 post-hit sphere-tracing steps cannot repair this case: a ray that grazes without ever intersecting has **no true surface to converge to**, so those steps just walk the point further along the ray.

Measured on the default Mandelbulb view (mean luminance per 1-px ring inward from the silhouette, `DeepZoomLab`):

| Configuration | ring 0 (edge) | ring 2 | rim dip |
|---|---|---|---|
| Path tracing + cone tracing **on** | 44.4 | 60.5 | **−26.6%** |
| Path tracing + cone tracing **off** | 71.7 | 76.6 | −6.5% |
| Classic shading (no path tracing) | 154.6 | 151.3 | +2.2% (no rim) |

So roughly **4/5 of the rim is the cone-tracing offset** and 1/5 is genuine **limb darkening** (at a silhouette the surface is tangent to the view: `NdotL` collapses and the visible hemisphere is mostly the object itself — any renderer darkens there). The rim **does not exist in classic shading**; the path tracer is what compounds the bad normal over several bounces, and it is on by default via "Auto Full Quality". Turning off Cone Tracing removes it at ~1.7× the render cost.


---

## Interactive preview vs full quality

`renderPreview` and `renderFull` used to differ only in sample count. Both resized the
engine to the **full** viewport and built the **same** uniforms, so navigating a
path-traced scene paid for path tracing, full resolution, every ray step, shadow step, AO
step and DoF on each frame. `withReducedQuality` existed on every params class and was
called from nowhere.

The preview path now cheapens itself in the uniform map only, leaving the scene's own
settings untouched so the next full-quality pass is unaffected:

- **`previewScale`** (0.2–1.0, default 0.5) — the engine is resized to that fraction of
  the viewport and the ImageView scales the result back up. Resolution is the larger
  lever: half scale is a quarter of the pixels. `engine.resize()` is a no-op when the size
  is unchanged, so continuous navigation stays at preview size and only reallocates once,
  on the way back to full quality.
- **`previewFastShading`** (default on) — classic shading instead of path tracing, no DoF,
  no volumetric fog, no deep-zoom LOD, half the ray steps, a quarter of the shadow steps,
  2 AO steps.

Measured on `NODE_COOL1`, cost of one navigation frame:

| | frame |
|---|---|
| before — 1920×1080, path traced | 2459 ms |
| after — scale 0.5, classic shading | **258 ms** (9.5×) |
| after — scale 0.35 | **187 ms** (13×) |

The return to full quality was already in place and is unchanged: `GLSLFractalizerApp`'s
render loop calls `renderFull()` once `HQ_DELAY_MS` passes with no interaction, gated by
the "Auto Full Quality" checkbox. Controls are in QualityPanel → Quality Settings.

---

## Progressive batching and how soon a render can be interrupted

`ProgressiveRenderer` submits a batch of samples to the GL thread as one blocking call and
only tests the cancelled flag **between** batches, so the batch size is also the worst-case
delay before navigation can interrupt a full-quality pass. It was a fixed 8: free on a cheap
scene, and seconds of dead viewport at 1080p path-traced where a sample costs ~600 ms.

Batches are now sized from the measured cost of the previous one, targeting `BATCH_TARGET_NS`
(120 ms) of wall clock and capped at the old 8 so cheap scenes are unchanged. Measured at
1280x720 path traced: worst tick **372 ms down to 130 ms**.

Two things had to be right for that to be a win rather than a trade:

- The readback and Image conversion in `updateImage()` is a **fixed cost per tick**, so
  sizing batches for responsiveness alone bought thirteen readbacks instead of three and a
  40% longer render. The display refresh is rate-limited separately (`IMAGE_INTERVAL_NS`,
  200 ms), which buys interruptibility without buying readbacks.
- The batch timing needs an explicit `glSync()`. GL calls return before the GPU is done, and
  without the wait the estimate collapses, the batch grows back to 8 and the worst tick goes
  to 896 ms — worse than the starting point. Verified by removing it.

Exports do not go through this path; see **[EXPORT.md](EXPORT.md)** for their own
progress-vs-work problem. `test/ResponsivenessProbe` reports the longest tick;
`test/ResizeProbe` measures the framebuffer reallocation a preview/full switch costs (9.5 ms
at 1080p, 24.8 ms at 4K — real, but minor next to the batch).

---

### Since 3.2.2: the viewport scheduler

The interactive viewport is one object, `ViewportScheduler`, described in
[docs/INTERACTIVE_RENDER.md](INTERACTIVE_RENDER.md): the JavaFX thread freezes the scene
into a `SceneSnapshot` and posts it; the scheduler turns it into bounded steps on the GL
thread (compile if needed, a preview sized by a cost model to fit 30 ms, then a refinement
whose samples are drawn in strips when one exceeds the budget), with the preemption policy
in one place. The batching, cancel flags and heuristics this section used to describe are
gone with `ProgressiveRenderer`.

Measured with `NavigationFluidityProbe` (one request per frame from the JavaFX thread, 3 s
of camera motion, previewScale 0.5, fast shading):

| scene, viewport | images/s | request to image, median | JavaFX held per request | first preview after a move during refinement |
|---|---|---|---|---|
| Mandelbulb 1280x720 | 54 | 3 ms | 0 ms | 40 ms |
| Julia bulb (path traced) 1280x720 | 58 | 5 ms | 0 ms | 28 ms |
| Albedo 0.39 (29 effect nodes) 1280x720 | 41 | 8 ms | 0 ms | 61 ms |
| Albedo 0.39 1920x1080 | 37 | 7 ms | 0 ms | 50 ms |
| Labyrinth 1920x1080 | 39 | 10 ms | 0 ms | 59 ms |

Before this work the last column was 128 ms on any scene (one 120 ms batch) and one whole
sample on a heavy one: 800 ms to 4.5 s on Labyrinth at 1080p, whose full-quality sample
costs 12 s.

The controls are the other half of "as soon as I touch something". `SliderFluidityProbe`
builds the app's panels (the node graph editor, Lighting, Material, Quality) against the
real controller, in a real window, and drags one slider of each at 30 ticks a second on
Albedo 0.39 at 1280x720: a tick holds the JavaFX thread for 0 ms (2 ms at most for the
node editor, which redraws its canvas), the worst JavaFX stall while dragging is 17 ms for
the node editor and 1 ms for the other panels, and each tick's preview reaches the
viewport in about 30 ms, which is the preview step budget: the first image of a preview
is one sample sized to fit it. Nothing on the slider path needed fixing; the probe stays
as the guard. Exports do not go through the scheduler (`ExportAfterPreviewProbe`,
`RenderRegression check`: bit-exact). `ResponsivenessProbe` guards the refinement's
throughput: 41 ms a sample on the Julia bulb at 1280x720, 39 whole; 282 ms on Albedo 0.39
at 1080p against 190-210 whole, the price of a resume within 60 ms there (`StripCostProbe`
for why: a sync between two draws costs the tail of the first).

## Why renders looked washed out (rim light)

Colour had been flat across the whole project, and the cause was neither the palette nor
the orbit traps. `test/ColorProbe` forces a pure red/green/blue gradient and renders the
same camera through several passes, so any pass that comes out less than vivid is the one
destroying saturation:

| pass | mean HSV saturation |
|------|--------------------|
| ORBIT_TRAP (raw factors, no palette lookup) | 0.674 |
| DIFFUSE (`baseColor * NdotL` — the palette, nothing added) | 0.612 |
| FINAL classic | **0.289** |
| FINAL classic, specular and ambient both zeroed | **0.274** |
| FINAL path traced | 0.569 |

The palette pipeline is fine — DIFFUSE keeps the saturation. FINAL loses more than half of
it, and zeroing specular *and* ambient barely helps, which rules out the obvious suspects.
What remained was one line:

```glsl
vec3 rimLight = lightColor * rim * 0.15;   // rim = fresnel(viewDir, normal, 3.0)
```

An unconditional white term, hard-coded, with no control. It is proportional to the
Fresnel factor, and on a convoluted fractal a grazing angle is most of the surface.
Worse, roughly half the surface sits in full shadow (`calcShadow` returns exactly 0 as
soon as the shadow ray reaches any geometry), where the diffuse term is zero — so the
white rim was the *only* thing lighting those regions, and the palette had nothing to
show there. Disabling it took FINAL from 0.289 to 0.414, and 0.274 to 0.431 with the
other terms off.

It is now `rimIntensity` (`AbstractFractalParams`, serialized in `RenderingConfig`, slider
in QualityPanel → Glow / Rim). **The default stays 0.15 so existing scenes render exactly
as before** — all `RenderRegression` scenes still pass — but every shipped preset sets
0.03, which takes them from beige to full colour (mean frame saturation ~0.70).

The remainder of the gap between DIFFUSE and FINAL is ACES tone mapping, whose path-to-
white desaturates bright output by design; `toneMapMode`, `exposure` and `saturation`
already exist as controls for it.

**Still missing:** those post-processing settings live on the engine's `PostProcessSettings`
and are **not serialized in `.frac` at all**, so tuning saturation or tone mapping and
saving a scene loses it on reload — the same class of gap as `coloringMode` had.

---

## Why a frame reads as a single tone (palette-driven sky)

Separate from the rim light, and just as structural. The Space sky — `skyType 1`, the
default in most presets — builds its nebula from the **same palette texture the fractal
reads**:

```glsl
vec3 nebula = getSmoothPalette(n1 * 1.2 + paletteOffset) * smoothstep(...) * cloudDensity * 0.4;
```

So object and background are always the same hue family. With a single-hue gradient the
entire frame comes out one colour, which is exactly what "everything is brown" looks like.
Same object, same gradient, three skies:

| sky | result |
|-----|--------|
| 1 — Space (reads the palette) | green object on a green sky, one tone |
| 2 — Ocean | green object on a blue sky |
| 3 — Studio | green object on neutral grey |

**A related trap when judging this:** measure saturation over **surface pixels only**. A
whole-frame mean includes a palette-tinted sky and reports a colourful number for an image
that is uniformly one hue. Measured on the surface, `HYBRID_BOXBULB` at 0.369 was *not*
less saturated than the visibly-purple `JULIA_FOUND_CLUSTER` at 0.387 — brown is a
saturated orange, and the problem was hue, never saturation.

**Multi-hue gradients do not fix it — in modes 0-8.** Surface saturation drops from 0.369
to 0.154 when one is used there. Those modes build their lookup from orbit traps, whose
field varies faster than a pixel, so sweeping the palette wide enough to cross several
hues makes neighbouring positions average to grey inside a pixel.

**Modes 9-12 do fix it.** They read geometry rather than orbit traps — surface orientation
(9), world position (10), curvature (11), view angle (12) — which varies slowly and in
large regions, so a multi-hue gradient survives. `test/ColorDemo` renders one scene under
all thirteen modes as a labelled sheet with a hue-spread count per mode, and the split is
unambiguous: 0-8 give one hue whatever gradient they are handed, 9-12 put several on the
same object. This is what the shipped presets now use.

**Choosing between them:** Triplanar (10) is keyed to world position, so on a deep-zoom
framing its noise sits at a scale far larger than the view and flattens to a single value —
`JULIA_BULB_ABYSS` blew out to white under it. Normal Map (9) depends only on orientation
and is scale-free, which is what a close-up needs. Triplanar suits whole-object framings.

**Fixed:** the nebula now has a colour of its own. `nebulaColor` and `nebulaTint`
(`AbstractFractalParams`, serialized in `EffectsConfig`) blend `renderSpaceLegacy`'s nebula
away from the palette lookup towards an explicit colour — tint 0 keeps the historical
behaviour, so existing scenes are untouched. Every shipped preset sets a nebula that
contrasts with its object: deep blue behind a golden bulb, teal behind a violet one, warm
amber behind an icy Menger.

Moving the presets to a neutral studio sky was the wrong answer to this, and worth
recording as a trade not to repeat: it removed the monochrome frame by removing the
background altogether, which is a worse image than the one it replaced. The sky needed its
own colour, not to be switched off.

---

## Deep Zoom (Fine Detail)

What actually limits detail during a deep dive, measured with `DeepZoomLab` (see Test Harnesses).

### View-relative scales

Radii pinned to world units stop making sense once the whole frame is a fraction of a world unit across: they span many screen-heights, the occlusion term saturates to a flat tint and shadow rays start beyond every nearby fold, so the image flattens and darkens exactly where the detail is. Three helpers in `common.glsl` express those radii relative to the view instead:

| Helper | Meaning |
|--------|---------|
| `viewScaleAt(d)` | world-space half-height of the frustum at distance `d` |
| `pixelScaleAt(d)` | world-space size of one pixel at distance `d` (cone-tracing footprint, or derived from `resolution`) |
| `surfaceBias(d)` | secondary-ray offset — a few pixel footprints, i.e. the real accuracy of the hit point |

- **`calcAO`** caps its outer probe radius at `min(0.13, 0.12 * viewScaleAt(dist))` and renormalises the (length-scaled) occlusion sum back to the 0.13 reference, so AO *strength* is unchanged while its *scale* follows the zoom.
- **Every shadow bias** (9 call sites: classic shade, reflections, the 4 path-tracer NEE blocks, extra lights, volumetric fog, AOV pass) now uses `surfaceBias(dist)` instead of the fixed `0.005 + dist * 0.01`.

Fine-detail views gain crevice definition and contact shadows.

**Caveat — `surfaceBias` is resolution-dependent, and the regression harness hides it.** The bias now scales with the pixel footprint, so it shrinks as resolution rises: at 480×270 it lands near the old fixed `0.005 + dist * 0.01`, but at 1280×720 it is ~5× smaller, which deepens self-shadowing in the folds. Measured on the default Mandelbulb view at 720p: surface in full shadow **37.9% → 50.1%**, mean surface luminance **130.6 → 121.2 (−7%)**. `RenderRegression` runs at 480×270 and reports ALL PASS (mean diff < 1/255) — it does **not** catch this. Check resolution-dependent shading changes at final render resolution, not at harness resolution.

### Zoom-adaptive iteration budget

A DE run with a fixed iteration budget stops resolving structure below a scale set by that budget; the boundary it *does* resolve is a smooth manifold. `gExtraIterations` (set once per pixel in `main()` from `zoomDetailIterations(sceneDE_simple(camPos))`) adds `detailLOD` iterations per octave of camera clearance below one world unit, capped at `detailLODMax`. Every fractal shader spends it as `maxIterations + gExtraIterations`, including the normalisers that turn iteration counts into coloring factors.

**Off by default** (`detailLOD = 0`): on an IFS or a Mandelbox the iteration count sets the *shape*, not just how finely it is resolved, so raising it silently would change every saved scene. Enable it in QualityPanel → Raymarcher when diving.

Measured on a Mandelbox at 236× zoom: `detailLOD = 2` gives **+13% detail and +7 points of edge density** at no extra render time; `4` adds ~1% more.

### What the iteration budget cannot fix

Two regimes, and they need different answers:

- **Self-similar formulas hold up.** Mandelbox, camDist 4.73 → 0.02 (236×): detail 10013 → 6788 (−32%), luminance and contrast steady. The renderer is not the bottleneck.
- **Mandelbrot-mode formulas go smooth.** Mandelbulb, camDist 1.4 → 0.02: detail 8568 → 260 (**−97%**), a featureless drape. This is the formula, not the renderer: adding `pos` each iteration leaves large analytic bulbs whose surface is locally smooth, and the DE saturates — beyond ~11 extra iterations the render is bit-identical (verified at `detailLOD` 2, 4 and 8).

The fix is **Julia mode**: a fixed constant instead of `pos` makes every point of the set a boundary point. `MandelbulbParams.juliaC{x,y,z}` (0,0,0 = Mandelbrot, unchanged), matching the existing Bristorbrot / QuaternionJulia4D convention; the Node Graph editor picks up the sliders automatically under a "Julia C" group. With `c = (0.42, 0.18, -0.31)`, a Mandelbulb dive from camDist 1.24 → 0.034 (88×) holds detail 8729 → 10650 — no collapse at all.

---

## Adaptive Sampling

Variance-based convergence detection that skips already-converged pixels during progressive rendering. Concentrates GPU effort on noisy regions (fractal detail, path-traced reflections) while skipping smooth areas (background, sky, flat surfaces). Best gains on fractal scenes with visible sky/background (~30-50% speedup). Minimal gain on closed scenes like Cornell Box.

- **Variance texture**: RGBA32F (`image2D`, binding 5). Per-pixel: R=sumLum, G=sumSqLum, B=count.
- **Convergence criterion**: Variance-of-mean (`popVariance / count < threshold`). This measures actual noise in the pixel average, not raw sample dispersion.
- **raytracer.glsl**: Early exit at top of `main()` if converged → `FragColor = vec4(0)` (additive blend adds nothing). Variance stats updated via `imageStore` after shading.
- **postprocess/bloom_extract/display**: Per-pixel sample count division (`texture(varianceTex, uv).b`) instead of global `sampleCount` when adaptive is on.
- **GLSLEngine**: Variance texture + FBO lifecycle, `glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)` between samples, `GL_TEXTURE_FETCH_BARRIER_BIT` before postprocess read.
- **Zero overhead when OFF**: No imageLoad/imageStore, no memory barriers.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `adaptiveSampling` (bool, default false)
  - `varianceThreshold` (float, default 0.0005 — stddev ~2.2% noise)
  - `minAdaptiveSamples` (int, default 16 — minimum passes before convergence check)
- **UI**: QualityPanel "Adaptive Sampling" TitledPane (checkbox + Threshold slider + Min Samples slider).
- **Interaction with sample counts**: Min Adaptive Samples is a floor before checking, Preview/Export Samples is the ceiling. A pixel renders between `minAdaptiveSamples` and `maxSamples` passes.
- **Render timer**: Status bar shows elapsed time after full quality render completes (e.g., "Rendered 64 samples in 3.2s").

---

## Cinematic Rendering Pipeline

1. **Volumetric Fog & God Rays**: Physically-based scattering with Henyey-Greenstein phase function and shadow-aware light accumulation.
2. **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax based on camera movement.
3. **Optics (Lens Effects)**: Realistic camera imperfections including Lens Dirt (dust/spots) and JJ Abrams style anamorphic horizontal flares.
4. **Color Grading**: Procedural LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant professional looks.
5. **Monte Carlo Path Tracing**: NEE + MIS, GGX microfacet BRDF.
6. **Advanced Lighting**: Spot light with configurable cone angle and edge softness.

---

## Cornell Box & Glass Refraction

The Cornell Box scene (`cornellbox.glsl`) uses `#define HAS_PER_OBJECT_MATERIAL` for per-object material assignment via `getObjectMaterial(OrbitTrap)`. Glass refraction in path tracing uses a two-surface approach: entry refraction + interior march using `abs(DE_simple)` + exit refraction, solving the SDF negative-distance problem inside glass bodies.

### Spectral dispersion (after 3.2.2)

A prism splits white light because the index of refraction depends on the wavelength. The
path tracer can do the same. It is off by default and costs nothing while off:
`dispersionEnabled` (Material panel, *Physical Material* section, *Dispersion (path
tracing)*; it applies to every glass surface, the global material or a MaterialNode's, so
it is not greyed out when the global type is not glass) and `dispersion`, Cauchy's B coefficient in micrometres squared (crown glass
0.004, flint 0.013; the slider goes to 0.05 because the effect is the point). Both are
uniforms, saved in the `.frac` next to `ior`; no recompilation.

How it works (`common.glsl`, both path-tracing entry points of `raytracer.glsl`):

- each path draws one wavelength, uniform in 400-700 nm. The draw happens only when the
  effect is on, so a render without dispersion consumes the same random sequence as before
  and is bit-exact with the goldens (RenderRegression);
- at a glass surface the index becomes n(λ) = n_d + B (1/λ² − 1/λ_d²), λ in micrometres,
  λ_d = 0.5876 µm (the d line, where n equals the material's IOR). Blue bends more than red;
- at the first dispersive refraction the path's throughput is multiplied by the colour of
  its wavelength: the Wyman, Sloan and Shirley multi-lobe fit of the CIE 1931 matching
  functions, XYZ to linear sRGB, negatives clamped, divided by the mean over the band
  (0.587, 0.384, 0.363) so that the average over wavelengths is white. A path that never
  meets glass is never tinted, so the effect exists only behind glass and the rest of the
  frame is unchanged (an A/B of `PRISM_GEM` with the box ticked and unticked, rendered by
  GalleryRender from two copies of the `.frac`, differs inside the octahedron only).

Two shipped presets show it:

- `presets/PRISM_GEM.frac`: a glass octahedron (IOR 1.7, B 0.03) in front of a near-white
  Mandelbulb under the nebula sky. The fractal is grey on purpose: every colour in the
  image is the dispersion of the glass, blue on one side and orange on the other of each
  edge seen through the gem.
- `presets/PRISM_LENS.frac`: the same fractal, coloured this time, behind a glass sphere
  (IOR 1.5, B 0.03): a lens, the detail magnified and smeared into a rainbow whorl.

Why the presets put the glass in front of the fractal rather than making the fractal glass:
a transmitted path inside a Mandelbulb or a Menger sponge is folded many times before it
finds a way out, most of them die within the bounce budget, and the object renders black
whatever the dispersion. Convex glass with a detailed object behind it is the case for the
effect. The wavelength is one more dimension of the integral, so glass needs more samples
than the same scene without it (the previews above are 128-160 spp at 960x540); flat colour
seen through glass is the noisiest case, fine detail behind it the cleanest.

### Caustics, and one light transport for every light (after 3.2.2)

Light concentrated by glass or a mirror onto a matte surface, the crescent under a glass
ball, the spot a lens throws, is what a path tracer with next-event estimation cannot render:
at a matte point it samples the light directly and a shadow ray through glass is blocked;
from the camera it never finds a delta sun behind a chain of specular surfaces, and finds an
area light behind one only by chance. So the engine traces the other way too, and the two
directions are combined by multiple importance sampling: every path is counted once, by
whichever way found it, weighted by how likely each way was to find it. Off by default; on
with *Caustics (photons from the sun)* in the Quality panel's path-tracing section, and only
with path tracing. Everything below is compiled in under `BIDIR`, a define present only
then: the program of a scene without caustics is what it always was, byte for byte.

How it works (`lights.glsl`, `photon.glsl`, the `BIDIR` blocks of `pathTrace`,
`GLSLEngine.photonPass`):

- **A second program per scene.** The scene source compiled once more with `PHOTON_PASS`,
  which takes the camera main out of `raytracer.glsl` and appends `photon.glsl`: one fragment
  is one photon. Compiled only when caustics are on for that scene, by the scheduler's
  compile job or by `activateCurrentProgram` for exports.
- **Every light of the list sends photons.** A light is drawn by power: the sun (from a square
  facing it, centred on *Caustic Centre*, half a side of *Caustic Extent*, lifted above the
  scene, with the sun disc's softness), the beam (from its disk), the emissive primitives
  (a point uniform on the surface, a cosine-weighted direction). Each photon carries the flux
  its draw stands for. Point and spot lights send none: their draw stands alone.
- **One photon pass per sample.** After each accumulation sample the engine draws
  side x side photons (128 to 1024 a side, 512 by default) into two small attachments,
  where each landed and what it adds, then splats them as points with additive blending
  into the accumulation. The post-process divides by the sample count as before, so bloom,
  tone mapping, tiles and exports see caustics without knowing about them. Adaptive
  sampling is off while caustics are on.
- **The flight.** Marched with `rayMarchSimple` like a camera ray. Glass refracts it (the
  path tracer's glass exactly; with dispersion on the photon has a wavelength, so the
  caustics are rainbows), metal reflects it through the same GGX sampling whatever the
  roughness, the ocean reflects or scatters it, a matte surface scatters it. Once it has
  passed a glass, a mirror or a metal, a coin (one in two) at every matte or metal vertex
  decides whether it lands there or goes on; before that it only goes on. The photon pass
  makes the paths with such a vertex between the light and the landing; direct and diffuse
  light are the path tracer's, whose draw has far less variance than a scatter of landings
  (with the photons allowed to land anywhere, the sun's emission cells showed through as
  patches on a plain floor).
- **The landing.** The photon is connected to the camera: the pixel that sees the point, a
  march back to the eye for visibility, and the radiance it adds, phi * f * cosX / (d²
  A_pixel cos³Cam), f the BRDF for the camera's direction. With depth of field the eye is a
  point of the lens and the pixel the one whose centre ray meets the lens ray on the focal
  sphere. The 360 projection has no caustics. A landing is capped at 64 times what a photon of
  average flux adds at the distance of the caustic centre: the fireflies of a landing right
  under the camera, and nothing else.
- **The weights.** A path from the camera to a light can be made three ways: by the path
  tracer drawing the light from its last matte or metal vertex, by a path tracer ray hitting
  an emitter, by a photon landing where the camera sees it. Both tracers compute, for every
  path they make, the density of the other ways of making it, as a running product over the
  path's segments of one density over the other (area measure; a delta on either side counts
  as one; the photon's landing and passing coins count; the photon pass's densities count
  as many times as it draws, side² per sample, against one camera path per pixel), and
  weight their contribution by the power heuristic. The path tracer counts the coins the
  photon would have flipped (the matte and metal vertices before the last specular or metal
  one, seen from the light) and gives the photon pass nothing on a path without such a
  vertex. The roughness threshold of the first version (metal below 0.1 only) is gone: where
  the draw is the better way the photons weigh next to nothing, where the photons are the
  only way (a delta light behind glass) they weigh one, and rough metal is shared
  continuously. Two details that mattered: a photon cannot land on glass, so a path whose
  first vertex is glass is the path tracer's alone; and the segment through a glass body is
  one vertex on both sides, whose cosine is taken where the ray leaves it.
- **Where the sun's photons go.** Most of the square sees no glass. The splat pass also marks,
  on a 64 x 64 map of the square, the cells whose photons met glass or metal; the engine reads
  it back after the first four passes and every sixteenth after, lists the active cells, and
  the photon pass draws three quarters of the sun's photons from that list, a quarter over
  the whole square, each with the flux of its mixture density; the path tracer reads the same
  map for its weights.

- **The gather, for a caustic seen through glass.** A landing has to be visible from the
  camera, and a draw from a matte point behind glass is blocked: the caustic under a ball
  seen through a window, the focus of a lens seen through the lens, is no strategy's above.
  So the photon pass also stores its first eligible vertex (position, flux, normal, incoming
  direction, one slot per photon, an SSBO), three compute passes build a hash grid over
  them (`grid_count`, `grid_scan`, `grid_scatter`: counts per cell, their prefix sum, the
  photons scattered into their cells' slots; 65536 cells of the gather radius), and the path
  tracer, at the first matte or metal vertex it reaches through nothing but glass or
  mirrors, gathers the photons within the radius: their flux through the BRDF over the
  gather area. Rebuilt every pass, before the sample it serves, the radius shrinking with
  the passes as k^-0.15 from 2 % of the distance to the caustic centre (progressive photon
  mapping), so the estimate converges. From such a vertex a BSDF ray that reaches an emitter
  through glass is the gather's path and counts nothing. The window scene of `BidirProbe`
  (the panel scene behind a pane of glass; the path tracer alone finds the caustic by chance
  through the ball and the pane, slowly) converges to the same image: 0.6 % apart, 1 % over
  blocks, the caustic band 0.02 %, and the noise falls 1.4x.
- **Point and spot lights send photons too**, from their jittered point over the sphere or
  the cone with the soft edge as a weight; at their first hit the photons take the draw's
  range falloff instead of the inverse square their flux carries, so the photons and the
  draw tell the same light (CausticProbe's point-light check: 0.999).

What it cannot do yet: photons from the sky (the path tracer has those already, through the
glass it sees), the emitters' light in the fog before a glass (the fog march scatters the
sun and the beam only).

The proofs. `CausticProbe`: a matte slab under the sun with `causticDebug` on, so every
landing weighs one, renders the sun's direct light twice, once by NEE and once by photons,
and the ratio must be 1 (0.998 on the dev machine; 0.992 with a glass ball on the slab and
the emission map active). `BidirProbe`: a glass ball on a slab under an emissive panel, no
sun, a scene the path tracer alone reaches entirely (the caustic under the ball by chance),
rendered by the path tracer alone and with the photon pass weighted against it: the
converged images must agree, and do: 0.25 % apart at 320x180, 0.6 % over 8x8 blocks (the
marcher's cone epsilon inflates the panel a ray can hit by about that); a smooth metal
block instead of the ball checks the GGX bookkeeping (0.1 %, 0.3 % over blocks); the beam
across the slab checks the beam's (0.02 %). The photons remove 1.2x the RMSE at 32 spp on
the panel scene and 1.1x on the metal one, for 3x the time per sample; the caustic is where
they earn it, the rest of the image is the path tracer's as before.

Presets: `CAUSTIC_BALL`, a glass ball on a slab with the sun low, the crescent rimmed by
the dispersion; `CAUSTIC_LENS`, a small glass ball beside the near-white Mandelbulb with
the sun from the right, its focus on the fractal inside the ball's shadow.

### Emitters sampled directly (after 3.2.2)

An emissive node of the graph (a MaterialNode with an emission) used to be a light the path
tracer found by chance: a bounce had to hit it. The Cornell box's lamp is such a node. Now
the graph's emitters that have an analytic shape are listed (`LightList`, `lights.glsl`, an
SSBO at binding 7): a MaterialNode with a solid colour and an emission over a sphere or a
box, reached through standard transforms (translation, rotation, uniform scale) and unions.
For those the path tracer, at every matte or metal vertex, draws one emitter by power and one
point on it (uniform over its surface), marches to it, and adds its radiance through the
BRDF; a bounce that still hits an emitter is weighted against that draw (power heuristic on
area densities), so nothing is counted twice. Emissive fractals, palette-coloured emitters,
primitives under a twist or a subtraction are not listed and stay found by chance.

Compiled in under `HAS_EMITTERS`, a define the controller adds only when the list is not
empty: a scene without an emitter compiles the same program as before, bit-exact. The same
list is what the photon pass draws from, whatever the light (above).

`EmitterProbe` is the proof. Its built-in slab scene (a matte slab and a box under a dim
panel; nothing reaches the firefly clamp) rendered by chance and drawn must converge to the
same image: 0.1 % apart at 1280x720, 1.6 % at 320x180, where the marcher's cone epsilon
inflates the panel a bounce can hit by about that much of its area. The draw removes 2.6x
the RMSE at 32 spp on the slab and 2.5x on the Cornell box, for 1.7x the time per sample.
On the Cornell box the two references differ by 10 %: its lamp (emission 15) is clamped far
more when found by chance (13.5 per hit against a clamp of 8) than when drawn, and the drawn
image is the less wrong one; the probe gives no verdict on such a scene.

The visibility test taught one thing. A march towards a point on an emitter stops a cone
epsilon short of the surface, further along the ray at grazing incidence, and starts a bias
above the shading point; comparing the distance it covered to the distance to the point
rejected most of the floor. What says the emitter was reached is that the march stopped
near the point drawn.

### The beam light (after 3.2.2)

The additional light (Lighting panel, *Additional Light*) has a fourth type, *Beam*: a
directional light confined to a cylinder, a laser or a shaft. Its axis runs from the
light's position along its direction, its radius is the *Area / Beam Radius*, its length the
*Range*, and the *Spot Edge Softness* is the width of its soft edge. Inside the cylinder the
irradiance is the intensity, with no falloff (it is collimated); a point is lit when nothing
sits between it and the beam's source plane, marched back along the axis. Sun-like in the
shading, so it works in classic and path-traced modes alike; with caustics on it sends
photons like the sun. Its draw and its glow in the fog compile in under `EXTRA_BEAM`, a
define present only when the additional light is a beam: measured on the built-in shaders,
the draw alone cost 2 to 3 s of compile per program, and no scene without a beam pays it.

### The medium: the beam seen in the air (after 3.2.2)

A beam in a vacuum is invisible; the picture of a beam through a prism is a picture of fog.
The volumetric fog (Quality panel) scattered the sun along the camera rays, one shadow march
per step; it scatters the beam too now. Not with a second march per step: a full marcher
inlined inside that loop sent the NVIDIA compiler past its cliff and no program compiled
within five minutes (ShaderCompileProbe is how to see that). Instead the beam's cylinder is
intersected with the camera ray analytically, the segment is clipped to the beam's free
length (one march per pixel along the axis, to the first surface), and the in-scattering is
integrated over it with the fog's extinction and the beam's soft edge in eight steps and no
march. The beam's light past a glass (the cone a lens makes of it) is the photon pass's:
past a glass, mirror or metal a photon may scatter in the fog before its next surface, at a
distance drawn from the fog's density, and it is connected to the camera from there, with
the phase function and the fog's albedo (its colour), the connection attenuated as the
camera rays are. Before such a vertex the photon flies straight: its direct light in the
fog is the camera rays' march. Neither the photon's flight nor the path tracer's bounces are
attenuated by the fog, the same approximation on both sides, so the caustic paths keep
agreeing (BidirProbe's beam scene: 0.02 %).

Two things the first version left out, found when the prism looked unreal (2026-09-12):

- **The beam's glow is seen only along the camera ray.** A face of glass or metal reflects
  or refracts what is bright around it, and in a dark foggy room the bright thing is the
  beam; a matte surface next to the beam is lit by the glowing fog. Neither path existed:
  the fog march ran on the camera segment alone, and the photon pass scatters photons only
  past a glass. So the beam's in-scatter (`beamInScatter`, the same analytic integral) is
  now added on every path segment after a bounce, weighted by the path's throughput: the
  prism's faces show the beam in reflection and refraction, the beam's image appears
  inside the glass by internal reflection, the slab glows faintly along the beam. Neither
  the photon pass nor the fog march makes these paths, so nothing is counted twice, and
  the bounce segments stay unattenuated as before. Compiled under `EXTRA_BEAM` only.
- **The beam ended as a disc.** Its free length is one march along its axis, and the whole
  cylinder was cut at that distance: into a slanted face the beam stopped short on one side
  and went into the glass on the other. The march now keeps the surface's normal too, and
  each fibre of the beam ends where it meets the plane of that surface.
- **The beam stopped at the first mirror.** Its path in the fog (`beamPath`) now follows
  every perfect mirror it meets, a metal no rougher than `MIRROR_ROUGHNESS` (0.05), up to
  four segments, each with what the mirrors before it kept (Schlick with the metal's colour
  as F0) and the fog's toll on the beam so far. The reflected segments are then the fog
  march's, smooth from the first sample, where they were the photon pass's and grainy; and
  the photon pass lets a beam photon scatter in the fog only once it has met something
  other than a perfect mirror (`gMirrorOnly`), so nothing is counted twice. Past a glass,
  a rough metal or a matte surface the beam is no longer a beam and stays the photons'.

### The glass's haze: the beam seen inside the prism

A clear glass shows nothing of a beam crossing it: nothing scatters inside. The pictures
that make a prism legible are of a slightly hazy glass, and so is the album cover: the beam
enters, bends, and its fan opens inside the glass, colour by colour. *Glass Haze* (Material
panel; `glassHaze`, global like the dispersion, 0 by default and then free) is that: a
scattering density inside every glass. Where a path's segment runs inside a glass that the
beam's path ends in, the beam's axis is refracted at the entry for that path's own
wavelength (so the paths of different wavelengths see the beam at different angles and the
fan opens in the picture by itself), its length to the exit is marched, and the same chord
integral as in the fog is taken with the haze as the medium (`hazeInScatter`): what enters
is what the fog and the mirrors let through and the entry face did not reflect. The beam
inside is taken as a cylinder along the refracted axis, right for a flat face and a sketch
for a curved one; only the beam's direct passage is shown, not its internal reflections.
A hazy glass also dims what passes through it, `exp(-haze * length)`, on the path tracer's
side and on the photon's alike, so the spectrum on the table keeps agreeing with what the
prism lets out (PRISM_BEAM at 0.3: the spectrum 70 -> 60, the prism 13 -> 32 of mean
value). `PRISM_BEAM.frac` and `LASER_TABLE.frac` use 0.3 and 0.4.

The red patch at the prism's right foot is not a defect: it is the beam's internal
reflection off the exit face, sent to the base and back, leaving the right face near the
critical angle, where only the long wavelengths escape (the shorter ones, with their higher
index, are totally reflected). Without dispersion nothing but a few speckles gets out;
without the photon pass the path tracer cannot find the path (`out/prism_red` variants,
2026-09-12).
- **The beam did not fade.** Its irradiance now carries the fog's extinction from its
  source (`exp(-t * fogDensity)` along the axis), in the fog march, in the surface draw and
  in the photon's first flight alike, so the two strategies still tell the same beam:
  BidirProbe on `BEAM_FOG.frac` (the beam in fog onto a glass ball) has the slab's bands,
  caustic included, agree within 0.5 %, while the bands of the air above carry the cone the
  photons alone can make (+6 to +10 %), as designed. A beam through 3 units of fog at 0.35
  arrives at 35 %; the presets' beams are set brighter for it.

`presets/BEAM_FOG.frac` shows it: the beam across a foggy slab into a glass ball, seen in
the air before the ball, focused into a cone seen in the air behind it, with a caustic on
the slab. The cone is the photons' and is grainy until the samples accumulate; the incoming
beam is the march's and is smooth from the first sample.

### The acceptance scene: a beam through a prism

`presets/PRISM_BEAM.frac`, the picture the whole of this was aimed at: a white beam fixed in
the world enters the left face of a glass triangular prism (the twelfth primitive,
`TRIANGULAR_PRISM`, an equilateral section along Z) near the angle of minimum deviation, and
leaves the right face as a fan of colour, seen in the fog, that lands on the slab as a
spectrum; a faint reflection of the beam climbs from the entry face. The beam in the air is
the camera rays' march; everything past the glass, the fan and the spectrum and the
reflection, is the photon pass's, dispersion included; the two meet without a seam because
each path is one strategy's, or shared by weights that sum to one. The scene compiles in
17 s (EXTRA_BEAM, BIDIR, HAS_MATERIALS) plus 4 s for its photon program, and renders at
960x540 in 14 ms per sample with 1024 x 1024 photons.

The first version of the scene was judged unreal next to other engines (2026-09-12): the
beam dull and grey, the prism a black silhouette. Half of it was the scene, half the engine.
The scene: in a black room nothing lights a prism and it has nothing to reflect, so a faint
gradient sky (0.12) gives the glass its reflections and the slab its grey; the beam at 100
scattered from the side gave 0.2 of radiance, dull by construction, so it is 600 with bloom
for the halo a bright beam has on film; and the camera looks from the front and a little
left, as the album cover does, at the prism's side faces rather than its triangular end.
The engine: the beam's glow seen in the faces and the slab, and the beam fading along its
way, the two terms above. `docs/gallery/prism_beam.jpg` is the result.

`presets/LASER_TABLE.frac` takes the same pieces further, as an optical table seen from
above and in front: a thin laser (a beam of 1.5 cm radius, 3000 of intensity for the thin
chord a camera ray gets of it) leaves a laser module (a dark cylinder with an emissive
aperture, `SceneBuilder.laserModule`), zigzags on two front-surface mirrors on posts (thin
metal boxes of roughness zero, `mountedMirror`) into a slightly hazy prism whose fan opens
inside it and lands on the table as a spectrum; the table is an optical breadboard (a
repeated cylinder subtracted from the slab, `breadboard`), the fog thin (0.12), with bloom,
a faint sky and a little depth of field. The beam and its two reflected segments are the
fog march's, smooth from the first sample; the fan, the spectrum and the entry face's faint
reflection are the photon pass's, and sharpen with the samples. `docs/gallery/laser_table.jpg`
is its render at 256 spp.

For a beam aimed at something, the light has to stay put while the camera moves, so the
additional light can now be *Fixed in the world*: position and direction are then scene
coordinates as given, instead of the camera-relative offsets (scaled by a tenth, laterally
damped) the other modes keep by default. The flag was in the file format all along and
forced on; it is honoured now. `presets/BEAM_SLAB.frac` shows the beam across a dim slab
onto a matte block: the disc it paints, the bounce light in front of it, the shadow behind.

---

## Surface Effects (Per-Node via EffectNode)

Procedural surface effects (Erosion, Crystallization, Moss) applied per-node in the node graph via `EffectNode`. Each effect wraps a child node and modifies its distance field, making effects composable and stackable.

> **Note:** Surface effects are exclusively per-node via the node graph system. The global QualityPanel controls have been removed. Global effect uniforms are forced to disabled in `GLSLFractalizerController` when in Node Graph mode.

### Architecture

`EffectNode` is a unary graph node (like `TransformNode`) with 3 effect types:

| EffectType | Displacement | Attenuation | Type-Specific Params |
|------------|-------------|-------------|---------------------|
| **EROSION** | Weathering cracks, hydraulic channels, thermal rounding | ×0.05 | `erosionType` (0=All, 1=Hydraulic, 2=Thermal, 3=Cracks) |
| **CRYSTAL** | Voronoi-based outward crystal growth | ×0.1 | `sharpness` (0.5-5) |
| **MOSS** | Organic growth in crevices/horizontal surfaces | ×0.2 | (none) |

Common parameters: `strength` (0-1), `time` (0-20), `scale` (0.1-5).

### GLSL Implementation

`common.glsl` provides parameterized functions (`*P()`) that accept parameters instead of reading global uniforms. Global functions are wrappers. `GraphCompiler` emits calls to `*P()` functions with per-node prefixed uniforms (`e0_strength`, `e0_time`, etc.):

```glsl
// GraphCompiler emits (example: erosion, full DE):
{ float _emaxD = erosionMaxDisplacementP(e0_strength, e0_time, e0_scale);
  if (n0_d < _emaxD + 0.1) n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, e0_erosionType); }
```

- **Proximity gating**: Displacement only computed when `DE < maxDisplacement + 0.1` — skips 80-90% of ray steps
- **Full vs Light**: in the node graph, `DE()` and `DE_simple()` emit the **same** `*P()` call, with the erosion type baked in as a literal so the compiler drops the unused noise families. `DE_simple` used to call the `*LightP()` variants, a cheaper noise that describes a different surface: normals, shadows and AO on a surface the eye ray never hit. On a planet whose land relief was 0.044 the two differed by up to 0.04, which read as a golf-ball ocean and dark fringes along every coast near the terminator. The light variants remain in the legacy global-effects path of the built-in shaders, where making them full pushed the compile past the driver's limit (NVIDIA `C9999`); measure with `ShaderCompileProbe`.
- **Stacking**: Effects can wrap other effects (e.g., Erosion wrapping Crystal wrapping Mandelbulb)

### Files

| File | Role |
|------|------|
| `graph/EffectNode.java` | Unary node: EffectType enum, strength/time/scale + type-specific params |
| `shaders/common.glsl` | 9 parameterized `*P()` functions + 9 global wrappers |
| `graph/GraphCompiler.java` | Phase 3.5 (effect uniforms), `emitEffectDE()`, `collectUniformsFromNode()` |
| `ui/components/NodeGraphEditor.java` | "Wrap in Effect" menu, `buildEffectDetail()` panel, red color |

### Animation

Animatable per-node: `strength`, `time`, `scale` (+ `sharpness` for CRYSTAL). `erosionType` is structural (not animated). Color: red (`#F44336`) in timeline.

### NVIDIA GLSL Pitfall

Avoid `+=`/`*=` on swizzled components (e.g., `flowP.y *= 0.25`) — causes `C9999: Unhandled expr op assign+` fatal error. Use explicit assignments.

---

## Domain Distortion (Legacy — Superseded by Node Graph TransformNode)

> **Note:** The global domain distortion system (QualityPanel) still works for single-fractal modes but is functionally superseded by the Node Graph's `TransformNode`, which provides the same 5 transform types (Twist, Bend, Taper, Repetition, Repetition 1D) plus Standard and Mirror — applied per-node with composable stacking. See **[NODE_GRAPH.md](NODE_GRAPH.md)** (TransformNode section).

The legacy system applies space-warping globally to `pos` BEFORE DE evaluation via `applyDomainDistortion()` in `common.glsl`. Parameters in `AbstractFractalParams` (`distortionEnabled`, `distortionType`, `distortionAxis`, `distortionStrength`, `distortionFrequency`, `distortionOffset`), serialized in `EffectsConfig`. UI in QualityPanel "Domain Distortion" TitledPane.

---

## Spline Camera Paths (Catmull-Rom)

AnimationTrack supports an opt-in `splineInterpolation` mode. When enabled, `getValue()` uses Catmull-Rom interpolation (4 control points) instead of linear (2 points), producing smooth curved trajectories through keyframes.

- **Enabled by default** on `camPos` and `camQuat` tracks (camera position and rotation).
- **Formula**: Standard Catmull-Rom: `q(t) = 0.5 * ((2*P1) + (-P0+P2)*t + (2*P0-5*P1+4*P2-P3)*t^2 + (-P0+3*P1-3*P2+P3)*t^3)`
- **Easing + Spline**: Easing modulates `t` before spline evaluation. LINEAR = constant speed along curve, EASE_IN_OUT = decelerate at keyframes.
- **Boundary clamping**: P0 = P1 when no prior keyframe, P3 = P2 when no next keyframe.
- **Quaternion normalization**: float[] of length 4 are auto-normalized after spline to prevent drift.
- **Serialization**: `splineInterpolation` boolean in `TrackConfig` (default false = backward compatible).
- **UI**: Green "S" indicator on each track in TimelineWidget. Click to toggle. Dim gray when off.

---

## Viewport HUD & Feedback

- **3D Orientation Compass**: Real-time 3D axes (X, Y, Z) in the top-right corner, showing camera world orientation via quaternion math.
- **Dynamic Speed Meter**: A segmented thrust indicator on the right edge that appears when adjusting movement speed, then fades out.
- **Telemetry Overlay**: Professional technical readout (FOV, Speed) in the bottom-left for monitoring.
- **Focus Ring**: Visual circular feedback (Cyan/Red) at the click position when setting Depth of Field focal distance.

---

## Render Quality Finishes

Display-output and shading refinements (`postprocess.glsl`, `raytracer.glsl`):

- **Ordered dithering (4x4 Bayer)**: applied as the last step before the 8-bit quantization (which happens CPU-side via `floor(c*255)`). `color += bayerDither(gl_FragCoord.xy) / 255.0` turns the truncation into spatially-stable stochastic rounding — removes gradient banding without visible noise.
- **Display-referred sharpening**: the unsharp mask runs **after** tone map + gamma (via `toDisplayReferred()`), not on raw linear HDR — sharpens perceptual values instead of over-shooting highlights / crushing shadows.
- **Per-channel subsurface scattering**: `calcSSS()` returns a `vec3` with per-channel absorption `vec3(6, 8, 11.2)` (red penetrates deeper than blue) — a subtle warm tint in thin back-lit regions while preserving overall intensity (green = the previous scalar `8.0`).
- **Ambient floor**: `ambient = getAmbientLighting(normal) * baseColor * mix(0.2, 1.0, ao)` — deep crevices keep 20% ambient instead of crushing to black (multi-bounce fill approximation).
- **NEE soft-shadow consistency**: in all four path-trace blocks (modern/classic × metallic/lambertian) the jittered sun-disc sample drives **both** the shadow ray and the BRDF (`NdotL`/`H`/`NdotH`), so visibility and shading agree. Hard-shadow output is unchanged (RNG consumption preserved).

## Sample Accumulation Batching

`GLSLEngine.renderSamples(uniforms, count)` renders a whole batch in one GL-thread pass: constant pass state (FBO, blend, textures, SSBO, user uniforms) is bound **once** instead of per sample, with **no per-sample `glFinish`** — the GPU pipelines all samples and synchronises only at readback. Used by progressive preview and still-frame export. Note: export wall-clock is GPU-bound (the raymarch dominates 93–99%; readback / pixel conversion / PNG encode are negligible), so this is mainly a CPU-overhead and sync cleanup, not a large export speedup.

## Test Harnesses: Regression, Benchmark, Traveller

Two layers. The **unit suite** under `src/test/java` runs with `mvn test`, needs no GPU, and is
what CI runs on every push: graph compiler output, material SSBO layout, hybrid chains and their
library, save/reload round trips, camera and animation maths, and the node graph panel built
against a stub controller. The two checks that used to be stand-alone probes live there now
(`ConfigRoundTripTest`, `UiWiringTest`): they cover bugs that are invisible in the source — a
control that is written, wired to an action and never added to its container, and a setting
that is simply absent from the saved file. Both had already got through twice. Running the
suite on the module path also caught a third: a package Gson needs opened in `module-info`,
which only bites when the app runs as a module (the jlink image), not from an IDE classpath.

The **GPU harnesses** are eighteen headless tools in `org.fractalizer.test` (invocations in
**[CLAUDE.md](../CLAUDE.md)** Build Commands), all writing under `out/` (gitignored). Most
exist because a real bug got through a reading of the code — running one is faster and more
reliable than re-reading the path it covers:

| tool | what it catches |
|------|-----------------|
| `RenderRegression` | visual regression + benchmark; accepts a detail-scene manifest |
| `DeepZoomLab` | detail / saturation / contrast on a camera list or the scene's own camera (`scene`), A/B any parameter |
| `ColorProbe` | which shading pass loses saturation (ORBIT_TRAP → DIFFUSE → FINAL) |
| `ColorDemo` | which coloring modes put more than one hue on an object |
| `JuliaProspector` | autonomous search of Julia-constant space |
| `HybridLab` | hybrid chains, with controls that must reproduce the stand-alone formulas |
| `HybridProspector` | command line over `explore/ChainProspector`, the autonomous search of hybrid-chain space: random structures (types, gating, estimator) compiled once each, parameter draws swept as uniforms, auto-framed on the depth AOV, scored (detail × structure factor, so dust does not win), library chains marked, winners as `.frac`; `--breed` runs one generation of `ChainBreeder` from saved parents |
| `FractalNavigator` | global → fine-detail camera traveller |
| `PresetForge` | build demo presets and render a preview of each |
| `ResizeProbe` | framebuffer cost of a preview↔full switch |
| `ResponsivenessProbe` | worst tick = delay before a cancel can interrupt a render |
| `ExportProgressProbe` | how far ahead of the work an export progress bar runs |
| `ShaderCompileProbe` | how long each shader takes to compile, and which one never returns: the built-ins (7-10 s each here), then any `.frac`; `--render WxH spp outDir scene.frac` skips the built-ins and renders one scene in ~15 s, the quickest look at a preset change |
| `ExportAfterPreviewProbe` | the cheap preview must not leak into an export |
| `CausticProbe` | caustics: the photons' energy against NEE (must be 1, with and without the emission map), the cost of a pass, pictures with and without |
| `EmitterProbe` | emitters drawn directly against found by chance: the same converged image (the slab scene), the noise removed, the cost |
| `BidirProbe` | the path tracer alone against the path tracer with the photon pass weighted against it: the same converged image (panel, metal, beam scenes; the window scene for the gather), the noise, the cost |
| `ProspectSwapProbe` | the Discoveries search runs on a throw-away scene; the user's scene must come back pixel-identical |
| `ExploreProbe` | the app's Explore button, headless: scored views or parameter variations from any camera, time per view |
| `ThumbnailForge` | the Presets & Chains browser's thumbnails, every chain and preset; `install` ships them as resources |
| `GalleryRender` | every `.frac` in a directory rendered as the app would show it (README gallery, release page) |

The ones below have enough behaviour to need describing:

- **`RenderRegression`** — renders fixed scenes deterministically (bit-exact reproducible per GPU, self-diff 0). `update` writes golden images, `check` diffs against them and fails on any change beyond a small tolerance, `bench` reports median render time. Goldens are GPU-specific and gitignored (`out/test_regression/`). Accepts a navigator manifest to validate/bench on fine-**detail** views instead of default global cameras.
- **`FractalNavigator`** — autonomous global → fine-detail camera "traveller", validated across ~15 fractal types + node-graph `.frac` presets:
  - **auto-frame** the global view (backs off oversized fractals like Mandelbox);
  - **depth-guided target**: scan a 3×3 view-plane grid of aim points (depth AOV) and pick the most-detailed solid patch — skips hollow cores (Menger) and empty gaps;
  - **dive** along the view axis in shrinking steps;
  - **sweet-spot selection**: score each step (`detail × coverage-band × centering`; detail = variance of the Laplacian over depth-masked surface pixels) and keep the best, avoiding the smooth close-up washout.
  - Modes: `travel` | `fly` (eased flight global→sweet-spot → PNG sequence → mp4) | `manifest` (write per-fractal sweet-spot cameras) | `list` (explicit cameras). Output to `out/nav/` (gitignored).
- **`DeepZoomLab`** — renders one camera list under a set of parameter variants and reports surface-masked metrics: `detail` (variance of the Laplacian), `edges%` (fraction of surface pixels with |Laplacian| > 8), `lum` and `contrast` (mean / stddev of surface luminance — these catch "it goes dark" and "it goes flat"), `cov%`. Background is masked via the depth AOV so a shrinking silhouette cannot masquerade as a change in surface quality. Variants resolve by reflection against the params **and** the node-graph leaf params, e.g. `detailLOD=0,2,4` or `juliaCx=0.42`.
- **`PresetForge`** — builds demo `.frac` presets from `SceneBuilder` specs and renders a preview of each, so a candidate is judged before it is kept. Two traps it exists to avoid: the framing cameras come from `FractalNavigator` sweet spots (default global cameras do not show the detail a demo is for), and each spec must set its own **gradient** — `paletteIndex` does not feed the palette texture.

- **`JuliaProspector`** — searches Julia-constant space for fractals nobody has framed yet, described below.

### Autonomous discovery in Julia-constant space

The Julia constant is a 3D parameter and every value of it is a **different fractal**, so the space of shapes is continuous — and almost entirely uninteresting. A constant well inside the Mandelbulb gives a smooth blob; one well outside gives disconnected dust. What makes the search tractable is the Mandelbrot/Julia duality: the constants worth rendering are the ones **on the boundary of the Mandelbulb**, and the distance estimator already knows where that boundary is.

So the search is not a random sweep:

1. **CPU, no rendering.** The same DE as `fractals/mandelbulb.glsl`, reimplemented in Java, is sphere-traced inward along a Fibonacci-sphere set of directions until it lands on the surface. Every landing point is a boundary constant by construction. A small offset per direction walks the candidate just inside or just outside, which controls how connected the resulting set is — inside gives fat closed forms, outside gives filigree.
2. **GPU.** A small thumbnail per surviving candidate, classic shading, a fixed three-quarter camera.
3. **Score.** The same `FrameScore.aesthetic()` the traveller uses: fine-detail energy × a coverage band peaking near 55% × where the detail energy sits in frame.
4. **Diversity filter.** Ranking alone returns a *family portrait* — neighbouring constants give near-identical sets, and the first run had three of its top four from one direction. Candidates are taken greedily in rank order and kept only if they are at least `MIN_SEPARATION` from everything already kept, which turns the result into a catalogue of the space rather than its best neighbourhood.
5. **Output.** Ranked list, contact sheet, and the winners written as `.frac`.

Measured: 177 candidates rendered and scored in 6.2 s, 53 surviving a 0.35 separation. `JULIA_FOUND_BRANCH` and `JULIA_FOUND_CLUSTER` in `presets/` came out of it — branching, coral-like forms unlike anything that was in the preset set.

### Two gotchas for headless rendering

- **The palette must be uploaded explicitly.** `FractalConfig.applyTo(params)` restores the gradient onto the params, but the GPU texture is only written by `GLSLFractalizerController.updatePaletteTexture(params.getCustomGradient())`. Skip it and every render comes out monochrome no matter what the preset says — which is exactly what the older headless harnesses do, so their images are greyscale by accident, not by design.
- **`SceneBuilder` factory defaults are not the app defaults.** `SceneBuilder.mandelbox()` uses `scale = -1.5, minRadius = 0.5`; `MandelboxParams` defaults to `scale = 2.0, minRadius = 0.25`. A camera found by the traveller against stock parameters will frame empty space if the preset is built from the factory defaults — override the parameters to match whatever the camera was found against.
