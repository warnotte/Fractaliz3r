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

## Rejected

| # | Feature | Reason |
|---|---------|--------|
| 2 | Fractal Portals | Implemented and reverted — visually uninteresting |
| 20 | Raymarched Reflections | Already covered by path tracing |

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

### 15. Droste Effect
**Status:** IDEA

Post-process recursive spiral — the rendered image contains itself in a spiral zoom. Parameters: tiling, inner/outer radius, periodicity, strands, twist. Could be a post-process pass on the final framebuffer.

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
