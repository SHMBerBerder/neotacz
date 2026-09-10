package com.tacz.guns.client.model.gltf.convert;

import java.util.List;
import java.util.Objects;

public final class GltfRenderMesh {
    private final String name;
    private final List<GltfRenderPrimitive> primitives;
    private final float[] defaultMorphWeights;

    public GltfRenderMesh(String name, List<GltfRenderPrimitive> primitives, float[] defaultMorphWeights) {
        this.name = name == null ? "" : name;
        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        this.defaultMorphWeights = defaultMorphWeights == null ? new float[0] : defaultMorphWeights.clone();
        for (float weight : this.defaultMorphWeights) {
            if (!Float.isFinite(weight)) {
                throw new IllegalArgumentException("defaultMorphWeights must be finite");
            }
        }
    }

    public String name() {
        return name;
    }

    public List<GltfRenderPrimitive> primitives() {
        return primitives;
    }

    public float[] defaultMorphWeights() {
        return defaultMorphWeights.clone();
    }
}
