package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.GunMod;
import com.tacz.guns.client.model.gltf.quality.Ktx2ImageDecoder;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns uploaded glTF images and one-pixel material parameter textures registered with Minecraft.
 * Exactly one live instance may own the stable dynamic identifiers at a time. GPU RGBA8 texels
 * share one immutable cache-wide budget, defaulting to {@link #MAX_DECODED_TEXTURE_BYTES}. All GPU
 * and lifecycle methods must run on the render thread.
 */
public final class GltfPbrDynamicTextureCache implements AutoCloseable {
    public static final int MAX_ENCODED_IMAGE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_IMAGE_DIMENSION = 8192;
    public static final long MAX_IMAGE_PIXELS = (long) MAX_IMAGE_DIMENSION * MAX_IMAGE_DIMENSION;
    public static final long MAX_DECODED_TEXTURE_BYTES = 512L * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final HexFormat HEX = HexFormat.of();
    private static final AtomicReference<GltfPbrDynamicTextureCache> ACTIVE_OWNER = new AtomicReference<>();
    private static final GltfPbrMaterialFactors DEFAULT_FACTORS = GltfPbrMaterialFactors.defaults();
    private static final GltfPbrFactorEncoding DEFAULT_BASE_COLOR = DEFAULT_FACTORS.baseColorEncoding();
    private static final GltfPbrFactorEncoding DEFAULT_EMISSIVE = DEFAULT_FACTORS.emissiveEncoding();
    private static final GltfPbrFactorEncoding DEFAULT_PARAMETERS = DEFAULT_FACTORS.parametersEncoding();

    private final TextureStore textureStore;
    private final Runnable renderThreadAssertion;
    private final long maxDecodedTextureBytes;
    private final Map<Identifier, OwnedTexture> textures = new HashMap<>();
    private long decodedTextureBytes;
    private boolean closed;

    public GltfPbrDynamicTextureCache(TextureManager textureManager) {
        this(textureManager, MAX_DECODED_TEXTURE_BYTES);
    }

    public GltfPbrDynamicTextureCache(TextureManager textureManager, long maxDecodedTextureBytes) {
        this(new MinecraftTextureStore(textureManager), RenderSystem::assertOnRenderThread, maxDecodedTextureBytes);
    }

    GltfPbrDynamicTextureCache(TextureStore textureStore) {
        this(textureStore, MAX_DECODED_TEXTURE_BYTES);
    }

    GltfPbrDynamicTextureCache(TextureStore textureStore, long maxDecodedTextureBytes) {
        this(textureStore, () -> {
        }, maxDecodedTextureBytes);
    }

    private GltfPbrDynamicTextureCache(
            TextureStore textureStore,
            Runnable renderThreadAssertion,
            long maxDecodedTextureBytes
    ) {
        this.textureStore = Objects.requireNonNull(textureStore, "textureStore");
        this.renderThreadAssertion = Objects.requireNonNull(renderThreadAssertion, "renderThreadAssertion");
        validateDecodedBudget(0L, 0L, maxDecodedTextureBytes);
        // Models/materials cannot raise the budget for an already-populated shared GPU cache.
        this.maxDecodedTextureBytes = maxDecodedTextureBytes;
        // Dynamic ids intentionally stay stable across reloads. A single owner prevents one cache
        // from replacing or releasing another cache's TextureManager entries with the same ids.
        if (!ACTIVE_OWNER.compareAndSet(null, this)) {
            throw new IllegalStateException("Only one live glTF dynamic texture cache is supported");
        }
    }

    /**
     * Creates or reuses every texture needed by a material and returns its immutable binding.
     */
    public GltfPbrMaterial material(Identifier materialKey, GltfPbrMaterialInput input) {
        assertOnRenderThread();
        ensureOpen();
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(input, "input");

        GltfPbrTextureSlots slots = input.textures();
        GltfPbrMaterialFactors factors = input.factors();
        GltfPbrFactorEncoding baseColorEncoding = factors.baseColorEncoding();
        GltfPbrFactorEncoding emissiveEncoding = factors.emissiveEncoding();
        GltfPbrFactorEncoding parametersEncoding = factors.parametersEncoding();
        long startingDecodedBytes = decodedTextureBytes;
        List<Identifier> addedIds = new ArrayList<>(8);
        try {
            return new GltfPbrMaterial(
                    texture(slots.baseColor(), addedIds),
                    texture(slots.metallicRoughness(), addedIds),
                    texture(slots.normal(), addedIds),
                    texture(slots.occlusion(), addedIds),
                    texture(slots.emissive(), addedIds),
                    factorTexture(materialKey, "base_color_factor", baseColorEncoding, DEFAULT_BASE_COLOR,
                            addedIds),
                    factorTexture(materialKey, "emissive_factor", emissiveEncoding, DEFAULT_EMISSIVE,
                            addedIds),
                    factorTexture(materialKey, "pbr_parameters", parametersEncoding, DEFAULT_PARAMETERS,
                            addedIds),
                    input.alphaMode(),
                    input.cull(),
                    GltfPbrSamplers.from(slots)
            );
        } catch (RuntimeException | Error failure) {
            rollbackMaterial(addedIds, startingDecodedBytes, failure);
            throw failure;
        }
    }

    /** Returns null for an absent glTF texture slot so {@link GltfPbrMaterial} can use its fallback. */
    public Identifier texture(GltfPbrTextureSource source) {
        assertOnRenderThread();
        ensureOpen();
        return texture(source, null);
    }

    private Identifier texture(
            GltfPbrTextureSource source,
            List<Identifier> addedIds
    ) {
        if (source == null) {
            return null;
        }

        Identifier dynamicId = source.dynamicId();
        if (textures.containsKey(dynamicId)) {
            return dynamicId;
        }
        ensureDecodedBudget(source.resourceKey(), source.decodedBytes());

        NativeImage image = null;
        try {
            image = decodeImage(source.resourceKey(), source.encodedImage(), source.imageHeader(), source.settings());
            NativeImage ownedImage = image;
            image = null;
            register(
                    dynamicId,
                    "TACZ glTF image " + source.resourceKey(),
                    ownedImage,
                    source.settings(),
                    source.decodedBytes(),
                    addedIds
            );
            return dynamicId;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Failed to decode glTF image " + source.resourceKey(), exception);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /** Releases all owned GPU textures and invalidates RenderTypes that reference their identifiers. */
    public void clear() {
        assertOnRenderThread();
        ensureOpen();
        releaseTextures();
    }

    public int size() {
        assertOnRenderThread();
        ensureOpen();
        return textures.size();
    }

    public long decodedTextureBytes() {
        assertOnRenderThread();
        ensureOpen();
        return decodedTextureBytes;
    }

    @Override
    public void close() {
        assertOnRenderThread();
        if (closed) {
            return;
        }
        Throwable failure = null;
        try {
            releaseTextures();
        } catch (RuntimeException | Error cleanupFailure) {
            failure = cleanupFailure;
        } finally {
            closed = true;
            if (!ACTIVE_OWNER.compareAndSet(this, null)) {
                IllegalStateException ownershipFailure = new IllegalStateException(
                        "glTF dynamic texture cache lost its global ownership"
                );
                if (failure == null) {
                    failure = ownershipFailure;
                } else {
                    failure.addSuppressed(ownershipFailure);
                }
            }
        }
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    private Identifier factorTexture(
            Identifier materialKey,
            String kind,
            GltfPbrFactorEncoding encoding,
            GltfPbrFactorEncoding fallbackEncoding,
            List<Identifier> addedIds
    ) {
        if (encoding.equals(fallbackEncoding)) {
            return null;
        }
        byte[] rgba = {
                (byte) encoding.red(),
                (byte) encoding.green(),
                (byte) encoding.blue(),
                (byte) encoding.alpha()
        };
        Identifier dynamicId = dynamicId(kind, materialKey, rgba);
        if (textures.containsKey(dynamicId)) {
            return dynamicId;
        }

        ensureDecodedBudget(materialKey, 4L);
        NativeImage image = null;
        try {
            image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, encoding.argb());
            NativeImage ownedImage = image;
            image = null;
            register(dynamicId, "TACZ glTF " + kind + " " + materialKey, ownedImage,
                    GltfPbrTextureSource.Settings.DEFAULT, 4L, addedIds);
            return dynamicId;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void register(
            Identifier id,
            String label,
            NativeImage image,
            GltfPbrTextureSource.Settings settings,
            long decodedBytes,
            List<Identifier> addedIds
    ) {
        boolean registered = false;
        try {
            textureStore.register(id, label, image, settings);
            registered = true;
            textures.put(id, new OwnedTexture(decodedBytes));
            decodedTextureBytes += decodedBytes;
            if (addedIds != null) {
                addedIds.add(id);
            }
        } catch (RuntimeException | Error failure) {
            textures.remove(id);
            if (registered) {
                try {
                    textureStore.release(id);
                } catch (RuntimeException | Error releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
    }

    private void releaseTextures() {
        Throwable failure = null;
        try {
            for (Identifier id : List.copyOf(textures.keySet())) {
                try {
                    textureStore.release(id);
                } catch (RuntimeException | Error releaseFailure) {
                    if (failure == null) {
                        failure = releaseFailure;
                    } else {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            }
        } finally {
            textures.clear();
            decodedTextureBytes = 0L;
            try {
                GltfPbrRenderTypes.clearCache();
            } catch (RuntimeException | Error renderTypeFailure) {
                if (failure == null) {
                    failure = renderTypeFailure;
                } else {
                    failure.addSuppressed(renderTypeFailure);
                }
            }
        }
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    private void rollbackMaterial(List<Identifier> addedIds, long startingDecodedBytes, Throwable failure) {
        boolean releasedAny = false;
        for (int index = addedIds.size() - 1; index >= 0; index--) {
            Identifier id = addedIds.get(index);
            OwnedTexture owned = textures.remove(id);
            if (owned == null) {
                continue;
            }
            releasedAny = true;
            try {
                textureStore.release(id);
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
        decodedTextureBytes = startingDecodedBytes;
        if (releasedAny) {
            GltfPbrRenderTypes.clearCache();
        }
    }

    static void validateEncodedImage(Identifier resourceKey, byte[] encoded) {
        inspectImage(resourceKey, encoded);
    }

    static PreparedImage prepareImage(Identifier resourceKey, byte[] encoded) {
        return prepareImage(resourceKey, encoded, GltfPbrTextureSource.Settings.DEFAULT);
    }

    static PreparedImage prepareImage(Identifier resourceKey, byte[] encoded, GltfPbrTextureSource.Settings settings) {
        ImageHeader header = inspectImage(resourceKey, encoded, settings);
        int levels = GltfTextureMipmaps.levelCount(header.width(), header.height(), settings.mipmapped());
        String identity = settings.mipIdentity(levels);
        if (header.kind() == ImageKind.KTX2) identity = Ktx2ImageDecoder.VERSION + ":" + identity;
        return new PreparedImage(
                dynamicId("image", resourceKey, encoded, identity),
                header,
                GltfTextureMipmaps.gpuBytes(header.width(), header.height(), settings.mipmapped())
        );
    }

    static NativeImage decodeImage(Identifier resourceKey, byte[] encoded) throws IOException {
        return decodeImage(resourceKey, encoded, inspectImage(resourceKey, encoded), GltfPbrTextureSource.Settings.DEFAULT);
    }

    private static ImageHeader inspectImage(Identifier resourceKey, byte[] encoded) {
        return inspectImage(resourceKey, encoded, GltfPbrTextureSource.Settings.DEFAULT);
    }

    private static ImageHeader inspectImage(Identifier resourceKey, byte[] encoded, GltfPbrTextureSource.Settings settings) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "glTF image " + resourceKey + " exceeds " + MAX_ENCODED_IMAGE_BYTES + " encoded bytes"
            );
        }
        ImageHeader header;
        if (isPng(encoded)) {
            header = pngHeader(resourceKey, encoded);
        } else if (isJpeg(encoded)) {
            header = jpegHeader(resourceKey, encoded);
        } else if (Ktx2ImageDecoder.matches(encoded)) {
            // This runs once per binding, before any content-shared GPU cache lookup. A valid
            // color binding must never let a later invalid data/normal binding bypass its DFD.
            var dimensions = Ktx2ImageDecoder.inspect(encoded, settings.role());
            header = new ImageHeader(ImageKind.KTX2, dimensions.width(), dimensions.height());
        } else {
            throw new IllegalArgumentException("glTF image " + resourceKey + " is not an encoded PNG, JPEG, or Basis KTX2");
        }
        validateDimensions(resourceKey, header.width(), header.height());
        return header;
    }

    private static void validateDimensions(Identifier resourceKey, int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0
                || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                || pixels > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException(
                    "glTF image " + resourceKey + " has unsupported dimensions " + width + "x" + height
            );
        }
    }

    private static ImageHeader pngHeader(Identifier resourceKey, byte[] encoded) {
        if (encoded.length < 24
                || readUnsignedInt(encoded, 8) != 13L
                || encoded[12] != 'I' || encoded[13] != 'H'
                || encoded[14] != 'D' || encoded[15] != 'R') {
            throw new IllegalArgumentException("glTF PNG " + resourceKey + " has no valid IHDR chunk");
        }
        return new ImageHeader(
                ImageKind.PNG,
                checkedDimension(resourceKey, "width", readUnsignedInt(encoded, 16)),
                checkedDimension(resourceKey, "height", readUnsignedInt(encoded, 20))
        );
    }

    private static ImageHeader jpegHeader(Identifier resourceKey, byte[] encoded) {
        int cursor = 2;
        while (cursor < encoded.length) {
            while (cursor < encoded.length && (encoded[cursor] & 0xFF) != 0xFF) {
                cursor++;
            }
            while (cursor < encoded.length && (encoded[cursor] & 0xFF) == 0xFF) {
                cursor++;
            }
            if (cursor >= encoded.length) {
                break;
            }

            int marker = encoded[cursor++] & 0xFF;
            if (marker == 0xD9) {
                break;
            }
            if (marker == 0x00 || marker == 0xD8
                    || marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) {
                continue;
            }
            if (cursor + 2 > encoded.length) {
                break;
            }
            int segmentLength = readUnsignedShort(encoded, cursor);
            if (segmentLength < 2 || cursor + segmentLength > encoded.length) {
                throw new IllegalArgumentException("glTF JPEG " + resourceKey + " has a truncated marker segment");
            }
            if (isStartOfFrame(marker)) {
                if (segmentLength < 8) {
                    throw new IllegalArgumentException("glTF JPEG " + resourceKey + " has a truncated SOF marker");
                }
                int height = readUnsignedShort(encoded, cursor + 3);
                int width = readUnsignedShort(encoded, cursor + 5);
                return new ImageHeader(ImageKind.JPEG, width, height);
            }
            if (marker == 0xDA) {
                break;
            }
            cursor += segmentLength;
        }
        throw new IllegalArgumentException("glTF JPEG " + resourceKey + " has no supported SOF marker");
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF
                && marker != 0xC4
                && marker != 0xC8
                && marker != 0xCC;
    }

    private static NativeImage decodeImage(Identifier resourceKey, byte[] encoded, ImageHeader header,
                                           GltfPbrTextureSource.Settings settings) throws IOException {
        NativeImage image;
        if (header.kind() == ImageKind.PNG) {
            image = NativeImage.read(encoded);
        } else if (header.kind() == ImageKind.KTX2) {
            image = Ktx2ImageDecoder.decodeToNativeImage(encoded, settings.role(), () -> false);
        } else {
            image = decodeJpeg(resourceKey, encoded);
        }
        int actualWidth = image.getWidth();
        int actualHeight = image.getHeight();
        if (actualWidth != header.width() || actualHeight != header.height()) {
            image.close();
            throw new IOException(
                    "Decoded glTF image dimensions changed from " + header.width() + "x" + header.height()
                            + " to " + actualWidth + "x" + actualHeight
            );
        }
        return image;
    }

    private static NativeImage decodeJpeg(Identifier resourceKey, byte[] encoded) throws IOException {
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(encoded));
        if (buffered == null) {
            throw new IOException("No Java ImageIO JPEG reader accepted " + resourceKey);
        }

        NativeImage image = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
        int[] row = new int[buffered.getWidth()];
        try {
            for (int y = 0; y < buffered.getHeight(); y++) {
                buffered.getRGB(0, y, buffered.getWidth(), 1, row, 0, buffered.getWidth());
                for (int x = 0; x < buffered.getWidth(); x++) {
                    image.setPixel(x, y, row[x]);
                }
            }
            return image;
        } catch (RuntimeException exception) {
            image.close();
            throw exception;
        } finally {
            buffered.flush();
        }
    }

    private static int checkedDimension(Identifier resourceKey, String axis, long value) {
        if (value <= 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("glTF image " + resourceKey + " has invalid " + axis + " " + value);
        }
        return (int) value;
    }

    private static long readUnsignedInt(byte[] encoded, int offset) {
        return (long) (encoded[offset] & 0xFF) << 24
                | (long) (encoded[offset + 1] & 0xFF) << 16
                | (long) (encoded[offset + 2] & 0xFF) << 8
                | encoded[offset + 3] & 0xFFL;
    }

    private static int readUnsignedShort(byte[] encoded, int offset) {
        return (encoded[offset] & 0xFF) << 8 | (encoded[offset + 1] & 0xFF);
    }

    static void validateDecodedBudget(long currentBytes, long additionalBytes) {
        validateDecodedBudget(currentBytes, additionalBytes, MAX_DECODED_TEXTURE_BYTES);
    }

    static void validateDecodedBudget(long currentBytes, long additionalBytes, long maxDecodedTextureBytes) {
        if (currentBytes < 0L || additionalBytes < 0L
                || maxDecodedTextureBytes < 0L
                || currentBytes > maxDecodedTextureBytes
                || additionalBytes > maxDecodedTextureBytes - currentBytes) {
            throw new IllegalArgumentException("glTF decoded/GPU texture byte budget exceeded");
        }
    }

    private void ensureDecodedBudget(
            Identifier resourceKey,
            long additionalBytes
    ) {
        try {
            validateDecodedBudget(decodedTextureBytes, additionalBytes, maxDecodedTextureBytes);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "glTF texture " + resourceKey + " exceeds the remaining decoded/GPU texture budget of "
                            + Math.max(0L, maxDecodedTextureBytes - decodedTextureBytes) + " bytes",
                    exception
            );
        }
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private static boolean isPng(byte[] encoded) {
        if (encoded.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (encoded[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJpeg(byte[] encoded) {
        return encoded.length >= 3
                && (encoded[0] & 0xFF) == 0xFF
                && (encoded[1] & 0xFF) == 0xD8
                && (encoded[2] & 0xFF) == 0xFF;
    }

    static Identifier dynamicId(String kind, Identifier resourceKey, byte[] content) {
        return dynamicId(kind, resourceKey, content, "");
    }

    private static Identifier dynamicId(String kind, Identifier resourceKey, byte[] content, String mipIdentity) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every supported Java runtime must provide SHA-256", exception);
        }
        digest.update(kind.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        // Samplers live on material bindings. Only generated pixel semantics separate image owners.
        if (!mipIdentity.isEmpty()) {
            digest.update(mipIdentity.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        if (!"image".equals(kind)) {
            digest.update(resourceKey.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        digest.update(content);
        return Identifier.fromNamespaceAndPath(
                GunMod.MOD_ID,
                "dynamic/gltf/" + kind + "/" + HEX.formatHex(digest.digest())
        );
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("glTF dynamic texture cache is closed");
        }
    }

    private void assertOnRenderThread() {
        renderThreadAssertion.run();
    }

    enum ImageKind {
        PNG,
        JPEG,
        KTX2
    }

    record ImageHeader(ImageKind kind, int width, int height) {
    }

    record PreparedImage(Identifier dynamicId, ImageHeader header, long decodedBytes) {
    }

    interface TextureStore {
        /** Takes ownership of {@code image}, including when registration fails. */
        void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings);

        void release(Identifier id);
    }

    private static final class MinecraftTextureStore implements TextureStore {
        private final TextureManager textureManager;

        private MinecraftTextureStore(TextureManager textureManager) {
            this.textureManager = Objects.requireNonNull(textureManager, "textureManager");
        }

        @Override
        public void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
            UploadOnlyTexture texture = null;
            try {
                texture = new UploadOnlyTexture(label, image, settings);
                textureManager.register(id, texture);
            } catch (RuntimeException | Error failure) {
                try {
                    if (texture != null) {
                        texture.close();
                    } else {
                        image.close();
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override
        public void release(Identifier id) {
            textureManager.release(id);
        }
    }

    private record OwnedTexture(long decodedBytes) {
    }
}
