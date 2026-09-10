package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationPose;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMorphTarget;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import de.javagl.jgltf.model.PbrMaterialModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JgltfRuntimeConverterTest {
    private static final float EPSILON = 1.0E-5f;

    private final JgltfModelLoader loader = new JgltfModelLoader();
    private final JgltfRuntimeConverter converter = new JgltfRuntimeConverter();

    @Test
    void convertsInterleavedIndexedPbrAsset() throws Exception {
        ConvertedGltfAsset asset = convert(GltfTestAssets.staticPbr().json());
        GltfRenderPrimitive render = asset.renderMeshes().getFirst().primitives().getFirst();
        GltfMeshPrimitive runtime = render.runtimePrimitive();

        assertArrayEquals(new int[]{2, 0, 1}, render.indices());
        assertArrayEquals(new float[]{
                1, 2, 3,
                -1, 0, 2,
                0, 4, -2
        }, runtime.positions(), EPSILON);
        assertArrayEquals(new float[]{
                0, 1, 0,
                0, 0, 1,
                1, 0, 0
        }, runtime.normals(), EPSILON);
        assertArrayEquals(new float[0], runtime.tangents(), EPSILON);
        assertArrayEquals(new float[]{0.25f, 0.75f, 1, 0, 0.5f, 0.25f}, render.texCoords0(), EPSILON);
        assertArrayEquals(new float[]{
                1, 128 / 255.0f, 0, 64 / 255.0f,
                0, 1, 64 / 255.0f, 1,
                32 / 255.0f, 64 / 255.0f, 128 / 255.0f, 1
        }, render.colors0(), EPSILON);
        assertEquals(0, render.materialIndex());

        GltfPbrMaterialData material = asset.materials().getFirst();
        assertArrayEquals(new float[]{0.8f, 0.6f, 0.4f, 0.5f}, material.baseColorFactor(), EPSILON);
        assertEquals(0.25f, material.metallicFactor(), EPSILON);
        assertEquals(0.75f, material.roughnessFactor(), EPSILON);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, material.emissiveFactor(), EPSILON);
        assertEquals(0.8f, material.normalScale(), EPSILON);
        assertEquals(0.6f, material.occlusionStrength(), EPSILON);
        assertEquals(GltfAlphaMode.MASK, material.alphaMode());
        assertEquals(0.4f, material.alphaCutoff(), EPSILON);
        assertTrue(material.doubleSided());
        assertDefaultTexture(material.baseColorTexture());
        assertDefaultTexture(material.metallicRoughnessTexture());
        assertDefaultTexture(material.normalTexture());
        assertDefaultTexture(material.occlusionTexture());
        assertDefaultTexture(material.emissiveTexture());
        assertEquals(1, asset.images().size());
        assertTrue(asset.images().getFirst().encodedBytes().length > 0);
        assertEquals(0, asset.defaultSceneIndex());
        assertEquals(0, asset.activeSceneIndex());
    }

    @Test
    void forcesExternalBuffersToLittleEndian() throws Exception {
        GltfTestAssets.Asset source = GltfTestAssets.staticPbr();
        String externalJson = source.json().replace(GltfTestAssets.dataUri(source.bytes()), "mesh.bin");
        NormalizedGltfModel loaded = loader.load(
                input(externalJson),
                uri -> ByteBuffer.wrap(source.bytes())
        );

        ConvertedGltfAsset asset = converter.convert(loaded);

        assertArrayEquals(
                new float[]{1, 2, 3, -1, 0, 2, 0, 4, -2},
                asset.renderMeshes().getFirst().primitives().getFirst().runtimePrimitive().positions(),
                EPSILON
        );
        assertArrayEquals(new int[]{2, 0, 1}, asset.renderMeshes().getFirst().primitives().getFirst().indices());
    }

    @Test
    void convertsSkinMorphAndNamedAnimationClipWithExtraInverseBindMatrix() throws Exception {
        ConvertedGltfAsset asset = convert(GltfTestAssets.skinMorphAnimation().json());
        GltfRenderPrimitive render = asset.renderMeshes().getFirst().primitives().getFirst();
        GltfMeshPrimitive primitive = render.runtimePrimitive();

        assertArrayEquals(new int[]{0, 1, 1}, render.indices());
        assertArrayEquals(new int[]{0, 1, 0, 0, 0, 1, 0, 0}, primitive.joints0());
        assertArrayEquals(new float[]{1, 0, 0, 0, 128 / 255.0f, 127 / 255.0f, 0, 0},
                primitive.weights0(), EPSILON);
        GltfMorphTarget target = primitive.morphTargets().getFirst();
        assertArrayEquals(new float[]{1, 0, 0, 1, 0, 0}, target.positionDeltas(), EPSILON);
        assertArrayEquals(new float[]{0, 0, 1, 0, 1, 0}, target.normalDeltas(), EPSILON);
        assertArrayEquals(new float[]{0.2f}, asset.meshDefaultMorphWeights(0), EPSILON);
        assertArrayEquals(new float[]{0.2f}, asset.renderMeshes().getFirst().defaultMorphWeights(), EPSILON);
        assertArrayEquals(new float[]{0.25f}, asset.nodeMorphWeights(0), EPSILON);

        GltfSkin skin = asset.runtimeScene().skins().getFirst();
        assertArrayEquals(new int[]{1, 2}, skin.joints());
        assertEquals(-1.0f, skin.inverseBindMatrix(1).m31(), EPSILON);
        assertArrayEquals(new int[]{0, 1}, asset.runtimeScene().rootNodes());
        assertArrayEquals(new int[]{2}, asset.runtimeScene().nodes().get(1).children());
        assertEquals(0, asset.runtimeScene().nodes().getFirst().meshIndex());
        assertEquals(0, asset.runtimeScene().nodes().getFirst().skinIndex());

        GltfAnimationClip clip = asset.animations().getFirst();
        assertEquals("cycle", clip.name());
        assertEquals(2.0f, clip.durationSeconds(), EPSILON);
        assertEquals(3, clip.animation().channels().size());
        GltfAnimationPose pose = clip.animation().sample(1.0f);
        assertVectorEquals(new Vector3f(0, 2, 0), pose.translations().get(2));
        assertVectorEquals(new Vector3f(2, 1, 1), pose.scales().get(2));
        assertArrayEquals(new float[]{0.5f}, pose.weights().get(0), EPSILON);
    }

    @Test
    void convertsFactorOnlyMaterialWithoutTexCoord0() throws Exception {
        String json = withoutMaterialTextures(GltfTestAssets.staticPbr().json())
                .replace("\"TEXCOORD_0\":2,", "");

        ConvertedGltfAsset asset = convert(json);

        assertArrayEquals(new float[0], asset.renderMeshes().getFirst().primitives().getFirst().texCoords0());
    }

    @Test
    void decomposesStaticNodeMatrixIncludingNegativeScale() throws Exception {
        String json = GltfTestAssets.staticPbr().json().replace(
                "\"nodes\":[{\"mesh\":0}]",
                "\"nodes\":[{\"mesh\":0,\"matrix\":[-2,0,0,0,0,3,0,0,0,0,4,0,5,6,7,1]}]"
        );

        ConvertedGltfAsset asset = convert(json);
        float[] matrix = asset.runtimeScene().nodes().getFirst().localTransform().toMatrix().get(new float[16]);

        assertArrayEquals(new float[]{
                -2, 0, 0, 0,
                0, 3, 0, 0,
                0, 0, 4, 0,
                5, 6, 7, 1
        }, matrix, EPSILON);
    }

    @Test
    void rejectsUnsupportedOrInconsistentSemantics() {
        String staticJson = GltfTestAssets.staticPbr().json();
        String animatedJson = GltfTestAssets.skinMorphAnimation().json();
        assertRejectsProgrammaticInvalidEmissive(staticJson);
        List<RejectionCase> cases = List.of(
                new RejectionCase(staticJson.replace("\"mode\":4", "\"mode\":1"), "TRIANGLES"),
                new RejectionCase(staticJson.replaceFirst("\"type\":\"VEC3\"", "\"type\":\"VEC2\""), "POSITION"),
                new RejectionCase(animatedJson.replace(",\"WEIGHTS_0\":3", ""), "WEIGHTS_0"),
                new RejectionCase(staticJson.replace(
                        "\"baseColorTexture\":{\"index\":0}",
                        "\"baseColorTexture\":{\"index\":0,\"texCoord\":1}"
                ), "TEXCOORD_1"),
                new RejectionCase(staticJson.replace("\"TEXCOORD_0\":2,", ""), "requires TEXCOORD_0"),
                new RejectionCase(staticJson.replace(
                        "\"COLOR_0\":3",
                        "\"COLOR_0\":3,\"TANGENT\":4"
                ), "TANGENT"),
                new RejectionCase(animatedJson.replace(
                        "\"JOINTS_0\":2,\"WEIGHTS_0\":3",
                        "\"JOINTS_0\":2,\"WEIGHTS_0\":3,\"JOINTS_1\":2"
                ), "JOINTS_1"),
                new RejectionCase(animatedJson.replace(
                        "\"POSITION\":0,\"NORMAL\":1,\"JOINTS_0\":2",
                        "\"POSITION\":0,\"JOINTS_0\":2"
                ), "base NORMAL"),
                new RejectionCase(animatedJson.replace(
                        "{\"POSITION\":4,\"NORMAL\":5}",
                        "{\"POSITION\":4,\"NORMAL\":5,\"TANGENT\":4}"
                ), "TANGENT"),
                new RejectionCase(staticJson.replace(
                        "\"asset\":{\"version\":\"2.0\"}",
                        "\"asset\":{\"version\":\"2.0\"},\"extensionsUsed\":[\"VENDOR_optional\"]"
                ), "extensions"),
                new RejectionCase(staticJson.replace(
                        "\"nodes\":[{\"mesh\":0}]",
                        "\"nodes\":[{\"mesh\":0,\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],\"translation\":[0,0,0]}]"
                ), "matrix together"),
                new RejectionCase(animatedJson.replace(
                        "{\"name\":\"jointTip\",\"translation\":[0,1,0]}",
                        "{\"name\":\"jointTip\",\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,0,1,0,1]}"
                ), "targets node"),
                new RejectionCase(animatedJson.replace(
                        "\"bufferView\":5,\"byteOffset\":4,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"",
                        "\"bufferView\":5,\"byteOffset\":4,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\""
                ), "arity/count"),
                new RejectionCase(withSparsePosition(staticJson), "sparse accessor")
        );

        for (RejectionCase rejection : cases) {
            GltfConversionException exception = assertThrows(
                    GltfConversionException.class,
                    () -> convert(rejection.json()),
                    rejection.keyword()
            );
            assertTrue(exception.getMessage().contains(rejection.keyword()), exception.getMessage());
        }
    }

    private void assertRejectsProgrammaticInvalidEmissive(String json) {
        try {
            NormalizedGltfModel loaded = loader.load(input(json));
            PbrMaterialModel material = (PbrMaterialModel) loaded.model().getMaterialModels().getFirst();
            material.setEmissiveFactor(new double[]{0.1, 0.2, 1.3});
            GltfConversionException exception = assertThrows(
                    GltfConversionException.class,
                    () -> converter.convert(loaded)
            );
            assertTrue(exception.getMessage().contains("emissiveFactor"), exception.getMessage());
        } catch (Exception exception) {
            throw new AssertionError("valid fixture load setup failed", exception);
        }
    }

    private ConvertedGltfAsset convert(String json) throws Exception {
        return converter.convert(loader.load(input(json)));
    }

    private static ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String withSparsePosition(String json) {
        return json.replace(
                "\"max\":[1,4,3]}",
                "\"max\":[1,4,3],\"sparse\":{\"count\":1,"
                        + "\"indices\":{\"bufferView\":1,\"byteOffset\":2,\"componentType\":5123},"
                        + "\"values\":{\"bufferView\":0,\"byteOffset\":0}}}"
        );
    }

    private static String withoutMaterialTextures(String json) {
        return json
                .replace("\"baseColorTexture\":{\"index\":0},", "")
                .replaceAll(",\\s*\"metallicRoughnessTexture\":\\{\"index\":0}", "")
                .replace("\"normalTexture\":{\"index\":0,\"scale\":0.8},", "")
                .replace("\"occlusionTexture\":{\"index\":0,\"strength\":0.6},", "")
                .replace("\"emissiveTexture\":{\"index\":0},", "");
    }

    private static void assertDefaultTexture(GltfTextureBinding texture) {
        assertNotNull(texture);
        assertEquals(0, texture.imageIndex());
        assertEquals(0, texture.texCoord());
        assertEquals(GltfSamplerData.DEFAULT, texture.sampler());
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    private record RejectionCase(String json, String keyword) {
    }
}
