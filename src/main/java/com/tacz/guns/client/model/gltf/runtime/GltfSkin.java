package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Objects;

public final class GltfSkin {
    private final int[] joints;
    private final Matrix4f[] inverseBindMatrices;

    public GltfSkin(int[] joints, List<? extends Matrix4fc> inverseBindMatrices) {
        if (joints == null || joints.length == 0) {
            throw new IllegalArgumentException("skin must contain at least one joint");
        }
        this.joints = joints.clone();
        for (int joint : this.joints) {
            if (joint < 0) {
                throw new IllegalArgumentException("joint node index must be non-negative: " + joint);
            }
        }
        Objects.requireNonNull(inverseBindMatrices, "inverseBindMatrices");
        if (inverseBindMatrices.size() != this.joints.length) {
            throw new IllegalArgumentException("inverseBindMatrices size must match joints length");
        }
        this.inverseBindMatrices = new Matrix4f[inverseBindMatrices.size()];
        for (int i = 0; i < inverseBindMatrices.size(); i++) {
            this.inverseBindMatrices[i] = new Matrix4f(Objects.requireNonNull(inverseBindMatrices.get(i), "inverseBindMatrix"));
        }
    }

    public int jointCount() {
        return joints.length;
    }

    public int[] joints() {
        return joints.clone();
    }

    public int jointNodeIndex(int jointIndex) {
        return joints[jointIndex];
    }

    public Matrix4f inverseBindMatrix(int jointIndex) {
        return new Matrix4f(inverseBindMatrices[jointIndex]);
    }

    Matrix4f inverseBindMatrixUnsafe(int jointIndex) {
        return inverseBindMatrices[jointIndex];
    }
}
