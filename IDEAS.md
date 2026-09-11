# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

---

## Completed

| # | Feature | Notes |
|---|---------|-------|
| 1 | Unified Node Graph UI | Primary interface, replaced FractalPanel sliders |
| 3 | Erosion Simulation | Per-node via EffectNode (cracks, hydraulic, thermal) |
| 4 | Boolean Operations (CSG) | Union/Intersect/Subtract/Morph in CSGNode |
| 5 | Moss / Lichen Growth | Per-node via EffectNode |
| 6 | Multi-Fractal Nesting | CSG operation in CSGNode |
| 7 | Crystallization | Per-node via EffectNode (Voronoi) |
| 8 | Primitive SDF Nodes | 11 primitives as PrimitiveNode in graph |
| 9 | Domain Distortions | 7 modes in TransformNode |
| 10 | Cone Tracing | Pixel-aware adaptive epsilon |
| 11 | Per-Node Materials | MaterialNode with SSBO, per-node PBR overrides (color, roughness, metallic, IOR, emission) |
| 12 | IFS Base Primitive | 5 shapes (Sphere, Box, Octahedron, Torus, Rounded Box) |
| 23 | Spectral dispersion through glass | One wavelength per path, Cauchy index, CIE tint; off by default and bit-exact when off. Presets PRISM_GEM and PRISM_LENS. See docs/RENDERING.md § Spectral dispersion |
| 25 | Caustics from the sun | The scene compiled once more as a photon tracer, one photon pass per sample splatted into the accumulation, emission importance-sampled from a map of where the glass is; off by default and bit-exact when off. Presets CAUSTIC_BALL and CAUSTIC_LENS. See docs/RENDERING.md § Caustics |

## Rejected

| # | Feature | Reason |
|---|---------|--------|
| 2 | Fractal Portals | Implemented and reverted — visually uninteresting |
| 20 | Raymarched Reflections | Already covered by path tracing |
| 15 | Droste Effect | Implemented as a post-process (fb9e3f9, 2026-09-11), judged and reverted the same day: a picture-in-picture spiral is a gimmick that adds nothing to a fractal renderer. Do not re-suggest |

---

## Open Ideas

### 14. New Fractal Formulas
**Status:** MOSTLY DONE — Mandelorus done; the rest of the list that could be written as a
sequence of maps now lives in the hybrid step library (28 step types, HybridPresets), reachable
without writing a formula. Amazing Surf, Benesi (T1 fold and mag transform), Kaliset,
Pseudo-Kleinian, Riemann Sphere, octahedral and icosahedral KIFS, the cosine bulb, per-iteration
rotation, twist, plane and kaleidoscope folds, and per-step iteration gating came in the same
batch. Still needing real new maths: Phoenix needs the previous z (the chain state is one
vector), MandelBolic / Appell / Borromean new step types.

Exotic fractal types to expand the formula library:
- ~~Mandelorus~~ — DONE
- ~~**Buffalo**~~ — DONE as a hybrid chain (Bulb -> Abs Fold -> Add Seed), no shader needed
- ~~**BoxBulb**~~ — DONE as a hybrid chain (Bulb -> Box Fold -> Add Seed); also ships as presets/HYBRID_BOXBULB.frac
- ~~**Tetrabrot**~~ — its 3D section is the `QUAT_SQUARE` step (Quaternion Julia chain); the
  bicomplex 4th component is dropped, so the full 4D set is not it
- **Phoenix** — Phoenix Julia set in 3D
- ~~**MarbleMarcher**~~ — DONE as a hybrid chain (Menger Fold -> Rotate), no shader needed
- **MandelBolic** — Hyperbolic 3-space extension (Poincaré-Ahlfors)
- **Appell** — Appell Polynomials / Clifford Analysis, skeleton-like patterns
- **Borromean** — 3 interlocking complex planes, tetrahedral symmetries
- ~~**JuliaMorph**~~ — DONE as a hybrid chain (Complex Power -> Twist -> Add Seed, Julia mode)

### 16. Procedural Texturing
**Status:** IDEA

UV mapping for fractals with multiple modes: Orbit Trap, Iterations, Radial, Z-Depth, Angle, Normal, Decomposition, Potential Log-Log, Green's Flow. Multiple layers with blend modes. Big scope — would replace the current coloring system.

### 17. Sonification
**Status:** IDEA

Generate sound from fractal geometry. Logarithmic spiral probe sampling, maps distance field to frequency/harmonics. Niche — inverse of the existing audio-reactive system.

### 18. Parameter Modulation (LFOs)
**Status:** IDEA

Internal LFOs to modulate any parameter in real-time. Sine/triangle/square/noise waveforms with rate, gain, offset. Independent from audio-reactive system. Relatively simple — similar to existing animation track system but free-running.

### 19. Drawing / Measurement Tools
**Status:** IDEA

2D overlay for measuring distances, circles, rectangles on the fractal surface. Useful for understanding scale and structure. Niche.

### 21. Camera Collision (Surface Avoidance)
**Status:** IDEA

Prevent the camera from entering the fractal surface during navigation. Evaluate DE at the camera position each frame (GPU readback or CPU-side approximation) and clamp movement so the camera stays outside the surface. Could work as a toggle: "Collision Mode" checkbox. Useful for cinematic fly-throughs where accidentally going inside the fractal breaks the view.

Approaches:
- **GPU readback**: Render a 1-pixel DE query at camera pos, read back with `glReadPixels` — accurate but 1-frame latency
- **CPU-side DE**: Re-implement a simplified DE on CPU — fast but must stay in sync with GPU shader
- **Raycast ahead**: March a short ray in the movement direction, stop before hitting surface — natural "sliding" along the surface

### 22. Discoveries Tab Follow-ups
**Status:** IDEA (parked 2026-09-11; the tab itself, Prospect and Breed, shipped in 3.2.0)

What the Discoveries tab of the Presets & Chains browser still lacks, in the order to do them:

1. **Save from the tab.** A discovery only survives by being loaded, then saved as the scene.
   A button on the tile that writes the `.frac` straight into a discoveries folder, with its
   thumbnail.
2. **Name discoveries.** They are FOUND_01, CHILD_03. A readable name derived from the chain
   (its step types), editable by the user, written into the `.frac` and shown on the tile.
3. **Lineage history.** Keep the successive generations of a breeding run, know which parents a
   child came from, go back to an earlier generation. Turns Breed from a draw into a selection
   tool.
4. **IFS recipe.** The recipe without a seed rarely renders anything visible (the per-recipe
   count says so on every run). Fix in the grammar, not in the score.
5. **Novelty.** The rule "canonical signature = fold and power steps only" lets variants of
   known families through. Refine once the rest is there.

Do 1 and 2 together: small, and without them discoveries are lost.

### 24. One Material Model (global material = the default material)
**Status:** IDEA (parked 2026-09-11, raised by the user; a large reflection, not to be started on the side)

Today a scene has two material vocabularies: the global material of the Material panel (the
surfaces with no node, `matId` −1) and the `MaterialNode` of the graph (overrides per subtree
through the SSBO, a sentinel for "inherit"). The model is sound as long as both sides carry the
same properties, and they drift: IOR is per node, spectral dispersion (added the same day) is
global only, so its checkbox lives in the global panel while the glass of the prism presets is
a node. The user looked for it in the node.

Rule until then: a new material property exists on both sides (global + node with an inherit
sentinel) or is clearly global by nature.

Direction when it is opened: the global material becomes literally a *default material*, the
same record and the same editor component as a `MaterialNode`, one list of fields for the UI,
the `.frac` and the SSBO (the default material could be slot 0). Then a property cannot exist on
one side only, by construction.

### 26. Caustics Follow-ups, and one light transport for every light
**Status:** IN PROGRESS (the photon pass shipped after 3.2.2; the light list and emitter
sampling followed, see docs/RENDERING.md § Emitters sampled directly)

The direction decided on 2026-09-11: caustics must work from any light, the sun, a beam, the
extra light, an emissive primitive, and the split rules (a photon lands only after glass, metal
below roughness 0.1, direct light by the path tracer and light after glass by the photons) are
to be replaced by MIS between the path tracer and the light tracer, both drawing from one
light list, media included; then merging (VCM) for caustics seen through glass. The
acceptance scene is a white beam through a prism fanning into a spectrum. Everything
bidirectional compiles in under its own define: the fast path stays byte-identical. Steps:

1. the light list and emitter sampling with MIS in the path tracer (done);
2. a beam light (a directional light confined to a cylinder), and the additional light
   fixable in the world (done);
3. the photon pass emitting from every light of the list, with MIS weights in both tracers
   in place of the split rules (done; BidirProbe is the proof);
4. the medium: the beam scattered along camera rays, photons scattering in the fog past a
   glass (done; see docs/RENDERING.md § The medium);
5. a triangular prism primitive and the beam-through-prism preset (done: PRISM_BEAM, the
   acceptance scene, see docs/RENDERING.md § The acceptance scene).

Done since: the gather for a caustic seen through glass (a hash grid over the pass's photon
vertices, gathered at the first matte vertex reached through glass; BidirProbe's window scene),
and photons from the point and spot lights. Still open: the emitters' light in the fog before a
glass, photons from the sky (not needed), and the emission square's extent and centre by hand.

The follow-ups the photon pass alone leaves open (kept for the record):

1. **Seen through glass.** A photon lands only where the camera sees it directly, so the
   focus of a lens right behind it is hidden. The fix is a gather: keep the landed photons in
   a spatial structure and let the path tracer's diffuse vertices reached through glass read
   them (a photon map, with its radius and its bias). Large.
2. **The extra light.** Photons leave the sun only. The spot or area light needs its own
   emission (a cone, a disk) and the same bookkeeping.
3. **The metal threshold.** Below roughness 0.1 the photons reflect, above it the path tracer's
   NEE stands; between 0.1 and 0.3 that NEE is partly clamped, so smooth-but-not-mirror metal is
   under-lit either way. A MIS-style split by roughness instead of a threshold.
4. **The emission square.** Extent and centre are set by hand. The bounds of the specular
   nodes of the graph would set them.

