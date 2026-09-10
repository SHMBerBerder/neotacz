package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import org.lwjgl.stb.STBIR_RESIZE;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.lwjgl.stb.STBImageResize.*;

/** Role-aware resizing using the existing STB v2 filter, not a per-pixel nearest approximation. */
public final class TextureImageFilter {
    public static final String VERSION = "stbir2-mitchell-role-alpha-coverage-wrap-clamp-v2";
    private static final int MAX_DIMENSION = 8192;

    public enum Role { BASE_COLOR, EMISSIVE, NORMAL, ORM, OCCLUSION }

    public record Spec(Role role, int maxSize, GltfAlphaMode alphaMode, float alphaCutoff,
                float alphaFactor, GltfSamplerData sampler) {
        public Spec {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(alphaMode, "alphaMode");
            Objects.requireNonNull(sampler, "sampler");
            if (maxSize < 1 || maxSize > MAX_DIMENSION) throw new IllegalArgumentException("invalid texture size cap");
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0 || alphaCutoff > 1
                    || !Float.isFinite(alphaFactor) || alphaFactor < 0 || alphaFactor > 1) {
                throw new IllegalArgumentException("alpha parameters must be finite UNORM values");
            }
            TextureVariantPolicy.validateSampler(sampler);
        }
    }

    public record Dimensions(int width, int height) { }
    record Result(byte[] encoded, int width, int height) { }

    private TextureImageFilter() { }

    static Dimensions inspect(byte[] encoded) {
        if (Ktx2ImageHeader.matches(encoded)) {
            Ktx2ImageHeader header = Ktx2ImageHeader.read(encoded);
            return new Dimensions(header.width(), header.height());
        }
        ByteBuffer source = nativeEncoded(encoded);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(source, width, height, channels)) {
                throw new IllegalArgumentException("Invalid PNG/JPEG header: " + STBImage.stbi_failure_reason());
            }
            return checkedDimensions(width.get(0), height.get(0));
        } finally {
            MemoryUtil.memFree(source);
        }
    }

    static Dimensions target(Dimensions source, int maxSize) {
        int largest = Math.max(source.width(), source.height());
        if (largest <= maxSize) return source;
        return new Dimensions(Math.max(1, (int) ((long) source.width() * maxSize / largest)),
                Math.max(1, (int) ((long) source.height() * maxSize / largest)));
    }

    static Result resize(byte[] encoded, Spec spec) {
        return resize(encoded, spec, () -> false);
    }

    static Result resize(byte[] encoded, Spec spec, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);
        Ktx2ImageHeader ktx = Ktx2ImageHeader.matches(encoded) ? Ktx2ImageHeader.read(encoded) : null;
        if (ktx != null) ktx.validateUsage(spec.role());
        Dimensions sourceSize = ktx == null ? inspect(encoded) : new Dimensions(ktx.width(), ktx.height());
        checkCancelled(cancelled);
        Dimensions targetSize = target(sourceSize, spec.maxSize());
        if (sourceSize.equals(targetSize) && ktx == null) return new Result(encoded, sourceSize.width(), sourceSize.height());

        ByteBuffer resized = null;
        try (Pixels owner = ktx == null ? decodeStb(encoded) : Ktx2ImageDecoder.decode(encoded, ktx, cancelled)) {
            checkCancelled(cancelled);
            if (owner.width() != sourceSize.width() || owner.height() != sourceSize.height()) {
                throw new IllegalArgumentException("Decoded dimensions changed after header validation");
            }
            ByteBuffer decoded = owner.pixels();
            if (sourceSize.equals(targetSize)) {
                byte[] result = encodePng(decoded, targetSize);
                checkCancelled(cancelled);
                return new Result(result, targetSize.width(), targetSize.height());
            }
            resized = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(targetSize.width(), targetSize.height()), 4));
            resizePixels(decoded, sourceSize, resized, targetSize, spec, maskCoverage(decoded, spec));
            // A native operation cannot be preempted; retire stale work before its next allocation/encode.
            checkCancelled(cancelled);
            // The 8K decode is not needed during PNG encoding and never belongs to a retained asset.
            owner.close();
            checkCancelled(cancelled);
            byte[] result = encodePng(resized, targetSize);
            checkCancelled(cancelled);
            return new Result(result, targetSize.width(), targetSize.height());
        } finally {
            MemoryUtil.memFree(resized);
        }
    }

    /** Filters caller-owned RGBA8 pixels; no decode, encode, ownership transfer, or retained buffer. */
    public static void resizePixels(ByteBuffer source, Dimensions sourceSize, ByteBuffer output,
                                    Dimensions targetSize, Spec spec, double referenceMaskCoverage) {
        checkedDimensions(sourceSize.width(), sourceSize.height());
        checkedDimensions(targetSize.width(), targetSize.height());
        if (targetSize.width() > sourceSize.width() || targetSize.height() > sourceSize.height()
                || !source.isDirect() || !output.isDirect() || output.isReadOnly()
                || source.remaining() != sourceSize.width() * sourceSize.height() * 4
                || output.remaining() != targetSize.width() * targetSize.height() * 4) {
            throw new IllegalArgumentException("Filtering requires exact, direct RGBA8 buffers without upscaling");
        }
        ByteBuffer decoded = source.slice(), resized = output.slice();
        boolean srgb = spec.role() == Role.BASE_COLOR || spec.role() == Role.EMISSIVE;
        boolean transparent = spec.role() == Role.BASE_COLOR && spec.alphaMode() != GltfAlphaMode.OPAQUE;
        int layout = srgb ? (transparent ? STBIR_RGBA : STBIR_RGBA_NO_AW) : STBIR_4CHANNEL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            STBIR_RESIZE filter = STBIR_RESIZE.malloc(stack);
            stbir_resize_init(filter, decoded, sourceSize.width(), sourceSize.height(), 0,
                    resized, targetSize.width(), targetSize.height(), 0,
                    layout, srgb ? STBIR_TYPE_UINT8_SRGB : STBIR_TYPE_UINT8);
            try {
                stbir_set_edgemodes(filter, edgeMode(spec.sampler().wrapS()), edgeMode(spec.sampler().wrapT()));
                stbir_set_filters(filter, STBIR_FILTER_MITCHELL, STBIR_FILTER_MITCHELL);
                if (stbir_resize_extended(filter) == 0) throw new IllegalStateException("STB image resize failed");
            } finally {
                stbir_free_samplers(filter);
            }
        }
        if (spec.role() == Role.NORMAL) normalizeNormals(resized);
        if (spec.role() == Role.BASE_COLOR && spec.alphaMode() == GltfAlphaMode.MASK) {
            preserveMaskCoverage(resized, spec, referenceMaskCoverage);
        }
    }

    private static int edgeMode(int wrap) {
        return wrap == de.javagl.jgltf.model.GltfConstants.GL_REPEAT ? STBIR_EDGE_WRAP : STBIR_EDGE_CLAMP;
    }

    /** One scalar preserves base-level alpha coverage without retaining the base pixels for all mips. */
    public static double maskCoverage(ByteBuffer source, Spec spec) {
        int cutoff = Math.round(spec.alphaCutoff() * 255), factor = Math.round(spec.alphaFactor() * 255);
        if (spec.role() != Role.BASE_COLOR || spec.alphaMode() != GltfAlphaMode.MASK
                || cutoff == 0 || factor == 0 || cutoff > factor) return 0;
        return coverage(alphaHistogram(source.slice()), (cutoff * 255 + factor - 1) / factor, 1);
    }

    interface Pixels extends AutoCloseable {
        int width();
        int height();
        ByteBuffer pixels();
        @Override void close();
    }

    private static Pixels decodeStb(byte[] encoded) {
        ByteBuffer source = nativeEncoded(encoded);
        ByteBuffer decoded = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            decoded = STBImage.stbi_load_from_memory(source, width, height, channels, 4);
            if (decoded == null) throw new IllegalArgumentException("PNG/JPEG decode failed: " + STBImage.stbi_failure_reason());
            StbPixels result = new StbPixels(decoded, width.get(0), height.get(0));
            decoded = null;
            return result;
        } finally {
            if (decoded != null) STBImage.stbi_image_free(decoded);
            MemoryUtil.memFree(source);
        }
    }

    private static final class StbPixels implements Pixels {
        private ByteBuffer data;
        private final int width, height;
        private StbPixels(ByteBuffer data, int width, int height) { this.data = data; this.width = width; this.height = height; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public ByteBuffer pixels() { return data; }
        @Override public void close() {
            if (data != null) { STBImage.stbi_image_free(data); data = null; }
        }
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("glTF quality generation retired");
    }

    private static void normalizeNormals(ByteBuffer pixels) {
        for (int index = 0; index < pixels.capacity(); index += 4) {
            double x = (pixels.get(index) & 255) / 127.5 - 1;
            double y = (pixels.get(index + 1) & 255) / 127.5 - 1;
            double z = (pixels.get(index + 2) & 255) / 127.5 - 1;
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 0.01) { x = 0; y = 0; z = 1; length = 1; }
            pixels.put(index, unorm(x / length * 0.5 + 0.5));
            pixels.put(index + 1, unorm(y / length * 0.5 + 0.5));
            pixels.put(index + 2, unorm(z / length * 0.5 + 0.5));
        }
    }

    private static void preserveMaskCoverage(ByteBuffer output, Spec spec, double desired) {
        // Match the renderer's UNORM8 material factors. Coverage is measured at texel centers for
        // this cutoff and vertex alpha 1; discrete low-res texels cannot represent every coverage.
        int cutoff = Math.round(spec.alphaCutoff() * 255), factor = Math.round(spec.alphaFactor() * 255);
        if (cutoff == 0 || factor == 0 || cutoff > factor) return;
        int requiredAlpha = (cutoff * 255 + factor - 1) / factor;
        if (!Double.isFinite(desired) || desired < 0 || desired > 1) {
            throw new IllegalArgumentException("Invalid reference alpha coverage");
        }
        int[] outputHistogram = alphaHistogram(output);
        double bestScale = 1, bestError = Math.abs(coverage(outputHistogram, requiredAlpha, 1) - desired);
        // A 256-bin histogram makes every possible quantized coverage transition inexpensive to
        // examine. Choose the nearest achievable coverage, preferring the least alpha change.
        for (int alpha = 1; alpha <= 255; alpha++) {
            if (outputHistogram[alpha] == 0) continue;
            double transition = (requiredAlpha - 0.5) / alpha;
            for (double candidate : new double[]{Math.max(0, transition - 1.0e-7), transition + 1.0e-7}) {
                double error = Math.abs(coverage(outputHistogram, requiredAlpha, candidate) - desired);
                if (error < bestError - 1.0e-12
                        || Math.abs(error - bestError) < 1.0e-12 && Math.abs(candidate - 1) < Math.abs(bestScale - 1)) {
                    bestError = error;
                    bestScale = candidate;
                }
            }
        }
        if (bestScale != 1) {
            for (int index = 3; index < output.capacity(); index += 4) {
                output.put(index, (byte) Math.min(255, Math.round((output.get(index) & 255) * bestScale)));
            }
        }
    }

    private static int[] alphaHistogram(ByteBuffer pixels) {
        int[] histogram = new int[256];
        for (int index = 3; index < pixels.capacity(); index += 4) histogram[pixels.get(index) & 255]++;
        return histogram;
    }

    private static double coverage(int[] histogram, int cutoff, double scale) {
        long total = 0, covered = 0;
        for (int alpha = 0; alpha < histogram.length; alpha++) {
            total += histogram[alpha];
            if (Math.min(255, Math.round(alpha * scale)) >= cutoff) covered += histogram[alpha];
        }
        return (double) covered / total;
    }

    private static byte[] encodePng(ByteBuffer pixels, Dimensions size) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        boolean[] oversized = {false};
        try (STBIWriteCallback writer = STBIWriteCallback.create((context, data, count) -> {
            // Do not throw across the native callback. Reject the result after STB has released
            // its temporary PNG buffer, without allocating an oversized Java encoded copy.
            if (oversized[0] || count < 0 || count > TextureVariantPolicy.MAX_ENCODED_BYTES - encoded.size()) {
                oversized[0] = true;
                return;
            }
            byte[] bytes = new byte[count];
            MemoryUtil.memByteBuffer(data, count).get(bytes);
            encoded.writeBytes(bytes);
        })) {
            if (!STBImageWrite.stbi_write_png_to_func(writer, 0, size.width(), size.height(), 4, pixels, size.width() * 4)) {
                throw new IllegalStateException("Could not encode derived PNG");
            }
        }
        if (oversized[0]) throw new IllegalArgumentException("Derived PNG exceeds the renderer's encoded-byte limit");
        byte[] result = encoded.toByteArray();
        TextureVariantPolicy.validateDerivedPng(result);
        return result;
    }

    private static ByteBuffer nativeEncoded(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        TextureVariantPolicy.validateEncodedLength(encoded.length);
        boolean png = encoded.length >= 8 && (encoded[0] & 255) == 137
                && encoded[1] == 'P' && encoded[2] == 'N' && encoded[3] == 'G';
        boolean jpeg = encoded.length >= 3 && (encoded[0] & 255) == 255
                && (encoded[1] & 255) == 216 && (encoded[2] & 255) == 255;
        if (!png && !jpeg) {
            throw new IllegalArgumentException("Only bounded PNG/JPEG images can be filtered");
        }
        return MemoryUtil.memAlloc(encoded.length).put(encoded).flip();
    }

    private static Dimensions checkedDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Unsupported image dimensions: " + width + "x" + height);
        }
        return new Dimensions(width, height);
    }

    private static byte unorm(double value) {
        return (byte) Math.max(0, Math.min(255, Math.round(value * 255)));
    }

}
