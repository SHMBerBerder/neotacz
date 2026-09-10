package com.tacz.guns.client.model.gltf.convert;

public final class GltfSceneData {
    private final String name;
    private final int[] rootNodes;

    public GltfSceneData(String name, int[] rootNodes) {
        this.name = name == null ? "" : name;
        this.rootNodes = rootNodes == null ? new int[0] : rootNodes.clone();
        for (int node : this.rootNodes) {
            if (node < 0) {
                throw new IllegalArgumentException("root node indices must be non-negative");
            }
        }
    }

    public String name() {
        return name;
    }

    public int[] rootNodes() {
        return rootNodes.clone();
    }
}
