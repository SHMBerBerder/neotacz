package com.tacz.guns.client.model.gltf.convert;

import java.util.Objects;

public final class GltfPbrMaterialData {
    private final String name;
    private final float[] baseColorFactor;
    private final float metallicFactor;
    private final float roughnessFactor;
    private final float[] emissiveFactor;
    private final float normalScale;
    private final float occlusionStrength;
    private final GltfAlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;
    private final GltfTextureBinding baseColorTexture;
    private final GltfTextureBinding metallicRoughnessTexture;
    private final GltfTextureBinding normalTexture;
    private final GltfTextureBinding occlusionTexture;
    private final GltfTextureBinding emissiveTexture;

    public GltfPbrMaterialData(
            String name,
            float[] baseColorFactor,
            float metallicFactor,
            float roughnessFactor,
            float[] emissiveFactor,
            float normalScale,
            float occlusionStrength,
            GltfAlphaMode alphaMode,
            float alphaCutoff,
            boolean doubleSided,
            GltfTextureBinding baseColorTexture,
            GltfTextureBinding metallicRoughnessTexture,
            GltfTextureBinding normalTexture,
            GltfTextureBinding occlusionTexture,
            GltfTextureBinding emissiveTexture
    ) {
        this.name = name == null ? "" : name;
        this.baseColorFactor = copyFactor(baseColorFactor, 4, "baseColorFactor");
        this.metallicFactor = requireFinite(metallicFactor, "metallicFactor");
        this.roughnessFactor = requireFinite(roughnessFactor, "roughnessFactor");
        this.emissiveFactor = copyFactor(emissiveFactor, 3, "emissiveFactor");
        this.normalScale = requireFinite(normalScale, "normalScale");
        this.occlusionStrength = requireFinite(occlusionStrength, "occlusionStrength");
        this.alphaMode = Objects.requireNonNull(alphaMode, "alphaMode");
        this.alphaCutoff = requireFinite(alphaCutoff, "alphaCutoff");
        this.doubleSided = doubleSided;
        this.baseColorTexture = baseColorTexture;
        this.metallicRoughnessTexture = metallicRoughnessTexture;
        this.normalTexture = normalTexture;
        this.occlusionTexture = occlusionTexture;
        this.emissiveTexture = emissiveTexture;
    }

    public static GltfPbrMaterialData defaultMaterial() {
        return new GltfPbrMaterialData(
                "",
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f,
                1.0f,
                new float[3],
                1.0f,
                1.0f,
                GltfAlphaMode.OPAQUE,
                0.5f,
                false,
                null,
                null,
                null,
                null,
                null
        );
    }

    public String name() {
        return name;
    }

    public float[] baseColorFactor() {
        return baseColorFactor.clone();
    }

    public float metallicFactor() {
        return metallicFactor;
    }

    public float roughnessFactor() {
        return roughnessFactor;
    }

    public float[] emissiveFactor() {
        return emissiveFactor.clone();
    }

    public float normalScale() {
        return normalScale;
    }

    public float occlusionStrength() {
        return occlusionStrength;
    }

    public GltfAlphaMode alphaMode() {
        return alphaMode;
    }

    public float alphaCutoff() {
        return alphaCutoff;
    }

    public boolean doubleSided() {
        return doubleSided;
    }

    public GltfTextureBinding baseColorTexture() {
        return baseColorTexture;
    }

    public GltfTextureBinding metallicRoughnessTexture() {
        return metallicRoughnessTexture;
    }

    public GltfTextureBinding normalTexture() {
        return normalTexture;
    }

    public GltfTextureBinding occlusionTexture() {
        return occlusionTexture;
    }

    public GltfTextureBinding emissiveTexture() {
        return emissiveTexture;
    }

    private static float[] copyFactor(float[] values, int expectedLength, String name) {
        if (values == null || values.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength);
        }
        float[] copy = values.clone();
        for (float value : copy) {
            requireFinite(value, name);
        }
        return copy;
    }

    private static float requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }
}
