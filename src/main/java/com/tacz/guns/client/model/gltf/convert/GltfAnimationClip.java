package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfAnimation;

import java.util.Objects;

public record GltfAnimationClip(String name, float durationSeconds, GltfAnimation animation) {
    public GltfAnimationClip {
        name = name == null ? "" : name;
        if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
            throw new IllegalArgumentException("durationSeconds must be finite and non-negative");
        }
        Objects.requireNonNull(animation, "animation");
    }
}
