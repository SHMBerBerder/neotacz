package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;

import java.util.Objects;

/** Bounded native mip generation: at most the current and next CPU images coexist. */
final class GltfTextureMipmaps {
    private GltfTextureMipmaps() { }

    static int levelCount(int width, int height, boolean mipmapped) {
        if (width < 1 || height < 1 || width > GltfPbrDynamicTextureCache.MAX_IMAGE_DIMENSION
                || height > GltfPbrDynamicTextureCache.MAX_IMAGE_DIMENSION) {
            throw new IllegalArgumentException("Invalid glTF mip dimensions");
        }
        // MC 26.2 validates upload extents using width/height >> level, without max(1,...).
        // Expose only this legal prefix through the GPU texture/view, never a zero-sized tail.
        return mipmapped ? 32 - Integer.numberOfLeadingZeros(Math.min(width, height)) : 1;
    }

    static long gpuBytes(int width, int height, boolean mipmapped) {
        int levels = levelCount(width, height, mipmapped);
        long bytes = 0;
        for (int level = 0; level < levels; level++) {
            bytes = Math.addExact(bytes, (long) (width >> level) * (height >> level) * 4);
        }
        return bytes;
    }

    /** Takes ownership of base, including validation/filter/upload failure. Upload must consume pixels synchronously. */
    static void upload(NativeImage base, GltfPbrTextureSource.Settings settings, LevelUploader uploader) {
        NativeImage current = Objects.requireNonNull(base, "base");
        NativeImage next = null;
        try {
            Objects.requireNonNull(uploader, "uploader");
            int width = current.getWidth(), height = current.getHeight();
            int levels = levelCount(width, height, settings.mipmapped());
            var spec = settings.filterSpec();
            double coverage = levels > 1 ? TextureImageFilter.maskCoverage(current.getPixelBytes(), spec) : 0;
            for (int level = 0; level < levels; level++) {
                uploader.upload(level, current);
                if (level + 1 < levels) {
                    int nextWidth = width >> (level + 1), nextHeight = height >> (level + 1);
                    next = new NativeImage(nextWidth, nextHeight, false);
                    TextureImageFilter.resizePixels(current.getPixelBytes(),
                            new TextureImageFilter.Dimensions(current.getWidth(), current.getHeight()),
                            next.getPixelBytes(), new TextureImageFilter.Dimensions(nextWidth, nextHeight), spec, coverage);
                }
                current.close();
                current = next;
                next = null;
            }
        } finally {
            if (next != null) next.close();
            if (current != null) current.close();
        }
    }

    @FunctionalInterface
    interface LevelUploader {
        void upload(int level, NativeImage image);
    }
}
