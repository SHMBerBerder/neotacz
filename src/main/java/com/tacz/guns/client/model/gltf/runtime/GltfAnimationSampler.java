package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Objects;

public final class GltfAnimationSampler {
    private static final float EPSILON = 1.0E-8f;

    private final GltfAnimationPath path;
    private final GltfInterpolation interpolation;
    private final int componentCount;
    private final float[] times;
    private final float[] values;

    private GltfAnimationSampler(GltfAnimationPath path, GltfInterpolation interpolation, int componentCount, float[] times, float[] values) {
        this.path = Objects.requireNonNull(path, "path");
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        this.componentCount = componentCount;
        if (componentCount <= 0) {
            throw new IllegalArgumentException("componentCount must be positive");
        }
        this.times = copyTimes(times);
        if (interpolation == GltfInterpolation.CUBICSPLINE && this.times.length < 2) {
            throw new IllegalArgumentException("CUBICSPLINE animation requires at least two keyframes");
        }
        this.values = copyValues(values);
        int valuesPerKey = interpolation == GltfInterpolation.CUBICSPLINE ? componentCount * 3 : componentCount;
        if (this.values.length != this.times.length * valuesPerKey) {
            throw new IllegalArgumentException("animation values length does not match times and interpolation");
        }
    }

    public static GltfAnimationSampler translation(GltfInterpolation interpolation, float[] times, float[] values) {
        return new GltfAnimationSampler(GltfAnimationPath.TRANSLATION, interpolation, 3, times, values);
    }

    public static GltfAnimationSampler scale(GltfInterpolation interpolation, float[] times, float[] values) {
        return new GltfAnimationSampler(GltfAnimationPath.SCALE, interpolation, 3, times, values);
    }

    public static GltfAnimationSampler weights(int weightCount, GltfInterpolation interpolation, float[] times, float[] values) {
        return new GltfAnimationSampler(GltfAnimationPath.WEIGHTS, interpolation, weightCount, times, values);
    }

    public static GltfAnimationSampler rotation(GltfInterpolation interpolation, float[] times, float[] values) {
        return new GltfAnimationSampler(GltfAnimationPath.ROTATION, interpolation, 4, times, values);
    }

    public GltfAnimationPath path() {
        return path;
    }

    public GltfInterpolation interpolation() {
        return interpolation;
    }

    public int componentCount() {
        return componentCount;
    }

    public float[] times() {
        return times.clone();
    }

    public Vector3f sampleVector3(float time) {
        if (componentCount != 3 || path == GltfAnimationPath.ROTATION) {
            throw new IllegalStateException("sampler does not produce Vector3 values");
        }
        float[] sample = sampleFloats(time);
        return new Vector3f(sample[0], sample[1], sample[2]);
    }

    public float[] sampleFloats(float time) {
        if (path == GltfAnimationPath.ROTATION) {
            throw new IllegalStateException("use sampleRotation for rotation samplers");
        }
        if (times.length == 1 || time <= times[0]) {
            return keyValue(0);
        }
        if (time >= times[times.length - 1]) {
            return keyValue(times.length - 1);
        }
        int key = lowerKey(time);
        if (interpolation == GltfInterpolation.STEP) {
            return keyValue(key);
        }
        float deltaTime = times[key + 1] - times[key];
        float alpha = (time - times[key]) / deltaTime;
        if (interpolation == GltfInterpolation.LINEAR) {
            return lerp(keyValue(key), keyValue(key + 1), alpha);
        }
        return cubic(key, alpha, deltaTime);
    }

    public Quaternionf sampleRotation(float time) {
        if (path != GltfAnimationPath.ROTATION) {
            throw new IllegalStateException("sampler does not produce rotations");
        }
        if (times.length == 1 || time <= times[0]) {
            return quaternion(keyValue(0));
        }
        if (time >= times[times.length - 1]) {
            return quaternion(keyValue(times.length - 1));
        }
        int key = lowerKey(time);
        if (interpolation == GltfInterpolation.STEP) {
            return quaternion(keyValue(key));
        }
        float deltaTime = times[key + 1] - times[key];
        float alpha = (time - times[key]) / deltaTime;
        if (interpolation == GltfInterpolation.LINEAR) {
            Quaternionf from = quaternion(keyValue(key));
            Quaternionf to = quaternion(keyValue(key + 1));
            return from.slerp(to, alpha).normalize();
        }
        return quaternion(cubic(key, alpha, deltaTime));
    }

    private int lowerKey(float time) {
        int index = Arrays.binarySearch(times, time);
        if (index >= 0) {
            return Math.min(index, times.length - 2);
        }
        return -index - 2;
    }

    private float[] keyValue(int keyIndex) {
        float[] result = new float[componentCount];
        int offset = valueOffset(keyIndex);
        System.arraycopy(values, offset, result, 0, componentCount);
        return result;
    }

    private float[] keyInTangent(int keyIndex) {
        float[] result = new float[componentCount];
        int offset = keyIndex * componentCount * 3;
        System.arraycopy(values, offset, result, 0, componentCount);
        return result;
    }

    private float[] keyOutTangent(int keyIndex) {
        float[] result = new float[componentCount];
        int offset = keyIndex * componentCount * 3 + componentCount * 2;
        System.arraycopy(values, offset, result, 0, componentCount);
        return result;
    }

    private int valueOffset(int keyIndex) {
        if (interpolation == GltfInterpolation.CUBICSPLINE) {
            return keyIndex * componentCount * 3 + componentCount;
        }
        return keyIndex * componentCount;
    }

    private float[] cubic(int key, float alpha, float deltaTime) {
        float[] p0 = keyValue(key);
        float[] m0 = keyOutTangent(key);
        float[] p1 = keyValue(key + 1);
        float[] m1 = keyInTangent(key + 1);
        float t2 = alpha * alpha;
        float t3 = t2 * alpha;
        float h00 = 2.0f * t3 - 3.0f * t2 + 1.0f;
        float h10 = t3 - 2.0f * t2 + alpha;
        float h01 = -2.0f * t3 + 3.0f * t2;
        float h11 = t3 - t2;
        float[] result = new float[componentCount];
        for (int i = 0; i < componentCount; i++) {
            result[i] = h00 * p0[i] + h10 * m0[i] * deltaTime + h01 * p1[i] + h11 * m1[i] * deltaTime;
        }
        return result;
    }

    private static float[] lerp(float[] from, float[] to, float alpha) {
        float[] result = new float[from.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = from[i] + (to[i] - from[i]) * alpha;
        }
        return result;
    }

    private static Quaternionf quaternion(float[] xyzw) {
        float lengthSquared = 0.0f;
        for (float value : xyzw) {
            lengthSquared += value * value;
        }
        if (lengthSquared < EPSILON) {
            throw new IllegalArgumentException("rotation sample must not be zero length");
        }
        return new Quaternionf(xyzw[0], xyzw[1], xyzw[2], xyzw[3]).normalize();
    }

    private static float[] copyTimes(float[] times) {
        if (times == null || times.length == 0) {
            throw new IllegalArgumentException("times must not be empty");
        }
        float[] copy = copyValues(times);
        if (copy[0] < 0.0f) {
            throw new IllegalArgumentException("animation times must be non-negative");
        }
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] <= copy[i - 1]) {
                throw new IllegalArgumentException("times must be strictly increasing");
            }
        }
        return copy;
    }

    private static float[] copyValues(float[] values) {
        if (values == null) {
            throw new NullPointerException("values");
        }
        float[] copy = values.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("animation values must be finite");
            }
        }
        return copy;
    }
}
