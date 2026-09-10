package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;
import com.tacz.guns.client.model.gltf.quality.TextureVariantPolicy;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GltfTextureMipmapsTest {
    private static final GltfSamplerData MIPPED = new GltfSamplerData(9729, 9987, 10497, 10497);

    @Test
    void nativeSamplerSubsetIsExplicitRatherThanAliasingUnsupportedModes() {
        for (int mag : new int[]{9728, 9729}) {
            for (int min : new int[]{9728, 9729, 9986, 9987}) {
                for (int wrap : new int[]{10497, 33071}) {
                    assertDoesNotThrow(() -> TextureVariantPolicy.validateSampler(new GltfSamplerData(mag, min, wrap, wrap)));
                }
            }
        }
        for (int min : new int[]{9984, 9985, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TextureVariantPolicy.validateSampler(new GltfSamplerData(9729, min, 10497, 10497)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> TextureVariantPolicy.validateSampler(new GltfSamplerData(9729, 9987, 33648, 10497)));
    }

    @Test
    void squareMipChainUsesEveryRealLevelAndClosesPixelsAfterTheUpload() {
        NativeImage base = new NativeImage(8, 8, true);
        List<NativeImage> uploads = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        GltfTextureMipmaps.upload(base, settings(TextureImageFilter.Role.ORM), (level, image) -> {
            assertEquals(uploads.size(), level);
            assertFalse(image.isClosed());
            uploads.add(image);
            widths.add(image.getWidth());
        });
        assertEquals(List.of(8, 4, 2, 1), widths);
        assertTrue(uploads.stream().allMatch(NativeImage::isClosed));
        assertEquals(340, GltfTextureMipmaps.gpuBytes(8, 8, true));
        assertEquals(357_913_940L, GltfTextureMipmaps.gpuBytes(8192, 8192, true));
    }

    @Test
    void noMipPathUploadsOnlyTheOriginalPixels() {
        NativeImage base = new NativeImage(8, 8, true);
        List<NativeImage> uploads = new ArrayList<>();
        GltfTextureMipmaps.upload(base, GltfPbrTextureSource.Settings.DEFAULT, (level, image) -> {
            assertEquals(0, level);
            assertSame(base, image);
            uploads.add(image);
        });
        assertEquals(1, uploads.size());
        assertTrue(base.isClosed());
        assertEquals(256, GltfTextureMipmaps.gpuBytes(8, 8, false));
    }

    @Test
    void rectangularAndNpotChainsExposeOnlyNonzeroNativeLevels() {
        for (int[] size : List.of(new int[]{3, 5}, new int[]{1, 8}, new int[]{8, 2}, new int[]{7, 9})) {
            int expectedLevels = 32 - Integer.numberOfLeadingZeros(Math.min(size[0], size[1]));
            long[] bytes = {0};
            int[] uploads = {0};
            GltfTextureMipmaps.upload(new NativeImage(size[0], size[1], true),
                    settings(TextureImageFilter.Role.ORM), (level, image) -> {
                        assertEquals(size[0] >> level, image.getWidth());
                        assertEquals(size[1] >> level, image.getHeight());
                        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
                        bytes[0] += (long) image.getWidth() * image.getHeight() * 4;
                        uploads[0]++;
                    });
            assertEquals(expectedLevels, uploads[0]);
            assertEquals(bytes[0], GltfTextureMipmaps.gpuBytes(size[0], size[1], true));
        }
        assertEquals(68, GltfTextureMipmaps.gpuBytes(3, 5, true));
        assertEquals(32, GltfTextureMipmaps.gpuBytes(1, 8, true));
    }

    @Test
    void failedLaterUploadClosesAllCreatedLevels() {
        NativeImage base = new NativeImage(8, 8, true);
        List<NativeImage> uploads = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> GltfTextureMipmaps.upload(base,
                settings(TextureImageFilter.Role.NORMAL), (level, image) -> {
                    uploads.add(image);
                    if (level == 2) throw new IllegalStateException("upload failure");
                }));
        assertEquals(3, uploads.size());
        assertTrue(uploads.stream().allMatch(NativeImage::isClosed));
    }

    @Test
    void colorMipIsFilteredInLinearLightAndNormalMipIsRenormalized() {
        NativeImage colors = new NativeImage(2, 2, true);
        colors.setPixel(0, 0, 0xff000000);
        colors.setPixel(0, 1, 0xff000000);
        colors.setPixel(1, 0, 0xffffffff);
        colors.setPixel(1, 1, 0xffffffff);
        GltfTextureMipmaps.upload(colors, settings(TextureImageFilter.Role.BASE_COLOR), (level, image) -> {
            if (level == 1) {
                int red = image.getPixel(0, 0) >> 16 & 255;
                assertTrue(red >= 180 && red <= 195, "sRGB midpoint must not be the encoded midpoint 128");
            }
        });
        NativeImage normals = new NativeImage(2, 2, true);
        normals.fillRect(0, 0, 2, 2, 0xff80b3b3);
        GltfTextureMipmaps.upload(normals, settings(TextureImageFilter.Role.NORMAL), (level, image) -> {
            if (level == 1) {
                int pixel = image.getPixel(0, 0);
                double x = ((pixel >> 16 & 255) / 127.5) - 1;
                double y = ((pixel >> 8 & 255) / 127.5) - 1;
                double z = ((pixel & 255) / 127.5) - 1;
                assertEquals(1, Math.sqrt(x * x + y * y + z * z), .015);
            }
        });
    }

    @Test
    void contentSharingSeparatesMipRolesButNotNonMipSamplerState() throws Exception {
        GltfImageData image = png(4, 4);
        Identifier first = Identifier.fromNamespaceAndPath("test", "first");
        Identifier second = Identifier.fromNamespaceAndPath("test", "second");
        var color = new GltfPbrTextureSource(first, image, settings(TextureImageFilter.Role.BASE_COLOR));
        var alias = new GltfPbrTextureSource(second, image, settings(TextureImageFilter.Role.BASE_COLOR));
        var normal = new GltfPbrTextureSource(first, image, settings(TextureImageFilter.Role.NORMAL));
        assertEquals(color.dynamicId(), alias.dynamicId());
        assertNotEquals(color.dynamicId(), normal.dynamicId());
        assertEquals(84, color.decodedBytes());
        var plain = new GltfPbrTextureSource(first, image);
        var nearestClamp = new GltfPbrTextureSource(second, image, new GltfPbrTextureSource.Settings(
                new GltfSamplerData(9728, 9728, 33071, 33071), TextureImageFilter.Role.NORMAL,
                GltfAlphaMode.OPAQUE, .5f, 1));
        assertEquals(plain.dynamicId(), nearestClamp.dynamicId());
        assertNotEquals(plain.dynamicId(), color.dynamicId());
    }

    @Test
    void fullMipBudgetAppliesBeforeDecodeAndMaterialBindingsKeepTheirSampler() throws Exception {
        var image = png(4, 4);
        var id = Identifier.fromNamespaceAndPath("test", "image");
        var color = new GltfPbrTextureSource(id, image, settings(TextureImageFilter.Role.BASE_COLOR));
        var alias = new GltfPbrTextureSource(Identifier.fromNamespaceAndPath("test", "alias"), image,
                settings(TextureImageFilter.Role.BASE_COLOR));
        var normal = new GltfPbrTextureSource(id, image, settings(TextureImageFilter.Role.NORMAL));
        TestStore rejected = new TestStore();
        try (var cache = new GltfPbrDynamicTextureCache(rejected, 83)) {
            assertThrows(IllegalArgumentException.class, () -> cache.texture(color));
            assertEquals(0, rejected.registrations);
            assertEquals(0, cache.decodedTextureBytes());
        }
        TestStore accepted = new TestStore();
        try (var cache = new GltfPbrDynamicTextureCache(accepted, 84)) {
            var material = cache.material(id, input(color, null));
            assertEquals(MIPPED, material.samplers().baseColor());
            assertEquals(GltfSamplerData.DEFAULT, material.samplers().normal());
            assertEquals(color.dynamicId(), cache.texture(alias));
            assertEquals(1, accepted.registrations);
            assertEquals(84, cache.decodedTextureBytes());
            assertThrows(IllegalArgumentException.class, () -> cache.texture(normal));
            assertEquals(Set.of(color.dynamicId()), accepted.active);
            cache.clear();
            assertTrue(accepted.active.isEmpty());
            assertEquals(0, cache.decodedTextureBytes());
        }
    }

    @Test
    void laterMipRegistrationFailureRollsBackNewOwnersWithoutReleasingSharedImages() throws Exception {
        var id = Identifier.fromNamespaceAndPath("test", "image");
        var color = new GltfPbrTextureSource(id, png(4, 4), settings(TextureImageFilter.Role.BASE_COLOR));
        var normal = new GltfPbrTextureSource(id, png(4, 4), settings(TextureImageFilter.Role.NORMAL));
        TestStore store = new TestStore();
        try (var cache = new GltfPbrDynamicTextureCache(store, 168)) {
            cache.texture(color);
            store.failId = normal.dynamicId();
            assertThrows(IllegalArgumentException.class, () -> cache.material(id, input(color, normal)));
            assertEquals(Set.of(color.dynamicId()), store.active);
            assertEquals(84, cache.decodedTextureBytes());
            assertEquals(1, cache.size());
            assertTrue(store.uploaded.stream().allMatch(NativeImage::isClosed));
        }
        assertTrue(store.active.isEmpty());
    }

    @Test
    void factorsCannotBypassMipAccountingAndRollbackReleasesOnlyTheNewChain() throws Exception {
        var id = Identifier.fromNamespaceAndPath("test", "factors");
        var image = png(4, 4);
        var existing = new GltfPbrTextureSource(id, image, settings(TextureImageFilter.Role.BASE_COLOR));
        var added = new GltfPbrTextureSource(id, image, settings(TextureImageFilter.Role.NORMAL));
        var factors = new GltfPbrMaterialFactors(new float[]{.5f, 1, 1, 1}, 1, 1, 1, 1,
                new float[]{0, 0, 0}, .5f);
        TestStore store = new TestStore();
        try (var cache = new GltfPbrDynamicTextureCache(store, 168)) {
            cache.texture(existing);
            assertThrows(IllegalArgumentException.class, () -> cache.material(id, new GltfPbrMaterialInput(
                    new GltfPbrTextureSlots(existing, null, added, null, null), factors,
                    GltfPbrAlphaMode.OPAQUE, true)));
            assertEquals(Set.of(existing.dynamicId()), store.active);
            assertEquals(84, cache.decodedTextureBytes());
            cache.material(id, new GltfPbrMaterialInput(GltfPbrTextureSlots.empty(), factors,
                    GltfPbrAlphaMode.OPAQUE, true));
            assertEquals(88, cache.decodedTextureBytes());
            assertThrows(IllegalArgumentException.class, () -> cache.texture(added));
        }
        assertTrue(store.active.isEmpty());
    }

    @Test
    void clampAndWrapAreDifferentFilterEdgesAndMaskCoverageUsesTheOriginalReference() {
        int wrap = filteredEdge(10497), clamp = filteredEdge(33071);
        assertNotEquals(wrap, clamp, "Texture filtering must honor authored S/T edge modes");
        NativeImage masked = new NativeImage(8, 8, true);
        masked.fillRect(0, 0, 8, 8, 0x00ffffff);
        masked.fillRect(0, 0, 4, 8, 0xffffffff);
        var spec = new GltfPbrTextureSource.Settings(MIPPED, TextureImageFilter.Role.BASE_COLOR,
                GltfAlphaMode.MASK, .5f, 1);
        GltfTextureMipmaps.upload(masked, spec, (level, image) -> {
            if (image.getWidth() < 2) return;
            int covered = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getPixel(x, y) >>> 24) >= 128) covered++;
                }
            }
            assertEquals(.5, (double) covered / (image.getWidth() * image.getHeight()), .01);
        });
    }

    private static int filteredEdge(int wrap) {
        NativeImage base = new NativeImage(8, 8, true);
        base.fillRect(0, 0, 1, 8, 0xffffffff);
        int[] edge = {0};
        var spec = new GltfPbrTextureSource.Settings(new GltfSamplerData(9729, 9987, wrap, wrap),
                TextureImageFilter.Role.ORM, GltfAlphaMode.OPAQUE, .5f, 1);
        GltfTextureMipmaps.upload(base, spec, (level, image) -> {
            if (level == 1) edge[0] = image.getPixel(0, 1);
        });
        return edge[0];
    }

    private static GltfPbrMaterialInput input(GltfPbrTextureSource color, GltfPbrTextureSource normal) {
        return new GltfPbrMaterialInput(new GltfPbrTextureSlots(color, null, normal, null, null),
                GltfPbrMaterialFactors.defaults(), GltfPbrAlphaMode.OPAQUE, true);
    }

    private static final class TestStore implements GltfPbrDynamicTextureCache.TextureStore {
        private final Set<Identifier> active = new HashSet<>();
        private final List<NativeImage> uploaded = new ArrayList<>();
        private int registrations;
        private Identifier failId;

        @Override
        public void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
            registrations++;
            // Exercise the production mip generator and ownership protocol, not GPU driver behavior.
            GltfTextureMipmaps.upload(image, settings, (level, pixels) -> {
                uploaded.add(pixels);
                if (id.equals(failId) && level == 1) throw new IllegalStateException("later upload failed");
            });
            active.add(id);
        }

        @Override
        public void release(Identifier id) { assertTrue(active.remove(id)); }
    }

    private static GltfPbrTextureSource.Settings settings(TextureImageFilter.Role role) {
        return new GltfPbrTextureSource.Settings(MIPPED, role, GltfAlphaMode.OPAQUE, .5f, 1);
    }

    private static GltfImageData png(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes));
        return new GltfImageData("image", "", "image/png", bytes.toByteArray());
    }
}
