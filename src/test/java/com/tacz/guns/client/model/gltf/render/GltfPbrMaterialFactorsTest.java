package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfPbrMaterialFactorsTest {
    @Test
    void quantizesCoreFactorsToNearestUnorm8() {
        GltfPbrMaterialFactors factors = new GltfPbrMaterialFactors(
                new float[]{1.0f, 0.5f, 0.0f, 0.25f},
                0.5f,
                1.0f,
                1.0f,
                0.25f,
                new float[]{0.0f, 0.5f, 1.0f},
                0.5f
        );

        assertEquals(new GltfPbrFactorEncoding(255, 128, 0, 64), factors.baseColorEncoding());
        assertEquals(new GltfPbrFactorEncoding(0, 128, 255, 128), factors.emissiveEncoding());
        assertEquals(new GltfPbrFactorEncoding(128, 255, 64, 64), factors.parametersEncoding());
    }

    @Test
    void defaultEncodingsMatchTheBundledFallbackTexels() throws IOException {
        GltfPbrMaterialFactors defaults = GltfPbrMaterialFactors.defaults();

        assertEquals(new GltfPbrFactorEncoding(255, 255, 255, 255), defaults.baseColorEncoding());
        assertEquals(new GltfPbrFactorEncoding(0, 0, 0, 128), defaults.emissiveEncoding());
        assertEquals(new GltfPbrFactorEncoding(255, 255, 64, 255), defaults.parametersEncoding());
        assertEquals(defaults.baseColorEncoding().argb(), readFallbackArgb("base_color_factor.png"));
        assertEquals(defaults.emissiveEncoding().argb(), readFallbackArgb("emissive_factor.png"));
        assertEquals(defaults.parametersEncoding().argb(), readFallbackArgb("pbr_parameters.png"));
        // glTF's default metallic and roughness factors are both 1, so an absent MR map must
        // contribute G=1 (roughness) and B=1 (metallic).
        assertEquals(0xFF00FFFF, readFallbackArgb("metallic_roughness.png"));
    }

    @Test
    void defensivelyCopiesMutableInputs() throws IOException {
        float[] baseColor = {1.0f, 0.5f, 0.25f, 1.0f};
        float[] emissive = {0.1f, 0.2f, 0.3f};
        byte[] encodedImage = readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png");
        GltfPbrMaterialFactors factors = new GltfPbrMaterialFactors(
                baseColor, 1.0f, 1.0f, 1.0f, 1.0f, emissive, 0.5f
        );
        GltfPbrTextureSource source = new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("tacz", "models/gltf/test.png"),
                encodedImage
        );

        baseColor[0] = 0.0f;
        emissive[0] = 1.0f;
        encodedImage[0] = 9;
        assertArrayEquals(new float[]{1.0f, 0.5f, 0.25f, 1.0f}, factors.baseColorFactor());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, factors.emissiveFactor());
        assertNotEquals(9, source.encodedImage()[0]);

        float[] returnedBaseColor = factors.baseColorFactor();
        byte[] returnedImage = source.encodedImage();
        returnedBaseColor[0] = 0.0f;
        returnedImage[0] = 9;
        assertNotEquals(0.0f, factors.baseColorFactor()[0]);
        assertNotEquals(9, source.encodedImage()[0]);
    }

    @Test
    void rejectsValuesThatCannotBeRepresentedByTheV1ParameterTextures() {
        assertThrows(IllegalArgumentException.class, () -> new GltfPbrMaterialFactors(
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f,
                1.0f,
                4.01f,
                1.0f,
                new float[]{0.0f, 0.0f, 0.0f},
                0.5f
        ));
        assertThrows(IllegalArgumentException.class, () -> new GltfPbrMaterialFactors(
                new float[]{Float.NaN, 1.0f, 1.0f, 1.0f},
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                new float[]{0.0f, 0.0f, 0.0f},
                0.5f
        ));
        assertThrows(IllegalArgumentException.class, () -> new GltfPbrMaterialFactors(
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                new float[]{0.0f, 0.0f, 1.1f},
                0.5f
        ));
    }

    @Test
    void precomputesStableImageIdsAtSourceConstruction() throws IOException {
        Identifier firstKey = Identifier.fromNamespaceAndPath("pack", "models/rifle/base.png");
        Identifier secondKey = Identifier.fromNamespaceAndPath("pack", "models/pistol/base.png");
        byte[] firstContent = readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png");
        byte[] secondContent = readResourceBytes("/assets/tacz/textures/pbr/fallback/normal.png");
        GltfPbrTextureSource first = new GltfPbrTextureSource(firstKey, firstContent);
        GltfPbrTextureSource same = new GltfPbrTextureSource(firstKey, firstContent.clone());
        GltfPbrTextureSource otherKey = new GltfPbrTextureSource(secondKey, firstContent);
        GltfPbrTextureSource otherContent = new GltfPbrTextureSource(firstKey, secondContent);

        assertEquals(first.dynamicId(), same.dynamicId());
        assertEquals(first.dynamicId(), otherKey.dynamicId());
        assertNotEquals(first.dynamicId(), otherContent.dynamicId());
        assertEquals(4L, first.decodedBytes());
        assertTrue(first.dynamicId().getPath().matches("dynamic/gltf/image/[0-9a-f]{64}"));
    }

    @Test
    void immutableAssetImageIsSharedWithoutRetainingAnotherEncodedCopy() throws Exception {
        byte[] original = readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png");
        byte[] expected = original.clone();
        GltfImageData image = new GltfImageData("base", "base.png", "image/png", original);
        GltfPbrTextureSource source = new GltfPbrTextureSource(materialKey("shared"), image);
        Identifier identity = source.dynamicId();

        original[0] = 0;
        image.encodedBytes()[0] = 0;
        source.encodedImage()[0] = 0;
        assertArrayEquals(expected, source.encodedImage());
        assertEquals(identity, source.dynamicId());

        // This is a retention assertion, not a heap-size estimate: the source keeps the converted
        // asset's immutable image object and has no second long-lived encoded byte array.
        var imageField = GltfPbrTextureSource.class.getDeclaredField("image");
        imageField.setAccessible(true);
        assertSame(image, imageField.get(source));
        for (var field : GltfPbrTextureSource.class.getDeclaredFields()) {
            assertNotEquals(byte[].class, field.getType());
        }
    }

    @Test
    void repeatedMaterialAndDifferentResourceKeysShareAnImage() throws IOException {
        TestTextureStore store = new TestTextureStore();
        Identifier sourceKey = Identifier.fromNamespaceAndPath("pack", "models/rifle/base.png");
        GltfPbrTextureSource source = new GltfPbrTextureSource(
                sourceKey,
                readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png")
        );

        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store)) {
            assertEquals(0, store.registrationCount);
            cache.material(materialKey("first"), materialInput(source, null, null));
            assertEquals(1, store.registrationCount);

            source.encodedImage()[0] = 0;
            cache.material(materialKey("second"), materialInput(source, null, null));
            GltfPbrTextureSource alias = new GltfPbrTextureSource(
                    Identifier.fromNamespaceAndPath("other_pack", "models/pistol/base.png"),
                    source.encodedImage()
            );
            cache.material(materialKey("third"), materialInput(alias, null, null));

            assertEquals(1, store.registrationCount);
            assertEquals(Set.of(source.dynamicId()), store.activeIds);
            assertEquals(1, cache.size());
            assertEquals(4L, cache.decodedTextureBytes());

            cache.clear();
            assertEquals(List.of(source.dynamicId()), store.releasedIds);
            assertEquals(0, cache.size());
            assertEquals(0L, cache.decodedTextureBytes());
            cache.texture(alias);
            assertEquals(2, store.registrationCount);
        }
        assertEquals(2, Collections.frequency(store.releasedIds, source.dynamicId()));
    }

    @Test
    void failedMaterialRollsBackOnlyItsNewTexturesAndDecodedBytes() throws IOException {
        TestTextureStore store = new TestTextureStore();
        byte[] validPng = readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png");
        GltfPbrTextureSource existing = new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("pack", "models/existing.png"),
                validPng
        );
        GltfPbrTextureSource newlyAdded = new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("pack", "models/new.png"),
                readResourceBytes("/assets/tacz/textures/pbr/fallback/normal.png")
        );
        GltfPbrTextureSource existingAlias = new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("another_pack", "models/existing.png"),
                validPng
        );
        GltfPbrTextureSource corruptBody = new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("pack", "models/corrupt.png"),
                onePixelPngHeaderOnly()
        );

        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store)) {
            cache.material(materialKey("existing"), materialInput(existing, null, null));
            int startingSize = cache.size();
            long startingBytes = cache.decodedTextureBytes();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.material(
                            materialKey("failing"),
                            materialInput(existingAlias, newlyAdded, corruptBody)
                    )
            );

            assertEquals(startingSize, cache.size());
            assertEquals(startingBytes, cache.decodedTextureBytes());
            assertEquals(Set.of(existing.dynamicId()), store.activeIds);
            assertEquals(1, Collections.frequency(store.releasedIds, newlyAdded.dynamicId()));
            assertEquals(0, Collections.frequency(store.releasedIds, existing.dynamicId()));

            cache.material(materialKey("reuse"), materialInput(existing, null, null));
            assertEquals(2, store.registrationCount);
        }
    }

    @Test
    void registrationFailureRollsBackNewTexturesButPreservesSharedOwners() throws IOException {
        TestTextureStore store = new TestTextureStore();
        GltfPbrTextureSource existing = source("existing", 0xFFFFFFFF);
        GltfPbrTextureSource alias = new GltfPbrTextureSource(materialKey("alias"), existing.encodedImage());
        GltfPbrTextureSource added = source("added", 0xFFFF0000);
        GltfPbrTextureSource failed = source("failed", 0xFF0000FF);
        store.failRegistrationId = failed.dynamicId();

        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store)) {
            cache.texture(existing);
            assertThrows(IllegalArgumentException.class, () -> cache.material(
                    materialKey("failed"), materialInput(alias, added, failed)
            ));
            assertTrue(store.failedImage.isClosed());
            assertEquals(Set.of(existing.dynamicId()), store.activeIds);
            assertEquals(List.of(added.dynamicId()), store.releasedIds);
            assertEquals(1, cache.size());
            assertEquals(4L, cache.decodedTextureBytes());

            store.failRegistrationId = null;
            cache.texture(failed);
            assertEquals(2, cache.size());
            assertEquals(8L, cache.decodedTextureBytes());
        }
        assertEquals(1, Collections.frequency(store.releasedIds, existing.dynamicId()));
        assertEquals(1, Collections.frequency(store.releasedIds, failed.dynamicId()));
    }

    @Test
    void materialFactorIdsRemainMaterialScoped() {
        byte[] rgba = {1, 2, 3, 4};
        Identifier first = GltfPbrDynamicTextureCache.dynamicId("base_color_factor", materialKey("first"), rgba);
        assertEquals(first, GltfPbrDynamicTextureCache.dynamicId("base_color_factor", materialKey("first"), rgba.clone()));
        assertNotEquals(first, GltfPbrDynamicTextureCache.dynamicId("base_color_factor", materialKey("second"), rgba));
        assertNotEquals(first, GltfPbrDynamicTextureCache.dynamicId("emissive_factor", materialKey("first"), rgba));
    }

    @Test
    void closeIsIdempotentForSharedImagesAndRejectsFurtherUse() throws IOException {
        TestTextureStore store = new TestTextureStore();
        GltfPbrTextureSource source = source("close", 0xFFFFFFFF);
        GltfPbrTextureSource alias = new GltfPbrTextureSource(materialKey("alias"), source.encodedImage());
        GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store);
        try {
            cache.texture(source);
            cache.texture(alias);
            cache.close();
            cache.close();
            assertEquals(List.of(source.dynamicId()), store.releasedIds);
            assertTrue(store.activeIds.isEmpty());
            assertThrows(IllegalStateException.class, () -> cache.texture(alias));
            assertThrows(IllegalStateException.class, cache::clear);
        } finally {
            cache.close();
        }
    }

    @Test
    void decodesRealPngAndJpegPayloads() throws IOException {
        Identifier key = Identifier.fromNamespaceAndPath("pack", "models/rifle/base.png");
        byte[] png = readResourceBytes("/assets/tacz/textures/pbr/fallback/base_color.png");
        byte[] jpeg = encodeJpeg(0xFFFFFFFF);

        GltfPbrDynamicTextureCache.validateEncodedImage(key, png);
        GltfPbrDynamicTextureCache.validateEncodedImage(key, jpeg);
        try (NativeImage decodedPng = GltfPbrDynamicTextureCache.decodeImage(key, png);
             NativeImage decodedJpeg = GltfPbrDynamicTextureCache.decodeImage(key, jpeg)) {
            assertEquals(1, decodedPng.getWidth());
            assertEquals(1, decodedPng.getHeight());
            assertEquals(0xFFFFFFFF, decodedPng.getPixel(0, 0));
            assertEquals(1, decodedJpeg.getWidth());
            assertEquals(1, decodedJpeg.getHeight());
            assertEquals(0xFFFFFFFF, decodedJpeg.getPixel(0, 0));
        }
    }

    @Test
    void rejectsTruncatedAndUnsupportedImagePayloadsBeforeNativeDecode() {
        Identifier key = Identifier.fromNamespaceAndPath("pack", "models/rifle/base.png");
        byte[] truncatedPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateEncodedImage(key, truncatedPng)
        );
        assertThrows(IllegalArgumentException.class, () -> new GltfPbrTextureSource(key, truncatedPng));
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateEncodedImage(
                        key,
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateEncodedImage(key, new byte[]{1, 2, 3})
        );
    }

    @Test
    void rejectsOversizedDimensionsFromHeadersBeforeDecode() {
        Identifier key = Identifier.fromNamespaceAndPath("pack", "models/rifle/base.png");

        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateEncodedImage(key, oversizedPngHeader())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateEncodedImage(key, oversizedJpegHeader())
        );
    }

    @Test
    void enforcesAggregateDecodedTextureBudgetWithoutOverflow() {
        long limit = GltfPbrDynamicTextureCache.MAX_DECODED_TEXTURE_BYTES;

        GltfPbrDynamicTextureCache.validateDecodedBudget(limit - 4L, 4L);
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateDecodedBudget(limit - 3L, 4L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateDecodedBudget(-1L, 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateDecodedBudget(Long.MAX_VALUE, Long.MAX_VALUE)
        );
    }

    @Test
    void supportsAnExplicitTwoGibBoundaryWithoutChangingTheDefault() {
        long explicitLimit = 2L * 1024L * 1024L * 1024L;

        assertEquals(512L * 1024L * 1024L, GltfPbrDynamicTextureCache.MAX_DECODED_TEXTURE_BYTES);
        GltfPbrDynamicTextureCache.validateDecodedBudget(explicitLimit - 4L, 4L, explicitLimit);
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateDecodedBudget(explicitLimit - 3L, 4L, explicitLimit)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPbrDynamicTextureCache.validateDecodedBudget(
                        GltfPbrDynamicTextureCache.MAX_DECODED_TEXTURE_BYTES,
                        1L,
                        GltfPbrDynamicTextureCache.MAX_DECODED_TEXTURE_BYTES
                )
        );
    }

    @Test
    void oneBudgetCoversEveryMaterialAndDirectTextureAndSurvivesClear() throws IOException {
        TestTextureStore store = new TestTextureStore();
        GltfPbrTextureSource first = source("first", 0xFFFFFFFF);
        GltfPbrTextureSource alias = new GltfPbrTextureSource(materialKey("other_pack"), first.encodedImage());
        GltfPbrTextureSource second = source("second", 0xFFFF0000);
        GltfPbrTextureSource third = source("third", 0xFF0000FF);
        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store, 8L)) {
            cache.material(materialKey("first"), materialInput(first, null, null));
            cache.material(materialKey("alias"), materialInput(alias, null, null));
            assertEquals(4L, cache.decodedTextureBytes());
            cache.material(materialKey("second"), materialInput(second, null, null));
            assertEquals(8L, cache.decodedTextureBytes());
            assertThrows(IllegalArgumentException.class, () -> cache.texture(third));
            assertEquals(2, store.registrationCount);
            assertEquals(2, cache.size());

            cache.clear();
            assertEquals(0L, cache.decodedTextureBytes());
            cache.texture(third);
            cache.texture(first);
            assertEquals(8L, cache.decodedTextureBytes());
            assertThrows(IllegalArgumentException.class, () -> cache.material(
                    materialKey("second"), materialInput(second, null, null)
            ));
            assertEquals(Set.of(first.dynamicId(), third.dynamicId()), store.activeIds);
        }
    }

    @Test
    void noMaterialParameterSlotCanBypassTheGlobalBudget() {
        List<GltfPbrMaterialFactors> parameters = List.of(
                factors(new float[]{0.5f, 1, 1, 1}, 1, new float[]{0, 0, 0}),
                factors(new float[]{1, 1, 1, 1}, 1, new float[]{1, 0, 0}),
                factors(new float[]{1, 1, 1, 1}, 0.5f, new float[]{0, 0, 0})
        );
        for (GltfPbrMaterialFactors factors : parameters) {
            TestTextureStore store = new TestTextureStore();
            try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store, 0L)) {
                cache.material(materialKey("default"), materialInput(null, null, null));
                assertThrows(IllegalArgumentException.class, () -> cache.material(
                        materialKey("parameter"), materialInput(null, null, null, factors)
                ));
                assertEquals(0, store.registrationCount);
                assertEquals(0L, cache.decodedTextureBytes());
            }
        }
    }

    @Test
    void factorBudgetFailureRollsBackOnlyNewImagesAndKeepsTheBudgetAvailable() throws IOException {
        TestTextureStore store = new TestTextureStore();
        GltfPbrTextureSource existing = source("existing", 0xFFFFFFFF);
        GltfPbrTextureSource alias = new GltfPbrTextureSource(materialKey("alias"), existing.encodedImage());
        GltfPbrTextureSource added = source("added", 0xFFFF0000);
        GltfPbrMaterialFactors factors = factors(new float[]{0.5f, 1, 1, 1}, 1, new float[]{0, 0, 0});
        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store, 8L)) {
            cache.texture(existing);
            assertThrows(IllegalArgumentException.class, () -> cache.material(
                    materialKey("too_large"), materialInput(alias, added, null, factors)
            ));
            assertEquals(List.of(added.dynamicId()), store.releasedIds);
            assertEquals(Set.of(existing.dynamicId()), store.activeIds);
            assertEquals(4L, cache.decodedTextureBytes());

            cache.material(materialKey("factor"), materialInput(null, null, null, factors));
            assertEquals(8L, cache.decodedTextureBytes());
            assertThrows(IllegalArgumentException.class, () -> cache.material(
                    materialKey("other_factor"), materialInput(null, null, null, factors)
            ));
            cache.material(materialKey("factor"), materialInput(alias, null, null, factors));
            assertEquals(8L, cache.decodedTextureBytes());
            assertEquals(2, cache.size());
        }
    }

    @Test
    void invalidBudgetDoesNotAcquireCacheOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new GltfPbrDynamicTextureCache(new TestTextureStore(), -1L));
        try (GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(new TestTextureStore())) {
            assertEquals(0L, cache.decodedTextureBytes());
        }
    }

    @Test
    void clearAttemptsEveryReleaseAndResetsLocalStateAfterFailures() throws IOException {
        FailingReleaseTextureStore store = new FailingReleaseTextureStore();
        GltfPbrTextureSource first = source("clear_first", 0xFFFFFFFF);
        GltfPbrTextureSource second = source("clear_second", 0xFFFF0000);
        GltfPbrTextureSource third = source("clear_third", 0xFF0000FF);
        GltfPbrDynamicTextureCache cache = new GltfPbrDynamicTextureCache(store);
        try {
            cache.material(materialKey("clear"), materialInput(first, second, third));
            store.failOn(first.dynamicId());
            store.failOn(third.dynamicId());

            RuntimeException failure = assertThrows(RuntimeException.class, cache::clear);

            assertEquals(Set.of(first.dynamicId(), second.dynamicId(), third.dynamicId()), store.releaseAttempts);
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(0, cache.size());
            assertEquals(0L, cache.decodedTextureBytes());
        } finally {
            cache.close();
        }
    }

    @Test
    void closeReleasesGlobalOwnershipEvenWhenTextureCleanupFails() throws IOException {
        FailingReleaseTextureStore store = new FailingReleaseTextureStore();
        GltfPbrTextureSource source = source("close_failure", 0xFFFFFFFF);
        GltfPbrDynamicTextureCache first = new GltfPbrDynamicTextureCache(store);
        first.material(materialKey("close"), materialInput(source, null, null));
        store.failOn(source.dynamicId());

        assertThrows(RuntimeException.class, first::close);
        assertEquals(Set.of(source.dynamicId()), store.releaseAttempts);

        try (GltfPbrDynamicTextureCache replacement = new GltfPbrDynamicTextureCache(new TestTextureStore())) {
            assertEquals(0, replacement.size());
        }
    }

    private static int readFallbackArgb(String fileName) throws IOException {
        String path = "/assets/tacz/textures/pbr/fallback/" + fileName;
        try (InputStream stream = GltfPbrMaterialFactorsTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + path);
            }
            BufferedImage image = ImageIO.read(stream);
            assertEquals(1, image.getWidth());
            assertEquals(1, image.getHeight());
            return image.getRGB(0, 0);
        }
    }

    private static byte[] readResourceBytes(String path) throws IOException {
        try (InputStream stream = GltfPbrMaterialFactorsTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + path);
            }
            return stream.readAllBytes();
        }
    }

    private static GltfPbrTextureSource source(String suffix, int color) throws IOException {
        return new GltfPbrTextureSource(
                Identifier.fromNamespaceAndPath("pack", "models/" + suffix + ".png"),
                encodeJpeg(color)
        );
    }

    private static byte[] encodeJpeg(int argb) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, argb);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "jpeg", output));
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static byte[] oversizedPngHeader() {
        byte[] header = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x20, 0x01,
                0x00, 0x00, 0x00, 0x01
        };
        return header;
    }

    private static byte[] onePixelPngHeaderOnly() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01
        };
    }

    private static byte[] oversizedJpegHeader() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xC0,
                0x00, 0x11,
                0x08,
                0x00, 0x01,
                0x20, 0x01,
                0x03,
                0x01, 0x11, 0x00,
                0x02, 0x11, 0x00,
                0x03, 0x11, 0x00
        };
    }

    private static Identifier materialKey(String suffix) {
        return Identifier.fromNamespaceAndPath("pack", "materials/" + suffix);
    }

    private static GltfPbrMaterialInput materialInput(
            GltfPbrTextureSource baseColor,
            GltfPbrTextureSource metallicRoughness,
            GltfPbrTextureSource normal
    ) {
        return materialInput(baseColor, metallicRoughness, normal, GltfPbrMaterialFactors.defaults());
    }

    private static GltfPbrMaterialFactors factors(float[] baseColor, float metallic, float[] emissive) {
        return new GltfPbrMaterialFactors(baseColor, metallic, 1, 1, 1, emissive, 0.5f);
    }

    private static GltfPbrMaterialInput materialInput(
            GltfPbrTextureSource baseColor,
            GltfPbrTextureSource metallicRoughness,
            GltfPbrTextureSource normal,
            GltfPbrMaterialFactors factors
    ) {
        return new GltfPbrMaterialInput(
                new GltfPbrTextureSlots(baseColor, metallicRoughness, normal, null, null),
                factors,
                GltfPbrAlphaMode.OPAQUE,
                true
        );
    }

    private static final class TestTextureStore implements GltfPbrDynamicTextureCache.TextureStore {
        private final Set<Identifier> activeIds = new LinkedHashSet<>();
        private final List<Identifier> releasedIds = new ArrayList<>();
        private int registrationCount;
        private Identifier failRegistrationId;
        private NativeImage failedImage;

        @Override
        public void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
            try {
                if (id.equals(failRegistrationId)) {
                    failedImage = image;
                    throw new IllegalStateException("injected registration failure");
                }
                assertTrue(activeIds.add(id), "duplicate texture registration " + id);
                registrationCount++;
            } finally {
                image.close();
            }
        }

        @Override
        public void release(Identifier id) {
            assertTrue(activeIds.remove(id), "release of unknown texture " + id);
            releasedIds.add(id);
        }
    }

    private static final class FailingReleaseTextureStore implements GltfPbrDynamicTextureCache.TextureStore {
        private final Set<Identifier> activeIds = new LinkedHashSet<>();
        private final Set<Identifier> releaseAttempts = new LinkedHashSet<>();
        private final Set<Identifier> failingIds = new LinkedHashSet<>();

        private void failOn(Identifier id) {
            failingIds.add(id);
        }

        @Override
        public void register(Identifier id, String label, NativeImage image, GltfPbrTextureSource.Settings settings) {
            try {
                assertTrue(activeIds.add(id), "duplicate texture registration " + id);
            } finally {
                image.close();
            }
        }

        @Override
        public void release(Identifier id) {
            releaseAttempts.add(id);
            activeIds.remove(id);
            if (failingIds.contains(id)) {
                throw new IllegalStateException("injected release failure for " + id);
            }
        }
    }
}
