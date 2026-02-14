# DONE : Appliquer les keyframes Timeline pendant l'export audio-reactif

> **Implemente** — Les keyframes de la timeline sont maintenant appliques pendant
> l'export audio-reactif. La camera suit l'animation et les effets audio s'ajoutent par-dessus.

## Probleme (resolu)

Quand on exportait une video audio-reactive via `AudioPanel`, l'animation de camera et les
keyframes de la timeline n'etaient **pas appliques**. La camera restait fixe. Seuls les
effets audio-reactifs (morph, shake, warp, etc.) fonctionnaient.

**Note :** en preview temps reel, pas de probleme — on positionne la timeline/camera
manuellement, puis on joue l'audio par-dessus. Le probleme ne concernait que l'export offline.

## Solution implementee

Meme pattern que `ExportPanel.startAnimationExport()` :

1. **AudioPanel.java** : `timelineSupplier` + `prepareFrameCallback` (2 champs, 2 setters)
2. **Boucle d'export** : avant chaque frame, `tl.setCurrentTime(frameTime)` + `prepareFrameCallback.run()` sur le FX thread via CountDownLatch
3. **GLSLFractalizerApp.java** : wiring vers `animationManager.getTimeline()` et `applyTimelineToParams()`

## Architecture

```
Pour chaque frame i:
  1. timeline.setCurrentTime(i / fps)    -> ecrit camPos, fov, power... dans FractalParams
  2. prepareFrameCallback.run()          -> AnimationManager.applyTimelineToParams()
  3. currentOfflineData = audioFrames[i] -> prepare les donnees audio
  4. buildUniforms()                     -> lit les params (mis a jour par timeline)
                                            puis AJOUTE les deltas audio par-dessus
  5. engine.renderSample()               -> rendu GPU
```

- Timeline = valeurs de **base** (position camera, power, fov...)
- Audio = **deltas additifs** par-dessus (morph, shake, warp...)

## Points d'attention

- L'audio drive la duree totale de l'export
- Si l'audio est plus long que la timeline, les keyframes clamp au dernier keyframe
- `prepareFrameCallback` s'execute sur le FX thread (CountDownLatch, thread-safe)

## Checklist

- [x] Ajouter `timelineSupplier` + `prepareFrameCallback` a AudioPanel
- [x] Modifier la boucle d'export pour appliquer la timeline par frame
- [x] Wirer dans GLSLFractalizerApp
- [x] Tester : animation camera + audio-reactive dans le meme export
