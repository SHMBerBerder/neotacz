package com.tacz.guns.client.model.gltf.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JgltfAssetAdapterTest {
    private static final String QUANTIZATION = "KHR_mesh_quantization";

    @Test
    void copyKeepsEveryOtherTopLevelPropertyAndResolvedPayloadWithoutMutatingSource() throws Exception {
        GlTF gltf = metadata();
        ByteBuffer binary = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        GltfAssetV2 original = new GltfAssetV2(gltf, binary);
        for (var reference : original.getReferences()) reference.getTarget().accept(ByteBuffer.wrap(new byte[]{5, 6, 7, 8}));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode before = mapper.valueToTree(gltf);
        GltfAsset adapted = JgltfAssetAdapter.forModelCreation(original);

        assertNotSame(original, adapted);
        assertNotSame(gltf, adapted.getGltf());
        assertEquals(before, mapper.valueToTree(gltf));
        ObjectNode expected = before.deepCopy();
        expected.putArray("extensionsUsed").add("MSFT_lod").add("VENDOR_metadata");
        expected.putArray("extensionsRequired").add("MSFT_lod");
        assertEquals(expected, mapper.valueToTree(adapted.getGltf()));
        assertEquals(binary, adapted.getBinaryData());
        assertEquals(original.getReferenceDatas(), adapted.getReferenceDatas());
        assertTrue(adapted.getBinaryData().isReadOnly());
        adapted.getReferenceDatas().values().forEach(bytes -> assertTrue(bytes.isReadOnly()));
        assertSame(gltf.getAccessors(), ((GlTF) adapted.getGltf()).getAccessors());
        assertSame(gltf.getMeshes(), ((GlTF) adapted.getGltf()).getMeshes());
    }

    @Test
    void loaderBuildsAdaptedModelButKeepsTrueNormalizedDeclarationsAndNumericValues() throws Exception {
        byte[] data = positions();
        for (String storage : List.of("external", "data-uri", "glb")) {
            String uri = storage.equals("data-uri") ? "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data)
                    : "positions.bin";
            ByteArrayInputStream input = storage.equals("glb") ? new ByteArrayInputStream(glb(data)) : input(json(uri));
            NormalizedGltfModel loaded = new JgltfModelLoader().load(input, name -> ByteBuffer.wrap(data));
            assertEquals(storage.equals("glb"), loaded.binary());
            assertSame(loaded.gltf(), loaded.asset().getGltf());
            assertEquals(List.of(QUANTIZATION, "MSFT_lod"), loaded.extensionsRequired());
            assertEquals(loaded.extensionsRequired(), loaded.gltf().getExtensionsRequired());
            assertEquals(loaded.extensionsUsed(), loaded.gltf().getExtensionsUsed());
            assertEquals(List.of("MSFT_lod"), loaded.model().getExtensionsModel().getExtensionsRequired());
            assertEquals(List.of("MSFT_lod"), loaded.model().getExtensionsModel().getExtensionsUsed());
            assertEquals(Map.of("origin", "quantized fixture"), loaded.gltf().getExtras());
            assertArrayEquals(new float[]{0, 0, 0, 100, 0, 0, 0, 100, 0},
                    new JgltfRuntimeConverter().convert(loaded).renderMeshes().getFirst().primitives().getFirst()
                            .runtimePrimitive().positions());
        }
    }

    @Test
    void unknownRequiredExtensionIsStillRejectedBeforeExternalResolution() throws Exception {
        ObjectNode declaration = (ObjectNode) new ObjectMapper().readTree(json("positions.bin"));
        declaration.putArray("extensionsUsed").add(QUANTIZATION).add("VENDOR_unknown_runtime");
        declaration.putArray("extensionsRequired").add(QUANTIZATION).add("VENDOR_unknown_runtime");
        AtomicInteger resolves = new AtomicInteger();
        GltfLoadException error = assertThrows(GltfLoadException.class, () -> new JgltfModelLoader().load(input(declaration.toString()), name -> {
            resolves.incrementAndGet();
            return ByteBuffer.wrap(positions());
        }));
        assertTrue(error.getMessage().contains("VENDOR_unknown_runtime"));
        assertEquals(0, resolves.get());
    }

    @Test
    void unrelatedOrIncompleteDeclarationsAreNotRewritten() throws Exception {
        GlTF gltf = new GlTF();
        Asset metadata = new Asset(); metadata.setVersion("2.0"); gltf.setAsset(metadata);
        GltfAssetV2 asset = new GltfAssetV2(gltf, null);
        assertSame(asset, JgltfAssetAdapter.forModelCreation(asset));
        gltf.setExtensionsUsed(List.of(QUANTIZATION));
        assertSame(asset, JgltfAssetAdapter.forModelCreation(asset));
        gltf.setExtensionsUsed(List.of("MSFT_lod")); gltf.setExtensionsRequired(List.of("MSFT_lod"));
        assertSame(asset, JgltfAssetAdapter.forModelCreation(asset));
    }

    @Test
    void missingAdaptedReferenceFailsClosed() {
        assertThrows(GltfLoadException.class, () -> JgltfAssetAdapter.forModelCreation(new GltfAssetV2(metadata(), null)));
    }

    private static GlTF metadata() {
        // This copy fixture exercises top-level metadata, not mesh semantic validity.
        GlTF gltf = new GlTF();
        Asset asset = new Asset(); asset.setVersion("2.0"); gltf.setAsset(asset);
        gltf.setExtensionsUsed(List.of(QUANTIZATION, "MSFT_lod", "VENDOR_metadata"));
        gltf.setExtensionsRequired(List.of(QUANTIZATION, "MSFT_lod"));
        gltf.setExtensions(Map.of("VENDOR_metadata", Map.of("value", 7)));
        gltf.setExtras(Map.of("source", "unchanged"));
        Buffer buffer = new Buffer(); buffer.setByteLength(4); buffer.setUri("mesh.bin");
        Image image = new Image(); image.setUri("image.png");
        gltf.setAccessors(List.of(new Accessor())); gltf.setAnimations(List.of(new Animation()));
        gltf.setBuffers(List.of(buffer)); gltf.setBufferViews(List.of(new BufferView()));
        gltf.setCameras(List.of(new Camera())); gltf.setImages(List.of(image));
        gltf.setMaterials(List.of(new Material())); gltf.setMeshes(List.of(new Mesh()));
        gltf.setNodes(List.of(new Node())); gltf.setSamplers(List.of(new Sampler()));
        gltf.setScene(0); gltf.setScenes(List.of(new Scene())); gltf.setSkins(List.of(new Skin()));
        gltf.setTextures(List.of(new Texture()));
        return gltf;
    }

    private static String json(String uri) {
        return """
                {"asset":{"version":"2.0"},"extras":{"origin":"quantized fixture"},
                 "extensionsUsed":["KHR_mesh_quantization","MSFT_lod"],
                 "extensionsRequired":["KHR_mesh_quantization","MSFT_lod"],
                 "buffers":[{"uri":"%s","byteLength":24}],
                 "bufferViews":[{"buffer":0,"byteLength":24,"byteStride":8,"target":34962}],
                 "accessors":[{"bufferView":0,"componentType":5122,"count":3,"type":"VEC3"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
                 "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """.formatted(uri);
    }

    private static byte[] positions() {
        ByteBuffer bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort(8, (short) 100); bytes.putShort(18, (short) 100);
        return bytes.array();
    }

    private static ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] glb(byte[] data) throws Exception {
        ObjectNode declaration = (ObjectNode) new ObjectMapper().readTree(json("positions.bin"));
        ((ObjectNode) declaration.get("buffers").get(0)).remove("uri");
        byte[] json = declaration.toString().getBytes(StandardCharsets.UTF_8);
        int alignedJsonLength = (json.length + 3) & ~3;
        ByteBuffer result = ByteBuffer.allocate(12 + 8 + alignedJsonLength + 8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546c67).putInt(2).putInt(result.capacity());
        result.putInt(alignedJsonLength).putInt(0x4e4f534a).put(json);
        while ((result.position() & 3) != 0) result.put((byte) 0x20);
        result.putInt(data.length).putInt(0x004e4942).put(data);
        return result.array();
    }
}
