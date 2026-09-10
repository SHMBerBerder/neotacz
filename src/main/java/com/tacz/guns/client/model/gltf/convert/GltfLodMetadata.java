package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfScene;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authored MSFT_lod chains. Values are lower-quality IDs, excluding the highest-quality key. */
public final class GltfLodMetadata {
    public static final GltfLodMetadata NONE = new GltfLodMetadata(Map.of(), Map.of());

    private final Map<Integer, List<Integer>> nodeChains;
    private final Map<Integer, List<Integer>> materialChains;

    public GltfLodMetadata(Map<Integer, List<Integer>> nodeChains, Map<Integer, List<Integer>> materialChains) {
        this.nodeChains = copyChains(nodeChains, "node");
        this.materialChains = copyChains(materialChains, "material");
    }

    public Map<Integer, List<Integer>> nodeChains() {
        return nodeChains;
    }

    public Map<Integer, List<Integer>> materialChains() {
        return materialChains;
    }

    int materialAtLevel(int material, int level) {
        List<Integer> chain = materialChains.get(material);
        return chain == null || level == 0 ? material : chain.get(Math.min(level, chain.size()) - 1);
    }

    void validate(GltfScene scene, List<GltfSceneData> scenes, int materialCount) {
        validateBounds(nodeChains, scene.nodes().size(), "node");
        validateBounds(materialChains, materialCount, "material");
        Set<Integer> lowerNodes = lowerNodeIndices();
        for (var node : scene.nodes()) {
            for (int child : node.children()) {
                if (lowerNodes.contains(child)) {
                    throw new IllegalArgumentException("MSFT_lod lower node has an ordinary parent: " + child);
                }
            }
        }
        for (GltfSceneData data : scenes) {
            for (int root : data.rootNodes()) {
                if (lowerNodes.contains(root)) {
                    throw new IllegalArgumentException("MSFT_lod lower node is also a scene root: " + root);
                }
            }
        }
        byte[] visited = new byte[scene.nodes().size()];
        for (int node = 0; node < visited.length; node++) validateGraph(scene, node, visited);
    }

    Set<Integer> lowerNodeIndices() {
        Set<Integer> lower = new HashSet<>();
        nodeChains.values().forEach(lower::addAll);
        return lower;
    }

    private void validateGraph(GltfScene scene, int node, byte[] visited) {
        if (visited[node] == 1) throw new IllegalArgumentException("cycle in MSFT_lod node graph: " + node);
        if (visited[node] == 2) return;
        visited[node] = 1;
        for (int child : scene.nodes().get(node).children()) validateGraph(scene, child, visited);
        for (int lower : nodeChains.getOrDefault(node, List.of())) validateGraph(scene, lower, visited);
        visited[node] = 2;
    }

    private static Map<Integer, List<Integer>> copyChains(Map<Integer, List<Integer>> source, String role) {
        Objects.requireNonNull(source, role + "Chains");
        Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
        Set<Integer> lower = new HashSet<>();
        for (var entry : source.entrySet()) {
            int high = Objects.requireNonNull(entry.getKey(), role + " LOD key");
            List<Integer> ids = List.copyOf(entry.getValue());
            if (high < 0 || ids.isEmpty()) throw new IllegalArgumentException("invalid MSFT_lod " + role + " chain");
            for (int id : ids) {
                if (id < 0 || id == high || !lower.add(id)) {
                    throw new IllegalArgumentException("self, duplicate, or negative MSFT_lod " + role + " ID: " + id);
                }
            }
            copy.put(high, ids);
        }
        for (int high : copy.keySet()) {
            if (lower.contains(high)) {
                throw new IllegalArgumentException("MSFT_lod must be defined on the highest " + role + " only: " + high);
            }
        }
        return Map.copyOf(copy);
    }

    private static void validateBounds(Map<Integer, List<Integer>> chains, int count, String role) {
        for (var entry : chains.entrySet()) {
            if (entry.getKey() >= count || entry.getValue().stream().anyMatch(id -> id >= count)) {
                throw new IllegalArgumentException("MSFT_lod " + role + " ID out of range");
            }
        }
    }
}
