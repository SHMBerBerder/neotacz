package com.tacz.guns.client.model.gltf.runtime;

import java.util.Objects;

public record GltfAnimationChannel(int nodeIndex, GltfAnimationSampler sampler) {
    public GltfAnimationChannel {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be non-negative");
        }
        Objects.requireNonNull(sampler, "sampler");
    }

    public GltfAnimationPath path() {
        return sampler.path();
    }
}
