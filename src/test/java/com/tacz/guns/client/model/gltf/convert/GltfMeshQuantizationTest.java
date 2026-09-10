package com.tacz.guns.client.model.gltf.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.runtime.GltfSceneTransforms;
import de.javagl.jgltf.model.ElementType;
import de.javagl.jgltf.model.impl.DefaultAccessorModel;
import de.javagl.jgltf.model.impl.DefaultBufferModel;
import de.javagl.jgltf.model.impl.DefaultBufferViewModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Type matrix from Khronos KHR_mesh_quantization, not a model-specific compatibility fixture. */
class GltfMeshQuantizationTest {
    @Test
    void requiredQuantizationAllowsSignedAndUnsignedPositionsWithOrWithoutNormalization() throws Exception {
        for (int type : List.of(5120, 5121, 5122, 5123)) for (boolean normalized : List.of(false, true)) {
            var result = primitive(convert(fixture("POSITION", type, normalized, false)));
            assertArrayEquals(expected(type, normalized, 3), result.runtimePrimitive().positions(), 1e-7f);
        }
    }

    @Test
    void signedNormalsNormalizeExactlyOnceIncludingTheNegativeEndpoint() throws Exception {
        for (int type : List.of(5120, 5122)) {
            assertArrayEquals(expected(type, true, 3), primitive(convert(fixture("NORMAL", type, true, false)))
                    .runtimePrimitive().normals(), 1e-7f);
        }
    }

    @Test
    void normalTypeMatrixRejectsUnsignedAndUnnormalizedIntegers() {
        for (int type : List.of(5120, 5121, 5122, 5123)) for (boolean normalized : List.of(false, true)) {
            if ((type == 5120 || type == 5122) && normalized) continue;
            assertThrows(GltfConversionException.class, () -> convert(fixture("NORMAL", type, normalized, false)));
        }
    }

    @Test
    void uvZeroAcceptsTheExtensionIntegerMatrixWithoutInventingDequantization() throws Exception {
        for (int type : List.of(5120, 5121, 5122, 5123)) for (boolean normalized : List.of(false, true)) {
            assertArrayEquals(expected(type, normalized, 2), primitive(convert(fixture("TEXCOORD_0", type, normalized, false)))
                    .texCoords0(), 1e-7f);
        }
    }

    @Test
    void integerPositionUsesTheExistingNodeTransformForDequantization() throws Exception {
        JsonObject json = fixture("POSITION", 5122, false, false);
        JsonObject node = json.getAsJsonArray("nodes").get(0).getAsJsonObject();
        node.add("scale", JsonParser.parseString("[0.001,0.001,0.001]"));
        node.add("translation", JsonParser.parseString("[10,20,30]"));
        ConvertedGltfAsset asset = convert(json);
        float[] positions = primitive(asset).runtimePrimitive().positions();
        Vector3f point = GltfSceneTransforms.computeWorldTransforms(asset.runtimeScene())[0]
                .transformPosition(new Vector3f(positions[0], positions[1], positions[2]));
        assertEquals(-22.768f, point.x, 1e-5f);
        assertEquals(20f, point.y, 1e-5f);
        assertEquals(62.767f, point.z, 1e-5f);
    }

    @Test
    void declarationMustBeBothUsedAndRequiredAndDoesNotRelaxCoreWithoutIt() throws Exception {
        for (String field : List.of("extensionsUsed", "extensionsRequired")) {
            JsonObject json = fixture("POSITION", 5122, true, false);
            json.remove(field);
            assertThrows(Exception.class, () -> convert(json));
        }
        JsonObject undeclared = fixture("POSITION", 5122, true, false);
        undeclared.remove("extensionsUsed"); undeclared.remove("extensionsRequired");
        assertThrows(GltfConversionException.class, () -> convert(undeclared));
        JsonObject coreUv = fixture("TEXCOORD_0", 5123, true, false);
        coreUv.remove("extensionsUsed"); coreUv.remove("extensionsRequired");
        assertArrayEquals(expected(5123, true, 2), primitive(convert(coreUv)).texCoords0(), 1e-7f);
        coreUv.getAsJsonArray("accessors").get(3).getAsJsonObject().addProperty("normalized", false);
        assertThrows(GltfConversionException.class, () -> convert(coreUv));
    }

    @Test
    void morphPositionIsSignedOnlyWhileMorphNormalsAlsoRequireNormalization() throws Exception {
        for (int type : List.of(5120, 5122)) for (boolean normalized : List.of(false, true)) {
            var target = primitive(convert(fixture("POSITION", type, normalized, true))).runtimePrimitive().morphTargets().getFirst();
            assertArrayEquals(expected(type, normalized, 3), target.positionDeltas(), 1e-7f);
        }
        for (int type : List.of(5121, 5123)) for (boolean normalized : List.of(false, true)) {
            assertThrows(GltfConversionException.class, () -> convert(fixture("POSITION", type, normalized, true)));
        }
        for (int type : List.of(5120, 5122)) {
            assertArrayEquals(expected(type, true, 3), primitive(convert(fixture("NORMAL", type, true, true)))
                    .runtimePrimitive().morphTargets().getFirst().normalDeltas(), 1e-7f);
            assertThrows(GltfConversionException.class, () -> convert(fixture("NORMAL", type, false, true)));
        }
        assertThrows(GltfConversionException.class, () -> convert(fixture("TEXCOORD_0", 5122, false, true)));
    }

    @Test
    void stillRejectsTangentsUvOneAndUnsupportedIntegerWidths() {
        assertThrows(GltfConversionException.class, () -> convert(fixture("TANGENT", 5122, true, false)));
        assertThrows(GltfConversionException.class, () -> convert(fixture("TEXCOORD_1", 5122, true, false)));
        assertThrows(GltfConversionException.class, () -> convert(fixture("POSITION", 5125, false, false)));
        assertThrows(GltfConversionException.class, () -> convert(fixture("POSITION", 5126, true, false)));
    }

    @Test
    void quantizedVertexElementsStillRequireFourByteAlignment() {
        JsonObject json = fixture("POSITION", 5122, true, false);
        json.getAsJsonArray("bufferViews").get(3).getAsJsonObject().addProperty("byteStride", 6);
        assertThrows(Exception.class, () -> convert(json));
        JsonObject offset = fixture("POSITION", 5122, true, false);
        offset.getAsJsonArray("accessors").get(3).getAsJsonObject().addProperty("byteOffset", 2);
        assertThrows(Exception.class, () -> convert(offset));
    }

    @Test
    void viewOffsetUsesComponentAlignmentWhileVertexOffsetAndStrideUseFourBytes() throws Exception {
        var loaded = new JgltfModelLoader().load(new ByteArrayInputStream(
                fixture("POSITION", 5122, false, false).toString().getBytes(StandardCharsets.UTF_8)));
        var rules = GltfMeshAttributeRules.read(loaded);
        // Also test the model view directly: compressed normalization can repack its offset.
        assertArrayEquals(expected(5122, false, 3),
                rules.read(shortPositions(2, 0, 8), "POSITION", false, 3, "POSITION"));
        for (int[] offsets : List.of(new int[]{1, 0, 8}, new int[]{2, 2, 8}, new int[]{2, 0, 6})) {
            assertThrows(GltfConversionException.class, () -> rules.read(
                    shortPositions(offsets[0], offsets[1], offsets[2]), "POSITION", false, 3, "POSITION"));
        }
        for (int offset : List.of(2, 1)) {
            JsonObject json = fixture("POSITION", 5122, false, false);
            JsonObject buffer = json.getAsJsonArray("buffers").get(3).getAsJsonObject();
            byte[] original = Base64.getDecoder().decode(buffer.get("uri").getAsString().split(",", 2)[1]);
            ByteBuffer shifted = ByteBuffer.allocate(offset + original.length);
            shifted.position(offset); shifted.put(original);
            buffer.addProperty("uri", uri(shifted.array())); buffer.addProperty("byteLength", shifted.capacity());
            json.getAsJsonArray("bufferViews").get(3).getAsJsonObject().addProperty("byteOffset", offset);
            if (offset == 2) assertArrayEquals(expected(5122, false, 3), primitive(convert(json)).runtimePrimitive().positions());
            else assertThrows(GltfConversionException.class, () -> convert(json));
        }
    }

    @Test
    void realMeshoptEncodedQuantizedPositionsReachTheConverterUnchanged() throws Exception {
        JsonObject json = fixture("POSITION", 5122, true, false);
        JsonObject rawBuffer = json.getAsJsonArray("buffers").get(3).getAsJsonObject();
        byte[] raw = Base64.getDecoder().decode(rawBuffer.get("uri").getAsString().split(",", 2)[1]);
        ByteBuffer input = MemoryUtil.memAlloc(raw.length).put(raw).flip();
        ByteBuffer output = MemoryUtil.memAlloc(Math.toIntExact(MeshOptimizer.meshopt_encodeVertexBufferBound(3, 8)));
        byte[] compressed;
        try {
            long size = MeshOptimizer.meshopt_encodeVertexBufferLevel(output, input, 3, 8, 2, 0);
            assertTrue(size > 0);
            compressed = new byte[Math.toIntExact(size)];
            output.get(compressed);
        } finally {
            MemoryUtil.memFree(output); MemoryUtil.memFree(input);
        }
        rawBuffer.remove("uri");
        rawBuffer.add("extensions", JsonParser.parseString("{\"EXT_meshopt_compression\":{\"fallback\":true}}"));
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", compressed.length); buffer.addProperty("uri", uri(compressed));
        json.getAsJsonArray("buffers").add(buffer);
        JsonObject extension = JsonParser.parseString("{\"buffer\":4,\"byteOffset\":0,\"byteStride\":8,\"count\":3,\"mode\":\"ATTRIBUTES\"}").getAsJsonObject();
        extension.addProperty("byteLength", compressed.length);
        JsonObject extensions = new JsonObject(); extensions.add("EXT_meshopt_compression", extension);
        json.getAsJsonArray("bufferViews").get(3).getAsJsonObject().add("extensions", extensions);
        json.getAsJsonArray("extensionsUsed").add("EXT_meshopt_compression");
        json.getAsJsonArray("extensionsRequired").add("EXT_meshopt_compression");
        var loaded = new JgltfModelLoader().load(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("KHR_mesh_quantization"), loaded.extensionsRequired());
        assertArrayEquals(expected(5122, true, 3), primitive(new JgltfRuntimeConverter().convert(loaded))
                .runtimePrimitive().positions(), 1e-7f);
    }

    @Test
    void realDracoIntegerVec3OutputIsPaddedWithoutChangingVertexValues() throws Exception {
        // Google draco3dgltf 1.5.7: AddInt16Attribute(POSITION,3,3,[0,0,0,100,0,0,0,100,0]),
        // AddFacesToMesh([0,1,2]), ExpertEncoder MESH_SEQUENTIAL_ENCODING, speed(5,5), deduplicate=false.
        String compressed = "RFJBQ08CAgEAAAABAwEAAQIBAQADAwAAAQABAQADA60qVRUDoHqBiAEAAAAAZAAAAA==";
        JsonObject json = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"extensionsUsed":["KHR_draco_mesh_compression","KHR_mesh_quantization"],
                 "extensionsRequired":["KHR_draco_mesh_compression","KHR_mesh_quantization"],
                 "buffers":[{"byteLength":49}],"bufferViews":[{"buffer":0,"byteLength":49}],
                 "accessors":[{"componentType":5123,"count":3,"type":"SCALAR"},
                              {"componentType":5122,"count":3,"type":"VEC3"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":1},"indices":0,
                  "extensions":{"KHR_draco_mesh_compression":{"bufferView":0,"attributes":{"POSITION":0}}}}]}],
                 "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """).getAsJsonObject();
        json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", "data:application/octet-stream;base64," + compressed);
        var loaded = new JgltfModelLoader().load(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
        var positions = loaded.model().getAccessorModels().get(1);
        assertEquals(8, positions.getBufferViewModel().getByteStride());
        assertEquals(24, positions.getBufferViewModel().getByteLength());
        assertArrayEquals(new float[]{0,0,0,100,0,0,0,100,0},
                primitive(new JgltfRuntimeConverter().convert(loaded)).runtimePrimitive().positions());
    }

    private static GltfRenderPrimitive primitive(ConvertedGltfAsset asset) {
        return asset.renderMeshes().getFirst().primitives().getFirst();
    }

    private static DefaultAccessorModel shortPositions(int viewOffset, int accessorOffset, int stride) {
        int length = accessorOffset + 3 * stride;
        ByteBuffer data = ByteBuffer.allocate(viewOffset + length).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = expected(5122, false, 3);
        for (int i = 0; i < 3; i++) {
            data.position(viewOffset + accessorOffset + i * stride);
            for (int j = 0; j < 3; j++) data.putShort((short) values[i * 3 + j]);
        }
        DefaultBufferModel buffer = new DefaultBufferModel();
        buffer.setBufferData(data.clear());
        DefaultBufferViewModel view = new DefaultBufferViewModel(34962);
        view.setBufferModel(buffer); view.setByteOffset(viewOffset);
        view.setByteLength(length); view.setByteStride(stride);
        DefaultAccessorModel accessor = new DefaultAccessorModel(5122, 3, ElementType.VEC3);
        accessor.setBufferViewModel(view); accessor.setByteOffset(accessorOffset); accessor.setByteStride(stride);
        return accessor;
    }

    private static ConvertedGltfAsset convert(JsonObject json) throws Exception {
        return new JgltfRuntimeConverter().convert(new JgltfModelLoader().load(
                new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private static JsonObject fixture(String semantic, int type, boolean normalized, boolean morph) {
        JsonObject root = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"extensionsUsed":["KHR_mesh_quantization"],
                 "extensionsRequired":["KHR_mesh_quantization"],"buffers":[],"bufferViews":[],"accessors":[],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2}}]}],
                 "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """).getAsJsonObject();
        append(root, 5126, false, 3, new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0});
        append(root, 5126, false, 3, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1});
        append(root, 5126, false, 2, new float[]{0, 0, 1, 0, 0, 1});
        int components = semantic.startsWith("TEXCOORD") ? 2 : semantic.equals("TANGENT") ? 4 : 3;
        append(root, type, normalized, components, expected(type, false, components));
        JsonObject primitive = root.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives").get(0).getAsJsonObject();
        if (morph) {
            JsonObject target = new JsonObject(); target.addProperty(semantic, 3);
            JsonArray targets = new JsonArray(); targets.add(target); primitive.add("targets", targets);
        } else primitive.getAsJsonObject("attributes").addProperty(semantic, 3);
        return root;
    }

    private static float[] expected(int type, boolean normalized, int components) {
        int minimum = type == 5120 ? -128 : type == 5122 ? -32768 : 0;
        int maximum = switch (type) { case 5120 -> 127; case 5121 -> 255; case 5122 -> 32767; case 5123 -> 65535; default -> 1; };
        float[] result = new float[3 * components];
        for (int i = 0; i < result.length; i++) {
            int value = i % 3 == 0 ? minimum : i % 3 == 2 ? maximum : 0;
            result[i] = normalized ? Math.max(value / (float) maximum, -1f) : value;
        }
        return result;
    }

    private static void append(JsonObject root, int type, boolean normalized, int components, float[] values) {
        int bytes = type == 5120 || type == 5121 ? 1 : type == 5122 || type == 5123 ? 2 : 4;
        int stride = (components * bytes + 3) & ~3;
        ByteBuffer data = ByteBuffer.allocate(3 * stride).order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < 3; v++) {
            data.position(v * stride);
            for (int c = 0; c < components; c++) {
                float value = values[v * components + c];
                if (bytes == 1) data.put((byte) value);
                else if (bytes == 2) data.putShort((short) value);
                else if (type == 5126) data.putFloat(value); else data.putInt((int) value);
            }
        }
        int index = root.getAsJsonArray("buffers").size();
        JsonObject buffer = new JsonObject(); buffer.addProperty("byteLength", data.capacity()); buffer.addProperty("uri", uri(data.array()));
        root.getAsJsonArray("buffers").add(buffer);
        JsonObject view = new JsonObject(); view.addProperty("buffer", index); view.addProperty("byteOffset", 0);
        view.addProperty("byteLength", data.capacity()); view.addProperty("byteStride", stride); view.addProperty("target", 34962);
        root.getAsJsonArray("bufferViews").add(view);
        JsonObject accessor = new JsonObject(); accessor.addProperty("bufferView", index); accessor.addProperty("byteOffset", 0);
        accessor.addProperty("componentType", type); accessor.addProperty("type", "VEC" + components);
        accessor.addProperty("count", 3); accessor.addProperty("normalized", normalized);
        root.getAsJsonArray("accessors").add(accessor);
    }

    private static String uri(byte[] data) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data);
    }
}
