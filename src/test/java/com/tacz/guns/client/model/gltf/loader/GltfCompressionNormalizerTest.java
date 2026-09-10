package com.tacz.guns.client.model.gltf.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import com.tacz.guns.client.model.gltf.quality.GltfRenderQuality;
import com.tacz.guns.client.model.gltf.quality.GltfTextureVariants;
import com.tacz.guns.client.model.gltf.quality.Ktx2ImageDecoder;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Public import -> core conversion -> texture derivation, with unmodified compressed payloads. */
class GltfCompressionNormalizerTest {
    private static final String DRACO = "KHR_draco_mesh_compression";
    private static final String BASIS = "KHR_texture_basisu";
    private static final String CYAN = "/gltf/ktx2/cyan_rgb_reference_uastc.ktx2";
    @TempDir Path cache;

    @Test
    void officialBoxJsonAndGlbReachTheConverterWithTheirNodesAndIndicesIntact() throws Exception {
        JsonObject source = fixtureJson("box/Box.gltf");
        byte[] bytes = fixture("/gltf/draco/box/Box.bin");
        int[] firstIndices = null;
        for (boolean binary : new boolean[]{false, true}) {
            var loaded = loadWrapped(source, bytes, "Box.bin", binary);
            // binary() describes normalized storage, not the input container's provenance.
            assertCompactStorage(loaded, 3, 648);
            assertNull(loaded.asset().getReferenceData("Box.bin"));
            assertTrue(loaded.extensionsUsed().isEmpty());
            assertTrue(loaded.extensionsRequired().isEmpty());
            var converted = new JgltfRuntimeConverter().convert(loaded);
            assertEquals(2, converted.runtimeScene().nodes().size());
            assertArrayEquals(new int[]{1}, converted.runtimeScene().nodes().getFirst().children());
            assertEquals(0, converted.runtimeScene().nodes().get(1).meshIndex());
            assertArrayEquals(new int[]{0}, converted.runtimeScene().rootNodes());
            assertTrue(converted.animations().isEmpty());
            assertTrue(converted.runtimeScene().skins().isEmpty());
            var primitive = converted.renderMeshes().getFirst().primitives().getFirst();
            assertEquals(24, primitive.runtimePrimitive().vertexCount());
            assertEquals(36, primitive.indices().length);
            if (firstIndices == null) firstIndices = primitive.indices();
            else assertArrayEquals(firstIndices, primitive.indices());
            for (int index : primitive.indices()) assertTrue(index >= 0 && index < 24);
            for (float coordinate : primitive.runtimePrimitive().positions()) {
                assertEquals(0.5, Math.abs(coordinate), 0.001);
            }
        }
        assertTrue(source.getAsJsonArray("extensionsRequired").toString().contains(DRACO));
    }

    @Test
    void officialSkinJsonAndGlbPreserveJointIndicesBindMatricesAndAnimationSamples() throws Exception {
        JsonObject source = fixtureJson("rigged-simple/RiggedSimple.gltf");
        byte[] bytes = fixture("/gltf/draco/rigged-simple/RiggedSimple0.bin");
        List<String> expectedNames = List.of("Z_UP", "Armature", "Cylinder", "Bone", "Bone.001");
        ConvertedGltfAsset previous = null;
        for (boolean binary : new boolean[]{false, true}) {
            var loaded = loadWrapped(source, bytes, "RiggedSimple0.bin", binary);
            // Four original animation/IBM views survive; the old compressed view does not.
            assertCompactStorage(loaded, 9, 11136);
            assertNull(loaded.asset().getReferenceData("RiggedSimple0.bin"));
            var converted = new JgltfRuntimeConverter().convert(loaded);
            assertEquals(expectedNames, converted.runtimeScene().nodes().stream().map(node -> node.name()).toList());
            assertEquals(0, converted.runtimeScene().nodes().get(2).skinIndex());
            assertArrayEquals(new int[]{3, 2}, converted.runtimeScene().nodes().get(1).children());
            assertArrayEquals(new int[]{3, 4}, converted.runtimeScene().skins().getFirst().joints());
            var primitive = converted.renderMeshes().getFirst().primitives().getFirst();
            assertEquals(160, primitive.runtimePrimitive().vertexCount());
            assertEquals(564, primitive.indices().length);
            assertTrue(Arrays.stream(primitive.runtimePrimitive().joints0()).anyMatch(joint -> joint == 1));
            assertEquals(640, primitive.runtimePrimitive().weights0().length);
            assertEquals(1, converted.animations().size());
            var animation = converted.animations().getFirst();
            assertEquals(2.083333f, animation.durationSeconds(), 0.000001f);
            assertEquals(3, animation.animation().channels().size());
            assertTrue(animation.animation().channels().stream().allMatch(channel -> channel.nodeIndex() == 4));
            var initial = animation.animation().sample(0.05f).rotations().get(4);
            var later = animation.animation().sample(1.0f).rotations().get(4);
            assertFalse(initial.equals(later, 0.001f), "uncompressed animation buffers must survive normalization");
            if (previous != null) {
                assertArrayEquals(previous.renderMeshes().getFirst().primitives().getFirst().indices(), primitive.indices());
                assertEquals(previous.runtimeScene().skins().getFirst().inverseBindMatrix(1),
                        converted.runtimeScene().skins().getFirst().inverseBindMatrix(1));
                assertEquals(previous.animations().getFirst().animation().sample(1.0f).rotations().get(4), later);
            }
            previous = converted;
        }
    }

    @Test
    void basisWithoutCoreFallbackLoadsFromExternalAndDataUriAndPreservesOriginalKtx() throws Exception {
        byte[] ktx = fixture(CYAN);
        for (boolean dataUri : new boolean[]{false, true}) {
            JsonObject source = triangle();
            String uri = dataUri ? dataUri("image/ktx2", ktx) : "cyan.ktx2";
            addBasis(source, object("{\"uri\":\"" + uri + "\",\"mimeType\":\"image/ktx2\"}"));
            List<String> resolved = new ArrayList<>();
            var loaded = loadJson(source, Map.of("cyan.ktx2", ktx), resolved);
            assertCompactStorage(loaded, 3, 68);
            assertEquals(dataUri ? List.of() : List.of("cyan.ktx2"), resolved);
            assertEquals(0, loaded.gltf().getTextures().getFirst().getSource());
            assertTrue(loaded.extensionsRequired().isEmpty());
            var converted = new JgltfRuntimeConverter().convert(loaded);
            assertEquals("image/ktx2", converted.images().getFirst().mimeType());
            assertArrayEquals(ktx, converted.images().getFirst().encodedBytes());
            assertRetainedCyanKtx(converted);
        }
    }

    @Test
    void embeddedGlbBasisPayloadPreservesOriginalKtxWithoutAnyExternalResource() throws Exception {
        JsonObject source = triangle();
        byte[] geometry = triangleBytes();
        byte[] ktx = fixture(CYAN);
        byte[] binary = new byte[geometry.length + ktx.length];
        System.arraycopy(geometry, 0, binary, 0, geometry.length);
        System.arraycopy(ktx, 0, binary, geometry.length, ktx.length);
        JsonObject buffer = source.getAsJsonArray("buffers").get(0).getAsJsonObject();
        buffer.remove("uri");
        buffer.addProperty("byteLength", binary.length);
        source.getAsJsonArray("bufferViews").add(object("{\"buffer\":0,\"byteOffset\":"
                + geometry.length + ",\"byteLength\":" + ktx.length + "}"));
        addBasis(source, object("{\"bufferView\":3,\"mimeType\":\"image/ktx2\"}"));
        var loaded = new JgltfModelLoader().load(new ByteArrayInputStream(glb(source, binary)));
        assertCompactStorage(loaded, 4, 68 + ((ktx.length + 3) & ~3));
        assertRetainedCyanKtx(new JgltfRuntimeConverter().convert(loaded));
    }

    @Test
    void basisExtensionSelectsItsSourceRatherThanTheValidCorePngFallback() throws Exception {
        JsonObject source = triangle();
        byte[] ktx = fixture(CYAN);
        addBasis(source, object("{\"uri\":\"cyan.ktx2\",\"mimeType\":\"image/ktx2\"}"));
        JsonArray images = source.getAsJsonArray("images");
        images.add(object("{\"uri\":\"" + dataUri("image/png", redPng()) + "\"}"));
        source.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source", 1);
        var loaded = loadJson(source, Map.of("cyan.ktx2", ktx), new ArrayList<>());
        assertEquals(0, loaded.gltf().getTextures().getFirst().getSource());
        var converted = new JgltfRuntimeConverter().convert(loaded);
        assertEquals(0, converted.materials().getFirst().baseColorTexture().imageIndex());
        assertRetainedCyanKtx(converted);
    }

    @Test
    void dracoAndBasisComposeWithoutReencodingTheMeshOrDroppingUncompressedUv() throws Exception {
        JsonObject source = fixtureJson("box/Box.gltf");
        // Only add ordinary UV storage. The official 118-byte Draco payload remains untouched.
        source.getAsJsonArray("buffers").add(object("{\"uri\":\"uv.bin\",\"byteLength\":192}"));
        source.getAsJsonArray("bufferViews").add(object("{\"buffer\":1,\"byteOffset\":0,\"byteLength\":192}"));
        source.getAsJsonArray("accessors").add(object(
                "{\"bufferView\":1,\"componentType\":5126,\"count\":24,\"type\":\"VEC2\"}"));
        source.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives")
                .get(0).getAsJsonObject().getAsJsonObject("attributes").addProperty("TEXCOORD_0", 3);
        addBasis(source, object("{\"uri\":\"cyan.ktx2\",\"mimeType\":\"image/ktx2\"}"));
        var loaded = loadJson(source, Map.of("Box.bin", fixture("/gltf/draco/box/Box.bin"),
                "uv.bin", new byte[192], "cyan.ktx2", fixture(CYAN)), new ArrayList<>());
        assertCompactStorage(loaded, 4, 840);
        assertTrue(loaded.extensionsUsed().isEmpty());
        var converted = new JgltfRuntimeConverter().convert(loaded);
        assertEquals(36, converted.renderMeshes().getFirst().primitives().getFirst().indices().length);
        assertEquals(48, converted.renderMeshes().getFirst().primitives().getFirst().texCoords0().length);
        assertRetainedCyanKtx(converted);
    }

    @Test
    void basisLargerThanTheQualityCapDerivesRealPngWithoutRetainingItsDataUri() throws Exception {
        byte[] ktx = fixture("/gltf/ktx2/color_grid_uastc_zstd.ktx2");
        JsonObject source = triangle();
        addBasis(source, object("{\"uri\":\"" + dataUri("image/ktx2", ktx) + "\",\"mimeType\":\"image/ktx2\"}"));
        var loaded = loadJson(source, Map.of(), new ArrayList<>());
        var converted = new JgltfRuntimeConverter().convert(loaded);
        var originalImage = converted.images().getFirst();
        assertEquals(new TextureImageFilter.Dimensions(1024, 1024),
                Ktx2ImageDecoder.inspect(originalImage.encodedBytes(), TextureImageFilter.Role.BASE_COLOR));
        long before = Ktx2ImageDecoder.transcodes();
        var derived = GltfTextureVariants.apply(converted, new GltfRenderQuality(512, 0, 1), cache);
        var imageData = derived.images().get(derived.materials().getFirst().baseColorTexture().imageIndex());
        assertEquals("image/png", imageData.mimeType());
        assertTrue(imageData.uri().startsWith("derived/"));
        assertNotSame(originalImage, imageData);
        assertArrayEquals(ktx, originalImage.encodedBytes());
        assertArrayEquals(converted.renderMeshes().getFirst().primitives().getFirst().indices(),
                derived.renderMeshes().getFirst().primitives().getFirst().indices());
        var image = ImageIO.read(new ByteArrayInputStream(imageData.encodedBytes()));
        assertNotNull(image);
        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());
        assertEquals(before + 1, Ktx2ImageDecoder.transcodes());
        assertEquals(1, GltfTextureVariants.snapshot().derivedImages());
        assertEquals(1, GltfTextureVariants.snapshot().cacheMisses());
        assertEquals(0, Ktx2ImageDecoder.liveTextures());
    }

    @Test
    void pngPayloadCannotMasqueradeAsBasisEvenWhenItHasAValidFallback() throws Exception {
        JsonObject source = triangle();
        addBasis(source, object("{\"uri\":\"fake.ktx2\",\"mimeType\":\"image/ktx2\"}"));
        byte[] png = redPng();
        source.getAsJsonArray("images").add(object("{\"uri\":\"" + dataUri("image/png", png) + "\"}"));
        source.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source", 1);
        var error = assertThrows(GltfLoadException.class,
                () -> loadJson(source, Map.of("fake.ktx2", png), new ArrayList<>()));
        assertTrue(error.getMessage().contains("not KTX2"), error.getMessage());
    }

    @Test
    void unknownRequiredQuantizationAndUndeclaredCompressionRemainFailClosed() throws Exception {
        for (String name : List.of("VENDOR_not_implemented", "KHR_meshopt_compression")) {
            JsonObject source = fixtureJson("box/Box.gltf");
            source.getAsJsonArray("extensionsUsed").add(name);
            source.getAsJsonArray("extensionsRequired").add(name);
            List<String> resolved = new ArrayList<>();
            var error = assertThrows(GltfLoadException.class, () -> loadJson(source, Map.of(), resolved));
            assertTrue(error.getMessage().contains(name), error.getMessage());
            assertTrue(resolved.isEmpty(), "unknown required extension is rejected before resource resolution");
        }
        JsonObject source = fixtureJson("box/Box.gltf");
        source.remove("extensionsUsed");
        source.remove("extensionsRequired");
        var error = assertThrows(GltfLoadException.class,
                () -> loadJson(source, Map.of("Box.bin", fixture("/gltf/draco/box/Box.bin")), new ArrayList<>()));
        assertTrue(error.getMessage().contains("must be declared"), error.getMessage());
    }

    @Test
    void declaredSourceBufferLimitAndViewRangeAreRejectedBeforeDracoDecode() throws Exception {
        JsonObject oversized = fixtureJson("box/Box.gltf");
        oversized.getAsJsonArray("buffers").get(0).getAsJsonObject()
                .addProperty("byteLength", GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES + 1);
        var tooLarge = assertThrows(GltfLoadException.class,
                () -> loadJson(oversized, Map.of("Box.bin", new byte[]{0}), new ArrayList<>()));
        assertTrue(tooLarge.getMessage().contains("source buffer allocation budget"), tooLarge.getMessage());
        JsonObject range = fixtureJson("box/Box.gltf");
        range.getAsJsonArray("bufferViews").get(0).getAsJsonObject().addProperty("byteOffset", 100);
        var outside = assertThrows(GltfLoadException.class,
                () -> loadJson(range, Map.of("Box.bin", fixture("/gltf/draco/box/Box.bin")), new ArrayList<>()));
        assertTrue(outside.getMessage().contains("range"), outside.getMessage());
    }

    private void assertRetainedCyanKtx(ConvertedGltfAsset asset) throws Exception {
        var originalImage = asset.images().get(asset.materials().getFirst().baseColorTexture().imageIndex());
        long before = Ktx2ImageDecoder.transcodes();
        var derived = GltfTextureVariants.apply(asset, new GltfRenderQuality(512, 0, 1), cache);
        int imageIndex = derived.materials().getFirst().baseColorTexture().imageIndex();
        var imageData = derived.images().get(imageIndex);
        assertEquals("image/ktx2", imageData.mimeType());
        assertSame(originalImage, imageData);
        assertArrayEquals(fixture(CYAN), imageData.encodedBytes());
        assertEquals(before, Ktx2ImageDecoder.transcodes());
        assertEquals(1, GltfTextureVariants.snapshot().retainedOriginalImages());
        assertEquals(0, GltfTextureVariants.snapshot().derivedImages());
        assertEquals(0, GltfTextureVariants.snapshot().cacheHits());
        assertEquals(0, GltfTextureVariants.snapshot().cacheMisses());
        try (var image = Ktx2ImageDecoder.decodeToNativeImage(imageData.encodedBytes(), TextureImageFilter.Role.BASE_COLOR,
                () -> false)) {
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
            int pixel = image.getPixel(0, 0);
            assertEquals(0, pixel >> 16 & 255, 8);
            assertEquals(255, pixel >> 8 & 255, 8);
            assertEquals(255, pixel & 255, 8);
            assertEquals(255, pixel >>> 24);
            assertEquals(0, Ktx2ImageDecoder.liveTextures());
        }
    }

    private static void assertCompactStorage(NormalizedGltfModel loaded, int expectedViews, int expectedBytes) {
        assertNull(loaded.asset().getBinaryData(), "normalized storage must not retain the source GLB BIN chunk");
        assertFalse(loaded.binary(), "normalized buffers are resolved core buffers, not the original GLB");
        assertEquals(expectedViews, loaded.gltf().getBufferViews().size(), "retire unreferenced compressed views");
        assertEquals(1, loaded.gltf().getBuffers().size(), "these small live views fit in one compact buffer");
        assertEquals(expectedBytes, loaded.gltf().getBuffers().getFirst().getByteLength().intValue(),
                "store only active view bytes with four-byte alignment, not the original compressed resource");
        var data = loaded.model().getBufferModels().getFirst().getBufferData();
        assertEquals(expectedBytes, data.remaining());
        int nextOffset = 0;
        for (var view : loaded.gltf().getBufferViews()) {
            assertEquals(0, view.getBuffer().intValue());
            int offset = view.getByteOffset() == null ? 0 : view.getByteOffset();
            assertEquals(nextOffset, offset, "live views must be tightly packed with explicit alignment");
            assertEquals(0, offset % 4);
            nextOffset += (view.getByteLength() + 3) & ~3;
        }
        assertEquals(expectedBytes, nextOffset);
    }

    private static NormalizedGltfModel loadWrapped(JsonObject original, byte[] bytes, String name, boolean binary)
            throws Exception {
        JsonObject source = original.deepCopy();
        if (!binary) return loadJson(source, Map.of(name, bytes), new ArrayList<>());
        source.getAsJsonArray("buffers").get(0).getAsJsonObject().remove("uri");
        return new JgltfModelLoader().load(new ByteArrayInputStream(glb(source, bytes)));
    }

    private static NormalizedGltfModel loadJson(JsonObject source, Map<String, byte[]> resources, List<String> resolved)
            throws Exception {
        return new JgltfModelLoader().load(new ByteArrayInputStream(source.toString().getBytes(StandardCharsets.UTF_8)),
                uri -> {
                    resolved.add(uri);
                    byte[] bytes = resources.get(uri);
                    if (bytes == null) throw new GltfLoadException("Unexpected fixture resource " + uri);
                    return ByteBuffer.wrap(bytes);
                });
    }

    private static JsonObject triangle() {
        JsonObject source = object("""
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":68}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":6}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{}}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """);
        source.getAsJsonArray("buffers").get(0).getAsJsonObject()
                .addProperty("uri", dataUri("application/octet-stream", triangleBytes()));
        return source;
    }

    private static byte[] triangleBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1}) buffer.putFloat(value);
        buffer.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        return buffer.array();
    }

    private static void addBasis(JsonObject source, JsonObject image) {
        for (String name : List.of("extensionsUsed", "extensionsRequired")) {
            if (!source.has(name)) source.add(name, new JsonArray());
            source.getAsJsonArray(name).add(BASIS);
        }
        JsonArray images = new JsonArray(); images.add(image); source.add("images", images);
        source.add("textures", JsonParser.parseString("[{\"extensions\":{\"KHR_texture_basisu\":{\"source\":0}}}]"));
        source.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("pbrMetallicRoughness")
                .add("baseColorTexture", object("{\"index\":0}"));
    }

    private static byte[] glb(JsonObject source, byte[] binary) {
        byte[] json = source.toString().getBytes(StandardCharsets.UTF_8);
        int jsonLength = (json.length + 3) & ~3;
        int binaryLength = (binary.length + 3) & ~3;
        ByteBuffer result = ByteBuffer.allocate(28 + jsonLength + binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546c67).putInt(2).putInt(result.capacity()).putInt(jsonLength).putInt(0x4e4f534a).put(json);
        while (result.position() < 20 + jsonLength) result.put((byte) 32);
        result.putInt(binaryLength).putInt(0x004e4942).put(binary);
        return result.array();
    }

    private static JsonObject fixtureJson(String name) throws Exception {
        return object(new String(fixture("/gltf/draco/" + name), StandardCharsets.UTF_8));
    }

    private static byte[] fixture(String name) throws Exception {
        try (var input = GltfCompressionNormalizerTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] redPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
