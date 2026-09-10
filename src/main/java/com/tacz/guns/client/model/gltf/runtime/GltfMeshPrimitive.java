package com.tacz.guns.client.model.gltf.runtime;

import java.util.List;
import java.util.Objects;

public final class GltfMeshPrimitive {
    private final float[] positions;
    private final float[] normals;
    private final float[] tangents;
    private final int[] joints0;
    private final float[] weights0;
    private final List<GltfMorphTarget> morphTargets;
    private final GltfMaterialReference material;
    private final int vertexCount;

    public GltfMeshPrimitive(
            float[] positions,
            float[] normals,
            float[] tangents,
            int[] joints0,
            float[] weights0,
            List<GltfMorphTarget> morphTargets,
            GltfMaterialReference material
    ) {
        this.positions = copyFloatArray(positions, "positions");
        if (this.positions.length == 0 || this.positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions length must be a non-empty multiple of 3");
        }
        this.vertexCount = this.positions.length / 3;
        this.normals = copyOptionalFloatArray(normals, vertexCount * 3, "normals");
        this.tangents = copyOptionalFloatArray(tangents, vertexCount * 4, "tangents");
        this.joints0 = copyOptionalIntArray(joints0, vertexCount * 4, "joints0");
        this.weights0 = copyOptionalFloatArray(weights0, vertexCount * 4, "weights0");
        if ((this.joints0.length == 0) != (this.weights0.length == 0)) {
            throw new IllegalArgumentException("joints0 and weights0 must both be present or both be empty");
        }
        this.morphTargets = List.copyOf(Objects.requireNonNull(morphTargets, "morphTargets"));
        for (GltfMorphTarget morphTarget : this.morphTargets) {
            morphTarget.validateVertexCount(vertexCount);
        }
        this.material = Objects.requireNonNull(material, "material");
    }

    public int vertexCount() {
        return vertexCount;
    }

    public float[] positions() {
        return positions.clone();
    }

    public float[] normals() {
        return normals.clone();
    }

    public float[] tangents() {
        return tangents.clone();
    }

    public int[] joints0() {
        return joints0.clone();
    }

    public float[] weights0() {
        return weights0.clone();
    }

    public List<GltfMorphTarget> morphTargets() {
        return morphTargets;
    }

    public GltfMaterialReference material() {
        return material;
    }

    boolean hasNormals() {
        return normals.length != 0;
    }

    boolean hasTangents() {
        return tangents.length != 0;
    }

    public boolean hasSkinAttributes() {
        return joints0.length != 0;
    }

    float position(int vertexIndex, int component) {
        return positions[vertexIndex * 3 + component];
    }

    float normal(int vertexIndex, int component) {
        return normals[vertexIndex * 3 + component];
    }

    float tangent(int vertexIndex, int component) {
        return tangents[vertexIndex * 4 + component];
    }

    int joint(int vertexIndex, int slot) {
        return joints0[vertexIndex * 4 + slot];
    }

    float weight(int vertexIndex, int slot) {
        return weights0[vertexIndex * 4 + slot];
    }

    private static float[] copyFloatArray(float[] values, String name) {
        if (values == null) {
            throw new NullPointerException(name);
        }
        float[] copy = values.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }

    private static float[] copyOptionalFloatArray(float[] values, int expectedLength, String name) {
        if (values == null || values.length == 0) {
            return new float[0];
        }
        float[] copy = copyFloatArray(values, name);
        if (copy.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength);
        }
        return copy;
    }

    private static int[] copyOptionalIntArray(int[] values, int expectedLength, String name) {
        if (values == null || values.length == 0) {
            return new int[0];
        }
        int[] copy = values.clone();
        if (copy.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength);
        }
        for (int value : copy) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must contain only non-negative joint indices");
            }
        }
        return copy;
    }
}
