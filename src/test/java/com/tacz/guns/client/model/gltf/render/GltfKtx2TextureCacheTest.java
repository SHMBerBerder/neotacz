package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.quality.Ktx2ImageDecoder;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GltfKtx2TextureCacheTest {
    @Test
    void mipBudgetRejectsBeforeKtxTranscodingOrRegistration() throws Exception {
        var source = source("mip", fixture("cyan_rgb_reference_uastc.ktx2"), TextureImageFilter.Role.BASE_COLOR, true);
        assertEquals(7, source.mipLevels());
        assertEquals(21844, source.decodedBytes());
        long before = Ktx2ImageDecoder.transcodes();
        var store = new CpuTextureStore();
        try (var cache = new GltfPbrDynamicTextureCache(store, 64 * 64 * 4)) {
            assertThrows(IllegalArgumentException.class, () -> cache.texture(source));
            assertEquals(before, Ktx2ImageDecoder.transcodes());
            assertEquals(0, cache.size());
            assertEquals(0, cache.decodedTextureBytes());
            assertTrue(store.images.isEmpty());
        }
    }

    @Test
    void sameCompressedContentSharesAnUploadAndClearRequiresAnotherDecode() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_uastc.ktx2");
        var source = source("first", bytes, TextureImageFilter.Role.BASE_COLOR, false);
        var other = source("other", bytes, TextureImageFilter.Role.EMISSIVE, false);
        assertEquals(source.dynamicId(), other.dynamicId());
        long before = Ktx2ImageDecoder.transcodes();
        var store = new CpuTextureStore();
        try (var cache = new GltfPbrDynamicTextureCache(store, source.decodedBytes())) {
            var id = cache.texture(source);
            assertEquals(id, cache.texture(other));
            assertEquals(before + 1, Ktx2ImageDecoder.transcodes());
            assertEquals(1, store.images.size());
            assertTrue(store.images.getFirst().isClosed());
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
            cache.clear();
            assertEquals(List.of(id), store.released);
            assertEquals(0, cache.decodedTextureBytes());
            assertEquals(id, cache.texture(source));
            assertEquals(before + 2, Ktx2ImageDecoder.transcodes());
        }
        assertTrue(store.images.stream().allMatch(NativeImage::isClosed));
    }

    @Test
    void eachBindingMustPassFullDfdAndHeaderAdmissionBeforeContentSharing() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_uastc.ktx2");
        var store = new CpuTextureStore();
        try (var cache = new GltfPbrDynamicTextureCache(store)) {
            cache.texture(source("color", bytes, TextureImageFilter.Role.BASE_COLOR, false));
            long before = Ktx2ImageDecoder.transcodes();
            assertThrows(IllegalArgumentException.class,
                    () -> source("bad_role", bytes, TextureImageFilter.Role.NORMAL, false));
            byte[] bomb = bytes.clone();
            ByteBuffer.wrap(bomb).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 8193);
            assertThrows(IllegalArgumentException.class,
                    () -> source("bad_size", bomb, TextureImageFilter.Role.BASE_COLOR, false));
            byte[] badLevel = bytes.clone();
            ByteBuffer.wrap(badLevel).order(ByteOrder.LITTLE_ENDIAN).putLong(96, Long.MAX_VALUE);
            assertThrows(IllegalArgumentException.class,
                    () -> source("bad_expansion", badLevel, TextureImageFilter.Role.BASE_COLOR, false));
            assertEquals(before, Ktx2ImageDecoder.transcodes());
            assertEquals(1, cache.size());
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
        }
    }

    @Test
    void ktxIdentityIncludesDecoderVersionAndMipsRemainRoleSpecific() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_uastc.ktx2");
        var source = source("version", bytes, TextureImageFilter.Role.BASE_COLOR, false);
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("image\0".getBytes(StandardCharsets.UTF_8));
        digest.update((Ktx2ImageDecoder.VERSION + ":\0").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        assertEquals(key("dynamic/gltf/image/" + HexFormat.of().formatHex(digest.digest())), source.dynamicId());
        assertNotEquals(GltfPbrDynamicTextureCache.dynamicId("image", source.resourceKey(), bytes), source.dynamicId());
        byte[] linear = linear(bytes);
        assertNotEquals(source("normal", linear, TextureImageFilter.Role.NORMAL, true).dynamicId(),
                source("orm", linear, TextureImageFilter.Role.ORM, true).dynamicId());
    }

    @Test
    void ktxBaseImageUsesTheExistingRoleAwareMipUploaderAndExactAllLevelBudget() throws Exception {
        var source = source("mip", linear(fixture("cyan_rgb_reference_uastc.ktx2")), TextureImageFilter.Role.NORMAL, true);
        var store = new CpuTextureStore();
        try (var cache = new GltfPbrDynamicTextureCache(store, source.decodedBytes())) {
            cache.texture(source);
            assertEquals(List.of(64, 32, 16, 8, 4, 2, 1), store.widths);
            assertEquals(21844, store.uploadedBytes);
            assertEquals(store.uploadedBytes, cache.decodedTextureBytes());
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
            assertTrue(store.images.getFirst().isClosed());
        }
    }

    @Test
    void materialFailureClosesNewPixelsAndRollsBackOnlyNewImages() throws Exception {
        byte[] uastc = fixture("cyan_rgb_reference_uastc.ktx2");
        var existing = source("existing", uastc, TextureImageFilter.Role.BASE_COLOR, false);
        var added = source("added", linear(uastc), TextureImageFilter.Role.ORM, false);
        var failing = source("failing", fixture("cyan_rgb_reference_basis.ktx2"), TextureImageFilter.Role.EMISSIVE, false);
        var store = new CpuTextureStore();
        store.failAt = 3;
        try (var cache = new GltfPbrDynamicTextureCache(store)) {
            var oldId = cache.texture(existing);
            var input = new GltfPbrMaterialInput(new GltfPbrTextureSlots(existing, added, null, null, failing),
                    null, GltfPbrAlphaMode.OPAQUE, false);
            assertThrows(IllegalArgumentException.class, () -> cache.material(key("material"), input));
            assertEquals(List.of(added.dynamicId()), store.released);
            assertEquals(1, cache.size());
            assertEquals(existing.decodedBytes(), cache.decodedTextureBytes());
            assertTrue(store.images.stream().allMatch(NativeImage::isClosed));
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
            long before = Ktx2ImageDecoder.transcodes();
            assertEquals(oldId, cache.texture(existing));
            assertEquals(before, Ktx2ImageDecoder.transcodes());
        }
    }

    @Test
    void invalidCompressedPayloadFailsWithoutPublishingAnImageAndReleasesTheDecoderPermit() throws Exception {
        byte[] invalid = fixture("color_grid_uastc_zstd.ktx2");
        var data = ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(Math.toIntExact(data.getLong(80)), 0);
        var source = source("invalid_payload", invalid, TextureImageFilter.Role.BASE_COLOR, false);
        var store = new CpuTextureStore();
        try (var cache = new GltfPbrDynamicTextureCache(store)) {
            assertThrows(IllegalArgumentException.class, () -> cache.texture(source));
            assertTrue(store.images.isEmpty());
            assertEquals(0, cache.size());
            assertEquals(0, cache.decodedTextureBytes());
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
            cache.texture(source("valid_after_failure", fixture("cyan_rgb_reference_basis.ktx2"),
                    TextureImageFilter.Role.BASE_COLOR, false));
            assertEquals(1, cache.size());
        }
    }

    private static GltfPbrTextureSource source(String name, byte[] bytes, TextureImageFilter.Role role, boolean mipmapped) {
        var sampler = new GltfSamplerData(9729, mipmapped ? 9987 : 9729, 10497, 10497);
        return new GltfPbrTextureSource(key(name), new GltfImageData(name, name + ".ktx2", "image/ktx2", bytes),
                new GltfPbrTextureSource.Settings(sampler, role, GltfAlphaMode.OPAQUE, .5f, 1));
    }

    private static Identifier key(String name) { return Identifier.fromNamespaceAndPath("tacz", name); }

    private static byte[] linear(byte[] bytes) {
        byte[] copy = bytes.clone();
        int dfd = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).getInt(48);
        copy[dfd + 13] = 0;
        copy[dfd + 14] = 1;
        return copy;
    }

    private static byte[] fixture(String name) throws Exception {
        try (var input = GltfKtx2TextureCacheTest.class.getResourceAsStream("/gltf/ktx2/" + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    /** Runs real CPU mip generation/ownership, not the GPU backend or TextureManager. */
    private static final class CpuTextureStore implements GltfPbrDynamicTextureCache.TextureStore {
        private final List<NativeImage> images = new ArrayList<>();
        private final List<Identifier> released = new ArrayList<>();
        private final List<Integer> widths = new ArrayList<>();
        private int failAt = -1;
        private long uploadedBytes;

        @Override
        public void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
            images.add(image);
            try (image) {
                assertEquals(0, Ktx2ImageDecoder.liveTextures(), "libktx owner must close before registration");
                if (images.size() == failAt) throw new IllegalStateException("injected registration failure");
                GltfTextureMipmaps.upload(image, settings, (level, pixels) -> {
                    assertFalse(pixels.isClosed());
                    widths.add(pixels.getWidth());
                    uploadedBytes += pixels.getPixelBytes().remaining();
                });
            }
        }

        @Override public void release(Identifier id) { released.add(id); }
    }
}
