package com.tacz.guns.client.model.gltf.render;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Render-only PBR binding. The loader side should supply five glTF texture maps plus three one-pixel
 * factor/parameter textures; null map slots use the backend fallbacks. The emissive-factor texture stores
 * linear emissive RGB and alphaCutoff in A. PBR parameters store normalScale divided by four in B.
 */
public record GltfPbrMaterial(
        Identifier baseColorTexture,
        Identifier metallicRoughnessTexture,
        Identifier normalTexture,
        Identifier occlusionTexture,
        Identifier emissiveTexture,
        Identifier baseColorFactorTexture,
        Identifier emissiveFactorTexture,
        Identifier pbrParametersTexture,
        GltfPbrAlphaMode alphaMode,
        boolean cull,
        GltfPbrSamplers samplers
) {
    public GltfPbrMaterial {
        baseColorTexture = fallback(baseColorTexture, GltfPbrFallbackTextures.BASE_COLOR);
        metallicRoughnessTexture = fallback(metallicRoughnessTexture, GltfPbrFallbackTextures.METALLIC_ROUGHNESS);
        normalTexture = fallback(normalTexture, GltfPbrFallbackTextures.NORMAL);
        occlusionTexture = fallback(occlusionTexture, GltfPbrFallbackTextures.OCCLUSION);
        emissiveTexture = fallback(emissiveTexture, GltfPbrFallbackTextures.EMISSIVE);
        baseColorFactorTexture = fallback(baseColorFactorTexture, GltfPbrFallbackTextures.BASE_COLOR_FACTOR);
        emissiveFactorTexture = fallback(emissiveFactorTexture, GltfPbrFallbackTextures.EMISSIVE_FACTOR);
        pbrParametersTexture = fallback(pbrParametersTexture, GltfPbrFallbackTextures.PBR_PARAMETERS);
        alphaMode = Objects.requireNonNullElse(alphaMode, GltfPbrAlphaMode.OPAQUE);
        samplers = Objects.requireNonNullElse(samplers, GltfPbrSamplers.DEFAULT);
    }

    public GltfPbrMaterial(Identifier baseColorTexture, Identifier metallicRoughnessTexture,
                          Identifier normalTexture, Identifier occlusionTexture, Identifier emissiveTexture,
                          Identifier baseColorFactorTexture, Identifier emissiveFactorTexture,
                          Identifier pbrParametersTexture, GltfPbrAlphaMode alphaMode, boolean cull) {
        this(baseColorTexture, metallicRoughnessTexture, normalTexture, occlusionTexture, emissiveTexture,
                baseColorFactorTexture, emissiveFactorTexture, pbrParametersTexture, alphaMode, cull,
                GltfPbrSamplers.DEFAULT);
    }

    public static GltfPbrMaterial opaque(Identifier baseColorTexture) {
        return new GltfPbrMaterial(
                baseColorTexture,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                GltfPbrAlphaMode.OPAQUE,
                true
        );
    }

    private static Identifier fallback(Identifier value, Identifier fallback) {
        return value == null ? fallback : value;
    }
}
