package com.tacz.guns.client.model.gltf.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GltfBasisFallbackRetirementTest {
    @Test
    void externalCorePngFallbackLosesItsImageUriAndResolvedBytesAfterBasisSelection() throws Exception {
        Fixture fixture = fixture(false, false);
        List<String> resolved = new ArrayList<>();
        NormalizedGltfModel loaded = load(fixture, resolved);
        assertTrue(resolved.contains("fallback.png"), "exercise retirement of a resolved fallback, not a missing payload");
        assertTrue(resolved.contains("cyan.ktx2"));
        ConvertedGltfAsset converted = assertOnlyBasisImage(loaded, fixture.ktx());
        assertEquals("cyan.ktx2", converted.images().getFirst().uri());
        assertNull(loaded.asset().getReferenceData("fallback.png"));
        assertFalse(loaded.asset().getReferenceDatas().containsKey("fallback.png"));
        assertTrue(loaded.asset().getReferences().stream().noneMatch(reference -> reference.getUri().equals("fallback.png")));
        assertTrue(loaded.asset().getReferenceDatas().values().stream()
                .noneMatch(data -> Arrays.equals(fixture.png(), bytes(data))));
        assertCompactGeometryAndImages(loaded, 3, triangleBytes());
    }

    @Test
    void embeddedCorePngFallbackLosesItsViewAndOriginalGlbStorageAfterBasisSelection() throws Exception {
        Fixture fixture = fixture(true, false);
        NormalizedGltfModel loaded = load(fixture, new ArrayList<>());
        ConvertedGltfAsset converted = assertOnlyBasisImage(loaded, fixture.ktx());
        assertEquals("", converted.images().getFirst().uri());
        assertEquals(3, loaded.gltf().getImages().getFirst().getBufferView());
        assertEquals(68, loaded.gltf().getBufferViews().get(3).getByteOffset());
        assertEquals(fixture.ktx().length, loaded.gltf().getBufferViews().get(3).getByteLength());
        ByteBuffer expected = ByteBuffer.allocate(68 + aligned(fixture.ktx().length));
        expected.put(triangleBytes()).put(fixture.ktx());
        assertCompactGeometryAndImages(loaded, 4, expected.array());
        assertNull(loaded.asset().getBinaryData(), "the old GLB BIN containing fallback PNG bytes must be released");
        assertFalse(loaded.binary());
    }

    @Test
    void anotherLiveTextureKeepsThePngAndBothTextureImageIndicesAreRemapped() throws Exception {
        for (boolean embedded : new boolean[]{false, true}) {
            Fixture fixture = fixture(embedded, true);
            NormalizedGltfModel loaded = load(fixture, new ArrayList<>());
            assertEquals(2, loaded.gltf().getImages().size(), "only the unreferenced leading image is retired");
            assertEquals(List.of("fallback-png", "selected-basis"),
                    loaded.gltf().getImages().stream().map(image -> image.getName()).toList());
            assertEquals(1, loaded.gltf().getTextures().get(0).getSource(), "Basis source moves from image 2 to 1");
            assertEquals(0, loaded.gltf().getTextures().get(1).getSource(), "ordinary source moves from image 1 to 0");
            ConvertedGltfAsset converted = new JgltfRuntimeConverter().convert(loaded);
            assertEquals(2, converted.images().size());
            assertEquals(1, converted.materials().get(0).baseColorTexture().imageIndex());
            assertEquals(0, converted.materials().get(1).baseColorTexture().imageIndex());
            assertEquals(List.of(0, 1), converted.renderMeshes().getFirst().primitives().stream()
                    .map(primitive -> primitive.materialIndex()).toList());
            assertEquals("image/png", converted.images().get(0).mimeType());
            assertArrayEquals(fixture.png(), converted.images().get(0).encodedBytes());
            assertEquals("image/ktx2", converted.images().get(1).mimeType());
            assertArrayEquals(fixture.ktx(), converted.images().get(1).encodedBytes());
            if (embedded) {
                assertEquals(3, loaded.gltf().getImages().get(0).getBufferView());
                assertEquals(4, loaded.gltf().getImages().get(1).getBufferView());
                assertCompactGeometryAndImages(loaded, 5, fixture.binary());
            } else {
                assertArrayEquals(fixture.png(), bytes(loaded.asset().getReferenceData("fallback.png")));
                assertCompactGeometryAndImages(loaded, 3, triangleBytes());
            }
        }
    }

    private static ConvertedGltfAsset assertOnlyBasisImage(NormalizedGltfModel loaded, byte[] ktx) throws Exception {
        assertEquals(1, loaded.gltf().getImages().size());
        assertEquals(1, loaded.model().getImageModels().size());
        assertEquals("selected-basis", loaded.gltf().getImages().getFirst().getName());
        assertEquals("image/ktx2", loaded.gltf().getImages().getFirst().getMimeType());
        assertEquals(0, loaded.gltf().getTextures().getFirst().getSource(), "old Basis image index 1 must become 0");
        assertFalse(loaded.extensionsUsed().contains("KHR_texture_basisu"));
        ConvertedGltfAsset converted = new JgltfRuntimeConverter().convert(loaded);
        assertEquals(1, converted.images().size());
        assertEquals(0, converted.materials().getFirst().baseColorTexture().imageIndex());
        assertArrayEquals(ktx, converted.images().getFirst().encodedBytes());
        return converted;
    }

    private static void assertCompactGeometryAndImages(NormalizedGltfModel loaded, int views, byte[] expected) {
        assertNull(loaded.asset().getBinaryData());
        assertEquals(views, loaded.gltf().getBufferViews().size());
        assertEquals(1, loaded.gltf().getBuffers().size());
        assertEquals(expected.length, loaded.gltf().getBuffers().getFirst().getByteLength());
        ByteBuffer actual = loaded.model().getBufferModels().getFirst().getBufferData();
        assertEquals(expected.length, actual.capacity(), "compacted storage must not retain the larger original backing buffer");
        assertArrayEquals(expected, bytes(actual));
    }

    private static Fixture fixture(boolean embedded, boolean shared) throws Exception {
        byte[] ktx;
        try (var input = GltfBasisFallbackRetirementTest.class.getResourceAsStream("/gltf/ktx2/cyan_rgb_reference_uastc.ktx2")) {
            assertNotNull(input);
            ktx = input.readAllBytes();
        }
        byte[] png = redPng();
        JsonObject source = triangle();
        JsonObject fallback = object("{\"name\":\"fallback-png\",\"mimeType\":\"image/png\"}");
        JsonObject basis = object("{\"name\":\"selected-basis\",\"mimeType\":\"image/ktx2\"}");
        byte[] binary = null;
        if (embedded) {
            ByteBuffer data = ByteBuffer.allocate(68 + aligned(png.length) + aligned(ktx.length));
            data.put(triangleBytes()).put(png).position(68 + aligned(png.length));
            data.put(ktx);
            binary = data.array();
            JsonObject buffer = source.getAsJsonArray("buffers").get(0).getAsJsonObject();
            buffer.remove("uri");
            buffer.addProperty("byteLength", binary.length);
            source.getAsJsonArray("bufferViews").add(view(68, png.length));
            source.getAsJsonArray("bufferViews").add(view(68 + aligned(png.length), ktx.length));
            fallback.addProperty("bufferView", 3);
            basis.addProperty("bufferView", 4);
        } else {
            fallback.addProperty("uri", "fallback.png");
            basis.addProperty("uri", "cyan.ktx2");
        }
        JsonArray images = new JsonArray();
        if (shared) {
            JsonObject orphan = fallback.deepCopy();
            orphan.addProperty("name", "unreferenced-leading-image");
            images.add(orphan);
        }
        images.add(fallback);
        images.add(basis);
        source.add("images", images);
        int fallbackIndex = shared ? 1 : 0;
        source.add("textures", JsonParser.parseString("[{\"source\":" + fallbackIndex
                + ",\"extensions\":{\"KHR_texture_basisu\":{\"source\":" + (fallbackIndex + 1) + "}}}]"));
        if (shared) {
            source.getAsJsonArray("textures").add(object("{\"source\":1}"));
            source.getAsJsonArray("materials").add(object("{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":1}}}"));
            JsonArray primitives = source.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
            JsonObject second = primitives.get(0).getAsJsonObject().deepCopy();
            second.addProperty("material", 1);
            primitives.add(second);
        }
        return new Fixture(source, binary, ktx, png);
    }

    private static NormalizedGltfModel load(Fixture fixture, List<String> resolved) throws Exception {
        byte[] source = fixture.binary() == null
                ? fixture.source().toString().getBytes(StandardCharsets.UTF_8) : glb(fixture.source(), fixture.binary());
        Map<String, byte[]> resources = Map.of("fallback.png", fixture.png(), "cyan.ktx2", fixture.ktx());
        return new JgltfModelLoader().load(new ByteArrayInputStream(source), uri -> {
            resolved.add(uri);
            byte[] bytes = resources.get(uri);
            if (bytes == null) throw new GltfLoadException("Unexpected fixture resource " + uri);
            return ByteBuffer.wrap(bytes);
        });
    }

    private static JsonObject triangle() {
        JsonObject source = object("""
                {"asset":{"version":"2.0"},"extensionsUsed":["KHR_texture_basisu"],"buffers":[{"byteLength":68}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":6}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                 "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """);
        source.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri",
                "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangleBytes()));
        return source;
    }

    private static byte[] triangleBytes() {
        ByteBuffer bytes = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1}) bytes.putFloat(value);
        bytes.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        return bytes.array();
    }

    private static byte[] redPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static JsonObject view(int offset, int length) {
        return object("{\"buffer\":0,\"byteOffset\":" + offset + ",\"byteLength\":" + length + "}");
    }

    private static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static int aligned(int bytes) { return (bytes + 3) & ~3; }

    private static byte[] bytes(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        return bytes;
    }

    private static byte[] glb(JsonObject source, byte[] binary) {
        byte[] json = source.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer glb = ByteBuffer.allocate(28 + aligned(json.length) + aligned(binary.length)).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67).putInt(2).putInt(glb.capacity()).putInt(aligned(json.length)).putInt(0x4e4f534a).put(json);
        while (glb.position() < 20 + aligned(json.length)) glb.put((byte) 32);
        glb.putInt(aligned(binary.length)).putInt(0x004e4942).put(binary);
        return glb.array();
    }

    private record Fixture(JsonObject source, byte[] binary, byte[] ktx, byte[] png) { }
}
