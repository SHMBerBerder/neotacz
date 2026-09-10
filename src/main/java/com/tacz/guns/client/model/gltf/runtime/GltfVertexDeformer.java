package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

public final class GltfVertexDeformer {
    private static final float EPSILON = 1.0E-8f;

    private GltfVertexDeformer() {
    }

    public static DeformedPrimitive deform(
            GltfMeshPrimitive primitive,
            GltfSkin skin,
            Matrix4fc[] nodeWorldTransforms,
            float[] morphWeights
    ) {
        if (primitive.hasSkinAttributes() && skin == null) {
            throw new IllegalArgumentException("skinned primitive requires a skin");
        }
        if (skin != null && nodeWorldTransforms == null) {
            throw new NullPointerException("nodeWorldTransforms");
        }

        Matrix4f[] jointMatrices = skin == null ? new Matrix4f[0] : computeJointMatrices(skin, nodeWorldTransforms);
        int vertexCount = primitive.vertexCount();
        float[] outPositions = new float[vertexCount * 3];
        float[] outNormals = primitive.hasNormals() ? new float[vertexCount * 3] : new float[0];
        float[] outTangents = primitive.hasTangents() ? new float[vertexCount * 4] : new float[0];

        for (int vertexIndex = 0; vertexIndex < vertexCount; vertexIndex++) {
            // glTF applies morph target deltas in mesh space before joint skinning.
            Vector3f position = morphedPosition(primitive, morphWeights, vertexIndex);
            Vector3f normal = primitive.hasNormals() ? morphedNormal(primitive, morphWeights, vertexIndex) : null;
            Vector3f tangent = primitive.hasTangents() ? morphedTangent(primitive, morphWeights, vertexIndex) : null;
            float tangentW = primitive.hasTangents() ? primitive.tangent(vertexIndex, 3) : 0.0f;

            if (primitive.hasSkinAttributes()) {
                skinVertex(primitive, vertexIndex, jointMatrices, position, normal, tangent);
            } else {
                normalizeIfPresent(normal);
                normalizeIfPresent(tangent);
            }

            writeVec3(outPositions, vertexIndex, position);
            if (normal != null) {
                writeVec3(outNormals, vertexIndex, normal);
            }
            if (tangent != null) {
                writeVec3ToVec4(outTangents, vertexIndex, tangent, tangentW);
            }
        }
        return new DeformedPrimitive(outPositions, outNormals, outTangents);
    }

    private static Matrix4f[] computeJointMatrices(GltfSkin skin, Matrix4fc[] nodeWorldTransforms) {
        Matrix4f[] jointMatrices = new Matrix4f[skin.jointCount()];
        for (int jointIndex = 0; jointIndex < skin.jointCount(); jointIndex++) {
            int nodeIndex = skin.jointNodeIndex(jointIndex);
            if (nodeIndex >= nodeWorldTransforms.length || nodeWorldTransforms[nodeIndex] == null) {
                throw new IllegalArgumentException("missing world transform for joint node " + nodeIndex);
            }
            jointMatrices[jointIndex] = new Matrix4f(nodeWorldTransforms[nodeIndex]).mul(skin.inverseBindMatrixUnsafe(jointIndex));
        }
        return jointMatrices;
    }

    private static Vector3f morphedPosition(GltfMeshPrimitive primitive, float[] morphWeights, int vertexIndex) {
        Vector3f result = new Vector3f(
                primitive.position(vertexIndex, 0),
                primitive.position(vertexIndex, 1),
                primitive.position(vertexIndex, 2)
        );
        for (int targetIndex = 0; targetIndex < primitive.morphTargets().size(); targetIndex++) {
            float weight = morphWeight(morphWeights, targetIndex);
            if (weight == 0.0f) {
                continue;
            }
            GltfMorphTarget target = primitive.morphTargets().get(targetIndex);
            result.x += target.positionDelta(vertexIndex, 0) * weight;
            result.y += target.positionDelta(vertexIndex, 1) * weight;
            result.z += target.positionDelta(vertexIndex, 2) * weight;
        }
        return result;
    }

    private static Vector3f morphedNormal(GltfMeshPrimitive primitive, float[] morphWeights, int vertexIndex) {
        Vector3f result = new Vector3f(
                primitive.normal(vertexIndex, 0),
                primitive.normal(vertexIndex, 1),
                primitive.normal(vertexIndex, 2)
        );
        for (int targetIndex = 0; targetIndex < primitive.morphTargets().size(); targetIndex++) {
            float weight = morphWeight(morphWeights, targetIndex);
            if (weight == 0.0f) {
                continue;
            }
            GltfMorphTarget target = primitive.morphTargets().get(targetIndex);
            result.x += target.normalDelta(vertexIndex, 0) * weight;
            result.y += target.normalDelta(vertexIndex, 1) * weight;
            result.z += target.normalDelta(vertexIndex, 2) * weight;
        }
        return result;
    }

    private static Vector3f morphedTangent(GltfMeshPrimitive primitive, float[] morphWeights, int vertexIndex) {
        Vector3f result = new Vector3f(
                primitive.tangent(vertexIndex, 0),
                primitive.tangent(vertexIndex, 1),
                primitive.tangent(vertexIndex, 2)
        );
        for (int targetIndex = 0; targetIndex < primitive.morphTargets().size(); targetIndex++) {
            float weight = morphWeight(morphWeights, targetIndex);
            if (weight == 0.0f) {
                continue;
            }
            GltfMorphTarget target = primitive.morphTargets().get(targetIndex);
            result.x += target.tangentDelta(vertexIndex, 0) * weight;
            result.y += target.tangentDelta(vertexIndex, 1) * weight;
            result.z += target.tangentDelta(vertexIndex, 2) * weight;
        }
        return result;
    }

    private static float morphWeight(float[] morphWeights, int targetIndex) {
        if (morphWeights == null || targetIndex >= morphWeights.length) {
            return 0.0f;
        }
        float weight = morphWeights[targetIndex];
        if (!Float.isFinite(weight)) {
            throw new IllegalArgumentException("morph weight must be finite");
        }
        return weight;
    }

    private static void skinVertex(
            GltfMeshPrimitive primitive,
            int vertexIndex,
            Matrix4f[] jointMatrices,
            Vector3f position,
            Vector3f normal,
            Vector3f tangent
    ) {
        float weightSum = 0.0f;
        for (int slot = 0; slot < 4; slot++) {
            float weight = primitive.weight(vertexIndex, slot);
            if (weight < 0.0f || !Float.isFinite(weight)) {
                throw new IllegalArgumentException("joint weight must be finite and non-negative");
            }
            weightSum += weight;
        }
        if (weightSum <= EPSILON) {
            throw new IllegalArgumentException("skinned vertex has zero total joint weight: " + vertexIndex);
        }

        Matrix4f blendedJointMatrix = new Matrix4f().zero();
        for (int slot = 0; slot < 4; slot++) {
            float normalizedWeight = primitive.weight(vertexIndex, slot) / weightSum;
            if (normalizedWeight == 0.0f) {
                continue;
            }
            int jointIndex = primitive.joint(vertexIndex, slot);
            if (jointIndex >= jointMatrices.length) {
                throw new IllegalArgumentException("joint index out of skin range: " + jointIndex);
            }
            addWeighted(blendedJointMatrix, jointMatrices[jointIndex], normalizedWeight);
        }

        Matrix3f linearMatrix = new Matrix3f(blendedJointMatrix);
        float determinant = linearMatrix.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= EPSILON) {
            throw new IllegalArgumentException(
                    "blended joint matrix must be finite and invertible at vertex "
                            + vertexIndex
            );
        }

        position.mulPosition(blendedJointMatrix);
        if (normal != null || tangent != null) {
            Matrix3f normalMatrix = linearMatrix;
            normalMatrix.invert().transpose();
            if (normal != null) {
                normal.mul(normalMatrix);
                normalizeIfPresent(normal);
            }
            if (tangent != null) {
                tangent.mul(normalMatrix);
                normalizeIfPresent(tangent);
            }
        }
    }

    private static void addWeighted(Matrix4f result, Matrix4f value, float weight) {
        result.m00(result.m00() + value.m00() * weight);
        result.m01(result.m01() + value.m01() * weight);
        result.m02(result.m02() + value.m02() * weight);
        result.m03(result.m03() + value.m03() * weight);
        result.m10(result.m10() + value.m10() * weight);
        result.m11(result.m11() + value.m11() * weight);
        result.m12(result.m12() + value.m12() * weight);
        result.m13(result.m13() + value.m13() * weight);
        result.m20(result.m20() + value.m20() * weight);
        result.m21(result.m21() + value.m21() * weight);
        result.m22(result.m22() + value.m22() * weight);
        result.m23(result.m23() + value.m23() * weight);
        result.m30(result.m30() + value.m30() * weight);
        result.m31(result.m31() + value.m31() * weight);
        result.m32(result.m32() + value.m32() * weight);
        result.m33(result.m33() + value.m33() * weight);
    }

    private static void normalizeIfPresent(Vector3f vector) {
        if (vector != null && vector.lengthSquared() > EPSILON) {
            vector.normalize();
        }
    }

    private static void writeVec3(float[] output, int vertexIndex, Vector3f value) {
        int offset = vertexIndex * 3;
        output[offset] = value.x;
        output[offset + 1] = value.y;
        output[offset + 2] = value.z;
    }

    private static void writeVec3ToVec4(float[] output, int vertexIndex, Vector3f value, float w) {
        int offset = vertexIndex * 4;
        output[offset] = value.x;
        output[offset + 1] = value.y;
        output[offset + 2] = value.z;
        output[offset + 3] = w;
    }
}
