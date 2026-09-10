package com.tacz.guns.client.model.gltf.render;

/** The five texture slots defined by the core glTF metallic-roughness material. */
public record GltfPbrTextureSlots(
        GltfPbrTextureSource baseColor,
        GltfPbrTextureSource metallicRoughness,
        GltfPbrTextureSource normal,
        GltfPbrTextureSource occlusion,
        GltfPbrTextureSource emissive
) {
    private static final GltfPbrTextureSlots EMPTY = new GltfPbrTextureSlots(null, null, null, null, null);

    public static GltfPbrTextureSlots empty() {
        return EMPTY;
    }
}
