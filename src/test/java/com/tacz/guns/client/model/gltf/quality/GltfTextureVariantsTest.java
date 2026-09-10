package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.*;
import com.tacz.guns.client.model.gltf.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GltfTextureVariantsTest {
    @TempDir Path directory;

    @Test
    void cancelledGenerationDoesNotInspectSourceOrPublishDiagnostics() {
        var before = GltfTextureVariants.snapshot();
        var original = asset(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        assertThrows(CancellationException.class, () -> GltfTextureVariants.apply(original,
                new GltfRenderQuality(512, 0, 1), directory, () -> true));
        assertSame(before, GltfTextureVariants.snapshot());
    }

    @Test
    void filterCancellationAfterDecodeLeavesTheSourceUsable() throws Exception {
        byte[] source = png(8, 4, new int[32]);
        var calls = new AtomicInteger();
        var filter = spec(TextureImageFilter.Role.BASE_COLOR, 4);
        assertThrows(CancellationException.class,
                () -> TextureImageFilter.resize(source, filter, () -> calls.incrementAndGet() >= 3));
        assertEquals(3, calls.get());
        assertEquals(4, TextureImageFilter.resize(source, filter).width());
    }

    @Test
    void filtersColorInLinearLightButOrmInLinearDataSpace() throws Exception {
        byte[] source = png(2, 1, new int[]{0xFF000000, 0xFFFFFFFF});
        int color = pixel(TextureImageFilter.resize(source, spec(TextureImageFilter.Role.BASE_COLOR, 1)).encoded(), 0, 0);
        int orm = pixel(TextureImageFilter.resize(source, spec(TextureImageFilter.Role.ORM, 1)).encoded(), 0, 0);
        assertEquals(188, color >> 16 & 255, 2);
        assertEquals(128, orm >> 16 & 255, 2);
        assertEquals(255, color >>> 24);
    }

    @Test
    void transparencyIsAlphaWeightedAndAlphaRemainsLinear() throws Exception {
        byte[] source = png(2, 1, new int[]{0xFFFF0000, 0x0000FF00});
        var blend = new TextureImageFilter.Spec(TextureImageFilter.Role.BASE_COLOR, 1,
                GltfAlphaMode.BLEND, 0.5f, 1, GltfSamplerData.DEFAULT);
        int result = pixel(TextureImageFilter.resize(source, blend).encoded(), 0, 0);
        assertEquals(255, result >> 16 & 255, 1);
        assertEquals(0, result >> 8 & 255, 1);
        assertEquals(128, result >>> 24, 2);

        int opaque = pixel(TextureImageFilter.resize(source, spec(TextureImageFilter.Role.BASE_COLOR, 1)).encoded(), 0, 0);
        assertEquals(188, opaque >> 8 & 255, 2);
    }

    @Test
    void normalsAreRenormalizedAndNeverAlphaWeighted() throws Exception {
        byte[] source = png(2, 1, new int[]{0xFFFF8080, 0x008080FF});
        int normal = pixel(TextureImageFilter.resize(source, spec(TextureImageFilter.Role.NORMAL, 1)).encoded(), 0, 0);
        double x = (normal >> 16 & 255) / 127.5 - 1;
        double y = (normal >> 8 & 255) / 127.5 - 1;
        double z = (normal & 255) / 127.5 - 1;
        assertEquals(1, Math.sqrt(x * x + y * y + z * z), 0.015);
        assertEquals(x, z, 0.015);
        assertTrue(x > 0.65 && z > 0.65);
    }

    @Test
    void maskCoverageUsesMaterialAlphaFactorAndLeavesColorsStraight() throws Exception {
        byte[] source = png(8, 1, new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        var mask = new TextureImageFilter.Spec(TextureImageFilter.Role.BASE_COLOR, 4,
                GltfAlphaMode.MASK, 0.5f, 0.75f, GltfSamplerData.DEFAULT);
        var filtered = TextureImageFilter.resize(source, mask);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(filtered.encoded()));
        int visible = 0;
        int cutoff = Math.round(0.5f * 255), factor = Math.round(0.75f * 255);
        for (int x = 0; x < image.getWidth(); x++) {
            int color = image.getRGB(x, 0);
            if ((color >>> 24) * factor >= cutoff * 255) visible++;
            assertEquals(255, color & 255, 1);
        }
        assertEquals(2, visible);
    }

    @Test
    void honorsNonSquareDimensionsWithoutUpscalingOrChangingSourceBytes() throws Exception {
        byte[] original = png(6, 2, new int[12]);
        byte[] expected = original.clone();
        var result = TextureImageFilter.resize(original, spec(TextureImageFilter.Role.ORM, 3));
        assertEquals(3, result.width());
        assertEquals(1, result.height());
        assertArrayEquals(expected, original);
        var retained = TextureImageFilter.resize(original, spec(TextureImageFilter.Role.ORM, 8));
        assertArrayEquals(original, retained.encoded());
        assertEquals(6, retained.width());
    }

    @Test
    void decodesJpegAndRejectsMalformedInputs() throws Exception {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "jpeg", output));
            jpeg = output.toByteArray();
        } finally { image.flush(); }
        var result = TextureImageFilter.resize(jpeg, spec(TextureImageFilter.Role.BASE_COLOR, 2));
        assertEquals(2, result.width());
        assertEquals(1, result.height());
        assertEquals(255, pixel(result.encoded(), 0, 0) >>> 24);
        assertThrows(IllegalArgumentException.class, () -> TextureImageFilter.inspect(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> TextureImageFilter.inspect(new byte[]{(byte) 255, (byte) 216, (byte) 255}));
    }

    @Test
    void cacheKeysSeparateContentRoleAlphaAndTargetAndRejectUnsupportedSamplers() throws Exception {
        byte[] original = png(2, 1, new int[]{0xFFFFFFFF, 0xFF000000});
        var base = spec(TextureImageFilter.Role.BASE_COLOR, 1);
        String key = TextureVariantCache.key(original, base);
        assertEquals(key, TextureVariantCache.key(original.clone(), base));
        assertNotEquals(key, TextureVariantCache.key(original, spec(TextureImageFilter.Role.NORMAL, 1)));
        assertNotEquals(key, TextureVariantCache.key(original, spec(TextureImageFilter.Role.BASE_COLOR, 2)));
        assertNotEquals(key, TextureVariantCache.key(original, new TextureImageFilter.Spec(
                TextureImageFilter.Role.BASE_COLOR, 1, GltfAlphaMode.MASK, 0.25f, 1, GltfSamplerData.DEFAULT)));
        assertNotEquals(key, TextureVariantCache.key(original, new TextureImageFilter.Spec(
                TextureImageFilter.Role.BASE_COLOR, 1, GltfAlphaMode.OPAQUE, 0.5f, 1,
                new GltfSamplerData(9729, 9729, 33071, 33071))));
        assertNotEquals(key, TextureVariantCache.key(png(2, 1, new int[]{0xFFFF0000, 0xFF000000}), base));
    }

    @Test
    void oversizedDerivedImagesAreRejectedBeforeNativeInspectionOrDiskWrites() throws Exception {
        int limit = com.tacz.guns.client.model.gltf.render.GltfPbrDynamicTextureCache.MAX_ENCODED_IMAGE_BYTES;
        assertEquals(limit, TextureVariantPolicy.MAX_ENCODED_BYTES);
        TextureVariantPolicy.validateEncodedLength(limit);
        byte[] oversized = new byte[limit + 1];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, oversized, 0, signature.length);
        Path cacheDirectory = directory.resolve("not-created");
        TextureVariantCache cache = new TextureVariantCache(cacheDirectory);
        assertThrows(IllegalArgumentException.class,
                () -> cache.write("0".repeat(64), new TextureImageFilter.Result(oversized, 4096, 4096)));
        assertThrows(IllegalArgumentException.class, () -> TextureImageFilter.inspect(oversized));
        assertFalse(Files.exists(cacheDirectory));
    }

    @Test
    void unsupportedSelectedSamplerFailsBeforeAnySourceInspectionOrCacheWrite() throws Exception {
        byte[] valid = png(1024, 512, new int[1024 * 512]);
        GltfSamplerData unsupported = new GltfSamplerData(9729, 9729, 33648, 10497);
        for (byte[] firstImage : List.of(valid, new byte[]{1, 2, 3})) {
            ConvertedGltfAsset asset = asset(firstImage, valid, "selected.png", unsupported, true);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> GltfTextureVariants.apply(asset, new GltfRenderQuality(512, 0, 1), directory));
            assertTrue(failure.getMessage().contains("LINEAR"));
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
        ConvertedGltfAsset unusedUnsupported = asset(valid, valid, "selected.png", unsupported, false);
        ConvertedGltfAsset selected = assertDoesNotThrow(
                () -> GltfTextureVariants.apply(unusedUnsupported, new GltfRenderQuality(512, 0, 1), directory));
        assertEquals(1, selected.images().size());
    }

    @Test
    void cachedJpegIsRejectedEvenWithCorrectChecksumAndDimensions() throws Exception {
        byte[] jpeg = jpeg(1, 1);
        String key = TextureVariantCache.key(jpeg, spec(TextureImageFilter.Role.BASE_COLOR, 1));
        TextureVariantCache cache = new TextureVariantCache(directory);
        Path entry = directory.resolve(key + ".tv");
        writeCacheEntry(entry, 1, 1, jpeg);
        assertNull(cache.read(key, 1, 1));
        assertFalse(Files.exists(entry));
        assertThrows(IllegalArgumentException.class, () -> cache.write(key, new TextureImageFilter.Result(jpeg, 1, 1)));
        assertFalse(Files.exists(entry));
        byte[] png = png(1, 1, new int[]{0xFFFFFFFF});
        assertTrue(cache.write(key, new TextureImageFilter.Result(png, 1, 1)));
        assertArrayEquals(png, cache.read(key, 1, 1));
    }

    @Test
    void diskCacheValidatesContentAndEnforcesItsByteBoundWithoutDeletingOtherFiles() throws Exception {
        byte[] first = png(1, 1, new int[]{0xFF0000FF});
        byte[] second = png(1, 1, new int[]{0xFFFF0000});
        var spec = spec(TextureImageFilter.Role.BASE_COLOR, 1);
        String firstKey = TextureVariantCache.key(first, spec), secondKey = TextureVariantCache.key(second, spec);
        TextureVariantCache cache = new TextureVariantCache(directory, first.length + second.length + 64L);
        Path foreign = directory.resolve("keep.txt");
        Files.writeString(foreign, "unrelated");
        cache.write(firstKey, new TextureImageFilter.Result(first, 1, 1));
        assertArrayEquals(first, cache.read(firstKey, 1, 1));
        Path firstPath = directory.resolve(firstKey + ".tv");
        byte[] corrupted = Files.readAllBytes(firstPath);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(firstPath, corrupted);
        assertNull(cache.read(firstKey, 1, 1));
        cache.write(firstKey, new TextureImageFilter.Result(first, 1, 1));
        cache.write(secondKey, new TextureImageFilter.Result(second, 1, 1));
        assertArrayEquals(second, cache.read(secondKey, 1, 1));
        assertNull(cache.read(firstKey, 1, 1));
        assertEquals("unrelated", Files.readString(foreign));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void selectedAssetRetainsOnlyUsedImagesAndWarmCacheSkipsOriginalDecode() throws Exception {
        byte[] source = png(1024, 512, new int[1024 * 512]);
        byte[] unused = png(1, 1, new int[]{0xFF00FF00});
        ConvertedGltfAsset original = asset(source, unused);
        GltfRenderQuality quality = new GltfRenderQuality(512, 0, 1);
        ConvertedGltfAsset first = GltfTextureVariants.apply(original, quality, directory);
        assertEquals(1, first.images().size());
        assertEquals(512, TextureImageFilter.inspect(first.images().getFirst().encodedBytes()).width());
        assertEquals(256, TextureImageFilter.inspect(first.images().getFirst().encodedBytes()).height());
        assertNull(first.materials().get(1).baseColorTexture());
        assertArrayEquals(source, original.images().getFirst().encodedBytes());
        assertEquals(1, GltfTextureVariants.snapshot().cacheMisses());
        assertEquals(1, GltfTextureVariants.snapshot().derivedImages());

        ConvertedGltfAsset warm = GltfTextureVariants.apply(original, quality, directory);
        assertArrayEquals(first.images().getFirst().encodedBytes(), warm.images().getFirst().encodedBytes());
        assertEquals(1, GltfTextureVariants.snapshot().cacheHits());
        assertEquals(0, GltfTextureVariants.snapshot().derivedImages());
        assertEquals(0, GltfTextureVariants.snapshot().cacheMisses());

        Path entry;
        try (var files = Files.list(directory)) {
            entry = files.filter(path -> path.toString().endsWith(".tv")).findFirst().orElseThrow();
        }
        writeCacheEntry(entry, 512, 256, jpeg(512, 256));
        ConvertedGltfAsset repaired = GltfTextureVariants.apply(original, quality, directory);
        assertArrayEquals(first.images().getFirst().encodedBytes(), repaired.images().getFirst().encodedBytes());
        assertEquals(1, GltfTextureVariants.snapshot().cacheMisses());
        assertEquals(1, GltfTextureVariants.snapshot().derivedImages());
    }

    @Test
    void derivativeDoesNotRetainTheOriginalDataUriPayload() throws Exception {
        byte[] source = png(1024, 512, new int[1024 * 512]);
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(source);
        ConvertedGltfAsset original = asset(source, png(1, 1, new int[]{0xFFFFFFFF}), dataUri);
        ConvertedGltfAsset derived = GltfTextureVariants.apply(original, new GltfRenderQuality(512, 0, 1), directory);
        assertEquals(1, derived.images().size());
        assertNotSame(original.images().getFirst(), derived.images().getFirst());
        assertTrue(derived.images().getFirst().uri().startsWith("derived/"));
        assertFalse(derived.images().getFirst().uri().contains(dataUri));
        assertEquals(dataUri, original.images().getFirst().uri());
    }

    private static TextureImageFilter.Spec spec(TextureImageFilter.Role role, int maxSize) {
        return new TextureImageFilter.Spec(role, maxSize, GltfAlphaMode.OPAQUE, 0.5f, 1, GltfSamplerData.DEFAULT);
    }

    private static int pixel(byte[] encoded, int x, int y) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(encoded)).getRGB(x, y);
    }

    private static byte[] png(int width, int height, int[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } finally { image.flush(); }
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "jpeg", output));
            return output.toByteArray();
        } finally { image.flush(); }
    }

    private static void writeCacheEntry(Path path, int width, int height, byte[] encoded) throws Exception {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeInt(0x54565831);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(encoded.length);
            output.write(MessageDigest.getInstance("SHA-256").digest(encoded));
            output.write(encoded);
        }
    }

    private static ConvertedGltfAsset asset(byte[] selectedImage, byte[] unusedImage) {
        return asset(selectedImage, unusedImage, "selected.png");
    }

    private static ConvertedGltfAsset asset(byte[] selectedImage, byte[] unusedImage, String selectedUri) {
        return asset(selectedImage, unusedImage, selectedUri, GltfSamplerData.DEFAULT, false);
    }

    private static ConvertedGltfAsset asset(byte[] selectedImage, byte[] unusedImage, String selectedUri,
                                           GltfSamplerData secondSampler, boolean bothSelected) {
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[0], new float[0],
                new int[0], new float[0], List.of(), new GltfMaterialReference("test"));
        GltfRenderPrimitive selected = new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, null, null, 0);
        GltfRenderPrimitive unused = new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, null, null, 1);
        List<GltfRenderMesh> meshes = List.of(new GltfRenderMesh("selected", List.of(selected), null),
                new GltfRenderMesh("unused", List.of(unused), null));
        int[] roots = bothSelected ? new int[]{0, 1} : new int[]{0};
        GltfScene scene = new GltfScene(List.of(new GltfNode("selected", GltfNodeTransform.identity(), null, 0, -1),
                new GltfNode("unselected", GltfNodeTransform.identity(), null, 1, -1)),
                List.of(new GltfMesh(List.of(primitive)), new GltfMesh(List.of(primitive))), List.of(), roots);
        List<float[]> weights = List.of(new float[0], new float[0]);
        return new ConvertedGltfAsset(scene, meshes, List.of(material(0), material(1, secondSampler)),
                List.of(new GltfImageData("selected", selectedUri, "image/png", selectedImage),
                        new GltfImageData("unused", "unused.png", "image/png", unusedImage)),
                List.of(), weights, weights, List.of(new GltfSceneData("scene", roots)), 0, 0);
    }

    private static GltfPbrMaterialData material(int image) {
        return material(image, GltfSamplerData.DEFAULT);
    }

    private static GltfPbrMaterialData material(int image, GltfSamplerData sampler) {
        return new GltfPbrMaterialData("material", new float[]{1, 1, 1, 1}, 1, 1, new float[]{0, 0, 0},
                1, 1, GltfAlphaMode.OPAQUE, 0.5f, false,
                new GltfTextureBinding(image, 0, sampler), null, null, null, null);
    }
}
