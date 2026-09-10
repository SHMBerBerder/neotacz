package com.tacz.guns.client.model.gltf.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfMeshoptDecoderTest {
    private static final String EXTENSION = "EXT_meshopt_compression";

    @Test
    void officialBoxEncodedWithNativeCodecKeepsGeometryMaterialsAndNodeIdsThroughConversion() throws Exception {
        byte[] source;
        try (var input = GltfMeshoptDecoderTest.class.getResourceAsStream("/gltf/meshopt/Box.glb")) {
            if (input == null) throw new AssertionError("missing official Box fixture");
            source = input.readAllBytes();
        }
        NormalizedGltfModel original = new JgltfModelLoader().load(new ByteArrayInputStream(source));
        int jsonLength = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        JsonObject json = JsonParser.parseString(new String(source, 20, jsonLength, StandardCharsets.UTF_8)).getAsJsonObject();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        for (int index = 0; index < original.model().getBufferViewModels().size(); index++) {
            var view = original.model().getBufferViewModels().get(index);
            byte[] raw = bytes(view.getBufferViewData());
            boolean indices = Integer.valueOf(34963).equals(view.getTarget());
            int stride = indices ? 2 : view.getByteStride();
            int count = raw.length / stride;
            byte[] encoded;
            if (indices) {
                ByteBuffer values = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                int[] array = new int[count];
                for (int entry = 0; entry < count; entry++) array[entry] = Short.toUnsignedInt(values.getShort());
                encoded = encodeIndices(array, "INDICES");
            } else {
                encoded = encodeAttributes(raw, stride);
            }
            JsonObject target = json.getAsJsonArray("bufferViews").get(index).getAsJsonObject();
            target.addProperty("buffer", 1);
            JsonObject extension = new JsonObject();
            extension.addProperty("buffer", 0);
            extension.addProperty("byteOffset", compressed.size());
            extension.addProperty("byteLength", encoded.length);
            extension.addProperty("byteStride", stride);
            extension.addProperty("count", count);
            extension.addProperty("mode", indices ? "INDICES" : "ATTRIBUTES");
            JsonObject extensions = new JsonObject();
            extensions.add(EXTENSION, extension);
            target.add("extensions", extensions);
            compressed.write(encoded);
        }
        JsonObject placeholder = json.getAsJsonArray("buffers").get(0).getAsJsonObject().deepCopy();
        placeholder.remove("uri");
        JsonObject compressedBuffer = new JsonObject();
        compressedBuffer.addProperty("byteLength", compressed.size());
        compressedBuffer.addProperty("uri", dataUri(compressed.toByteArray()));
        JsonArray buffers = new JsonArray();
        buffers.add(compressedBuffer);
        buffers.add(placeholder);
        json.add("buffers", buffers);
        json.add("extensionsUsed", JsonParser.parseString("[\"EXT_meshopt_compression\"]"));
        json.add("extensionsRequired", JsonParser.parseString("[\"EXT_meshopt_compression\"]"));
        NormalizedGltfModel decoded = load(json);
        var before = new JgltfRuntimeConverter().convert(original);
        var after = new JgltfRuntimeConverter().convert(decoded);
        assertEquals(before.runtimeScene().nodes().size(), after.runtimeScene().nodes().size());
        assertEquals(1, after.materials().size());
        var beforePrimitive = before.renderMeshes().getFirst().primitives().getFirst();
        var afterPrimitive = after.renderMeshes().getFirst().primitives().getFirst();
        assertArrayEquals(beforePrimitive.indices(), afterPrimitive.indices());
        assertArrayEquals(beforePrimitive.runtimePrimitive().positions(), afterPrimitive.runtimePrimitive().positions());
        assertArrayEquals(beforePrimitive.runtimePrimitive().normals(), afterPrimitive.runtimePrimitive().normals());
        assertArrayEquals(before.materials().getFirst().baseColorFactor(), after.materials().getFirst().baseColorFactor());
    }

    @Test
    void realNativeEncodedAttributesRoundTripWithSourceAndFallbackOffsets() throws Exception {
        byte[] original = floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        JsonObject json = fixture(encodeAttributes(original, 12), 3, 12, "ATTRIBUTES", null);
        json.getAsJsonArray("bufferViews").get(0).getAsJsonObject().addProperty("byteStride", 12);
        NormalizedGltfModel result = load(json);
        assertArrayEquals(original, decodedView(result));
        assertEquals(0, result.gltf().getBufferViews().getFirst().getByteOffset());
        assertEquals(12, result.gltf().getBufferViews().getFirst().getByteStride());
        assertFalse(result.extensionsUsed().contains(EXTENSION));
        assertFalse(result.extensionsRequired().contains(EXTENSION));
        var extensions = result.gltf().getBufferViews().getFirst().getExtensions();
        assertTrue(extensions == null || !extensions.containsKey(EXTENSION));
    }

    @Test
    void realNativeEncodedTriangleAndSequenceModesRoundTripAtBothIndexWidths() throws Exception {
        for (String mode : List.of("TRIANGLES", "INDICES")) {
            int[] indices = mode.equals("TRIANGLES") ? new int[]{0, 1, 2} : new int[]{9, 2, 7, 0, 5};
            byte[] compressed = encodeIndices(indices, mode);
            for (int width : List.of(2, 4)) {
                ByteBuffer expected = ByteBuffer.allocate(indices.length * width).order(ByteOrder.LITTLE_ENDIAN);
                for (int index : indices) {
                    if (width == 2) expected.putShort((short) index); else expected.putInt(index);
                }
                assertArrayEquals(expected.array(), decodedView(load(fixture(compressed, indices.length, width, mode, null))));
            }
        }
    }

    @Test
    void postDecodeFiltersRestoreKnownOctahedralQuaternionAndExponentialValues() throws Exception {
        for (int stride : List.of(4, 8)) {
            byte[] filtered = encodeFilter("OCTAHEDRAL", stride, new float[]{0, 0, 1, 0});
            byte[] restored = decodedView(load(fixture(encodeAttributes(filtered, stride), 1, stride,
                    "ATTRIBUTES", "OCTAHEDRAL")));
            ByteBuffer expected = ByteBuffer.allocate(stride).order(ByteOrder.LITTLE_ENDIAN);
            if (stride == 4) expected.put(new byte[]{0, 0, 127, 0});
            else expected.putShort((short) 0).putShort((short) 0).putShort((short) 32767).putShort((short) 0);
            assertArrayEquals(expected.array(), restored);
        }
        byte[] quaternion = encodeFilter("QUATERNION", 8, new float[]{0, 0, 0, 1});
        byte[] expectedQuaternion = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 32767).array();
        assertArrayEquals(expectedQuaternion, decodedView(load(fixture(encodeAttributes(quaternion, 8), 1, 8,
                "ATTRIBUTES", "QUATERNION"))));
        byte[] exponential = encodeFilter("EXPONENTIAL", 12, new float[]{1, 2, -3});
        assertArrayEquals(floats(1, 2, -3), decodedView(load(fixture(encodeAttributes(exponential, 12), 1, 12,
                "ATTRIBUTES", "EXPONENTIAL"))));
    }

    @Test
    void rejectsInvalidExtensionDimensionsModesFiltersAndRanges() {
        byte[] compressed = encodeAttributes(floats(1, 2, 3), 12);
        for (String replacement : List.of(
                "{\"count\":0}", "{\"count\":1.5}", "{\"count\":\"1\"}", "{\"count\":2147483647}",
                "{\"byteStride\":3}", "{\"byteStride\":260}", "{\"byteLength\":0}", "{\"byteLength\":99999}",
                "{\"byteOffset\":-1}", "{\"buffer\":2}", "{\"mode\":\"UNKNOWN\"}",
                "{\"filter\":\"COLOR\"}", "{\"filter\":\"OCTAHEDRAL\"}", "{\"filter\":\"QUATERNION\"}")) {
            JsonObject json = fixture(compressed, 1, 12, "ATTRIBUTES", null);
            JsonParser.parseString(replacement).getAsJsonObject().entrySet()
                    .forEach(entry -> extension(json).add(entry.getKey(), entry.getValue()));
            assertThrows(GltfLoadException.class, () -> load(json), replacement);
        }
        JsonObject mismatch = fixture(compressed, 1, 12, "ATTRIBUTES", null);
        mismatch.getAsJsonArray("bufferViews").get(0).getAsJsonObject().addProperty("byteStride", 16);
        assertThrows(GltfLoadException.class, () -> load(mismatch));
        JsonObject indexFilter = fixture(encodeIndices(new int[]{0, 1, 2}, "TRIANGLES"), 3, 2, "TRIANGLES", "EXPONENTIAL");
        assertThrows(GltfLoadException.class, () -> load(indexFilter));
        JsonObject partialTriangle = fixture(encodeIndices(new int[]{0, 1, 2}, "TRIANGLES"), 2, 2, "TRIANGLES", null);
        assertThrows(GltfLoadException.class, () -> load(partialTriangle));
    }

    @Test
    void rejectsNewerOrLegacyBitstreamHeadersAndTruncatedRealStreams() {
        byte[] attributes = encodeAttributes(floats(1, 2, 3), 12);
        assertEquals(0xa0, Byte.toUnsignedInt(attributes[0]));
        attributes[0] = (byte) 0xa1;
        assertThrows(GltfLoadException.class, () -> load(fixture(attributes, 1, 12, "ATTRIBUTES", null)));
        byte[] triangles = encodeIndices(new int[]{0, 1, 2}, "TRIANGLES");
        assertEquals(0xe1, Byte.toUnsignedInt(triangles[0]));
        triangles[0] = (byte) 0xe0;
        assertThrows(GltfLoadException.class, () -> load(fixture(triangles, 3, 2, "TRIANGLES", null)));
        byte[] real = encodeAttributes(floats(1, 2, 3), 12);
        byte[] truncated = java.util.Arrays.copyOf(real, real.length - 1);
        assertThrows(GltfLoadException.class, () -> load(fixture(truncated, 1, 12, "ATTRIBUTES", null)));
    }

    @Test
    void optionalExtensionUsesCompressedDataAndDoesNotHideCorruptionBehindFallback() throws Exception {
        byte[] original = floats(1, 2, 3);
        JsonObject json = fixture(encodeAttributes(original, 12), 1, 12, "ATTRIBUTES", null);
        json.remove("extensionsRequired");
        json.getAsJsonArray("buffers").get(1).getAsJsonObject().addProperty("uri", dataUri(new byte[20]));
        assertArrayEquals(original, decodedView(load(json)));
        extension(json).addProperty("byteLength", 1);
        assertThrows(GltfLoadException.class, () -> load(json));
    }

    @Test
    void doesNotTreatKhrCompressionAsSupportedByExtDecoding() {
        // KHR_mesh_quantization is separately validated by the converter, not decoded by meshopt.
        for (String unsupported : List.of("KHR_meshopt_compression")) {
            JsonObject json = fixture(encodeAttributes(floats(1, 2, 3), 12), 1, 12, "ATTRIBUTES", null);
            json.getAsJsonArray("extensionsUsed").add(unsupported);
            json.getAsJsonArray("extensionsRequired").add(unsupported);
            assertThrows(GltfLoadException.class, () -> load(json));
        }
    }

    private static NormalizedGltfModel load(JsonObject json) throws Exception {
        return new JgltfModelLoader().load(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] decodedView(NormalizedGltfModel model) {
        return bytes(model.model().getBufferViewModels().getFirst().getBufferViewData());
    }

    private static JsonObject extension(JsonObject json) {
        return json.getAsJsonArray("bufferViews").get(0).getAsJsonObject().getAsJsonObject("extensions")
                .getAsJsonObject(EXTENSION);
    }

    private static JsonObject fixture(byte[] compressed, int count, int stride, String mode, String filter) {
        byte[] source = new byte[compressed.length + 11];
        System.arraycopy(compressed, 0, source, 7, compressed.length);
        JsonObject json = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"extensionsUsed":["EXT_meshopt_compression"],
                 "extensionsRequired":["EXT_meshopt_compression"],"buffers":[{},
                  {"extensions":{"EXT_meshopt_compression":{"fallback":true}}}],
                 "bufferViews":[{"buffer":1,"byteOffset":8,"extensions":{"EXT_meshopt_compression":{}}}]}
                """).getAsJsonObject();
        JsonArray buffers = json.getAsJsonArray("buffers");
        buffers.get(0).getAsJsonObject().addProperty("byteLength", source.length);
        buffers.get(0).getAsJsonObject().addProperty("uri", dataUri(source));
        buffers.get(1).getAsJsonObject().addProperty("byteLength", count * stride + 8);
        json.getAsJsonArray("bufferViews").get(0).getAsJsonObject().addProperty("byteLength", count * stride);
        JsonObject extension = extension(json);
        extension.addProperty("buffer", 0);
        extension.addProperty("byteOffset", 7);
        extension.addProperty("byteLength", compressed.length);
        extension.addProperty("byteStride", stride);
        extension.addProperty("count", count);
        extension.addProperty("mode", mode);
        if (filter != null) extension.addProperty("filter", filter);
        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", 0);
        accessor.addProperty("count", count);
        if (!mode.equals("ATTRIBUTES")) {
            accessor.addProperty("componentType", stride == 2 ? 5123 : 5125);
            accessor.addProperty("type", "SCALAR");
        } else if ("OCTAHEDRAL".equals(filter) || "QUATERNION".equals(filter)) {
            accessor.addProperty("componentType", stride == 4 ? 5120 : 5122);
            accessor.addProperty("type", "VEC4");
            accessor.addProperty("normalized", true);
        } else {
            accessor.addProperty("componentType", 5126);
            accessor.addProperty("type", stride == 12 ? "VEC3" : stride == 8 ? "VEC2" : "SCALAR");
        }
        JsonArray accessors = new JsonArray();
        accessors.add(accessor);
        json.add("accessors", accessors);
        return json;
    }

    private static String dataUri(byte[] bytes) {
        return "data:application/octet-stream;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.duplicate().get(result);
        return result;
    }

    private static byte[] encodeAttributes(byte[] bytes, int stride) {
        ByteBuffer input = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        ByteBuffer output = MemoryUtil.memAlloc(Math.toIntExact(MeshOptimizer.meshopt_encodeVertexBufferBound(bytes.length / stride, stride)));
        try {
            long length = MeshOptimizer.meshopt_encodeVertexBufferLevel(output, input, bytes.length / stride, stride, 2, 0);
            assertTrue(length > 0);
            return bytes(output.limit(Math.toIntExact(length)));
        } finally {
            MemoryUtil.memFree(output);
            MemoryUtil.memFree(input);
        }
    }

    private static byte[] encodeIndices(int[] indices, String mode) {
        IntBuffer input = MemoryUtil.memAllocInt(indices.length).put(indices).flip();
        int vertexCount = java.util.Arrays.stream(indices).max().orElse(0) + 1;
        long bound = mode.equals("TRIANGLES") ? MeshOptimizer.meshopt_encodeIndexBufferBound(indices.length, vertexCount)
                : MeshOptimizer.meshopt_encodeIndexSequenceBound(indices.length, vertexCount);
        ByteBuffer output = MemoryUtil.memAlloc(Math.toIntExact(bound));
        try {
            long length = mode.equals("TRIANGLES") ? MeshOptimizer.meshopt_encodeIndexBuffer(output, input)
                    : MeshOptimizer.meshopt_encodeIndexSequence(output, input);
            assertTrue(length > 0);
            return bytes(output.limit(Math.toIntExact(length)));
        } finally {
            MemoryUtil.memFree(output);
            MemoryUtil.memFree(input);
        }
    }

    private static byte[] encodeFilter(String filter, int stride, float[] values) {
        FloatBuffer input = MemoryUtil.memAllocFloat(values.length).put(values).flip();
        ByteBuffer output = MemoryUtil.memAlloc(stride);
        try {
            switch (filter) {
                case "OCTAHEDRAL" -> MeshOptimizer.meshopt_encodeFilterOct(output, 1, stride, stride == 4 ? 8 : 16, input);
                case "QUATERNION" -> MeshOptimizer.meshopt_encodeFilterQuat(output, 1, stride, 16, input);
                case "EXPONENTIAL" -> MeshOptimizer.meshopt_encodeFilterExp(output, 1, stride, 23, input,
                        MeshOptimizer.meshopt_EncodeExpSeparate);
                default -> throw new AssertionError(filter);
            }
            return bytes(output);
        } finally {
            MemoryUtil.memFree(output);
            MemoryUtil.memFree(input);
        }
    }
}
