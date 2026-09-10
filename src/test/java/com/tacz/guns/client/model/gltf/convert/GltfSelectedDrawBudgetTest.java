package com.tacz.guns.client.model.gltf.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.quality.GltfMeshSimplifier;
import com.tacz.guns.client.model.gltf.quality.GltfRenderQuality;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GltfSelectedDrawBudgetTest {
    private static final GltfRenderQuality ORIGINAL = new GltfRenderQuality(8192, 0, 1);
    private static final GltfRenderQuality LOW = new GltfRenderQuality(1024, 1, .3f);

    @Test
    void sourceAboveDrawBudgetCanSelectTheAuthoredLowLodButNotTheHighLod() throws Exception {
        JsonObject json = fixture(110_000, 10_000);
        json.add("extensionsUsed", JsonParser.parseString("[\"MSFT_lod\"]"));
        json.getAsJsonArray("nodes").get(0).getAsJsonObject()
                .add("extensions", JsonParser.parseString("{\"MSFT_lod\":{\"ids\":[1]}}"));
        ConvertedGltfAsset source = convert(json);
        assertEquals(330_000, source.renderMeshes().get(0).primitives().getFirst().indices().length);
        assertThrows(IllegalArgumentException.class, () -> GltfMeshSimplifier.apply(source, ORIGINAL));
        var low = GltfMeshSimplifier.apply(source.withLodLevel(1), LOW);
        assertEquals(Set.of(1), low.selectedNodeIndices());
        assertEquals(30_000, low.renderMeshes().get(1).primitives().getFirst().indices().length);
        assertDoesNotThrow(() -> GltfAssetLimits.enforceSelectedDraw(low));
    }

    @Test
    void sumsSelectedInstancesNotUniqueMeshesAndDoesNotChargeUnselectedScenes() throws Exception {
        JsonObject json = fixture(60_000);
        json.getAsJsonArray("nodes").add(JsonParser.parseString("{\"mesh\":0}"));
        json.getAsJsonArray("scenes").get(0).getAsJsonObject().add("nodes", JsonParser.parseString("[0,1]"));
        ConvertedGltfAsset both = convert(json);
        assertThrows(IllegalArgumentException.class, () -> GltfMeshSimplifier.apply(both, ORIGINAL));
        json.getAsJsonArray("scenes").get(0).getAsJsonObject().add("nodes", JsonParser.parseString("[0]"));
        json.getAsJsonArray("scenes").add(JsonParser.parseString("{\"nodes\":[1]}"));
        assertDoesNotThrow(() -> GltfMeshSimplifier.apply(convert(json), ORIGINAL));
    }

    @Test
    void selectedDrawBoundaryIsStillExactlyOneHundredThousandTriangles() throws Exception {
        assertDoesNotThrow(() -> GltfMeshSimplifier.apply(convert(fixture(100_000)), ORIGINAL));
        assertThrows(IllegalArgumentException.class, () -> GltfMeshSimplifier.apply(convert(fixture(100_001)), ORIGINAL));
    }

    @Test
    void simplificationRunsBeforeTheSelectedDrawCheck() throws Exception {
        var source = convert(grid(230));
        var simplified = GltfMeshSimplifier.apply(source, new GltfRenderQuality(512, 3, .1f));
        assertTrue(simplified.renderMeshes().getFirst().primitives().getFirst().indices().length <= 300_000);
        assertDoesNotThrow(() -> GltfAssetLimits.enforceSelectedDraw(simplified));
    }

    private static JsonObject grid(int cells) {
        int vertices = (cells + 1) * (cells + 1);
        int indices = cells * cells * 6;
        ByteBuffer data = ByteBuffer.allocate(vertices * 12 + indices * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y <= cells; y++) for (int x = 0; x <= cells; x++) data.putFloat(x).putFloat(y).putFloat(0);
        for (int y = 0; y < cells; y++) for (int x = 0; x < cells; x++) {
            int a = y * (cells + 1) + x, b = a + 1, c = a + cells + 1, d = c + 1;
            for (int index : new int[]{a, b, c, b, d, c}) data.putShort((short) index);
        }
        JsonObject json = fixture(1);
        JsonObject buffer = json.getAsJsonArray("buffers").get(0).getAsJsonObject();
        buffer.addProperty("byteLength", data.capacity());
        buffer.addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data.array()));
        json.getAsJsonArray("bufferViews").get(0).getAsJsonObject().addProperty("byteLength", vertices * 12);
        json.getAsJsonArray("bufferViews").get(1).getAsJsonObject().addProperty("byteOffset", vertices * 12);
        json.getAsJsonArray("bufferViews").get(1).getAsJsonObject().addProperty("byteLength", indices * 2);
        json.getAsJsonArray("accessors").get(0).getAsJsonObject().addProperty("count", vertices);
        json.getAsJsonArray("accessors").get(1).getAsJsonObject().addProperty("count", indices);
        return json;
    }

    private static ConvertedGltfAsset convert(JsonObject json) throws Exception {
        return new JgltfRuntimeConverter().convert(new JgltfModelLoader().load(
                new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private static JsonObject fixture(int... triangles) {
        int indexCount = 0;
        for (int count : triangles) indexCount += count * 3;
        ByteBuffer data = ByteBuffer.allocate(36 + indexCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) data.putFloat(value);
        for (int i = 0; i < indexCount; i++) data.putShort((short) (i % 3));
        JsonObject json = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"buffers":[{}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
                 "meshes":[],"nodes":[],"scenes":[{"nodes":[0]}],"scene":0}
                """).getAsJsonObject();
        JsonObject buffer = json.getAsJsonArray("buffers").get(0).getAsJsonObject();
        buffer.addProperty("byteLength", data.capacity());
        buffer.addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data.array()));
        int offset = 36;
        for (int i = 0; i < triangles.length; i++) {
            int length = triangles[i] * 6;
            JsonObject view = new JsonObject(); view.addProperty("buffer", 0); view.addProperty("byteOffset", offset); view.addProperty("byteLength", length);
            json.getAsJsonArray("bufferViews").add(view);
            JsonObject accessor = new JsonObject(); accessor.addProperty("bufferView", i + 1); accessor.addProperty("componentType", 5123);
            accessor.addProperty("count", triangles[i] * 3); accessor.addProperty("type", "SCALAR");
            json.getAsJsonArray("accessors").add(accessor);
            JsonObject primitive = new JsonObject(); primitive.add("attributes", JsonParser.parseString("{\"POSITION\":0}")); primitive.addProperty("indices", i + 1);
            JsonArray primitives = new JsonArray(); primitives.add(primitive); JsonObject mesh = new JsonObject(); mesh.add("primitives", primitives);
            json.getAsJsonArray("meshes").add(mesh);
            JsonObject node = new JsonObject(); node.addProperty("mesh", i); json.getAsJsonArray("nodes").add(node);
            offset += length;
        }
        return json;
    }
}
