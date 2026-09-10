package com.tacz.guns.client.model.gltf.runtime;

public final class GltfMorphTarget {
    private final float[] positionDeltas;
    private final float[] normalDeltas;
    private final float[] tangentDeltas;

    public GltfMorphTarget(float[] positionDeltas, float[] normalDeltas, float[] tangentDeltas) {
        this.positionDeltas = copyVec3Array(positionDeltas, "positionDeltas");
        this.normalDeltas = copyVec3Array(normalDeltas, "normalDeltas");
        this.tangentDeltas = copyVec3Array(tangentDeltas, "tangentDeltas");
    }

    public float[] positionDeltas() {
        return positionDeltas.clone();
    }

    public float[] normalDeltas() {
        return normalDeltas.clone();
    }

    public float[] tangentDeltas() {
        return tangentDeltas.clone();
    }

    float positionDelta(int vertexIndex, int component) {
        return component(positionDeltas, vertexIndex, component);
    }

    float normalDelta(int vertexIndex, int component) {
        return component(normalDeltas, vertexIndex, component);
    }

    float tangentDelta(int vertexIndex, int component) {
        return component(tangentDeltas, vertexIndex, component);
    }

    void validateVertexCount(int vertexCount) {
        validateLength(positionDeltas, vertexCount, "positionDeltas");
        validateLength(normalDeltas, vertexCount, "normalDeltas");
        validateLength(tangentDeltas, vertexCount, "tangentDeltas");
    }

    private static float component(float[] values, int vertexIndex, int component) {
        if (values.length == 0) {
            return 0.0f;
        }
        return values[vertexIndex * 3 + component];
    }

    private static float[] copyVec3Array(float[] values, String name) {
        if (values == null || values.length == 0) {
            return new float[0];
        }
        if (values.length % 3 != 0) {
            throw new IllegalArgumentException(name + " length must be a multiple of 3");
        }
        float[] copy = values.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
        return copy;
    }

    private static void validateLength(float[] values, int vertexCount, String name) {
        if (values.length != 0 && values.length != vertexCount * 3) {
            throw new IllegalArgumentException(name + " length must be zero or vertexCount * 3");
        }
    }
}
