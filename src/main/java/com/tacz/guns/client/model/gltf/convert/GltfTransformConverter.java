package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import de.javagl.jgltf.model.NodeModel;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class GltfTransformConverter {
    private static final double EPSILON = 1.0E-6;

    private GltfTransformConverter() {
    }

    static GltfNodeTransform convert(NodeModel node, String role) throws GltfConversionException {
        double[] matrix = node.getMatrix();
        if (matrix != null) {
            return decompose(matrix, role);
        }
        double[] translation = orDefault(node.getTranslation(), 0.0, 0.0, 0.0);
        double[] rotation = orDefault(node.getRotation(), 0.0, 0.0, 0.0, 1.0);
        double[] scale = orDefault(node.getScale(), 1.0, 1.0, 1.0);
        requireLength(translation, 3, role + " translation");
        requireLength(rotation, 4, role + " rotation");
        requireLength(scale, 3, role + " scale");
        requireFinite(translation, role + " translation");
        requireFinite(rotation, role + " rotation");
        requireFinite(scale, role + " scale");
        return new GltfNodeTransform(
                new Vector3f((float) translation[0], (float) translation[1], (float) translation[2]),
                new Quaternionf((float) rotation[0], (float) rotation[1], (float) rotation[2], (float) rotation[3]),
                new Vector3f((float) scale[0], (float) scale[1], (float) scale[2])
        );
    }

    private static GltfNodeTransform decompose(double[] matrix, String role) throws GltfConversionException {
        requireLength(matrix, 16, role + " matrix");
        requireFinite(matrix, role + " matrix");
        if (Math.abs(matrix[3]) > EPSILON
                || Math.abs(matrix[7]) > EPSILON
                || Math.abs(matrix[11]) > EPSILON
                || Math.abs(matrix[15] - 1.0) > EPSILON) {
            throw new GltfConversionException(role + " matrix is not affine");
        }

        Vector3f column0 = vector(matrix[0], matrix[1], matrix[2]);
        Vector3f column1 = vector(matrix[4], matrix[5], matrix[6]);
        Vector3f column2 = vector(matrix[8], matrix[9], matrix[10]);
        float scaleX = column0.length();
        float scaleY = column1.length();
        float scaleZ = column2.length();
        if (scaleX <= EPSILON || scaleY <= EPSILON || scaleZ <= EPSILON) {
            throw new GltfConversionException(role + " matrix is singular and cannot be decomposed to TRS");
        }
        column0.div(scaleX);
        column1.div(scaleY);
        column2.div(scaleZ);

        float determinant = column0.dot(new Vector3f(column1).cross(column2));
        if (determinant < 0.0f) {
            scaleX = -scaleX;
            column0.negate();
            determinant = -determinant;
        }
        if (Math.abs(column0.dot(column1)) > EPSILON
                || Math.abs(column0.dot(column2)) > EPSILON
                || Math.abs(column1.dot(column2)) > EPSILON
                || Math.abs(determinant - 1.0f) > 1.0E-4f) {
            throw new GltfConversionException(role + " matrix contains shear or a non-orthogonal basis");
        }

        Quaternionf rotation = new Matrix3f(column0, column1, column2)
                .getNormalizedRotation(new Quaternionf())
                .normalize();
        GltfNodeTransform transform = new GltfNodeTransform(
                vector(matrix[12], matrix[13], matrix[14]),
                rotation,
                new Vector3f(scaleX, scaleY, scaleZ)
        );
        float[] reconstructed = transform.toMatrix().get(new float[16]);
        for (int i = 0; i < matrix.length; i++) {
            if (Math.abs(reconstructed[i] - matrix[i]) > 1.0E-4) {
                throw new GltfConversionException(role + " matrix cannot be represented exactly as TRS");
            }
        }
        return transform;
    }

    private static Vector3f vector(double x, double y, double z) throws GltfConversionException {
        if (x < -Float.MAX_VALUE || x > Float.MAX_VALUE
                || y < -Float.MAX_VALUE || y > Float.MAX_VALUE
                || z < -Float.MAX_VALUE || z > Float.MAX_VALUE) {
            throw new GltfConversionException("glTF transform value exceeds float range");
        }
        return new Vector3f((float) x, (float) y, (float) z);
    }

    private static double[] orDefault(double[] value, double... fallback) {
        return value == null ? fallback : value;
    }

    private static void requireLength(double[] values, int length, String role) throws GltfConversionException {
        if (values.length != length) {
            throw new GltfConversionException(role + " length must be " + length);
        }
    }

    private static void requireFinite(double[] values, String role) throws GltfConversionException {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new GltfConversionException(role + " must contain only finite values");
            }
        }
    }
}
