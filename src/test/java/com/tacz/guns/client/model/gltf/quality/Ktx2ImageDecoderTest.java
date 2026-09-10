package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.*;
import com.tacz.guns.client.model.gltf.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Ktx2ImageDecoderTest {
    @TempDir Path directory;

    @Test
    void realEtc1sAndUastcDecodeToCyanRgbaAndReleaseTheirNativeOwner() throws Exception {
        for (String name : List.of("cyan_rgb_reference_basis.ktx2", "cyan_rgb_reference_uastc.ktx2")) {
            byte[] bytes = fixture(name), original = bytes.clone();
            var header = Ktx2ImageHeader.read(bytes);
            assertEquals(64, header.width());
            assertEquals(64, header.height());
            assertEquals(16384, header.rgbaBytes());
            try (var decoded = Ktx2ImageDecoder.decode(bytes, header, () -> false)) {
                assertEquals(16384, decoded.pixels().remaining());
                assertEquals(0, decoded.pixels().get(0) & 255, 8);
                assertEquals(255, decoded.pixels().get(1) & 255, 8);
                assertEquals(255, decoded.pixels().get(2) & 255, 8);
                assertEquals(255, decoded.pixels().get(3) & 255);
            }
            assertArrayEquals(original, bytes);
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
        }
    }

    @Test
    void nativeImageCopyPreservesExactRgbaBytesAndArgbSentinelWithoutBorrowingTheOwner() throws Exception {
        for (String name : List.of("cyan_rgb_reference_basis.ktx2", "cyan_rgb_reference_uastc.ktx2",
                "alpha_simple_basis.ktx2", "color_grid_uastc_zstd.ktx2")) {
            byte[] bytes = fixture(name);
            byte[] expected;
            try (var decoded = Ktx2ImageDecoder.decode(bytes, Ktx2ImageHeader.read(bytes), () -> false)) {
                expected = new byte[decoded.pixels().remaining()];
                decoded.pixels().get(expected);
            }
            var image = Ktx2ImageDecoder.decodeToNativeImage(bytes, TextureImageFilter.Role.BASE_COLOR, () -> false);
            try (image) {
                assertEquals(0, Ktx2ImageDecoder.liveTextures(), "libktx must close before the NativeImage is returned");
                assertFalse(image.isClosed());
                byte[] actual = new byte[image.getPixelBytes().remaining()];
                image.getPixelBytes().get(actual);
                assertArrayEquals(expected, actual, name);
                int argb = (expected[3] & 255) << 24 | (expected[0] & 255) << 16
                        | (expected[1] & 255) << 8 | expected[2] & 255;
                assertEquals(argb, image.getPixel(0, 0), "RGBA bytes must map to the MC ARGB accessor without swizzling");
                if (name.startsWith("cyan")) {
                    assertEquals(0, argb >>> 16 & 255, 8);
                    assertEquals(255, argb & 255, 8);
                }
            }
            assertTrue(image.isClosed());
        }
    }

    @Test
    void nativeImageBridgeRejectsBadRolesAndClosesOnCancellationAfterCopy() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_uastc.ktx2");
        long before = Ktx2ImageDecoder.transcodes();
        assertThrows(IllegalArgumentException.class,
                () -> Ktx2ImageDecoder.inspect(bytes, TextureImageFilter.Role.NORMAL));
        assertThrows(IllegalArgumentException.class,
                () -> Ktx2ImageDecoder.decodeToNativeImage(bytes, TextureImageFilter.Role.NORMAL, () -> false));
        assertEquals(before, Ktx2ImageDecoder.transcodes());
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> Ktx2ImageDecoder.decodeToNativeImage(bytes,
                TextureImageFilter.Role.BASE_COLOR, () -> checks.incrementAndGet() >= 6));
        assertEquals(6, checks.get());
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
        try (var image = Ktx2ImageDecoder.decodeToNativeImage(bytes, TextureImageFilter.Role.BASE_COLOR, () -> false)) {
            assertEquals(64, image.getWidth());
        }
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    @Test
    void realAlphaMipsAndZstdPayloadsDecodeAndProducePng() throws Exception {
        for (String name : List.of("alpha_simple_basis.ktx2", "rgba-mipmap-reference-basis.ktx2",
                "color_grid_uastc_zstd.ktx2")) {
            byte[] bytes = fixture(name);
            var header = Ktx2ImageHeader.read(bytes);
            var result = TextureImageFilter.resize(bytes, spec(32));
            TextureVariantPolicy.validateDerivedPng(result.encoded());
            assertTrue(result.width() <= 32 && result.height() <= 32);
            var image = ImageIO.read(new ByteArrayInputStream(result.encoded()));
            assertNotNull(image);
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
            if (name.contains("mipmap")) assertTrue(header.levels().size() > 1);
        }
    }

    @Test
    void realAlphaMatchesItsSourcePngBeforeAndAfterResizing() throws Exception {
        var reference = ImageIO.read(new ByteArrayInputStream(fixture("alpha_simple.png")));
        assertNotNull(reference);
        assertEquals(8, reference.getWidth());
        assertEquals(8, reference.getHeight());
        // The upstream source is uniformly half transparent, not an alpha gradient.
        for (int y = 0; y < reference.getHeight(); y++) {
            for (int x = 0; x < reference.getWidth(); x++) assertEquals(128, reference.getRGB(x, y) >>> 24);
        }
        byte[] bytes = fixture("alpha_simple_basis.ktx2");
        try (var decoded = Ktx2ImageDecoder.decode(bytes, Ktx2ImageHeader.read(bytes), () -> false)) {
            var pixels = decoded.pixels();
            for (int y = 0; y < reference.getHeight(); y++) {
                for (int x = 0; x < reference.getWidth(); x++) {
                    assertEquals(reference.getRGB(x, y) >>> 24, pixels.get((y * decoded.width() + x) * 4 + 3) & 255);
                }
            }
        }
        for (int maximumSize : new int[]{32, 4}) {
            var result = TextureImageFilter.resize(bytes, spec(maximumSize));
            var derived = ImageIO.read(new ByteArrayInputStream(result.encoded()));
            assertEquals(Math.min(8, maximumSize), derived.getWidth());
            assertEquals(Math.min(8, maximumSize), derived.getHeight());
            for (int y = 0; y < derived.getHeight(); y++) {
                for (int x = 0; x < derived.getWidth(); x++) assertEquals(128, derived.getRGB(x, y) >>> 24);
            }
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
        }
    }

    @Test
    void unchangedKtxRetainsCompressedBytesWithoutCpuTranscodingOrPngCacheWrites() throws Exception {
        for (String name : List.of("cyan_rgb_reference_basis.ktx2", "cyan_rgb_reference_uastc.ktx2",
                "alpha_simple_basis.ktx2", "rgba-mipmap-reference-basis.ktx2", "color_grid_uastc_zstd.ktx2")) {
            byte[] bytes = fixture(name);
            var source = asset(bytes);
            long before = Ktx2ImageDecoder.transcodes();
            for (int pass = 0; pass < 2; pass++) {
                var result = GltfTextureVariants.apply(source, new GltfRenderQuality(8192, 0, 1), directory);
                assertEquals(before, Ktx2ImageDecoder.transcodes(), name);
                assertSame(source.images().getFirst(), result.images().getFirst(), name);
                assertArrayEquals(bytes, result.images().getFirst().encodedBytes(), name);
                assertEquals("image/ktx2", result.images().getFirst().mimeType());
                assertEquals(1, GltfTextureVariants.snapshot().retainedOriginalImages());
                assertEquals(0, GltfTextureVariants.snapshot().derivedImages());
                assertEquals(0, GltfTextureVariants.snapshot().cacheHits());
                assertEquals(0, GltfTextureVariants.snapshot().cacheMisses());
                assertEquals(0, Ktx2ImageDecoder.liveTextures());
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            }
        }
    }

    @Test
    void downsampledKtxIsNormalizedAndWarmCacheDoesNotTranscodeOrRetainDataUri() throws Exception {
        byte[] bytes = fixture("color_grid_uastc_zstd.ktx2");
        var source = asset(bytes);
        long before = Ktx2ImageDecoder.transcodes();
        var quality = new GltfRenderQuality(512, 0, 1);
        var cold = GltfTextureVariants.apply(source, quality, directory);
        assertEquals(before + 1, Ktx2ImageDecoder.transcodes());
        assertEquals("image/png", cold.images().getFirst().mimeType());
        assertTrue(cold.images().getFirst().uri().startsWith("derived/"));
        assertNotSame(source.images().getFirst(), cold.images().getFirst());
        var warm = GltfTextureVariants.apply(source, quality, directory);
        assertEquals(before + 1, Ktx2ImageDecoder.transcodes());
        assertArrayEquals(cold.images().getFirst().encodedBytes(), warm.images().getFirst().encodedBytes());
        assertArrayEquals(bytes, source.images().getFirst().encodedBytes());
        assertEquals(1, GltfTextureVariants.snapshot().cacheHits());
    }

    @Test
    void malformedLengthsDimensionsSgdAndDfdFailBeforeNativeAllocation() throws Exception {
        byte[] valid = fixture("cyan_rgb_reference_basis.ktx2");
        long before = Ktx2ImageDecoder.transcodes();
        for (int offset : new int[]{20, 24, 28, 32, 36, 40, 44, 48, 52, 64, 72, 80, 88, 96}) {
            byte[] invalid = valid.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, -1);
            assertThrows(IllegalArgumentException.class, () -> Ktx2ImageHeader.read(invalid), "offset=" + offset);
        }
        byte[] invalid = valid.clone();
        int sgd = ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).getInt(64);
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(sgd + 4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> Ktx2ImageHeader.read(invalid));
        assertEquals(before, Ktx2ImageDecoder.transcodes());
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void wrongUsageAndZstdExpansionBombAreRejectedWithoutTranscoding() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_uastc.ktx2");
        var header = Ktx2ImageHeader.read(bytes);
        assertThrows(IllegalArgumentException.class, () -> header.validateUsage(TextureImageFilter.Role.NORMAL));
        long before = Ktx2ImageDecoder.transcodes();
        assertThrows(IllegalArgumentException.class, () -> TextureImageFilter.resize(bytes,
                new TextureImageFilter.Spec(TextureImageFilter.Role.ORM, 32, GltfAlphaMode.OPAQUE,
                        0.5f, 1, GltfSamplerData.DEFAULT)));
        byte[] bomb = fixture("color_grid_uastc_zstd.ktx2");
        ByteBuffer.wrap(bomb).order(ByteOrder.LITTLE_ENDIAN).putLong(96, 1L << 40);
        assertThrows(IllegalArgumentException.class, () -> Ktx2ImageHeader.read(bomb));
        assertEquals(before, Ktx2ImageDecoder.transcodes());
    }

    @Test
    void cancellationAfterNativeCreateDestroysTheTextureAndLeavesSourceUsable() throws Exception {
        byte[] bytes = fixture("cyan_rgb_reference_basis.ktx2");
        var header = Ktx2ImageHeader.read(bytes);
        var calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> Ktx2ImageDecoder.decode(bytes, header,
                () -> calls.incrementAndGet() >= 3));
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
        try (var decoded = Ktx2ImageDecoder.decode(bytes, header, () -> false)) {
            assertEquals(64, decoded.width());
        }
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    @Test
    void legalPartialMipPyramidIsNotRejectedAsAnIncompleteChain() throws Exception {
        byte[] oneLevel = fixture("cyan_rgb_reference_uastc.ktx2");
        // Preserve real UASTC blocks; the uniform source permits the 32x32 mip to reuse them.
        byte[] twoLevels = new byte[1328 + 4096];
        System.arraycopy(oneLevel, 0, twoLevels, 0, 80);
        System.arraycopy(oneLevel, 104, twoLevels, 128, 164);
        System.arraycopy(oneLevel, 272, twoLevels, 304, 1024);
        System.arraycopy(oneLevel, 272, twoLevels, 1328, 4096);
        ByteBuffer buffer = ByteBuffer.wrap(twoLevels).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(40, 2).putInt(48, 128).putInt(56, 172);
        buffer.putLong(80, 1328).putLong(88, 4096).putLong(96, 4096);
        buffer.putLong(104, 304).putLong(112, 1024).putLong(120, 1024);
        var header = Ktx2ImageHeader.read(twoLevels);
        assertEquals(2, header.levels().size());
        assertEquals(20480, header.rgbaBytes());
        try (var decoded = Ktx2ImageDecoder.decode(twoLevels, header, () -> false)) {
            assertEquals(255, decoded.pixels().get(1) & 255, 8);
        }
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    @Test
    void metadataAndChannelMismatchFailBeforeCreatingNativeImages() throws Exception {
        byte[] source = fixture("cyan_rgb_reference_uastc.ktx2");
        for (int offset : new int[]{108, 114, 116, 117, 118, 119, 120, 124, 135, 148, 167}) {
            byte[] invalid = source.clone();
            invalid[offset] = (byte) 0xFF;
            assertThrows(IllegalArgumentException.class, () -> Ktx2ImageHeader.read(invalid), "offset=" + offset);
        }
        byte[] red = fixture("cyan_rgb_reference_basis.ktx2");
        red[117] = 0;
        red[118] = 1;
        red[135] = 3;
        var header = Ktx2ImageHeader.read(red);
        assertDoesNotThrow(() -> header.validateUsage(TextureImageFilter.Role.OCCLUSION));
        assertThrows(IllegalArgumentException.class, () -> header.validateUsage(TextureImageFilter.Role.ORM));
        assertThrows(IllegalArgumentException.class, () -> header.validateUsage(TextureImageFilter.Role.NORMAL));
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    @Test
    void nativeLoadFailureReleasesPermitAndNeverPublishesACacheEntry() throws Exception {
        byte[] invalid = fixture("color_grid_uastc_zstd.ktx2");
        var buffer = ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN);
        int payload = Math.toIntExact(buffer.getLong(80));
        buffer.putInt(payload, 0);
        assertDoesNotThrow(() -> Ktx2ImageHeader.read(invalid));
        assertThrows(IllegalArgumentException.class, () -> GltfTextureVariants.apply(asset(invalid),
                new GltfRenderQuality(512, 0, 1), directory));
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        byte[] valid = fixture("cyan_rgb_reference_basis.ktx2");
        try (var decoded = Ktx2ImageDecoder.decode(valid, Ktx2ImageHeader.read(valid), () -> false)) {
            assertEquals(64, decoded.width());
        }
    }

    @Test
    void nativeTranscodeOutputUsesTheExistingNormalRenormalization() throws Exception {
        byte[] linear = fixture("cyan_rgb_reference_uastc.ktx2");
        linear[117] = 0;
        linear[118] = 1;
        var result = TextureImageFilter.resize(linear, new TextureImageFilter.Spec(TextureImageFilter.Role.NORMAL,
                32, GltfAlphaMode.OPAQUE, 0.5f, 1, GltfSamplerData.DEFAULT));
        var image = ImageIO.read(new ByteArrayInputStream(result.encoded()));
        int color = image.getRGB(0, 0);
        double x = (color >> 16 & 255) / 127.5 - 1;
        double y = (color >> 8 & 255) / 127.5 - 1;
        double z = (color & 255) / 127.5 - 1;
        assertEquals(1, Math.sqrt(x * x + y * y + z * z), 0.015);
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    private static TextureImageFilter.Spec spec(int size) {
        return new TextureImageFilter.Spec(TextureImageFilter.Role.BASE_COLOR, size,
                GltfAlphaMode.BLEND, 0.5f, 1, GltfSamplerData.DEFAULT);
    }

    private static byte[] fixture(String name) throws Exception {
        try (var stream = Ktx2ImageDecoderTest.class.getResourceAsStream("/gltf/ktx2/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

    private static ConvertedGltfAsset asset(byte[] bytes) {
        var primitive = new GltfMeshPrimitive(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[0], new float[0], new int[0], new float[0], List.of(), new GltfMaterialReference("test"));
        var scene = new GltfScene(List.of(new GltfNode("mesh", GltfNodeTransform.identity(), null, 0, -1)),
                List.of(new GltfMesh(List.of(primitive))), List.of(), new int[]{0});
        var mesh = new GltfRenderMesh("mesh", List.of(new GltfRenderPrimitive(primitive,
                new int[]{0, 1, 2}, null, null, 0)), null);
        var material = new GltfPbrMaterialData("material", new float[]{1, 1, 1, 1}, 1, 1,
                new float[]{0, 0, 0}, 1, 1, GltfAlphaMode.OPAQUE, 0.5f, false,
                new GltfTextureBinding(0, 0, GltfSamplerData.DEFAULT), null, null, null, null);
        List<float[]> weights = List.of(new float[0]);
        return new ConvertedGltfAsset(scene, List.of(mesh), List.of(material),
                List.of(new GltfImageData("ktx", "data:image/ktx2;base64," + Base64.getEncoder().encodeToString(bytes),
                        "image/ktx2", bytes)), List.of(), weights, weights,
                List.of(new GltfSceneData("scene", new int[]{0})), 0, 0);
    }
}
