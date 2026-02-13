# Audio-Reactive Fractals — Documentation technique

## Vue d'ensemble

Système de fractales audio-réactives pour Fractaliz3r. Charge un fichier audio (MP3/WAV/AAC),
analyse le spectre en temps réel pour la preview, puis effectue un **rendu offline haute qualité**
(path tracer, N samples par frame) parfaitement synchronisé avec l'audio.

---

## Architecture

```
                        ┌─────────────────────────────────┐
                        │        PREVIEW (temps réel)      │
                        │                                  │
Fichier MP3/WAV ──► JavaFX MediaPlayer                     │
                        │                                  │
                   AudioSpectrumListener (~30fps)          │
                        │                                  │
                   float[] magnitudes (128 bandes, dB)     │
                        ▼                                  │
                   AudioReactiveEngine                     │
                   ├─ 8 bandes (sub-bass → air)            │
                   ├─ Beat detection (énergie)             │
                   ├─ Onset detection (flux spectral)      │
                   └─ Smoothing EMA                        │
                        │                                  │
                        ▼                                  │
                   AudioPanel.getAudioData()               │
                        │                                  │
                        ▼                                  │
                   GLSLFractalizerController.buildUniforms()│
                   ├─ 12 uniforms GLSL (audioEnabled,      │
                   │   audioBands[8], audioBeat, etc.)      │
                   ├─ applyAudioMorphing() (Java-side:     │
                   │   power, scale, rotations, etc.)      │
                   └─► GLSLEngine.renderSample() ──► écran │
                        └──────────────────────────────────┘

                        ┌─────────────────────────────────┐
                        │     EXPORT (rendu offline)       │
                        │                                  │
Fichier MP3/WAV ──► FFmpeg decode ──► PCM float32 mono     │
                        │                                  │
                   AudioPreAnalyzer                        │
                   ├─ FFT (Cooley-Tukey radix-2)           │
                   ├─ Fenêtre de Hann                      │
                   ├─ 128 bandes en dB (même format que    │
                   │   AudioSpectrumListener)              │
                   └─► AudioData[] (1 par frame)           │
                        │                                  │
                   Pour chaque frame :                     │
                   ├─ AudioPanel.setOfflineAudioData(i)    │
                   ├─ controller.exportAnimationFrame()    │
                   │   ├─ buildUniforms() (lit AudioData)  │
                   │   ├─ N samples, path tracer, etc.     │
                   │   └─ Sauvegarde PNG                   │
                   └─ Progression UI                       │
                        │                                  │
                   FFmpegExporter.createMP4WithAudio()     │
                   └─ PNG sequence + audio ──► MP4         │
                        └──────────────────────────────────┘
```

---

## Fichiers créés (3)

### 1. `src/main/java/org/fractalizer/audio/AudioReactiveEngine.java`

**Rôle** : Analyse spectrale temps réel et offline.

- `AudioData` record immutable : `bands[8]`, `level`, `beat`, `onset`, `bass`, `mid`, `treble`
- `processSpectrum(float[] magnitudes, float[] phases)` — appelé par AudioSpectrumListener
- `getLatestData()` — renvoie le dernier snapshot
- `setSmoothing(float)` / `setSensitivity(float)` — paramètres configurables
- `reset()` — réinitialise l'historique (beat detection, smoothing)

**Bandes de fréquences** (distribution logarithmique sur 128 bins) :

| Index | Nom       | Fréquence approx. |
|-------|-----------|--------------------|
| 0     | Sub-bass  | 20-60 Hz           |
| 1     | Bass      | 60-250 Hz          |
| 2     | Low-mid   | 250-500 Hz         |
| 3     | Mid       | 500-2000 Hz        |
| 4     | Upper-mid | 2-4 kHz            |
| 5     | Presence  | 4-6 kHz            |
| 6     | Brilliance| 6-12 kHz           |
| 7     | Air       | 12-20 kHz          |

### 2. `src/main/java/org/fractalizer/audio/AudioPreAnalyzer.java`

**Rôle** : Pré-analyse offline complète d'un fichier audio.

- `analyze(File audioFile, double fps, double maxDuration, float smoothing, float sensitivity, Consumer<Double> progress)` → `AudioData[]`
- Décode l'audio en PCM float32 mono via FFmpeg (subprocess)
- Applique FFT (Cooley-Tukey radix-2, fenêtre de Hann) à chaque position de frame
- Produit 128 magnitudes en dB (même format que `AudioSpectrumListener`)
- Passe chaque frame à `AudioReactiveEngine` pour obtenir bandes/beat/onset
- Retourne un tableau indexé par numéro de frame

**Dépendances** : FFmpeg (déjà requis pour l'export vidéo).

### 3. `src/main/java/org/fractalizer/ui/panels/AudioPanel.java`

**Rôle** : Panel UI complet pour l'audio-réactivité.

**Sections UI** :
- **Fichier** : Chargement MP3/WAV/AAC (FileChooser)
- **Transport** : Play/Pause/Stop, barre de progression, temps
- **Visualiseur** : Canvas 300x80, 8 barres colorées + indicateurs beat/onset
- **Mappings réactifs** (6 EnhancedSliders) :
  - Bass → Fractal Morph (0-1, défaut 0.5)
  - Mid → Color Shift (0-1, défaut 0.5)
  - Treble → Glow (0-1, défaut 0.3)
  - Beat → FOV Pulse (0-1, défaut 0.3)
  - Onset → Emissive Pulse (0-1, défaut 0.4)
  - Level → Fog/AO (0-1, défaut 0.2)
- **Sensibilité** : Smoothing (0-0.99), Beat Sensitivity (0-1)
- **Presets** : Subtle, Medium, Intense, Psychedelic
- **Export vidéo offline** : Résolution, Samples/frame, FPS, Durée, bouton Export
  - Utilise `ExportProgressDialog` (modal) : preview du frame, 2 barres (sample + total), ETA, pause/resume, cancel
  - MP4 partiel créé automatiquement en cas d'annulation (si frames > 0)

**Modes** :
- **Preview temps réel** : MediaPlayer + AudioSpectrumListener → rendu ~30fps basse qualité
- **Export offline** : AudioPreAnalyzer → rendu frame par frame haute qualité → ExportProgressDialog avec preview

**API publique** :
```java
boolean isAudioPlaying()       // true si preview OU export offline
AudioData getAudioData()       // données courantes (temps réel ou pré-calculées)
float getReactMorph()          // valeurs des sliders de mapping
float getReactColor()
float getReactGlow()
float getReactFOV()
float getReactOnset()
float getReactFog()
void setFrameExportCallback()  // callback pour le rendu offline
void dispose()                 // nettoyage MediaPlayer + timers
```

---

## Fichiers modifiés (6)

### 4. `pom.xml`

Ajout de la dépendance `javafx-media` :
```xml
<dependency>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-media</artifactId>
    <version>${javafx.version}</version>
</dependency>
```

### 5. `src/main/java/module-info.java`

Ajout : `requires javafx.media;`

### 6. `src/main/java/org/fractalizer/engine/GLSLEngine.java`

- `setUniformValue()` : ajout du support `float[]` générique (pour `audioBands[8]`)
- `ShaderProgram.setUniform1fv(String name, float[] values)` : nouvelle méthode utilisant `glUniform1fv()`

### 7. `src/main/resources/shaders/common.glsl`

Ajout des uniforms audio (après les uniforms existants) :
```glsl
uniform int audioEnabled;
uniform float audioLevel;
uniform float audioBeat;
uniform float audioOnset;
uniform float audioBands[8];
uniform float audioReactPower;
uniform float audioReactColor;
uniform float audioReactGlow;
uniform float audioReactFOV;
uniform float audioReactOnset;
uniform float audioReactFog;
```

Ajout de la fonction `rgb2hsv()` (symétrique de `hsv2rgb()` existante).

Modification de `getCameraRay()` : FOV pulse audio-réactif.

### 8. `src/main/resources/shaders/raytracer.glsl`

Modulations audio dans la fonction `shade()` (protégées par `if (audioEnabled != 0)`) :
- **Couleur** : rotation de teinte via `rgb2hsv/hsv2rgb` modulée par les mids
- **Glow** : énergie treble → émission lumineuse additionnelle
- **Beat flash** : flash blanc subtil sur les beats
- **Emissive** : pulse d'onset → augmentation temporaire de l'émissivité
- **Fog** : niveau audio global → densité de brouillard coloré

### 9. `src/main/java/org/fractalizer/ui/GLSLFractalizerController.java`

- Champ `AudioPanel audioPanel` + `setAudioPanel()`
- Dans `buildUniforms()` : 12 uniforms audio + appel à `applyAudioMorphing()`
- `applyAudioMorphing()` : modulation Java-side des paramètres fractals par type :

| Type de fractal     | Paramètres modulés                                          |
|---------------------|-------------------------------------------------------------|
| MANDELBULB          | power ±4.0, bailout ±1.5                                    |
| MANDELBOX           | scale ±0.8, foldingLimit ±0.5                               |
| MENGER_SPONGE       | scale ±0.5, offset wobble ±0.3                              |
| KALEIDOSCOPIC_IFS   | scale ±0.6, foldAngles ±30°/25°, offset ±0.5                |
| JULIA_3D            | juliaC xyz ±0.5/0.4/0.3                                     |
| POLYHEDRAL_IFS      | scale ±0.6, offset ±0.4, shift ±0.35, rotation1/2 ±25°/14° |
| SIERPINSKI          | scale ±0.3                                                   |
| PSEUDO_KLEINIAN     | CSize ±0.2, Size ±0.3                                       |
| APOLLONIAN          | scale ±0.3, foldRadius ±0.2                                  |
| BRISTORBROT         | bailout ±1.0                                                 |

### 10. `src/main/java/org/fractalizer/GLSLFractalizerApp.java`

- Création de l'onglet "Audio" avec AudioPanel
- Câblage `controller.setAudioPanel(audioPanel)`
- Mode rendu continu quand audio en lecture (désactive l'accumulation progressive)
- `stop()` : dispose AudioPanel

### 11. `src/main/java/org/fractalizer/render/FFmpegExporter.java`

- `createMP4WithAudio()` : muxe PNG sequence + fichier audio en MP4
- Calcul du framerate réel depuis la durée audio (sync parfaite)
- Padding dimensions paires (`-vf pad=...`)
- `Locale.US` pour les valeurs numériques (évite virgule décimale française)

---

## Comment retirer la fonctionnalité

Pour supprimer complètement l'audio-réactivité sans rien casser :

### Fichiers à supprimer
```
src/main/java/org/fractalizer/audio/AudioReactiveEngine.java
src/main/java/org/fractalizer/audio/AudioPreAnalyzer.java
src/main/java/org/fractalizer/ui/panels/AudioPanel.java
docs/AUDIO_REACTIVE.md
```

### Modifications à reverter

1. **`pom.xml`** : Supprimer la dépendance `javafx-media`

2. **`module-info.java`** : Supprimer `requires javafx.media;`

3. **`GLSLEngine.java`** : Dans `setUniformValue()`, remettre le `throw` dans le `default` du switch sur `arr.length`. Supprimer `setUniform1fv()` de ShaderProgram. (Optionnel — le support float[] générique ne nuit pas.)

4. **`common.glsl`** : Supprimer les 11 lignes `uniform ... audio*;`. Supprimer `rgb2hsv()`. Retirer le bloc audio dans `getCameraRay()`.

5. **`raytracer.glsl`** : Supprimer les blocs `if (audioEnabled != 0) { ... }` dans `shade()`.

6. **`GLSLFractalizerController.java`** :
   - Supprimer le champ `audioPanel` et `setAudioPanel()`
   - Dans `buildUniforms()`, supprimer tout le bloc `if (audioPanel != null ...)` (lignes 1002-1044) et la méthode `applyAudioMorphing()`
   - Les uniforms `audioEnabled`, etc. ne seront plus envoyés → GLSL les ignore (valeur 0 par défaut)

7. **`GLSLFractalizerApp.java`** : Supprimer la création de l'onglet Audio, le champ `audioPanel`, le `setAudioPanel()`, le mode rendu continu audio, et le `dispose()`.

8. **`FFmpegExporter.java`** : Supprimer `createMP4WithAudio()`. (La méthode `createMP4()` existante n'est pas affectée.)

9. **`CLAUDE.md`** : Supprimer la référence à `docs/AUDIO_REACTIVE.md`.

### Vérification après suppression
```bash
mvn clean compile    # Doit compiler sans erreur
mvn javafx:run       # Toutes les fractales fonctionnent normalement
```

Les uniforms audio non envoyés auront la valeur 0 par défaut en GLSL, donc `audioEnabled == 0` → aucun bloc audio ne s'exécute → zéro impact sur le rendu.

---

## Formats audio supportés

| Format | Support | Notes |
|--------|---------|-------|
| MP3    | Oui     | Via JavaFX Media |
| WAV    | Oui     | Via JavaFX Media |
| AAC    | Oui     | Via JavaFX Media |
| M4A    | Oui     | Via JavaFX Media |
| FLAC   | Non     | Limitation JavaFX |
| OGG    | Non     | Limitation JavaFX |

---

## Pipeline d'export offline (détail)

### Phase 1 : Pré-analyse audio
```
FFmpeg decode → PCM float32 mono 44100Hz
    ↓
Pour chaque frame (0 à totalFrames) :
    position = frame / fps (en secondes)
    échantillons = PCM[position * 44100 ... + windowSize]
    fenêtre de Hann
    FFT radix-2 (4096 points)
    → 128 magnitudes en dB [-60, 0]
    → AudioReactiveEngine.processSpectrum()
    → AudioData (bands, beat, onset, level)
    ↓
AudioData[] (tableau complet)
```

### Phase 2 : Rendu offline frame par frame
```
ExportProgressDialog (modal, animation mode) :
├─ Preview du frame rendu
├─ Barre bleue : progression sample par sample
├─ Barre orange : progression globale (frames)
├─ ETA : elapsed — ~remaining
└─ Pause/Resume + Cancel

Pour chaque frame :
    waitWhilePaused() (support pause/resume)
    AudioPanel.setOfflineFrame(frameIndex)
    → isAudioPlaying() retourne true
    → getAudioData() retourne precomputed[frameIndex]
    ↓
    controller.exportAnimationFrame(file, width, height, samples, progress, cancel)
    → buildUniforms() lit AudioData → uniforms GLSL
    → applyAudioMorphing() modifie les paramètres fractals
    → engine.renderSamples() (N samples, path tracer possible)
    → engine.readImage() → PNG + dialog.updatePreview()
    ↓
    dialog.updateTotalProgress() + dialog.updateStatus(ETA)
```

### Phase 3 : Encodage vidéo
```
dialog.setPauseEnabled(false)
dialog.setIndeterminate("Encoding MP4...")

FFmpegExporter.createMP4WithAudio(framesDir, audioFile, output, duration, crf)
    → framerate = totalFrames / duration
    → H.265 HEVC, CRF 20, AAC 192k
    → -vf pad (dimensions paires)
    → -t duration (sync exacte)
    → +faststart (streaming)
    → dialog.updateFrameProgress() pendant l'encodage

Annulation : MP4 partiel créé si frames > 0
Succès : dialog.showSuccess() + ouverture du fichier
```
