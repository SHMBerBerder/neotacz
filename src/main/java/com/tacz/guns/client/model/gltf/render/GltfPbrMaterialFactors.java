package com.tacz.guns.client.model.gltf.render;

import java.util.Objects;

/**
 * Core glTF material factors supported by the V1 PBR backend.
 *
 * <p>Minecraft 26.2 does not expose per-material uniforms on a RenderType, so these values are
 * encoded as nearest-value RGBA8 texels. This limits factor precision to 1/255. Normal scale is
 * mapped from [0, 4] to [0, 1]; values outside that range are rejected rather than silently
 * changing the material.</p>
 */
public record GltfPbrMaterialFactors(
        float[] baseColorFactor,
        float metallicFactor,
        float roughnessFactor,
        float normalScale,
        float occlusionStrength,
        float[] emissiveFactor,
        float alphaCutoff
) {
    public static final float MAX_NORMAL_SCALE = 4.0f;

    public GltfPbrMaterialFactors {
        baseColorFactor = copyVector("baseColorFactor", baseColorFactor, 4);
        emissiveFactor = copyVector("emissiveFactor", emissiveFactor, 3);
        validateUnorm("baseColorFactor", baseColorFactor);
        validateUnorm("metallicFactor", metallicFactor);
        validateUnorm("roughnessFactor", roughnessFactor);
        validateRange("normalScale", normalScale, 0.0f, MAX_NORMAL_SCALE);
        validateUnorm("occlusionStrength", occlusionStrength);
        validateUnorm("emissiveFactor", emissiveFactor);
        validateUnorm("alphaCutoff", alphaCutoff);
    }

    public static GltfPbrMaterialFactors defaults() {
        return new GltfPbrMaterialFactors(
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                new float[]{0.0f, 0.0f, 0.0f},
                0.5f
        );
    }

    @Override
    public float[] baseColorFactor() {
        return baseColorFactor.clone();
    }

    @Override
    public float[] emissiveFactor() {
        return emissiveFactor.clone();
    }

    public GltfPbrFactorEncoding baseColorEncoding() {
        return encode(baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3]);
    }

    public GltfPbrFactorEncoding emissiveEncoding() {
        return encode(emissiveFactor[0], emissiveFactor[1], emissiveFactor[2], alphaCutoff);
    }

    public GltfPbrFactorEncoding parametersEncoding() {
        return encode(metallicFactor, roughnessFactor, normalScale / MAX_NORMAL_SCALE, occlusionStrength);
    }

    private static GltfPbrFactorEncoding encode(float red, float green, float blue, float alpha) {
        return new GltfPbrFactorEncoding(unorm8(red), unorm8(green), unorm8(blue), unorm8(alpha));
    }

    private static int unorm8(float value) {
        return Math.round(value * 255.0f);
    }

    private static float[] copyVector(String name, float[] values, int length) {
        Objects.requireNonNull(values, name);
        if (values.length != length) {
            throw new IllegalArgumentException(name + " must contain " + length + " values");
        }
        return values.clone();
    }

    private static void validateUnorm(String name, float... values) {
        for (float value : values) {
            validateRange(name, value, 0.0f, 1.0f);
        }
    }

    private static void validateRange(String name, float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in [" + minimum + ", " + maximum + "]");
        }
    }
}
