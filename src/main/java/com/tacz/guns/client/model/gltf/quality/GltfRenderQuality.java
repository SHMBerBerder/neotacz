package com.tacz.guns.client.model.gltf.quality;

/** Immutable settings captured once per model generation, independent of hard resource budgets. */
public record GltfRenderQuality(int textureMaxSize, int lodLevel, float triangleRatio) {
    public GltfRenderQuality {
        if (textureMaxSize < 512 || textureMaxSize > 8192
                || Integer.bitCount(textureMaxSize) != 1) {
            throw new IllegalArgumentException("Texture size must be a power of two from 512 to 8192");
        }
        if (lodLevel < 0 || lodLevel > 4) {
            throw new IllegalArgumentException("Authored LOD level must be between 0 and 4");
        }
        if (!Float.isFinite(triangleRatio) || triangleRatio < .1f || triangleRatio > 1f) {
            throw new IllegalArgumentException("Triangle ratio must be between 0.1 and 1");
        }
    }
}
