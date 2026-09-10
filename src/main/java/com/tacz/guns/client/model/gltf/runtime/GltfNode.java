package com.tacz.guns.client.model.gltf.runtime;

import java.util.Arrays;
import java.util.Objects;

public final class GltfNode {
    private final String name;
    private final GltfNodeTransform localTransform;
    private final int[] children;
    private final int meshIndex;
    private final int skinIndex;

    public GltfNode(String name, GltfNodeTransform localTransform, int[] children, int meshIndex, int skinIndex) {
        this.name = name == null ? "" : name;
        this.localTransform = Objects.requireNonNull(localTransform, "localTransform");
        this.children = children == null ? new int[0] : children.clone();
        this.meshIndex = meshIndex;
        this.skinIndex = skinIndex;
        if (meshIndex < -1 || skinIndex < -1) {
            throw new IllegalArgumentException("meshIndex and skinIndex must be -1 or non-negative");
        }
        for (int child : this.children) {
            if (child < 0) {
                throw new IllegalArgumentException("child index must be non-negative: " + child);
            }
        }
    }

    public String name() {
        return name;
    }

    public GltfNodeTransform localTransform() {
        return localTransform;
    }

    public int[] children() {
        return children.clone();
    }

    int[] childrenUnsafe() {
        return children;
    }

    public int meshIndex() {
        return meshIndex;
    }

    public int skinIndex() {
        return skinIndex;
    }

    @Override
    public String toString() {
        return "GltfNode{" +
                "name='" + name + '\'' +
                ", children=" + Arrays.toString(children) +
                ", meshIndex=" + meshIndex +
                ", skinIndex=" + skinIndex +
                '}';
    }
}
