package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;

import java.util.Objects;

public final class GltfRenderPrimitive {
    private final GltfMeshPrimitive runtimePrimitive;
    private final int[] indices;
    private final float[] texCoords0;
    private final float[] colors0;
    private final int materialIndex;

    public GltfRenderPrimitive(
            GltfMeshPrimitive runtimePrimitive,
            int[] indices,
            float[] texCoords0,
            float[] colors0,
            int materialIndex
    ) {
        this.runtimePrimitive = Objects.requireNonNull(runtimePrimitive, "runtimePrimitive");
        if (indices == null || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must be a non-empty multiple of 3");
        }
        this.indices = indices.clone();
        for (int index : this.indices) {
            if (index < 0 || index >= runtimePrimitive.vertexCount()) {
                throw new IllegalArgumentException("index out of vertex range: " + index);
            }
        }
        this.texCoords0 = copyOptional(texCoords0, runtimePrimitive.vertexCount() * 2, "texCoords0");
        this.colors0 = copyOptional(colors0, runtimePrimitive.vertexCount() * 4, "colors0");
        if (materialIndex < -1) {
            throw new IllegalArgumentException("materialIndex must be -1 or non-negative");
        }
        this.materialIndex = materialIndex;
    }

    public GltfMeshPrimitive runtimePrimitive() {
        return runtimePrimitive;
    }

    public int[] indices() {
        return indices.clone();
    }

    public int indexCount() {
        return indices.length;
    }

    public float[] texCoords0() {
        return texCoords0.clone();
    }

    public float[] colors0() {
        return colors0.clone();
    }

    public int materialIndex() {
        return materialIndex;
    }

    private static float[] copyOptional(float[] values, int expectedLength, String name) {
        if (values == null || values.length == 0) {
            return new float[0];
        }
        if (values.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength);
        }
        float[] copy = values.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }
}
