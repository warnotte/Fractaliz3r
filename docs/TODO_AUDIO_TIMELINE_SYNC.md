# TODO : Appliquer les keyframes Timeline pendant l'export audio-reactif

## Probleme

Quand on exporte une video audio-reactive via `AudioPanel`, l'animation de camera et les
keyframes de la timeline ne sont **pas appliques**. La camera reste fixe, les parametres
animes aussi. Seuls les effets audio-reactifs (morph, shake, warp, etc.) fonctionnent.

**Note :** en preview temps reel, pas de probleme — on positionne la timeline/camera
manuellement, puis on joue l'audio par-dessus. Le probleme ne concerne que l'export offline.

**Cause racine :** `AudioPanel.startOfflineExport()` ne connait pas la `Timeline`.
Il itere les frames audio pre-analysees et appelle `frameExportCallback.exportFrame()`,
mais **ne fait jamais** `timeline.setCurrentTime(t)` ni `prepareFrameCallback.run()`.

A l'inverse, `ExportPanel.startAnimationExport()` fait correctement :
```java
Platform.runLater(() -> {
    timeline.setCurrentTime(time);
    prepareFrameCallback.run();   // -> AnimationManager.applyTimelineToParams()
});
```

---

## Pourquoi ca marchera sans conflit

L'export est **frame-locked** — tout est sequentiel et deterministe :

```
Pour chaque frame i:
  1. timeline.setCurrentTime(i / fps)    → ecrit camPos, fov, power... dans FractalParams
  2. prepareFrameCallback.run()          → AnimationManager.applyTimelineToParams()
  3. currentOfflineData = audioFrames[i] → prepare les donnees audio
  4. buildUniforms()                     → lit les params (mis a jour par timeline)
                                           puis AJOUTE les deltas audio par-dessus
  5. engine.renderSample()              → rendu GPU
```

Les deux systemes travaillent a des niveaux differents :
- Timeline = valeurs de **base** (position camera, power, fov...)
- Audio = **deltas additifs** par-dessus (morph, shake, warp...)

L'ordre est naturel car `applyTimelineToParams()` ecrit dans `FractalParams`,
puis `buildUniforms()` les lit et ajoute les modulations audio.

---

## Solution

### 1. AudioPanel.java — nouveaux champs

```java
private Supplier<Timeline> timelineSupplier;
private Runnable prepareFrameCallback;

public void setTimelineSupplier(Supplier<Timeline> supplier) {
    this.timelineSupplier = supplier;
}

public void setPrepareFrameCallback(Runnable callback) {
    this.prepareFrameCallback = callback;
}
```

### 2. AudioPanel.startOfflineExport() — dans la boucle frame, AVANT frameExportCallback

```java
// Apply timeline keyframes for this frame's time
double frameTime = frame / (double) fps;
Timeline tl = (timelineSupplier != null) ? timelineSupplier.get() : null;
if (tl != null && tl.hasKeyframes()) {
    CountDownLatch frameLatch = new CountDownLatch(1);
    Platform.runLater(() -> {
        try {
            tl.setCurrentTime(frameTime);
            if (prepareFrameCallback != null) prepareFrameCallback.run();
        } finally {
            frameLatch.countDown();
        }
    });
    frameLatch.await();
}
```

### 3. GLSLFractalizerApp.java — wiring

```java
audioPanel.setTimelineSupplier(() -> animationManager.getTimeline());
audioPanel.setPrepareFrameCallback(() -> animationManager.applyTimelineToParams());
```

---

## Points d'attention

**Duree : timeline vs audio**
- L'audio drive la duree totale de l'export
- Si l'audio est plus long que la timeline, les keyframes clamp au dernier keyframe
  (la camera garde sa derniere position)

**FPS**
- Utiliser le FPS du spinner d'export audio
- Convertir en temps pour la timeline : `frameTime = frame / exportFps`

**Thread safety**
- `prepareFrameCallback` s'execute sur le FX thread (CountDownLatch, comme ExportPanel)
- Le render se fait sur le GL thread apres
- Pas de race condition

---

## Fichiers concernes

| Fichier | Changement |
|---------|------------|
| `src/.../ui/panels/AudioPanel.java` | +2 champs, +2 setters, ~10 lignes dans boucle export |
| `src/.../ui/panels/ExportPanel.java` | Reference (pattern a copier) |
| `src/.../ui/GLSLFractalizerApp.java` | +2 lignes de wiring |

---

## Checklist

- [ ] Ajouter `timelineSupplier` + `prepareFrameCallback` a AudioPanel
- [ ] Modifier la boucle d'export pour appliquer la timeline par frame
- [ ] Wirer dans GLSLFractalizerApp
- [ ] Tester : animation camera + audio-reactive dans le meme export
