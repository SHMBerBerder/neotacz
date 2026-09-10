package com.tacz.guns.client.model.gltf.render;

import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;
import com.tacz.guns.client.model.gltf.quality.TextureVariantPolicy;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * An encoded PNG, JPEG, or Basis KTX2 image and its prepared cache identity.
 * Validation and full-content hashing happen once here, never on the render hot path.
 */
public final class GltfPbrTextureSource {
    private final Identifier resourceKey;
    private final GltfImageData image;
    private final Settings settings;
    private final GltfPbrDynamicTextureCache.PreparedImage preparedImage;

    public GltfPbrTextureSource(Identifier resourceKey, byte[] encodedImage) {
        this(resourceKey, new GltfImageData("", "", "", encodedImage));
    }

    public GltfPbrTextureSource(Identifier resourceKey, GltfImageData image) {
        this(resourceKey, image, Settings.DEFAULT);
    }

    public GltfPbrTextureSource(Identifier resourceKey, GltfImageData image, Settings settings) {
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        // The converted asset already owns immutable encoded bytes. Keep that owner, not another
        // resident copy per renderer; only hashing and a cache-miss decode need temporary copies.
        this.image = Objects.requireNonNull(image, "image");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.preparedImage = GltfPbrDynamicTextureCache.prepareImage(resourceKey, image.encodedBytes(), settings);
    }

    public Settings settings() { return settings; }

    public int mipLevels() {
        return GltfTextureMipmaps.levelCount(imageHeader().width(), imageHeader().height(), settings.mipmapped());
    }

    public Identifier resourceKey() {
        return resourceKey;
    }

    public byte[] encodedImage() {
        return image.encodedBytes();
    }

    Identifier dynamicId() {
        return preparedImage.dynamicId();
    }

    long decodedBytes() {
        return preparedImage.decodedBytes();
    }

    GltfPbrDynamicTextureCache.ImageHeader imageHeader() {
        return preparedImage.header();
    }

    /** Sampler state is independent of GPU image ownership; role/alpha affect generated mip pixels. */
    public record Settings(GltfSamplerData sampler, TextureImageFilter.Role role, GltfAlphaMode alphaMode,
                           float alphaCutoff, float alphaFactor) {
        public static final Settings DEFAULT = new Settings(GltfSamplerData.DEFAULT,
                TextureImageFilter.Role.BASE_COLOR, GltfAlphaMode.OPAQUE, .5f, 1);

        public Settings {
            // Reuse the same admission and alpha rules as offline quality derivation.
            new TextureImageFilter.Spec(role, GltfPbrDynamicTextureCache.MAX_IMAGE_DIMENSION,
                    alphaMode, alphaCutoff, alphaFactor, sampler);
        }

        public boolean mipmapped() { return TextureVariantPolicy.mipmapped(sampler); }

        TextureImageFilter.Spec filterSpec() {
            return new TextureImageFilter.Spec(role, GltfPbrDynamicTextureCache.MAX_IMAGE_DIMENSION,
                    alphaMode, alphaCutoff, alphaFactor, sampler);
        }

        String mipIdentity(int levels) {
            if (levels == 1) return "";
            return TextureImageFilter.VERSION + ":native-prefix-v1:" + levels + ":" + role + ":" + alphaMode
                    + ":" + Float.toHexString(alphaCutoff) + ":" + Float.toHexString(alphaFactor)
                    + ":" + sampler.wrapS() + ":" + sampler.wrapT();
        }
    }
}
