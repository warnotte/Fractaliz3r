# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

## 1. Unified Node Graph UI
**Status:** DONE

The Node Graph has replaced the traditional slider-heavy `FractalPanel` as the primary interface. All fractal editing, boolean composition (CSG), and coordinate transforms happen in a visual tree view. Sliders are dynamically generated in a detail panel for the selected node.

## 2. Fractal Portals — Non-Euclidean Geometry
**Status:** IDEA

Holes in space that teleport rays. Walk through a Mandelbulb and emerge inside a Mandelbox. Implemented directly in the raymarcher by detecting portal surfaces (sphere/plane) and remapping ray origin + direction on hit.

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
**Status:** TODO

IFS fractals (Menger Advanced, Menger Sponge Test, etc.) use `(length(z) - offset) / dr` as their final DE formula, which defines a **sphere** as the base primitive. When iterations are low or when zooming deep, these spheres become visible as "atomic" building blocks. Allow the user to choose the base primitive shape per fractal: Sphere (`length(z)`), Box (`sdBox(z)`), Octahedron, Torus, or even a secondary fractal DE. This would give radically different aesthetics from the same IFS fold sequence. Could be a ComboBox in the fractal panel or a per-node option in the Node Graph.
