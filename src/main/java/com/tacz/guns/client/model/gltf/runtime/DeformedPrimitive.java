package com.tacz.guns.client.model.gltf.runtime;

public final class DeformedPrimitive {
    private final float[] positions;
    private final float[] normals;
    private final float[] tangents;
    private final int vertexCount;

    DeformedPrimitive(float[] positions, float[] normals, float[] tangents) {
        this.positions = positions;
        this.normals = normals;
        this.tangents = tangents;
        this.vertexCount = positions.length / 3;
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

    public float[] position(int vertexIndex) {
        int offset = vertexIndex * 3;
        return new float[]{positions[offset], positions[offset + 1], positions[offset + 2]};
    }

    public float[] normal(int vertexIndex) {
        if (normals.length == 0) {
            return new float[0];
        }
        int offset = vertexIndex * 3;
        return new float[]{normals[offset], normals[offset + 1], normals[offset + 2]};
    }

    public float[] tangent(int vertexIndex) {
        if (tangents.length == 0) {
            return new float[0];
        }
        int offset = vertexIndex * 4;
        return new float[]{tangents[offset], tangents[offset + 1], tangents[offset + 2], tangents[offset + 3]};
    }
}
