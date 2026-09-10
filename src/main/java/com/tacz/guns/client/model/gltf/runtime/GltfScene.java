package com.tacz.guns.client.model.gltf.runtime;

import java.util.List;
import java.util.Objects;

public final class GltfScene {
    private final List<GltfNode> nodes;
    private final List<GltfMesh> meshes;
    private final List<GltfSkin> skins;
    private final int[] rootNodes;

    public GltfScene(List<GltfNode> nodes, List<GltfMesh> meshes, List<GltfSkin> skins, int[] rootNodes) {
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        this.meshes = List.copyOf(Objects.requireNonNull(meshes, "meshes"));
        this.skins = List.copyOf(Objects.requireNonNull(skins, "skins"));
        this.rootNodes = rootNodes == null ? new int[0] : rootNodes.clone();
        for (int rootNode : this.rootNodes) {
            requireNode(rootNode, "root");
        }
        for (GltfNode node : this.nodes) {
            for (int child : node.childrenUnsafe()) {
                requireNode(child, "child");
            }
            if (node.meshIndex() >= this.meshes.size()) {
                throw new IllegalArgumentException("mesh index out of range: " + node.meshIndex());
            }
            if (node.skinIndex() >= this.skins.size()) {
                throw new IllegalArgumentException("skin index out of range: " + node.skinIndex());
            }
        }
    }

    public List<GltfNode> nodes() {
        return nodes;
    }

    public List<GltfMesh> meshes() {
        return meshes;
    }

    public List<GltfSkin> skins() {
        return skins;
    }

    public int[] rootNodes() {
        return rootNodes.clone();
    }

    int[] rootNodesUnsafe() {
        return rootNodes;
    }

    private void requireNode(int nodeIndex, String role) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            throw new IllegalArgumentException(role + " node index out of range: " + nodeIndex);
        }
    }
}
