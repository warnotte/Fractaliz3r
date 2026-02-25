# Plan : Node Graph comme Unique UI Fractale

## Contexte

Actuellement FractalPanel a un ComboBox type + 10 VBoxes de sliders hand-crafted + un NodeGraphEditor caché. C'est redondant puisque les 10 types standards passent déjà par NodeGraphParams sous le capot. L'objectif : **le NodeGraphEditor devient l'unique UI fractale**. La sélection de type se fait DANS le graphe (propriété du FractalNode). Les sliders FractalPanel sont supprimés.

**Résultat** : FractalPanel contient le NodeGraphEditor (toujours visible) + Navigation. Plus de ComboBox type, plus de VBox par fractale, plus de Boolean Ops legacy, plus de Morph A↔B. Custom Shader devient un type de noeud (pas un mode séparé). Morph devient le 4e opérateur CSG.

---

## Fichiers modifiés

| Fichier | Changement |
|---------|-----------|
| `NodeGraphEditor.java` | Enrichir buildFractalDetail (enums, presets, groupes), ajouter dice/mutate, support Custom Shader node |
| `FractalPanel.java` | Supprimer ~900 lignes (10 VBox, 60+ sliders, combo handler). Nouveau layout minimal |
| `GLSLFractalizerApp.java` | Simplifier wiring (plus de condition NODE_GRAPH) |
| `CSGNode.java` | Ajouter Op.MORPH |
| `GraphCompiler.java` | Gérer MORPH dans le code gen CSG (`mix(d1, d2, blend)`) |
| `FractalNode.java` | Accepter CUSTOM_SHADER comme type valide |

**Fichiers non modifiés** : GLSLFractalizerController, FractalConfig, AnimationManager, les shaders — le pipeline rendering est déjà correct.

---

## Étape 1 : Enrichir NodeGraphEditor.buildFractalDetail()

### 1a. Support ComboBox pour enums non-@Animatable

Le Polyhedral IFS a un `polyType` (enum `PolyType`) qui n'est pas `@Animatable`. Ajouter un traitement spécial après les sliders auto-découverts :

```java
// Dans buildFractalDetail(), après la boucle @Animatable :
if (params instanceof PolyhedralIFSParams poly) {
    ComboBox<PolyhedralIFSParams.PolyType> polyCombo = new ComboBox<>();
    polyCombo.getItems().addAll(PolyhedralIFSParams.PolyType.values());
    polyCombo.setValue(poly.getPolyType());
    polyCombo.setMaxWidth(Double.MAX_VALUE);
    polyCombo.setOnAction(e -> {
        poly.setPolyType(polyCombo.getValue());
        onParameterChange();
    });
    detailPanel.getChildren().addAll(new Separator(), new Label("Symmetry:"), polyCombo);
}
```

### 1b. Presets

Ajouter des boutons presets pour les types qui en ont :

- **PolyhedralIFS** : 8 presets (Octa Classic, Twisted Octa, Sierpinski Tetra, Icosa Crystal, Dodeca Flower, Alien, Deep Coral, Cathedral)
- **QuaternionJulia4D** : 5 presets (Classic, 4D Flower, Wormhole, Crystal, Hypersphere)
- **KaleidoscopicIFS** : hint label "Classic Sierpinski: Scale=2, Offset=3"

Les presets appliquent les valeurs sur le FractalParams puis appellent `refreshDetailPanel()` + `onParameterChange()`.

Implémentation : méthode `buildFractalExtras(FractalNode fn)` appelée à la fin de `buildFractalDetail()`.

### 1c. Labels d'info / groupes visuels

Ajouter des séparateurs et labels de groupe dans buildFractalDetail pour les types qui ont beaucoup de params (Polyhedral: Offset, Shift, Rot1, Rot2 — comme dans l'ancien FractalPanel).

### 1d. Dice randomizer + mutation dans NodeGraphEditor

Ajouter les boutons dice (🎲), mutate (🧬), ◀, ▶ dans le toolbar du NodeGraphEditor ou au-dessus du detail panel.

Le mécanisme existant (récursive tree traversal de `randomizeAllIn`, `mutateAllIn`, `captureSnapshot`, `restoreSnapshot`) est transposable tel quel — il opère sur un container `Parent` avec des `EnhancedSlider` et `ComboBox`. On l'applique sur `detailPanel`.

Ajouter aussi le slider de mutation strength (5%-100%).

**Note** : la capture/restore par IdentityHashMap ne survivra pas si le detail panel est reconstruit (clic sur autre noeud). C'est acceptable — l'historique dice est par noeud sélectionné. On clear l'historique quand on change de noeud.

### 1e. Custom Shader comme type de noeud

CUSTOM_SHADER devient un type valide pour FractalNode. Quand un FractalNode est de type CUSTOM_SHADER :

- `FractalNode.createDefaultParams()` retourne un `CustomShaderParams` (avec le template par défaut)
- `buildFractalDetail()` détecte `params instanceof CustomShaderParams` et affiche un CustomShaderEditor intégré au detail panel
- Le GraphCompiler gère CUSTOM_SHADER : au lieu de charger un fichier `.glsl` depuis resources, il utilise le `shaderSource` du CustomShaderParams (préprocessé avec le prefix nX_)
- Retirer CUSTOM_SHADER de la liste EXCLUDED_TYPES dans NodeGraphEditor

Cela permet de mixer un shader custom avec d'autres fractales via CSG (ex: custom DE + Mandelbulb en Union).

**FractalPanel** : plus de `customShaderControls` séparé. Tout passe par le node graph.

### 1f. MORPH comme 4e opérateur CSG

Ajouter `MORPH` à `CSGNode.Op` :

```java
public enum Op { UNION, INTERSECT, SUBTRACT, MORPH }
```

Dans `GraphCompiler`, la génération CSG pour MORPH :

```glsl
// mix(d1, d2, blend) — blend de 0 (= left) à 1 (= right)
float cX_d = mix(leftDE, rightDE, cX_blend);
```

Le blend slider dans `buildCSGDetail()` fonctionne déjà (0-2). Pour MORPH, les valeurs utiles sont 0-1. On pourrait changer le range dynamiquement quand MORPH est sélectionné, mais 0-2 est ok aussi.

**Coloring** : pour MORPH, les facteurs de coloration (getFactors) devraient aussi être un mix. Le GraphCompiler devra blender les facteurs des deux enfants proportionnellement au blend.

---

## Étape 2 : Restructurer FractalPanel

### 2a. Supprimer les 10 VBox contrôles fractal-specific

Supprimer (~900 lignes) :
- 60+ champs slider (`mbPowerSlider`, `mbxScaleSlider`, etc.)
- 10 champs VBox (`mandelbulbControls`, etc.)
- 10 méthodes `create*Controls()` (createMandelbulbControls, createPolyhedralControls, etc.)
- `applyPolyPreset()`, `applyQJ4DPreset()` — déplacés dans NodeGraphEditor
- Le `fractalParamsBox` et ses enfants
- Le bloc show/hide dans `refreshFromParams()` (130 lignes de if/else if)
- Le bloc show/hide dans le combo handler (50 lignes de switch)
- Le champ `fractalParams` (plus nécessaire)

### 2b. Supprimer le ComboBox type entièrement

Plus besoin de combo ni de toggle — CUSTOM_SHADER est un type de noeud dans le graphe. Le ComboBox type et toute la logique associée sont supprimés.

### 2c. Supprimer Boolean Ops pane

Tout le TitledPane "Boolean Operations" est supprimé — ~170 lignes. CSG nodes dans le graphe le remplacent.

### 2d. Supprimer Morph A↔B

Supprimer le TitledPane "Morph A ↔ B" et les champs `morphA, morphB, morphTypeA, morphTypeB, morphSlider, morphLabel`.

**Remplacé par** : CSGNode.Op.MORPH (étape 1f). L'utilisateur crée un CSG node avec l'opérateur MORPH et glisse le blend slider de 0 à 1 pour morphér entre deux fractales.

### 2e. Nouveau layout FractalPanel

```
FractalPanel (ScrollPane)
└── VBox panel
    ├── nodeGraphEditor (toujours visible, taille flexible)
    └── TitledPane "Navigation"
        ├── speedSlider
        ├── positionLabel
        ├── navLabel + helpLabel
        └── resetBtn
```

Le NodeGraphEditor prend toute la place verticale disponible (VBox.setVgrow(ALWAYS)).
Plus de `customShaderControls` séparé — c'est un type de noeud dans le graphe.

### 2f. refreshFromParams() simplifié

```java
public void refreshFromParams(boolean suppress) {
    suppressRender = suppress;
    try {
        if (params instanceof NodeGraphParams ngp) {
            if (suppressRender && nodeGraphEditor.isLoaded()) {
                nodeGraphEditor.refreshSliders();
            } else {
                nodeGraphEditor.loadParams(ngp);
            }
        }
        speedSlider.setValue(camera.getMoveSpeed());
        updatePositionLabel();
    } finally {
        suppressRender = false;
    }
}
```

Tout est NodeGraphParams désormais (y compris Custom Shader via FractalNode).

### 2g. setParams() / onFractalTypeChanged callback

Le callback `onFractalTypeChanged` reste — il est utilisé par AnimationManager. Mais il est déclenché par le NodeGraphEditor quand on change le type d'un FractalNode (structural change), plus par un ComboBox FractalPanel.

---

## Étape 3 : Adapter GLSLFractalizerApp.java

Le wiring dans l'app qui conditionne le callback graph structure changed à NODE_GRAPH doit être rendu inconditionnel (déjà identifié dans le plan précédent). Vérifier que c'est le cas.

---

## Ordre d'implémentation

1. **CSGNode.Op.MORPH + GraphCompiler** — ajouter le 4e opérateur (petit, isolé)
2. **FractalNode + Custom Shader** — accepter CUSTOM_SHADER, adapter GraphCompiler
3. **NodeGraphEditor.buildFractalDetail()** — enum support, presets, hints, Custom Shader editor
4. **NodeGraphEditor dice/mutate** — boutons + logique dans le toolbar/detail
5. **FractalPanel restructuration** — supprimer old controls, nouveau layout
6. **GLSLFractalizerApp.java** — simplifier wiring
7. **Build + test**

## Vérification

1. `mvn clean compile` — succès
2. `mvn javafx:run` → Mandelbulb s'affiche par défaut dans le node graph (graphe à 1 noeud)
3. Click sur le FractalNode → detail panel montre les sliders (power, iterations, bailout)
4. Modifier power → le rendu change en temps réel
5. Changer le type du FractalNode (combo dans le detail panel) → recompile + nouveau rendu
6. Polyhedral IFS : le combo PolyType est présent, les 8 presets fonctionnent
7. Quaternion Julia 4D : les 5 presets fonctionnent
8. Dice : randomize les sliders du noeud sélectionné, historique fonctionne
9. Mutation : nudge les sliders autour des valeurs courantes
10. CSG MORPH : créer CSG node avec op MORPH, blend 0→1 morphe entre deux fractales
11. Custom Shader node : changer un FractalNode en Custom Shader, l'éditeur s'affiche, compile
12. Custom Shader + CSG : mixer un shader custom avec une fractale via Union
13. Save/Load : fichiers .frac se sauvent et se rechargent correctement
14. Animation : tracks découvertes correctement depuis les noeuds du graphe
15. Navigation pane : speed slider + position label toujours fonctionnels
