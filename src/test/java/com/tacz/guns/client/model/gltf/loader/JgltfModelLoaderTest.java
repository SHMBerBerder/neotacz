package com.tacz.guns.client.model.gltf.loader;

import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.BufferModel;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JgltfModelLoaderTest {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;
    private static final byte[] TRIANGLE_BUFFER = createTriangleBuffer();

    private final JgltfModelLoader loader = new JgltfModelLoader();

    @Test
    void loadsEmbeddedJsonBuffer() throws Exception {
        NormalizedGltfModel loaded = load(minimalGltfJson(dataUri(TRIANGLE_BUFFER)));

        assertEquals(1, loaded.counts().buffers());
        assertEquals(2, loaded.counts().accessors());
        assertEquals(1, loaded.counts().meshes());
        assertEquals(1, loaded.counts().nodes());
        assertEquals(1, loaded.counts().scenes());
        assertEquals(44, firstBuffer(loaded).getBufferData().capacity());
    }

    @Test
    void loadsEmbeddedGlbBuffer() throws Exception {
        NormalizedGltfModel loaded = loader.load(new ByteArrayInputStream(minimalGlb(TRIANGLE_BUFFER)));

        assertTrue(loaded.binary());
        assertEquals(1, loaded.counts().buffers());
        assertEquals(44, firstBuffer(loaded).getBufferData().capacity());
    }

    @Test
    void resolvesExternalBufferThroughInjectedResolver() throws Exception {
        AtomicReference<String> requestedUri = new AtomicReference<>();
        NormalizedGltfModel loaded = loader.load(
                new ByteArrayInputStream(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8)),
                uri -> {
                    requestedUri.set(uri);
                    return ByteBuffer.wrap(TRIANGLE_BUFFER);
                }
        );

        assertEquals("triangle.bin", requestedUri.get());
        assertEquals(44, firstBuffer(loaded).getBufferData().capacity());
    }

    @Test
    void resolvesExternalBufferWithLittleEndianAccessors() throws Exception {
        byte[] buffer = createEndianRegressionBuffer();
        NormalizedGltfModel loaded = loader.load(
                new ByteArrayInputStream(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8)),
                uri -> ByteBuffer.wrap(buffer)
        );

        AccessorFloatData positions = (AccessorFloatData) loaded.model()
                .getAccessorModels()
                .get(0)
                .getAccessorData();
        assertEquals(1.25f, positions.get(0, 0), 0.0f);
        assertEquals(-2.5f, positions.get(0, 1), 0.0f);
        assertEquals(123.75f, positions.get(0, 2), 0.0f);

        AccessorShortData indices = (AccessorShortData) loaded.model()
                .getAccessorModels()
                .get(1)
                .getAccessorData();
        assertEquals(0x0102, indices.getInt(0));
        assertEquals(0x0203, indices.getInt(1));
        assertEquals(0x03F4, indices.getInt(2));
    }

    @Test
    void rejectsUnknownRequiredExtension() {
        GltfLoadException exception = assertThrows(
                GltfLoadException.class,
                () -> load("""
                        {
                          "asset": {"version": "2.0"},
                          "extensionsRequired": ["VENDOR_unknown_runtime"]
                        }
                        """)
        );

        assertTrue(exception.getMessage().contains("VENDOR_unknown_runtime"));
    }

    @Test
    void rejectsJsonValuesDiscardedByJgltfSchemaSetters() {
        GltfLoadException exception = assertThrows(
                GltfLoadException.class,
                () -> load("""
                        {
                          "asset": {"version": "2.0"},
                          "materials": [{"emissiveFactor": [0.0, 0.0, 1.3]}]
                        }
                        """)
        );

        assertTrue(exception.getMessage().contains("emissiveFactor"), exception.getMessage());
        assertTrue(exception.getMessage().contains("schema validation"), exception.getMessage());
    }

    @Test
    void minecraftResolverMatchesStrictModelDirectoryPolicy() {
        Identifier model = Identifier.fromNamespaceAndPath("tacz", "models/gltf/weapons/rifle.gltf");
        Identifier nonCanonicalModel = Identifier.fromNamespaceAndPath(
                "tacz",
                "models/gltf/weapons/../rifle.gltf"
        );

        assertEquals(
                Identifier.fromNamespaceAndPath("tacz", "models/gltf/weapons/buffers/rifle.bin"),
                ResourceManagerGltfResourceResolver.resolveResourceId(model, "buffers/rifle.bin")
        );
        for (String rejected : List.of(
                "../buffers/rifle.bin",
                "../../../../pack.mcmeta",
                "https://example.invalid/rifle.bin",
                "rifle.bin?version=1",
                "rifle.bin#buffer",
                "buffers\\rifle.bin",
                "%2e%2e/rifle.bin",
                "texture.webp"
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourceManagerGltfResourceResolver.resolveResourceId(model, rejected),
                    rejected
            );
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourceManagerGltfResourceResolver.resolveResourceId(nonCanonicalModel, "rifle.bin")
        );
    }

    @Test
    void sharedResourcePolicyEnforcesPerFileLimit() throws Exception {
        assertArrayEquals(
                new byte[]{1, 2, 3, 4},
                GltfResourcePolicy.readAllBytesLimited(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4)
        );
        assertThrows(
                GltfLoadException.class,
                () -> GltfResourcePolicy.readAllBytesLimited(
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}),
                        4
                )
        );
    }

    private NormalizedGltfModel load(String json) throws Exception {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static BufferModel firstBuffer(NormalizedGltfModel model) {
        return model.model().getBufferModels().getFirst();
    }

    private static String minimalGltfJson(String bufferUri) {
        String bufferEntry = bufferUri == null
                ? "{\"byteLength\":44}"
                : "{\"uri\":\"" + bufferUri + "\",\"byteLength\":44}";
        return """
                {
                  "asset": {"version": "2.0"},
                  "buffers": [%s],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 36, "target": 34962},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 6, "target": 34963}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                     "min": [0.0, 0.0, 0.0], "max": [1.0, 1.0, 0.0]},
                    {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
                  ],
                  "meshes": [
                    {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}
                  ],
                  "nodes": [{"mesh": 0}],
                  "scenes": [{"nodes": [0]}],
                  "scene": 0
                }
                """.formatted(bufferEntry);
    }

    private static String dataUri(byte[] data) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data);
    }

    private static byte[] createTriangleBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : List.of(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        )) {
            buffer.putFloat(value);
        }
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 2);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    private static byte[] createEndianRegressionBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : List.of(
                1.25f, -2.5f, 123.75f,
                0.03125f, -4096.5f, 7.875f,
                -0.5f, 9.5f, 64.125f
        )) {
            buffer.putFloat(value);
        }
        buffer.putShort((short) 0x0102);
        buffer.putShort((short) 0x0203);
        buffer.putShort((short) 0x03F4);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    private static byte[] minimalGlb(byte[] binary) {
        byte[] json = minimalGltfJson(null).getBytes(StandardCharsets.UTF_8);
        int jsonLength = align4(json.length);
        int binaryLength = align4(binary.length);
        int totalLength = 12 + 8 + jsonLength + 8 + binaryLength;

        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(GLB_MAGIC);
        glb.putInt(2);
        glb.putInt(totalLength);
        glb.putInt(jsonLength);
        glb.putInt(JSON_CHUNK_TYPE);
        glb.put(json);
        pad(glb, jsonLength - json.length, (byte) ' ');
        glb.putInt(binaryLength);
        glb.putInt(BIN_CHUNK_TYPE);
        glb.put(binary);
        pad(glb, binaryLength - binary.length, (byte) 0);
        return glb.array();
    }

    private static int align4(int length) {
        return (length + 3) & ~3;
    }

    private static void pad(ByteBuffer buffer, int count, byte value) {
        for (int i = 0; i < count; i++) {
            buffer.put(value);
        }
    }
}
