package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSceneTransforms;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable selection and bind corrections; the original node table remains the rig authority. */
public final class GltfLodSelection {
    private final Set<Integer> nodeIndices;
    private final Set<Integer> meshIndices;
    private final Set<Integer> authoredMeshIndices;
    private final List<Replacement> replacements;
    private final int[] parents;

    GltfLodSelection(GltfScene scene, GltfLodMetadata metadata, int level) {
        GltfSceneTransforms.computeWorldTransforms(scene);
        parents = new int[scene.nodes().size()];
        Arrays.fill(parents, -1);
        for (int parent = 0; parent < parents.length; parent++) {
            for (int child : scene.nodes().get(parent).children()) parents[child] = parent;
        }
        Set<Integer> active = new LinkedHashSet<>();
        List<Replacement> corrections = new ArrayList<>();
        Set<Integer> lowerNodes = metadata.lowerNodeIndices();
        for (int root : scene.rootNodes()) {
            if (!lowerNodes.contains(root)) {
                select(scene, metadata, root, -1, level, new Matrix4f(), active, corrections);
            }
        }
        nodeIndices = Set.copyOf(active);
        meshIndices = meshesForNodes(scene, active);
        Set<Integer> authored = new HashSet<>();
        for (var chain : metadata.nodeChains().entrySet()) {
            collectSubtree(scene, chain.getKey(), authored);
            for (int lower : chain.getValue()) collectSubtree(scene, lower, authored);
        }
        authoredMeshIndices = meshesForNodes(scene, authored);
        replacements = List.copyOf(corrections);
    }

    public Set<Integer> nodeIndices() {
        return nodeIndices;
    }

    public Set<Integer> meshIndices() {
        return meshIndices;
    }

    public Set<Integer> authoredMeshIndices() {
        return authoredMeshIndices;
    }

    /** Called on fresh frame-owned arrays, never on stored rig or asset matrices. */
    public void applyToPose(Matrix4f[] worlds, boolean[] hidden, Matrix4f[] worldOverrides) {
        for (Replacement replacement : replacements) {
            // Native glTF channels target each LOD separately. Only an external rig override
            // transfers the high node's bind delta; copying its native animation would apply it twice.
            boolean mapped = worldOverrides != null && worldOverrides[replacement.highNode] != null;
            Matrix4f correction = mapped
                    ? new Matrix4f(worlds[replacement.highNode]).mul(replacement.inverseHighLocalBind)
                    : replacement.parentNode < 0 ? new Matrix4f() : new Matrix4f(worlds[replacement.parentNode]);
            for (int node : replacement.lowerSubtree) {
                if (!hasAbsoluteOverride(node, replacement.lowerSubtree, worldOverrides)) {
                    Matrix4f world = new Matrix4f(correction).mul(worlds[node]);
                    if (!world.isFinite()) throw new IllegalArgumentException("non-finite MSFT_lod world transform: " + node);
                    worlds[node] = world;
                }
                hidden[node] |= hidden[replacement.highNode];
            }
        }
    }

    private boolean hasAbsoluteOverride(int node, Set<Integer> subtree, Matrix4f[] overrides) {
        if (overrides == null) return false;
        while (subtree.contains(node)) {
            if (overrides[node] != null) return true;
            node = parents[node];
        }
        return false;
    }

    private static void select(GltfScene scene, GltfLodMetadata metadata, int high, int parent, int level,
                               Matrix4f parentBind, Set<Integer> active, List<Replacement> corrections) {
        Matrix4f local = scene.nodes().get(high).localTransform().toMatrix();
        Matrix4f highBind = new Matrix4f(parentBind).mul(local);
        List<Integer> lower = metadata.nodeChains().get(high);
        int selected = high;
        Matrix4f selectedBind = highBind;
        if (level > 0 && lower != null && invertible(highBind) && invertible(local)) {
            selected = lower.get(Math.min(level, lower.size()) - 1);
            Set<Integer> subtree = new LinkedHashSet<>();
            collectSubtree(scene, selected, subtree);
            // All levels occupy the high node's parent slot. H^-1 equals (P*H)^-1*P,
            // so retaining P here also handles translated/rotated parents and nested LODs.
            corrections.add(new Replacement(high, parent, new Matrix4f(local).invert(), Set.copyOf(subtree)));
            selectedBind = new Matrix4f(parentBind).mul(scene.nodes().get(selected).localTransform().toMatrix());
        }
        if (!active.add(selected)) throw new IllegalArgumentException("MSFT_lod node selected more than once: " + selected);
        for (int child : scene.nodes().get(selected).children()) {
            select(scene, metadata, child, selected, level, selectedBind, active, corrections);
        }
    }

    private static boolean invertible(Matrix4f matrix) {
        float determinant = matrix.determinant3x3();
        return matrix.isFinite() && Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-8f
                && new Matrix4f(matrix).invert().isFinite();
    }

    private static void collectSubtree(GltfScene scene, int node, Set<Integer> nodes) {
        if (!nodes.add(node)) return;
        for (int child : scene.nodes().get(node).children()) collectSubtree(scene, child, nodes);
    }

    private static Set<Integer> meshesForNodes(GltfScene scene, Set<Integer> nodes) {
        Set<Integer> meshes = new HashSet<>();
        for (int node : nodes) {
            int mesh = scene.nodes().get(node).meshIndex();
            if (mesh >= 0) meshes.add(mesh);
        }
        return Set.copyOf(meshes);
    }

    private record Replacement(int highNode, int parentNode, Matrix4f inverseHighLocalBind, Set<Integer> lowerSubtree) {
    }
}
