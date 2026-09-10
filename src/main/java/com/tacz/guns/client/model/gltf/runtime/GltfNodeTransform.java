package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

public final class GltfNodeTransform {
    private static final float EPSILON = 1.0E-8f;
    private static final GltfNodeTransform IDENTITY = new GltfNodeTransform(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(1.0f, 1.0f, 1.0f)
    );

    private final Vector3f translation;
    private final Quaternionf rotation;
    private final Vector3f scale;

    public GltfNodeTransform(Vector3fc translation, Quaternionfc rotation, Vector3fc scale) {
        this.translation = copyFinite(Objects.requireNonNull(translation, "translation"), "translation");
        this.rotation = copyUnit(Objects.requireNonNull(rotation, "rotation"));
        this.scale = copyFinite(Objects.requireNonNull(scale, "scale"), "scale");
    }

    public static GltfNodeTransform identity() {
        return IDENTITY;
    }

    public static GltfNodeTransform translation(float x, float y, float z) {
        return new GltfNodeTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f));
    }

    public Matrix4f toMatrix() {
        return new Matrix4f().translationRotateScale(translation, rotation, scale);
    }

    public Vector3f translation() {
        return new Vector3f(translation);
    }

    public Quaternionf rotation() {
        return new Quaternionf(rotation);
    }

    public Vector3f scale() {
        return new Vector3f(scale);
    }

    private static Vector3f copyFinite(Vector3fc value, String name) {
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3f(value);
    }

    private static Quaternionf copyUnit(Quaternionfc value) {
        float x = value.x();
        float y = value.y();
        float z = value.z();
        float w = value.w();
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w)) {
            throw new IllegalArgumentException("rotation must be finite");
        }
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (lengthSquared < EPSILON) {
            throw new IllegalArgumentException("rotation must not be zero length");
        }
        return new Quaternionf(value).normalize();
    }
}
