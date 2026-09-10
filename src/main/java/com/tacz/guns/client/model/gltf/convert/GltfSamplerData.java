package com.tacz.guns.client.model.gltf.convert;

import de.javagl.jgltf.model.GltfConstants;

public record GltfSamplerData(int magFilter, int minFilter, int wrapS, int wrapT) {
    public static final GltfSamplerData DEFAULT = new GltfSamplerData(
            GltfConstants.GL_LINEAR,
            GltfConstants.GL_LINEAR,
            GltfConstants.GL_REPEAT,
            GltfConstants.GL_REPEAT
    );
}
