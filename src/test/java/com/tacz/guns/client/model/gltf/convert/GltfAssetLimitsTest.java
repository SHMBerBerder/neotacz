package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.GltfRuntimePolicy;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.impl.DefaultImageModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfAssetLimitsTest {
    private final JgltfModelLoader loader = new JgltfModelLoader();
    private final JgltfRuntimeConverter converter = new JgltfRuntimeConverter();

    @Test
    void rejectsSourceVertexIndexAndAccessorAllocationBudgetsBeforeArrayReads() throws Exception {
        String base = GltfTestAssets.staticPbr().json();
        NormalizedGltfModel vertexAsset = load(base);
        setAccessorCount(vertexAsset.model().getAccessorModels().get(0), (int) GltfAssetLimits.MAX_SOURCE_VERTICES + 1);
        assertBudget(
                vertexAsset,
                "source vertices",
                GltfAssetLimits.MAX_SOURCE_VERTICES
        );
        NormalizedGltfModel indexAsset = load(base);
        setAccessorCount(indexAsset.model().getAccessorModels().get(5), (int) GltfAssetLimits.MAX_SOURCE_INDICES + 1);
        assertBudget(
                indexAsset,
                "source indices",
                GltfAssetLimits.MAX_SOURCE_INDICES
        );
        NormalizedGltfModel accessorAsset = load(base);
        setAccessorCount(
                accessorAsset.model().getAccessorModels().get(0),
                (int) (GltfAssetLimits.MAX_ACCESSOR_COMPONENTS / 3L + 1L)
        );
        assertBudget(
                accessorAsset,
                "accessor components",
                GltfAssetLimits.MAX_ACCESSOR_COMPONENTS
        );
    }

    @Test
    void rejectsNodeSkinAndJointBudgets() throws Exception {
        assertBudget(
                rootWith("\"nodes\":[" + repeated("{}", GltfAssetLimits.MAX_NODES + 1) + "]"),
                "nodes",
                GltfAssetLimits.MAX_NODES
        );
        assertBudget(
                rootWith("\"nodes\":[{}],\"skins\":["
                        + repeated("{\"joints\":[0]}", GltfAssetLimits.MAX_SKINS + 1) + "]"),
                "skins",
                GltfAssetLimits.MAX_SKINS
        );
        int jointCount = GltfAssetLimits.MAX_TOTAL_JOINTS + 1;
        assertBudget(
                rootWith("\"nodes\":[" + repeated("{}", jointCount) + "],\"skins\":[{\"joints\":["
                        + integerSequence(jointCount) + "]}]"),
                "skin joints",
                GltfAssetLimits.MAX_TOTAL_JOINTS
        );
    }

    @Test
    void rejectsMorphMaterialAndImageBudgets() throws Exception {
        String targets = repeated("{\"POSITION\":0}", GltfAssetLimits.MAX_MORPH_TARGETS_PER_PRIMITIVE + 1);
        assertBudget(
                GltfTestAssets.staticPbr().json().replace(
                        "\"material\":0,\"mode\":4}",
                        "\"material\":0,\"mode\":4,\"targets\":[" + targets + "]}"
                ),
                "morph targets per primitive",
                GltfAssetLimits.MAX_MORPH_TARGETS_PER_PRIMITIVE
        );
        String maxTargets = repeated("{\"POSITION\":0}", GltfAssetLimits.MAX_MORPH_TARGETS_PER_PRIMITIVE);
        String primitive = "{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"COLOR_0\":3},"
                + "\"indices\":5,\"material\":0,\"mode\":4,\"targets\":[" + maxTargets + "]}";
        assertBudget(
                GltfTestAssets.staticPbr().json().replace(
                        "{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"COLOR_0\":3},"
                                + "\"indices\":5,\"material\":0,\"mode\":4}",
                        repeated(primitive, GltfAssetLimits.MAX_TOTAL_MORPH_TARGETS
                                / GltfAssetLimits.MAX_MORPH_TARGETS_PER_PRIMITIVE + 1)
                ),
                "morph targets",
                GltfAssetLimits.MAX_TOTAL_MORPH_TARGETS
        );
        NormalizedGltfModel morphVertexAsset = load(
                GltfTestAssets.staticPbr().json().replace(
                        "\"material\":0,\"mode\":4}",
                        "\"material\":0,\"mode\":4,\"targets\":[" + maxTargets + "]}"
                )
        );
        setAccessorCount(morphVertexAsset.model().getAccessorModels().getFirst(), 20_000);
        assertBudget(
                morphVertexAsset,
                "morph vertex instances",
                GltfAssetLimits.MAX_MORPH_VERTEX_INSTANCES
        );
        assertBudget(
                rootWith("\"materials\":[" + repeated("{}", GltfAssetLimits.MAX_MATERIALS + 1) + "]"),
                "materials",
                GltfAssetLimits.MAX_MATERIALS
        );
        assertBudget(
                rootWith("\"images\":[" + repeated(
                        "{\"uri\":\"data:application/octet-stream;base64,AA==\"}",
                        GltfAssetLimits.MAX_IMAGES + 1
                ) + "]"),
                "images",
                GltfAssetLimits.MAX_IMAGES
        );
        NormalizedGltfModel imageBytesAsset = load(GltfTestAssets.staticPbr().json());
        ((DefaultImageModel) imageBytesAsset.model().getImageModels().getFirst()).setImageData(
                ByteBuffer.allocate((int) GltfAssetLimits.MAX_SINGLE_IMAGE_BYTES + 1)
        );
        assertBudget(imageBytesAsset, "single image bytes", GltfAssetLimits.MAX_SINGLE_IMAGE_BYTES);
    }

    @Test
    void explicitImageBudgetsApplyWithoutAnyModelIdAndDoNotChangeDefaults() throws Exception {
        assertEquals(16L * 1024L * 1024L, GltfAssetLimits.MAX_SINGLE_IMAGE_BYTES);
        assertEquals(64L * 1024L * 1024L, GltfAssetLimits.MAX_TOTAL_IMAGE_BYTES);
        var budgets = GltfRuntimePolicy.fromMiB(64, 384, 2048);
        int bytes = (int) GltfAssetLimits.MAX_SINGLE_IMAGE_BYTES + 1;
        for (int index = 0; index < 2; index++) {
            NormalizedGltfModel asset = assetWithImageBytes(bytes);
            ((DefaultImageModel) asset.model().getImageModels().getFirst()).setName("unrelated_asset_" + index);
            assertEquals(bytes, converter.convert(asset, budgets).images().getFirst().encodedBytes().length);
            assertBudget(asset, "single image bytes", GltfAssetLimits.MAX_SINGLE_IMAGE_BYTES);
        }
    }

    @Test
    void explicitImageBudgetsStillRejectSingleAndAggregateExcess() throws Exception {
        var budgets = GltfRuntimePolicy.fromMiB(64, 384, 2048);
        NormalizedGltfModel oversized = assetWithImageBytes((int) budgets.maxSingleImageBytes() + 1);
        assertTrue(assertThrows(GltfConversionException.class, () -> converter.convert(oversized, budgets))
                .getMessage().contains("single image bytes budget"));

        NormalizedGltfModel aggregate = load(rootWith("\"images\":[" + repeated(
                "{\"uri\":\"data:application/octet-stream;base64,AA==\"}", 7) + "]"));
        ByteBuffer shared = ByteBuffer.allocate((int) budgets.maxSingleImageBytes());
        for (int index = 0; index < 6; index++) {
            ((DefaultImageModel) aggregate.model().getImageModels().get(index)).setImageData(shared.duplicate());
        }
        assertTrue(assertThrows(GltfConversionException.class, () -> converter.convert(aggregate, budgets))
                .getMessage().contains("image bytes budget"));
    }

    @Test
    void rejectsAnimationBudgetsBeforeSamplerReads() throws Exception {
        String base = GltfTestAssets.skinMorphAnimation().json();
        String tooManyChannels = replaceAnimationChannels(base, repeated(
                "{\"sampler\":0,\"target\":{\"node\":2,\"path\":\"translation\"}}",
                GltfAssetLimits.MAX_ANIMATION_CHANNELS + 1
        ));
        assertBudget(tooManyChannels, "animation channels", GltfAssetLimits.MAX_ANIMATION_CHANNELS);

        NormalizedGltfModel inputAsset = load(base);
        setAccessorCount(inputAsset.model().getAccessorModels().get(7),
                (int) GltfAssetLimits.MAX_ANIMATION_INPUT_SAMPLES + 1);
        assertBudget(inputAsset, "animation input samples", GltfAssetLimits.MAX_ANIMATION_INPUT_SAMPLES);

        NormalizedGltfModel outputAsset = load(base);
        setAccessorCount(outputAsset.model().getAccessorModels().get(8),
                (int) (GltfAssetLimits.MAX_ANIMATION_OUTPUT_COMPONENTS / 3L + 1L));
        assertBudget(outputAsset, "animation output components", GltfAssetLimits.MAX_ANIMATION_OUTPUT_COMPONENTS);
    }

    private void assertBudget(String json, String budgetName, long limit) throws Exception {
        assertBudget(load(json), budgetName, limit);
    }

    private void assertBudget(NormalizedGltfModel loaded, String budgetName, long limit) {
        GltfConversionException exception = assertThrows(
                GltfConversionException.class,
                () -> converter.convert(loaded)
        );
        assertTrue(exception.getMessage().contains(budgetName + " budget"), exception.getMessage());
        assertTrue(exception.getMessage().contains(Long.toString(limit)), exception.getMessage());
    }

    private NormalizedGltfModel load(String json) throws Exception {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private NormalizedGltfModel assetWithImageBytes(int byteCount) throws Exception {
        NormalizedGltfModel asset = load(GltfTestAssets.staticPbr().json());
        ((DefaultImageModel) asset.model().getImageModels().getFirst()).setImageData(
                ByteBuffer.allocate(byteCount)
        );
        return asset;
    }

    private static void setAccessorCount(AccessorModel accessor, int count) throws Exception {
        Field field = accessor.getClass().getDeclaredField("count");
        field.setAccessible(true);
        field.setInt(accessor, count);
    }

    private static String rootWith(String body) {
        return "{\"asset\":{\"version\":\"2.0\"}," + body + "}";
    }

    private static String repeated(String value, int count) {
        return String.join(",", Collections.nCopies(count, value));
    }

    private static String integerSequence(int count) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(Integer.toString(i));
        }
        return String.join(",", values);
    }

    private static String replaceAnimationChannels(String json, String channels) {
        int start = json.indexOf("\"channels\":[");
        int end = json.indexOf("]}],", start);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("animation channels fixture not found");
        }
        return json.substring(0, start) + "\"channels\":[" + channels + "]" + json.substring(end + 1);
    }
}
