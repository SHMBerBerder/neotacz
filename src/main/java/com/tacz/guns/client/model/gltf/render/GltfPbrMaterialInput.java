package com.tacz.guns.client.model.gltf.render;

import java.util.Objects;

/** Loader-neutral input used to create a complete PBR RenderType binding. */
public record GltfPbrMaterialInput(
        GltfPbrTextureSlots textures,
        GltfPbrMaterialFactors factors,
        GltfPbrAlphaMode alphaMode,
        boolean cull
) {
    public GltfPbrMaterialInput {
        textures = Objects.requireNonNullElse(textures, GltfPbrTextureSlots.empty());
        factors = Objects.requireNonNullElseGet(factors, GltfPbrMaterialFactors::defaults);
        alphaMode = Objects.requireNonNullElse(alphaMode, GltfPbrAlphaMode.OPAQUE);
    }
}
