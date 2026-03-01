# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

## 1. Unified Node Graph UI
**Status:** DONE

The Node Graph has replaced the traditional slider-heavy `FractalPanel` as the primary interface. All fractal editing, boolean composition (CSG), and coordinate transforms happen in a visual tree view. Sliders are dynamically generated in a detail panel for the selected node.

## 2. Fractal Portals — Non-Euclidean Geometry
**Status:** REJECTED

Visually uninteresting. Implemented and reverted.

## 3. Erosion Simulation
**Status:** DONE (Per-Node)

Procedural erosion applied to any fractal DE via `EffectNode`. Three layers (weathering cracks, hydraulic channels, thermal rounding) with proximity gating and lightweight shadow/AO variant.

## 4. Boolean Operations Between Fractals
**Status:** DONE (Unified in Graph)

CSG union/intersection/subtraction/morph of two fractal distance fields. Integrated as `CSGNode` in the node graph.

## 5. Moss / Lichen Growth
**Status:** DONE (Per-Node)

Organic growth favoring crevices (high AO regions) and horizontal surfaces. Available as an effect type in `EffectNode`.

## 6. Multi-Fractal Nesting
**Status:** DONE (Unified in Graph)

Tiles a secondary fractal as micro-geometry on the primary fractal's surface. Integrated as a special CSG operation in `CSGNode`.

## 7. Crystallization
**Status:** DONE (Per-Node)

Outward crystalline growth via Voronoi-based noise. Available as an effect type in `EffectNode`.

## 8. Primitive SDF Nodes
**Status:** DONE

Analytic geometric primitives (Sphere, Box, Torus, etc.) can be added to the node graph and mixed with fractals via CSG.

## 9. Domain Distortions (Twist/Bend/Taper/Repetition)
**Status:** DONE (Unified in Graph)

Space-warping transforms available per-node via `TransformNode`. 7 modes: Standard, Mirror, Twist, Bend, Taper, Repetition, Repetition 1D.

## 10. Cone Tracing (Pixel-Aware Epsilon)
**Status:** DONE

Major rendering improvement that stabilizes geometry by scaling the distance estimator epsilon with the pixel's world-space footprint. Eliminates flickering and flickering at distance.

## 12. Configurable IFS Base Primitive
**Status:** DONE

IFS fractals now support 5 base primitive shapes (Sphere, Box, Octahedron, Torus, Rounded Box) via uniform-gated switch. No shader recompilation.

## 13. Fractal Portals — Non-Euclidean Geometry
**Status:** REJECTED

Spherical ray teleportation. Implemented and tested — visually uninteresting.

## 14. New Fractal Formulas (from GMT-fractals)
**Status:** IDEA

Exotic fractal types to expand the formula library:
- **Mandelorus** — Mandelbrot on a torus topology, solenoid structures with twist/vertical scaling
- **MandelBolic** — Hyperbolic 3-space extension (Poincaré-Ahlfors), perfect spherical bulbs
- **Appell** — Appell Polynomials / Clifford Analysis, skeleton-like interference patterns
- **Borromean** — 3 interlocking complex planes in cyclic feedback, tetrahedral symmetries
- **JuliaMorph** — 2D Julia sets stacked along Z-axis with twist/bend deformations
- **Tetrabrot** — 4D pseudo-quaternion set, diamond-like geometric symmetries
- **Buffalo** — Mandelbulb with absolute-value folds, furry plate-like textures
- **MarbleMarcher** — Dynamic Menger IFS with rotation/shifting from the game
- **BoxBulb** — Mandelbox + Mandelbulb hybrid
- **Phoenix** — Phoenix Julia set in 3D
- **MakinBrot, AmazingSurf, Dodecahedron, MandelMap, Modular...**

## 15. Droste Effect
**Status:** IDEA

Recursive self-referential spiral — the rendered image contains itself in a spiral zoom. Parameters: tiling, inner/outer radius, periodicity, strands, twist.

## 16. Procedural Texturing
**Status:** IDEA

UV mapping for fractals with multiple modes: Orbit Trap, Iterations, Radial, Z-Depth, Angle, Normal, Decomposition, Potential Log-Log, Green's Flow. Multiple layers with blend modes.

## 17. Sonification
**Status:** IDEA

Generate sound from fractal geometry. Logarithmic spiral probe sampling, maps distance field to frequency/harmonics.

## 18. Parameter Modulation (LFOs)
**Status:** IDEA

Internal LFOs to modulate any parameter in real-time. Sine/triangle/square/noise waveforms with rate, gain, offset. Independent from audio-reactive system.

## 19. Drawing / Measurement Tools
**Status:** IDEA

2D overlay for measuring distances, circles, rectangles on the fractal surface. Useful for understanding scale and structure.

## 20. Reflections (Raymarched)
**Status:** IDEA

GPU raytraced reflections with 1-3 bounces, roughness threshold cutoff, blend between raymarched and environment map. Separate from path tracing.
