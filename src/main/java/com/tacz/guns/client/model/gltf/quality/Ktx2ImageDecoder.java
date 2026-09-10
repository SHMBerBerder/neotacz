package com.tacz.guns.client.model.gltf.quality;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.ktx.KTX;
import org.lwjgl.util.ktx.ktxTexture;
import org.lwjgl.util.ktx.ktxTexture2;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** CPU-only Basis transcoding; a permit covers the native image's entire owned lifetime. */
public final class Ktx2ImageDecoder {
    public static final String VERSION = "libktx-4.4.2-rgba32-base-v1";
    private static final Semaphore COLD_IMAGE = new Semaphore(1, true);
    private static final AtomicInteger LIVE_TEXTURES = new AtomicInteger();
    private static final AtomicLong TRANSCODES = new AtomicLong();

    private Ktx2ImageDecoder() { }

    public static int liveTextures() { return LIVE_TEXTURES.get(); }
    public static long transcodes() { return TRANSCODES.get(); }

    public static boolean matches(byte[] encoded) { return Ktx2ImageHeader.matches(encoded); }

    /** Complete bounded admission and material-role validation, without native decoding. */
    public static TextureImageFilter.Dimensions inspect(byte[] encoded, TextureImageFilter.Role role) {
        var header = checkedHeader(encoded, role);
        return new TextureImageFilter.Dimensions(header.width(), header.height());
    }

    /** Returns an independently owned RGBA image; the caller must close or transfer its ownership. */
    public static NativeImage decodeToNativeImage(byte[] encoded, TextureImageFilter.Role role,
                                                 BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        TextureImageFilter.checkCancelled(cancelled);
        var header = checkedHeader(encoded, role);
        NativeImage image = null;
        boolean transferred = false;
        try {
            try (Decoded decoded = decode(encoded, header, cancelled)) {
                image = new NativeImage(decoded.width(), decoded.height(), false);
                // NativeImage cannot borrow libktx pData: its close uses a different owner/free.
                // Both buffers are tight RGBA8, so copy directly without PNG or heap pixel arrays.
                image.getPixelBytes().put(decoded.pixels());
                TextureImageFilter.checkCancelled(cancelled);
            }
            // Destroy libktx and release its single-image permit before GPU upload/mip generation.
            transferred = true;
            return image;
        } finally {
            if (!transferred && image != null) image.close();
        }
    }

    private static Ktx2ImageHeader checkedHeader(byte[] encoded, TextureImageFilter.Role role) {
        Objects.requireNonNull(role, "role");
        var header = Ktx2ImageHeader.read(encoded);
        header.validateUsage(role);
        return header;
    }

    static Decoded decode(byte[] encoded, Ktx2ImageHeader header, BooleanSupplier cancelled) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(cancelled, "cancelled");
        TextureImageFilter.checkCancelled(cancelled);
        // Revalidate even when a caller supplies a header: it must describe these exact bytes.
        if (!header.equals(Ktx2ImageHeader.read(encoded))) throw new IllegalArgumentException("KTX2 header changed before decode");
        acquire(cancelled);
        ByteBuffer source = null;
        ktxTexture2 texture = null;
        boolean transferred = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            TextureImageFilter.checkCancelled(cancelled);
            source = MemoryUtil.memAlloc(encoded.length).put(encoded).flip();
            var output = stack.callocPointer(1);
            check(KTX.ktxTexture2_CreateFromMemory(source, KTX.KTX_TEXTURE_CREATE_LOAD_IMAGE_DATA_BIT, output), "create");
            if (output.get(0) == 0) throw new IllegalStateException("libktx returned a null texture");
            texture = ktxTexture2.create(output.get(0));
            LIVE_TEXTURES.incrementAndGet();
            // LOAD_IMAGE_DATA owns the payload and closes its source stream before returning.
            MemoryUtil.memFree(source);
            source = null;
            TextureImageFilter.checkCancelled(cancelled);
            if (texture.baseWidth() != header.width() || texture.baseHeight() != header.height()
                    || texture.numLevels() != header.levels().size() || texture.numDimensions() != 2
                    || texture.isArray() || texture.isCubemap() || texture.isVideo()
                    || texture.numLayers() != 1 || texture.numFaces() != 1
                    || texture.dataSize() != header.inflatedBytes()) {
                throw new IllegalArgumentException("libktx loaded metadata differs from its bounded header");
            }
            if (!KTX.ktxTexture2_NeedsTranscoding(texture)) throw new IllegalArgumentException("KTX2 is not Basis transcodable");
            TRANSCODES.incrementAndGet();
            check(KTX.ktxTexture2_TranscodeBasis(texture, KTX.KTX_TTF_RGBA32, 0), "transcode");
            TextureImageFilter.checkCancelled(cancelled);
            if (texture.dataSize() != header.rgbaBytes() || texture.isCompressed()
                    || (texture.vkFormat() != 37 && texture.vkFormat() != 43)) {
                throw new IllegalArgumentException("libktx produced unexpected RGBA data");
            }
            var offset = stack.callocPointer(1);
            check(KTX.ktxTexture2_GetImageOffset(texture, 0, 0, 0, offset), "base mip offset");
            int bytes = Math.multiplyExact(Math.multiplyExact(header.width(), header.height()), 4);
            if (offset.get(0) < 0 || offset.get(0) > texture.dataSize() - bytes
                    || KTX.ktxTexture_GetRowPitch(ktxTexture.create(texture.address()), 0) != header.width() * 4) {
                throw new IllegalArgumentException("libktx RGBA base mip range or row pitch is invalid");
            }
            ByteBuffer pixels = texture.pData().slice(Math.toIntExact(offset.get(0)), bytes);
            Decoded result = new Decoded(texture, pixels, header.width(), header.height());
            transferred = true;
            return result;
        } finally {
            MemoryUtil.memFree(source);
            if (!transferred) {
                try { destroy(texture); }
                finally { COLD_IMAGE.release(); }
            }
        }
    }

    private static void acquire(BooleanSupplier cancelled) {
        try {
            while (!COLD_IMAGE.tryAcquire(20, TimeUnit.MILLISECONDS)) TextureImageFilter.checkCancelled(cancelled);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("KTX2 transcode interrupted");
        }
    }

    private static void check(int code, String operation) {
        if (code != KTX.KTX_SUCCESS) throw new IllegalArgumentException("KTX2 " + operation + " failed: " + KTX.ktxErrorString(code));
    }

    private static void destroy(ktxTexture2 texture) {
        if (texture != null) {
            try { KTX.ktxTexture2_Destroy(texture); }
            finally { LIVE_TEXTURES.decrementAndGet(); }
        }
    }

    static final class Decoded implements TextureImageFilter.Pixels {
        private ktxTexture2 texture;
        private ByteBuffer pixels;
        private final int width, height;

        private Decoded(ktxTexture2 texture, ByteBuffer pixels, int width, int height) {
            this.texture = texture;
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public ByteBuffer pixels() {
            if (pixels == null) throw new IllegalStateException("KTX2 decoded pixels are closed");
            return pixels.duplicate();
        }
        @Override public void close() {
            var owned = texture;
            texture = null;
            pixels = null;
            if (owned != null) {
                try { destroy(owned); }
                finally { COLD_IMAGE.release(); }
            }
        }
    }
}
