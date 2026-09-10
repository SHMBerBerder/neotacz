package com.tacz.guns.client.model.gltf.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfLodMetadata;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimation;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationSampler;
import com.tacz.guns.client.model.gltf.runtime.GltfInterpolation;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfLodSelectionTest {
    @Test
    void optionalAndRequiredLodChooseExactlyOneAuthoredBranchAndClampAtLastLevel() throws Exception {
        for (boolean required : List.of(false, true)) {
            JsonObject json = fixture();
            if (required) json.add("extensionsRequired", array("[\"MSFT_lod\"]"));
            ConvertedGltfAsset asset = convert(json);
            assertEquals(Set.of(0), asset.selectedNodeIndices());
            assertEquals(Set.of(1), asset.withLodLevel(1).selectedNodeIndices());
            ConvertedGltfAsset lowest = asset.withLodLevel(4);
            assertEquals(Set.of(2), lowest.selectedNodeIndices());
            assertEquals(Set.of(2), lowest.selectedMeshIndices());
            assertEquals(Set.of(0, 1, 2), lowest.authoredLodMeshIndices());
            GltfGunBodyPose.Frame pose = GltfGunBodyPose.sample(lowest, null, 0);
            assertFalse(pose.active(0));
            assertFalse(pose.active(1));
            assertTrue(pose.active(2));
        }
    }

    @Test
    void lowerNodeUsesHighestBindDeltaAndInheritsTemporaryHidingWithoutMutatingInput() throws Exception {
        ConvertedGltfAsset asset = convert(fixture()).withLodLevel(1);
        Matrix4f high = new Matrix4f().translation(13, 0, 0);
        GltfGunBodyPose.Frame moved = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{high, null, null});
        assertEquals(23, moved.worldTransform(1).m30(), 1.0E-5);
        assertEquals(13, high.m30(), 1.0E-5);
        GltfGunBodyPose.Frame hidden = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{new Matrix4f().translation(13, 0, 0).scale(0), null, null});
        assertTrue(hidden.hidden(1));
        assertTrue(hidden.worldTransform(1).isFinite());
        assertFalse(GltfGunBodyPose.sample(asset, null, 0).hidden(1));
        assertEquals(20, GltfGunBodyPose.sample(asset, null, 0).worldTransform(1).m30(), 1.0E-5);
    }

    @Test
    void singularHighBindStaysOnHighBranchAndCanRecoverFromZeroAtRuntime() throws Exception {
        JsonObject json = fixture();
        json.getAsJsonArray("nodes").get(0).getAsJsonObject().add("scale", array("[0,0,0]"));
        ConvertedGltfAsset low = convert(json).withLodLevel(2);
        assertEquals(Set.of(0), low.selectedNodeIndices());
        GltfGunBodyPose.Frame hidden = GltfGunBodyPose.sample(low, null, 0);
        assertTrue(hidden.hidden(0));
        assertTrue(hidden.worldTransform(0).isFinite());
        GltfGunBodyPose.Frame restored = GltfGunBodyPose.sample(low, null, 0,
                new Matrix4f[]{new Matrix4f(), null, null});
        assertTrue(restored.active(0));
        assertFalse(restored.hidden(0));
        assertTrue(restored.worldTransform(0).isFinite());
    }

    @Test
    void replacementOccupiesTheSameTranslatedRotatedParentSlotIncludingChildren() throws Exception {
        JsonObject json = withParent();
        json.getAsJsonArray("nodes").get(1).getAsJsonObject().add("children", array("[4]"));
        json.getAsJsonArray("nodes").add(JsonParser.parseString("{\"translation\":[2,0,0],\"mesh\":1}"));
        ConvertedGltfAsset asset = convert(json).withLodLevel(1);
        GltfGunBodyPose.Frame bind = GltfGunBodyPose.sample(asset, null, 0);
        assertEquals(Set.of(1, 3, 4), asset.selectedNodeIndices());
        assertEquals(5, bind.worldTransform(1).m30(), 1.0E-4);
        assertEquals(26, bind.worldTransform(1).m31(), 1.0E-4);
        assertEquals(28, bind.worldTransform(4).m31(), 1.0E-4);
        Matrix4f high = new Matrix4f().translation(5, 6, 0).rotateZ((float) Math.PI / 2).translate(13, 0, 0);
        GltfGunBodyPose.Frame moved = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{high, null, null, null, null});
        assertEquals(29, moved.worldTransform(1).m31(), 1.0E-4);
        assertEquals(31, moved.worldTransform(4).m31(), 1.0E-4);
        GltfGunBodyPose.Frame hidden = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{new Matrix4f(high).scale(0), null, null, null, new Matrix4f()});
        assertTrue(hidden.hidden(1));
        assertTrue(hidden.hidden(4));
    }

    @Test
    void eachLodUsesItsOwnNativeAnimationWithoutDoubleApplyingTheHighChannel() throws Exception {
        ConvertedGltfAsset asset = convert(withParent()).withLodLevel(1);
        GltfAnimationClip clip = new GltfAnimationClip("authored levels", 1, new GltfAnimation(List.of(
                translation(0, 10, 13), translation(1, 20, 23))));
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, clip, 1);
        assertEquals(5, frame.worldTransform(1).m30(), 1.0E-4);
        assertEquals(29, frame.worldTransform(1).m31(), 1.0E-4);
        assertEquals(19, frame.worldTransform(0).m31(), 1.0E-4);
    }

    @Test
    void absoluteOverridesInsideTheSelectedBranchAreNotReparentedTwice() throws Exception {
        JsonObject json = withParent();
        json.getAsJsonArray("nodes").get(1).getAsJsonObject().add("children", array("[4]"));
        json.getAsJsonArray("nodes").add(JsonParser.parseString("{\"translation\":[2,0,0],\"mesh\":1}"));
        ConvertedGltfAsset asset = convert(json).withLodLevel(1);
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{null, new Matrix4f().translation(77, 0, 0), null, null, null});
        assertEquals(77, frame.worldTransform(1).m30(), 1.0E-4);
        assertEquals(79, frame.worldTransform(4).m30(), 1.0E-4);
        assertEquals(0, frame.worldTransform(4).m31(), 1.0E-4);
        GltfGunBodyPose.Frame hidden = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{new Matrix4f().scale(0), new Matrix4f().translation(77, 0, 0), null, null, null});
        assertTrue(hidden.hidden(1));
        assertTrue(hidden.hidden(4));
        assertEquals(79, hidden.worldTransform(4).m30(), 1.0E-4);
    }

    @Test
    void sharedParentAnimationIsInheritedExactlyOnce() throws Exception {
        ConvertedGltfAsset asset = convert(withParent()).withLodLevel(1);
        GltfAnimationClip clip = new GltfAnimationClip("parent", 1, new GltfAnimation(List.of(
                new GltfAnimationChannel(3, GltfAnimationSampler.translation(GltfInterpolation.LINEAR,
                        new float[]{0, 1}, new float[]{5, 6, 0, 8, 9, 0})))));
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, clip, 1);
        assertEquals(8, frame.worldTransform(1).m30(), 1.0E-4);
        assertEquals(29, frame.worldTransform(1).m31(), 1.0E-4);
    }

    @Test
    void nestedLodKeepsAnInnerAbsoluteRigOverrideWhileInheritingOuterHiding() throws Exception {
        JsonObject json = withParent();
        json.getAsJsonArray("nodes").get(1).getAsJsonObject().add("children", array("[4]"));
        json.getAsJsonArray("nodes").add(JsonParser.parseString("""
                {"translation":[2,0,0],"mesh":1,"extensions":{"MSFT_lod":{"ids":[5]}}}
                """));
        json.getAsJsonArray("nodes").add(JsonParser.parseString("{\"translation\":[4,0,0],\"mesh\":2}"));
        ConvertedGltfAsset asset = convert(json).withLodLevel(1);
        assertEquals(Set.of(1, 3, 5), asset.selectedNodeIndices());
        GltfGunBodyPose.Frame bind = GltfGunBodyPose.sample(asset, null, 0);
        assertEquals(30, bind.worldTransform(5).m31(), 1.0E-4);
        Matrix4f high = new Matrix4f().translation(5, 6, 0).rotateZ((float) Math.PI / 2).translate(13, 0, 0);
        Matrix4f inner = new Matrix4f().translation(80, 0, 0);
        GltfGunBodyPose.Frame mapped = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{high, null, null, null, inner, null});
        assertEquals(82, mapped.worldTransform(5).m30(), 1.0E-4);
        assertEquals(0, mapped.worldTransform(5).m31(), 1.0E-4);
        GltfGunBodyPose.Frame hidden = GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{new Matrix4f(high).scale(0), null, null, null, inner, null});
        assertTrue(hidden.hidden(5));
        assertEquals(82, hidden.worldTransform(5).m30(), 1.0E-4);
    }

    @Test
    void lowerMorphTopologyKeepsItsOwnWeightsAndDisconnectedSkinJointsStayReachable() throws Exception {
        ConvertedGltfAsset asset = convert(skinnedMorphFixture()).withLodLevel(1);
        GltfAnimationClip clip = new GltfAnimationClip("morph", 1, new GltfAnimation(List.of(
                new GltfAnimationChannel(0, GltfAnimationSampler.weights(1, GltfInterpolation.LINEAR,
                        new float[]{0, 1}, new float[]{0.1f, 0.9f})),
                new GltfAnimationChannel(1, GltfAnimationSampler.weights(2, GltfInterpolation.LINEAR,
                        new float[]{0, 1}, new float[]{0.2f, 0.3f, 0.4f, 0.5f})))));
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, clip, 1,
                new Matrix4f[]{new Matrix4f().translation(13, 0, 0), null, null, null});
        assertArrayEquals(new float[]{0.4f, 0.5f}, frame.morphWeights(1));
        assertArrayEquals(new float[]{0.9f}, frame.morphWeights(0));
        assertEquals(7, frame.worldTransform(asset.runtimeScene().skins().getFirst().jointNodeIndex(0)).m30(), 1.0E-5);
        assertFalse(frame.active(3));
        var deformed = GltfVertexDeformer.deform(asset.renderMeshes().get(1).primitives().getFirst().runtimePrimitive(),
                asset.runtimeScene().skins().getFirst(), frame.worldTransforms(), frame.morphWeights(1));
        assertEquals(7, deformed.positions()[0], 1.0E-4);
        assertEquals(8.9, deformed.positions()[3], 1.0E-4);
    }

    @Test
    void implicitSceneRootsDoNotRenderLowerLevelsAlongsideTheHighLevel() throws Exception {
        JsonObject json = fixture();
        json.remove("scenes");
        json.remove("scene");
        ConvertedGltfAsset asset = convert(json);
        assertEquals(Set.of(0), asset.selectedNodeIndices());
        assertEquals(Set.of(2), asset.withLodLevel(2).selectedNodeIndices());
    }

    @Test
    void materialLodOnlyUsesTheSelectedNodeBranchAndCopiesKeepMetadata() throws Exception {
        JsonObject json = fixture();
        json.add("materials", array("""
                [{"extensions":{"MSFT_lod":{"ids":[1]}}},{},
                 {"extensions":{"MSFT_lod":{"ids":[3]}}},{}]
                """));
        json.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives")
                .get(0).getAsJsonObject().addProperty("material", 0);
        json.getAsJsonArray("meshes").get(1).getAsJsonObject().getAsJsonArray("primitives")
                .get(0).getAsJsonObject().addProperty("material", 2);
        ConvertedGltfAsset original = convert(json);
        ConvertedGltfAsset selected = original.withLodLevel(1);
        assertEquals(Set.of(3), selected.selectedMaterialIndices());
        assertEquals(3, selected.renderMeshes().get(1).primitives().getFirst().materialIndex());
        assertEquals(0, original.renderMeshes().get(0).primitives().getFirst().materialIndex());
        ConvertedGltfAsset copy = selected.withRenderData(selected.renderMeshes(), selected.materials(), selected.images());
        assertSame(selected.lods(), copy.lods());
        assertEquals(selected.selectedNodeIndices(), copy.selectedNodeIndices());
        assertEquals(selected.selectedMaterialIndices(), copy.selectedMaterialIndices());
        assertSame(copy.renderMeshes().get(1).primitives().getFirst().runtimePrimitive(),
                copy.runtimeScene().meshes().get(1).primitives().getFirst());
        assertSame(copy, copy.withLodLevel(1));
        assertThrows(IllegalStateException.class, () -> copy.withLodLevel(0));
    }

    @Test
    void malformedIdsAndDuplicateOrdinaryReachabilityAreRejected() {
        for (String ids : List.of("[]", "[0]", "[1,1]", "[-1]", "[3]", "[0.5]", "[\"1\"]")) {
            JsonObject json = fixture();
            json.getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonObject("extensions")
                    .getAsJsonObject("MSFT_lod").add("ids", array(ids));
            assertThrows(Exception.class, () -> convert(json), ids);
        }
        JsonObject repeated = fixture();
        repeated.getAsJsonArray("scenes").get(0).getAsJsonObject().add("nodes", array("[0,1]"));
        assertThrows(Exception.class, () -> convert(repeated));
        JsonObject cycle = fixture();
        cycle.getAsJsonArray("nodes").get(1).getAsJsonObject()
                .add("extensions", JsonParser.parseString("{\"MSFT_lod\":{\"ids\":[0]}}"));
        assertThrows(Exception.class, () -> convert(cycle));
        JsonObject child = fixture();
        child.getAsJsonArray("nodes").get(0).getAsJsonObject().add("children", array("[1]"));
        assertThrows(Exception.class, () -> convert(child));
    }

    @Test
    void unknownExtensionsRemainFailClosed() {
        for (String declaration : List.of("extensionsUsed", "extensionsRequired")) {
            JsonObject json = fixture();
            json.add(declaration, array("[\"MSFT_lod\",\"VENDOR_unknown\"]"));
            assertThrows(Exception.class, () -> convert(json));
        }
    }

    @Test
    void metadataDefensivelyCopiesIdsAndRejectsSelfOrDuplicateParents() {
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>(List.of(1));
        GltfLodMetadata metadata = new GltfLodMetadata(Map.of(0, ids), Map.of());
        ids.set(0, 2);
        assertEquals(List.of(1), metadata.nodeChains().get(0));
        assertThrows(IllegalArgumentException.class, () -> new GltfLodMetadata(Map.of(0, List.of(0)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GltfLodMetadata(
                Map.of(0, List.of(2), 1, List.of(2)), Map.of()));
    }

    private static ConvertedGltfAsset convert(JsonObject json) throws Exception {
        return new JgltfRuntimeConverter().convert(new JgltfModelLoader().load(
                new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private static JsonArray array(String text) {
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private static GltfAnimationChannel translation(int node, float start, float end) {
        return new GltfAnimationChannel(node, GltfAnimationSampler.translation(GltfInterpolation.LINEAR,
                new float[]{0, 1}, new float[]{start, 0, 0, end, 0, 0}));
    }

    private static JsonObject withParent() {
        JsonObject json = fixture();
        json.getAsJsonArray("nodes").add(JsonParser.parseString("""
                {"translation":[5,6,0],"rotation":[0,0,0.7071067811865476,0.7071067811865476],"children":[0]}
                """));
        json.getAsJsonArray("scenes").get(0).getAsJsonObject().add("nodes", array("[3]"));
        return json;
    }

    private static JsonObject skinnedMorphFixture() {
        JsonObject json = fixture();
        ByteBuffer data = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        for (float component : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) data.putFloat(component);
        data.put(new byte[12]);
        for (int vertex = 0; vertex < 3; vertex++) {
            for (float component : new float[]{1, 0, 0, 0}) data.putFloat(component);
        }
        JsonObject buffer = json.getAsJsonArray("buffers").get(0).getAsJsonObject();
        buffer.addProperty("byteLength", 96);
        buffer.addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data.array()));
        json.getAsJsonArray("bufferViews").add(JsonParser.parseString("{\"buffer\":0,\"byteOffset\":36,\"byteLength\":12}"));
        json.getAsJsonArray("bufferViews").add(JsonParser.parseString("{\"buffer\":0,\"byteOffset\":48,\"byteLength\":48}"));
        json.getAsJsonArray("accessors").add(JsonParser.parseString("{\"bufferView\":1,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"}"));
        json.getAsJsonArray("accessors").add(JsonParser.parseString("{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}"));
        for (int level = 0; level < 2; level++) {
            JsonObject primitive = json.getAsJsonArray("meshes").get(level).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject();
            primitive.add("targets", array(level == 0 ? "[{\"POSITION\":0}]" : "[{\"POSITION\":0},{\"POSITION\":0}]"));
            if (level == 1) {
                primitive.getAsJsonObject("attributes").addProperty("JOINTS_0", 1);
                primitive.getAsJsonObject("attributes").addProperty("WEIGHTS_0", 2);
            }
        }
        json.getAsJsonArray("nodes").get(0).getAsJsonObject().add("weights", array("[0.1]"));
        json.getAsJsonArray("nodes").get(1).getAsJsonObject().add("weights", array("[0.2,0.3]"));
        json.getAsJsonArray("nodes").get(1).getAsJsonObject().addProperty("skin", 0);
        json.getAsJsonArray("nodes").add(JsonParser.parseString("{\"translation\":[7,0,0]}"));
        json.add("skins", array("[{\"joints\":[3]}]"));
        return json;
    }

    private static JsonObject fixture() {
        ByteBuffer buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float component : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) buffer.putFloat(component);
        JsonObject json = JsonParser.parseString("""
                {"asset":{"version":"2.0"},"extensionsUsed":["MSFT_lod"],
                 "buffers":[{"byteLength":36}],"bufferViews":[{"buffer":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
                   "min":[0,0,0],"max":[1,1,0]}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]},
                           {"primitives":[{"attributes":{"POSITION":0}}]},
                           {"primitives":[{"attributes":{"POSITION":0}}]}],
                 "nodes":[{"name":"high","mesh":0,"translation":[10,0,0],
                   "extensions":{"MSFT_lod":{"ids":[1,2]}}},
                   {"name":"medium","mesh":1,"translation":[20,0,0]},
                   {"name":"low","mesh":2,"translation":[30,0,0]}],
                 "scenes":[{"nodes":[0]}],"scene":0}
                """).getAsJsonObject();
        json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri",
                "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(buffer.array()));
        return json;
    }
}
