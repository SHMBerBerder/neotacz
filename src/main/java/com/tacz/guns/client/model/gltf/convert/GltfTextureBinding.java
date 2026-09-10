package com.tacz.guns.client.model.gltf.convert;

import java.util.Objects;

public record GltfTextureBinding(int imageIndex, int texCoord, GltfSamplerData sampler) {
    public GltfTextureBinding {
        if (imageIndex < 0) {
            throw new IllegalArgumentException("imageIndex must be non-negative");
        }
        if (texCoord != 0) {
            throw new IllegalArgumentException("only TEXCOORD_0 is supported");
        }
        Objects.requireNonNull(sampler, "sampler");
    }
}
