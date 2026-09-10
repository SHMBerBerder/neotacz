package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.GltfLodMetadata;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.convert.GltfSceneData;
import com.tacz.guns.client.model.gltf.runtime.GltfMaterialReference;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimation;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationSampler;
import com.tacz.guns.client.model.gltf.runtime.GltfInterpolation;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfLodNodeMapBridgeTest {
    @Test
    void rejectsHighRigidAuthorityWhenSelectedSkinOnlyUsesJointsOutsideItsReplacementSubtree() {
        ConvertedGltfAsset original = asset(true, false);
        assertDoesNotThrow(() -> bridge(original, "High", rig()));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> bridge(original.withLodLevel(1), "High", rig()));
        assertTrue(failure.getMessage().contains("does not affect"));
    }

    @Test
    void selectedExternalSkinJointIsStillAValidDirectTargetAndDrivesVertices() {
        ConvertedGltfAsset selected = asset(true, false).withLodLevel(1);
        BedrockGunModel rig = rig();
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> bridge(selected, "Joint", rig));
        rig.getNode("bolt").offsetX = 1;
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(selected, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(6, skinnedFirstVertexX(selected, frame), 1.0E-5);
    }

    @Test
    void highAuthorityStillDrivesTheSelectedRigidReplacement() {
        ConvertedGltfAsset selected = asset(false, false).withLodLevel(1);
        BedrockGunModel rig = rig();
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> bridge(selected, "High", rig));
        rig.getNode("bolt").offsetX = 1;
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(selected, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(19, frame.worldTransform(1).m30(), 1.0E-5);
    }

    @Test
    void highAuthorityStillDrivesSelectedSkinJointsInsideTheReplacementSubtree() {
        ConvertedGltfAsset selected = asset(true, true).withLodLevel(1);
        BedrockGunModel rig = rig();
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> bridge(selected, "High", rig));
        rig.getNode("bolt").offsetX = 1;
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(selected, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(26, skinnedFirstVertexX(selected, frame), 1.0E-5);
    }

    @Test
    void directSelectedJointMappingUsesItsEffectiveLodBindUnderTheHighNodesParent() {
        ConvertedGltfAsset selected = withParent(asset(true, true)).withLodLevel(1);
        BedrockGunModel rig = rig();
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> bridge(selected, "Joint", rig));
        GltfGunBodyPose.Frame bind = GltfGunBodyPose.sample(selected, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(32, skinnedFirstVertexX(selected, bind), 1.0E-5);
        rig.getNode("bolt").offsetX = 1;
        GltfGunBodyPose.Frame moved = GltfGunBodyPose.sample(selected, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(31, skinnedFirstVertexX(selected, moved), 1.0E-5);
    }

    @Test
    void rejectsNativeParentAnimationThatCompetesWithTheMappedSelectedJoint() {
        ConvertedGltfAsset selected = withParent(asset(true, true)).withLodLevel(1);
        GltfAnimationClip clip = translationClip(3, 5, 6);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GltfNodeMapBridge.create(selected, Map.of("bolt", "Joint"), Set.of(), rig(), 1, clip));
        assertTrue(failure.getMessage().contains("TRS channel"));
    }

    @Test
    void rejectsReversedExternalAuthorityHierarchyAcrossTheSelectedReplacement() {
        ConvertedGltfAsset selected = asset(true, true).withLodLevel(1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GltfNodeMapBridge.create(selected, Map.of("bolt", "Joint", "handle", "High"),
                        Set.of(), rig(), 1, null));
        assertTrue(failure.getMessage().contains("ancestor direction"));
    }

    @Test
    void highNativeChannelDoesNotBecomeAnAncestorChannelOfTheSelectedLowJoint() {
        ConvertedGltfAsset selected = asset(true, true).withLodLevel(1);
        GltfAnimationClip clip = translationClip(0, 10, 14);
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> GltfNodeMapBridge.create(selected,
                Map.of("bolt", "Joint"), Set.of(), rig(), 1, clip));
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(selected, clip, 1, bridge.snapshotWorldOverrides());
        assertEquals(27, skinnedFirstVertexX(selected, frame), 1.0E-5);
    }

    @Test
    void lowNativeChannelStillAnimatesBelowAMappedHighAuthority() {
        ConvertedGltfAsset selected = asset(true, true).withLodLevel(1);
        GltfAnimationClip clip = translationClip(1, 20, 23);
        BedrockGunModel rig = rig();
        GltfNodeMapBridge bridge = assertDoesNotThrow(() -> GltfNodeMapBridge.create(selected,
                Map.of("bolt", "High"), Set.of(), rig, 1, clip));
        rig.getNode("bolt").offsetX = 1;
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(selected, clip, 1, bridge.snapshotWorldOverrides());
        assertEquals(29, skinnedFirstVertexX(selected, frame), 1.0E-5);
    }

    private static float skinnedFirstVertexX(ConvertedGltfAsset asset, GltfGunBodyPose.Frame frame) {
        return GltfVertexDeformer.deform(asset.renderMeshes().get(1).primitives().getFirst().runtimePrimitive(),
                asset.runtimeScene().skins().getFirst(), frame.worldTransforms(), new float[0]).positions()[0];
    }

    private static GltfNodeMapBridge bridge(ConvertedGltfAsset asset, String target, BedrockGunModel rig) {
        return GltfNodeMapBridge.create(asset, Map.of("bolt", target), Set.of(), rig, 1, null);
    }

    private static GltfAnimationClip translationClip(int node, float start, float end) {
        return new GltfAnimationClip("translation", 1, new GltfAnimation(List.of(new GltfAnimationChannel(node,
                GltfAnimationSampler.translation(GltfInterpolation.LINEAR, new float[]{0, 1},
                        new float[]{start, 0, 0, end, 0, 0})))));
    }

    private static ConvertedGltfAsset withParent(ConvertedGltfAsset original) {
        List<GltfNode> nodes = new ArrayList<>(original.runtimeScene().nodes());
        nodes.add(new GltfNode("Parent", GltfNodeTransform.translation(5, 0, 0), new int[]{0}, -1, -1));
        int[] roots = {3};
        return new ConvertedGltfAsset(new GltfScene(nodes, original.runtimeScene().meshes(),
                original.runtimeScene().skins(), roots), original.renderMeshes(), List.of(), List.of(), List.of(),
                List.of(new float[0], new float[0], new float[0], new float[0]), original.meshDefaultMorphWeights(),
                List.of(new GltfSceneData("scene", roots)), 0, 0, original.lods());
    }

    private static ConvertedGltfAsset asset(boolean skinnedLow, boolean jointInsideLow) {
        GltfMeshPrimitive rigid = primitive(false);
        GltfMeshPrimitive low = primitive(skinnedLow);
        List<GltfNode> nodes = List.of(
                new GltfNode("High", GltfNodeTransform.translation(10, 0, 0), new int[0], 0, -1),
                new GltfNode("Low", GltfNodeTransform.translation(20, 0, 0),
                        jointInsideLow ? new int[]{2} : new int[0], 1, skinnedLow ? 0 : -1),
                new GltfNode("Joint", GltfNodeTransform.translation(7, 0, 0), new int[0], -1, -1));
        int[] roots = jointInsideLow ? new int[]{0} : new int[]{0, 2};
        List<GltfMesh> meshes = List.of(new GltfMesh(List.of(rigid)), new GltfMesh(List.of(low)));
        List<GltfSkin> skins = skinnedLow ? List.of(new GltfSkin(new int[]{2}, List.of(new Matrix4f()))) : List.of();
        return new ConvertedGltfAsset(new GltfScene(nodes, meshes, skins, roots), List.of(render(rigid), render(low)),
                List.of(), List.of(), List.of(), List.of(new float[0], new float[0], new float[0]),
                List.of(new float[0], new float[0]), List.of(new GltfSceneData("scene", roots)), 0, 0,
                new GltfLodMetadata(Map.of(0, List.of(1)), Map.of()));
    }

    private static GltfMeshPrimitive primitive(boolean skinned) {
        return new GltfMeshPrimitive(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[0], new float[0],
                skinned ? new int[12] : new int[0],
                skinned ? new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0} : new float[0],
                List.of(), new GltfMaterialReference("default"));
    }

    private static GltfRenderMesh render(GltfMeshPrimitive primitive) {
        return new GltfRenderMesh("mesh", List.of(new GltfRenderPrimitive(primitive, new int[]{0, 1, 2},
                new float[0], new float[0], -1)), new float[0]);
    }

    private static BedrockGunModel rig() {
        String json = """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.lod_test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                           {"name":"bolt","parent":"root","pivot":[0,24,0]},
                           {"name":"handle","parent":"bolt","pivot":[0,24,0]}]}]}
                """;
        return new BedrockGunModel(new Gson().fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }
}
