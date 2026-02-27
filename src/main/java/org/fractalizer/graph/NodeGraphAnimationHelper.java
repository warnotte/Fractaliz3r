package org.fractalizer.graph;

import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.AnimatableParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bridge between the node graph and the animation system.
 * Traverses the graph tree and produces {@link AnimatableParameter} descriptors
 * for each node, using stable node names as track prefixes.
 */
public final class NodeGraphAnimationHelper {

    private NodeGraphAnimationHelper() {}

    /**
     * Info about one node's animatable parameters.
     */
    public record NodeAnimInfo(
        GraphNode node,
        String nodeName,
        Color groupColor,
        List<AnimatableParameter> parameters
    ) {}

    /**
     * DFS the graph tree and discover all animatable parameters.
     * Each node produces a group with its stable name.
     */
    public static List<NodeAnimInfo> discoverAnimatableParameters(GraphNode root) {
        List<NodeAnimInfo> result = new ArrayList<>();
        discoverRecursive(root, result);
        return result;
    }

    private static void discoverRecursive(GraphNode node, List<NodeAnimInfo> result) {
        if (node == null) return;

        String nodeName = node.getName();
        if (nodeName == null) nodeName = "Unnamed";

        List<AnimatableParameter> params = new ArrayList<>();
        Color color;

        if (node instanceof PrimitiveNode pn) {
            color = Color.web("#8BC34A");
            params.add(new AnimatableParameter(
                "sizeX", PrimitiveNode.getSizeXLabel(pn.getPrimitiveType()), Float.class,
                () -> pn.getSizeX(),
                v -> pn.setSizeX(((Number) v).floatValue())
            ));
            if (PrimitiveNode.usesSizeY(pn.getPrimitiveType())) {
                params.add(new AnimatableParameter(
                    "sizeY", PrimitiveNode.getSizeYLabel(pn.getPrimitiveType()), Float.class,
                    () -> pn.getSizeY(),
                    v -> pn.setSizeY(((Number) v).floatValue())
                ));
            }
            if (PrimitiveNode.usesSizeZ(pn.getPrimitiveType())) {
                params.add(new AnimatableParameter(
                    "sizeZ", PrimitiveNode.getSizeZLabel(pn.getPrimitiveType()), Float.class,
                    () -> pn.getSizeZ(),
                    v -> pn.setSizeZ(((Number) v).floatValue())
                ));
            }
            params.add(new AnimatableParameter(
                "rounding", "Rounding", Float.class,
                () -> pn.getRounding(),
                v -> pn.setRounding(((Number) v).floatValue())
            ));
            params.add(new AnimatableParameter(
                "shell", "Shell", Float.class,
                () -> pn.getShell(),
                v -> pn.setShell(((Number) v).floatValue())
            ));
        } else if (node instanceof FractalNode fn) {
            color = Color.web("#2196F3");
            AbstractFractalParams fp = fn.getFractalParams();
            if (fp != null) {
                // Reuse reflection-based discovery from @Animatable annotations
                params.addAll(fp.getAnimatableParameters());
            }
        } else if (node instanceof TransformNode tn) {
            color = getTransformColor(tn);
            params.addAll(discoverTransformParams(tn));
        } else if (node instanceof EffectNode en) {
            color = Color.web("#F44336");
            params.addAll(discoverEffectParams(en));
        } else if (node instanceof CSGNode csn) {
            color = Color.web("#FF9800");
            params.add(new AnimatableParameter(
                "blend", "Blend", Float.class,
                () -> csn.getBlend(),
                v -> csn.setBlend(((Number) v).floatValue())
            ));
        } else {
            return;
        }

        if (!params.isEmpty()) {
            result.add(new NodeAnimInfo(node, nodeName, color, params));
        }

        for (GraphNode child : node.getChildren()) {
            discoverRecursive(child, result);
        }
    }

    private static Color getTransformColor(TransformNode tn) {
        return switch (tn.getMode()) {
            case MIRROR -> Color.web("#9C27B0");
            case TWIST -> Color.web("#E91E63");
            case BEND -> Color.web("#FF5722");
            case TAPER -> Color.web("#795548");
            case REPETITION -> Color.web("#00BCD4");
            case REPETITION_1D -> Color.web("#009688");
            default -> Color.web("#4CAF50");
        };
    }

    private static List<AnimatableParameter> discoverTransformParams(TransformNode tn) {
        List<AnimatableParameter> params = new ArrayList<>();

        switch (tn.getMode()) {
            case STANDARD -> {
                params.add(floatArrayParam("offsetX", "Offset X", tn::getOffset, 0));
                params.add(floatArrayParam("offsetY", "Offset Y", tn::getOffset, 1));
                params.add(floatArrayParam("offsetZ", "Offset Z", tn::getOffset, 2));
                params.add(floatArrayParam("rotationX", "Rotation X", tn::getRotation, 0));
                params.add(floatArrayParam("rotationY", "Rotation Y", tn::getRotation, 1));
                params.add(floatArrayParam("rotationZ", "Rotation Z", tn::getRotation, 2));
                params.add(new AnimatableParameter(
                    "scale", "Scale", Float.class,
                    () -> tn.getScale(),
                    v -> tn.setScale(((Number) v).floatValue())
                ));
            }
            case MIRROR -> {
                int axis = tn.getAxis();
                params.add(floatArrayParam("mirrorOffset", "Mirror Offset", tn::getOffset, axis));
            }
            case TWIST, BEND, TAPER -> {
                String prefix = tn.getMode().name().toLowerCase();
                params.add(new AnimatableParameter(
                    prefix + "Strength", "Strength", Float.class,
                    () -> tn.getScale(),
                    v -> tn.setScale(((Number) v).floatValue())
                ));
                params.add(new AnimatableParameter(
                    prefix + "Frequency", "Frequency", Float.class,
                    () -> tn.getFrequency(),
                    v -> tn.setFrequency(((Number) v).floatValue())
                ));
                params.add(floatArrayParam(prefix + "Offset", "Offset", tn::getOffset, 0));
            }
            case REPETITION_1D -> {
                params.add(floatArrayParam("period", "Period", tn::getOffset, tn.getAxis()));
            }
            case REPETITION -> {
                params.add(floatArrayParam("periodX", "Period X", tn::getOffset, 0));
                params.add(floatArrayParam("periodY", "Period Y", tn::getOffset, 1));
                params.add(floatArrayParam("periodZ", "Period Z", tn::getOffset, 2));
            }
        }

        return params;
    }

    private static List<AnimatableParameter> discoverEffectParams(EffectNode en) {
        List<AnimatableParameter> params = new ArrayList<>();
        params.add(new AnimatableParameter(
            "strength", "Strength", Float.class,
            () -> en.getStrength(),
            v -> en.setStrength(((Number) v).floatValue())
        ));
        params.add(new AnimatableParameter(
            "time", "Time", Float.class,
            () -> en.getTime(),
            v -> en.setTime(((Number) v).floatValue())
        ));
        params.add(new AnimatableParameter(
            "scale", "Scale", Float.class,
            () -> en.getScale(),
            v -> en.setScale(((Number) v).floatValue())
        ));
        if (en.getEffectType() == EffectNode.EffectType.CRYSTAL) {
            params.add(new AnimatableParameter(
                "sharpness", "Sharpness", Float.class,
                () -> en.getSharpness(),
                v -> en.setSharpness(((Number) v).floatValue())
            ));
        }
        return params;
    }

    /**
     * Create an AnimatableParameter that reads/writes a specific index of a float[].
     */
    private static AnimatableParameter floatArrayParam(String name, String displayName,
                                                        Supplier<float[]> arrayGetter, int index) {
        return new AnimatableParameter(
            name, displayName, Float.class,
            () -> arrayGetter.get()[index],
            v -> arrayGetter.get()[index] = ((Number) v).floatValue()
        );
    }
}
