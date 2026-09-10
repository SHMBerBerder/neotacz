package com.tacz.guns.client.model.gltf.render;

/**
 * One RGBA8 texel used to pass material factors through Minecraft's texture-only RenderType setup.
 */
public record GltfPbrFactorEncoding(int red, int green, int blue, int alpha) {
    public GltfPbrFactorEncoding {
        validateChannel("red", red);
        validateChannel("green", green);
        validateChannel("blue", blue);
        validateChannel("alpha", alpha);
    }

    public int argb() {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static void validateChannel(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be an unsigned 8-bit value");
        }
    }
}
