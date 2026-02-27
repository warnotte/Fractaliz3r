package org.fractalizer.graph;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility for assigning and managing unique stable names on graph nodes.
 * Names are persistent (survive recompilation) and used for animation track IDs.
 */
public final class GraphNodeNamer {

    private GraphNodeNamer() {}

    /**
     * Generate a base name for a node based on its type.
     */
    public static String generateBaseName(GraphNode node) {
        if (node instanceof PrimitiveNode pn) {
            return pn.getPrimitiveType().getDisplayName();
        } else if (node instanceof FractalNode fn) {
            return fn.getFractalType().getDisplayName();
        } else if (node instanceof CSGNode) {
            return "CSG";
        } else if (node instanceof EffectNode en) {
            return en.getEffectType().getDisplayName();
        } else if (node instanceof TransformNode tn) {
            return tn.getMode().getDisplayName();
        } else if (node instanceof MaterialNode) {
            return "Material";
        }
        return "Node";
    }

    /**
     * DFS the tree and assign names to any node whose name is null.
     * Already-named nodes are preserved. Uniqueness ensured via suffix (_2, _3, ...).
     */
    public static void ensureAllNamed(GraphNode root) {
        Set<String> usedNames = collectNames(root);
        assignNames(root, usedNames);
    }

    /**
     * Collect all non-null names in the tree.
     */
    public static Set<String> collectNames(GraphNode root) {
        Set<String> names = new HashSet<>();
        collectNamesRecursive(root, names);
        return names;
    }

    /**
     * Rename a node, ensuring uniqueness within the tree.
     * @return true if rename succeeded, false if the name is already taken
     */
    public static boolean renameNode(GraphNode root, GraphNode target, String newName) {
        if (newName == null || newName.isBlank()) return false;
        String trimmed = newName.trim();

        // Check uniqueness: the new name must not be used by any other node
        Set<String> usedNames = collectNames(root);
        // Remove the target's current name (it's allowed to keep its own name)
        if (target.getName() != null) {
            usedNames.remove(target.getName());
        }
        if (usedNames.contains(trimmed)) {
            return false;
        }

        target.setName(trimmed);
        return true;
    }

    private static void collectNamesRecursive(GraphNode node, Set<String> names) {
        if (node == null) return;
        if (node.getName() != null) {
            names.add(node.getName());
        }
        for (GraphNode child : node.getChildren()) {
            collectNamesRecursive(child, names);
        }
    }

    private static void assignNames(GraphNode node, Set<String> usedNames) {
        if (node == null) return;

        if (node.getName() == null) {
            String baseName = generateBaseName(node);
            String uniqueName = makeUnique(baseName, usedNames);
            node.setName(uniqueName);
            usedNames.add(uniqueName);
        }

        for (GraphNode child : node.getChildren()) {
            assignNames(child, usedNames);
        }
    }

    private static String makeUnique(String baseName, Set<String> usedNames) {
        if (!usedNames.contains(baseName)) return baseName;
        for (int i = 2; ; i++) {
            String candidate = baseName + "_" + i;
            if (!usedNames.contains(candidate)) return candidate;
        }
    }
}
