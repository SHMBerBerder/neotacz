package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.render.GltfPbrDynamicTextureCache;
import de.javagl.jgltf.model.GltfConstants;

import java.util.Objects;

/** Shared admission rules for CPU derivation and the renderer that consumes its encoded images. */
public final class TextureVariantPolicy {
    static final int MAX_ENCODED_BYTES = GltfPbrDynamicTextureCache.MAX_ENCODED_IMAGE_BYTES;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private TextureVariantPolicy() { }

    public static void validateSampler(GltfSamplerData sampler) {
        Objects.requireNonNull(sampler, "sampler");
        if ((sampler.magFilter() != GltfConstants.GL_LINEAR && sampler.magFilter() != GltfConstants.GL_NEAREST)
                || (sampler.minFilter() != GltfConstants.GL_LINEAR && sampler.minFilter() != GltfConstants.GL_NEAREST
                    && sampler.minFilter() != GltfConstants.GL_LINEAR_MIPMAP_LINEAR
                    && sampler.minFilter() != GltfConstants.GL_NEAREST_MIPMAP_LINEAR)
                || !supportedWrap(sampler.wrapS()) || !supportedWrap(sampler.wrapT())) {
            throw new IllegalArgumentException(
                    "glTF native sampling supports NEAREST/LINEAR, NEAREST_MIPMAP_LINEAR/LINEAR_MIPMAP_LINEAR, "
                            + "and REPEAT/CLAMP_TO_EDGE; nearest-mip selection and MIRRORED_REPEAT are unsupported");
        }
    }

    public static boolean mipmapped(GltfSamplerData sampler) {
        validateSampler(sampler);
        return sampler.minFilter() == GltfConstants.GL_NEAREST_MIPMAP_LINEAR
                || sampler.minFilter() == GltfConstants.GL_LINEAR_MIPMAP_LINEAR;
    }

    private static boolean supportedWrap(int wrap) {
        return wrap == GltfConstants.GL_REPEAT || wrap == GltfConstants.GL_CLAMP_TO_EDGE;
    }

    static void validateEncodedLength(int length) {
        if (length <= 0 || length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("glTF image exceeds the renderer's " + MAX_ENCODED_BYTES + " encoded-byte limit");
        }
    }

    static void validateDerivedPng(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        validateEncodedLength(encoded.length);
        if (encoded.length < PNG_SIGNATURE.length) throw new IllegalArgumentException("Derived texture is not a PNG");
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (encoded[index] != PNG_SIGNATURE[index]) throw new IllegalArgumentException("Derived texture is not a PNG");
        }
    }
}
