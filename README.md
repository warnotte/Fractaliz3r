# 🌌 Fractaliz3r GLSL

**Fractaliz3r** is a high-performance, cinematic 3D fractal explorer and renderer. It leverages the power of GLSL shaders and progressive raymarching to create breathtaking visualizations of complex mathematical worlds.

![Version](https://img.shields.io/badge/version-1.0-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)
![License](https://img.shields.io/badge/license-Non--Commercial-red)

---

## ✨ Key Features

### 🎬 Cinematic Rendering Pipeline
Fractaliz3r isn't just a math tool; it's a digital camera for the abstract.
*   **Volumetric Fog & God Rays**: Realistic light scattering with shadow-aware atmospheric effects.
*   **Optics & Lens Effects**: JJ Abrams-style anamorphic flares, lens dirt, and procedural starbursts.
*   **Depth of Field (DoF)**: Physically-based bokeh with a "Click-to-Focus" interaction.
*   **Color Grading**: Built-in LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant atmosphere.

### 🥽 VR & 360° Exploration
Step inside the fractal with full panoramic support.
*   **Equirectangular Projection**: Render full 360° maps compatible with VR headsets.
*   **Automated Metadata**: Instant injection of Photo Sphere (XMP) for images.
*   **VR Video Support**: Automated injection of 360° metadata for YouTube/Facebook via FFmpeg/ExifTool.
*   **High-Res Export**: Standard VR presets (2K/4K) in JPEG and H.265 (HEVC) formats.

### 🎨 Advanced Visual Control
*   **Visual Gradient Editor**: A professional GPU-based coloring system with draggable stops and presets.
*   **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax.
*   **Physical Materials**: Support for Lambertian, Metallic, and Glass materials with real-time path tracing.
*   **Dice Randomizer**: One-click parameter randomization with undo/redo history and per-slider lock protection.
*   **Morph Crossfade**: Smooth interpolation between two parameter snapshots for discovering intermediate fractal shapes.

---

## 🚀 Getting Started

### Prerequisites
*   **Java 21** or higher.
*   **Maven** for building.
*   **FFmpeg**: Must be **installed on your system** and available in your **PATH** for MP4 video export.
*   **ExifTool**: Must be **installed on your system** and available in your **PATH** for automated 360° video metadata injection.

### Installation & Run
```bash
# Clone the repository
git clone https://github.com/warnotte/Fractaliz3r.git
cd Fractaliz3r

# Compile and run
mvn compile
mvn javafx:run
```

---

## 🕹️ Controls

| Input | Action |
| :--- | :--- |
| **Arrow Keys** | Move Forward / Backward / Strafe |
| **Mouse Drag** | Look Around |
| **Q / E** | Roll Camera |
| **Page Up / Down** | Move Up / Down |
| **Space** | Render Full Quality (Refinement) |
| **R** | Reset Camera |
| **Scroll Wheel** | Adjust Movement Speed |
| **Middle Click** | Focus on Surface (for DoF) |

---

## 🛠️ Technical Stack
*   **Core**: Java 21 (JPMS Modules)
*   **Graphics**: OpenGL 4.3+ via [LWJGL 3](https://www.lwjgl.org/)
*   **UI**: JavaFX with a custom **Modern Dark Theme**
*   **Serialization**: Gson for `.frac` configuration files
*   **Video**: FFmpeg & ExifTool integration for high-quality VR export

---

## 📜 License
This project is provided for **non-commercial use only**. You are free to use, study, and modify the code, but commercial exploitation, redistribution for profit, or inclusion in commercial products is strictly prohibited.

---
*Created with ❤️ for mathematical beauty.*