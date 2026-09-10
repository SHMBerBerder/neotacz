package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;

/** An immutable GPU texture; unlike DynamicTexture, it has no persistent CPU pixel allocation. */
final class UploadOnlyTexture extends AbstractTexture {
    /** Takes ownership of the image, including upload failure. */
    UploadOnlyTexture(String label, NativeImage image) {
        this(label, image, GltfPbrTextureSource.Settings.DEFAULT);
    }

    UploadOnlyTexture(String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
        // MC ReloadableTexture.apply uses the same lifetime: writeToTexture consumes/copies the
        // pixels before returning. Keeping a NativeImage afterward doubles 8K texture residency.
        try (image) {
            RenderSystem.assertOnRenderThread();
            GpuDevice device = RenderSystem.getDevice();
            int levels = GltfTextureMipmaps.levelCount(image.getWidth(), image.getHeight(), settings.mipmapped());
            texture = device.createTexture(() -> label, 5, GpuFormat.RGBA8_UNORM,
                    image.getWidth(), image.getHeight(), 1, levels);
            sampler = GltfPbrSamplers.resolve(settings.sampler());
            textureView = device.createTextureView(texture);
            var encoder = device.createCommandEncoder();
            GltfTextureMipmaps.upload(image, settings,
                    (level, pixels) -> encoder.writeToTexture(texture, pixels, level, 0, 0, 0));
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        // Relinquish both handles even when a backend cleanup fails; repeated release is harmless.
        var ownedView = textureView;
        var ownedTexture = texture;
        textureView = null;
        texture = null;
        Throwable failure = null;
        try {
            if (ownedView != null) {
                ownedView.close();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            if (ownedTexture != null) {
                ownedTexture.close();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
