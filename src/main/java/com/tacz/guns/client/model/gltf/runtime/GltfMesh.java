package com.tacz.guns.client.model.gltf.runtime;

import java.util.List;
import java.util.Objects;

public final class GltfMesh {
    private final List<GltfMeshPrimitive> primitives;

    public GltfMesh(List<GltfMeshPrimitive> primitives) {
        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
    }

    public List<GltfMeshPrimitive> primitives() {
        return primitives;
    }
}
