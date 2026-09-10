package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.convert.GltfSceneData;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimation;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationSampler;
import com.tacz.guns.client.model.gltf.runtime.GltfInterpolation;
import com.tacz.guns.client.model.gltf.runtime.GltfMaterialReference;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMorphTarget;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfZeroScaleVisibilityTest {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer()).create();

    @Test
    void fourMappingsKeepIndependentChildWorldOverrideAbsolute() {
        String json = """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.mapping","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"body","parent":"root","pivot":[0,24,0]},
                    {"name":"slide","parent":"body","pivot":[0,24,0]},
                    {"name":"hinge","parent":"slide","pivot":[0,24,0]},
                    {"name":"magazine","parent":"root","pivot":[0,24,0]}]}]}
                """;
        BedrockGunModel rig = new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
        ConvertedGltfAsset asset = asset(List.of(
                node("whole", new Vector3f(1), new int[]{1, 3}, 0, -1),
                node("sliding", new Vector3f(1), new int[]{2}, 0, -1),
                node("handle", new Vector3f(1), new int[0], 0, -1),
                node("detachable", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0});
        GltfNodeMapBridge bridge = GltfNodeMapBridge.create(asset,
                Map.of("body", "whole", "slide", "sliding", "hinge", "handle", "magazine", "detachable"),
                Set.of(), rig, 1, null);
        rig.getNode("body").offsetX = 2;
        rig.getNode("slide").offsetZ = 0.2f;
        rig.getNode("magazine").offsetY = 3;
        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, null, 0, bridge.snapshotWorldOverrides());
        assertEquals(-2, frame.worldTransform(0).m30(), 1e-6);
        assertEquals(-2, frame.worldTransform(1).m30(), 1e-6);
        assertEquals(-2, frame.worldTransform(2).m30(), 1e-6);
        assertEquals(0.2, frame.worldTransform(2).m32(), 1e-6);
        assertEquals(0, frame.worldTransform(3).m30(), 1e-6);
        assertEquals(-3, frame.worldTransform(3).m31(), 1e-6);
    }

    @Test
    void mappedSourceUnderflowIsNotAnAuthoredVisibilityCommand() {
        BedrockGunModel rig = rig(true);
        GltfGunBodyRenderer renderer = renderer(asset(List.of(
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0}), "underflow_mapping", null, "{\"panel\":\"panel\"}", rig);
        scale(rig.getNode("carrier"), 1.0E-30f);
        scale(rig.getNode("panel"), 1.0E-30f);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> renderer.preparedAtTime(0));
        assertTrue(error.getMessage().contains("singular"));
    }

    @Test
    void mappedAncestorZeroHidesButPartialAndNearZeroDoNot() {
        BedrockGunModel rig = rig(true);
        GltfGunBodyRenderer renderer = renderer(asset(List.of(
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0}), "ancestor_mapping", null, "{\"panel\":\"panel\"}", rig);
        float[] original = renderer.preparedAtTime(0).getFirst().geometry().positions();
        scale(rig.getNode("carrier"), 0);
        assertTrue(renderer.preparedFrameAtTime(0).allHidden());
        scale(rig.getNode("carrier"), 1);
        assertArrayEquals(original, renderer.preparedAtTime(0).getFirst().geometry().positions());
        rig.getNode("panel").xScale = 0;
        assertThrows(IllegalArgumentException.class, () -> renderer.preparedAtTime(0));
        scale(rig.getNode("panel"), 1.0E-4f);
        assertThrows(IllegalArgumentException.class, () -> renderer.preparedAtTime(0));
    }

    @Test
    void embeddedExactZeroHidesAndRestoresTwoUnrelatedRigidHierarchies() {
        for (boolean nested : new boolean[]{false, true}) {
            List<GltfNode> nodes = nested
                    ? List.of(node("carrier", new Vector3f(1), new int[]{1}, -1, -1),
                    node("panel", new Vector3f(1), new int[0], 0, -1))
                    : List.of(node("panel", new Vector3f(1), new int[0], 0, -1));
            GltfAnimationClip clip = scaleClip(0, new Vector3f(0));
            GltfGunBodyRenderer renderer = renderer(asset(nodes, rigid(), List.of(), List.of(clip),
                    new float[0], new int[]{0}), nested ? "hinged_panel" : "display_piece", "blink", null, null);
            float[] original = renderer.preparedAtTime(0).getFirst().geometry().positions();
            for (int cycle = 0; cycle < 3; cycle++) {
                assertTrue(renderer.preparedAtTime(1).isEmpty());
                assertTrue(renderer.preparedFrameAtTime(1).allHidden());
                assertArrayEquals(original, renderer.preparedAtTime(2).getFirst().geometry().positions());
                assertFalse(renderer.preparedFrameAtTime(2).allHidden());
            }
        }
    }

    @Test
    void mappedHiddenParentCannotBeEscapedByIndependentChildWorldOverride() {
        BedrockGunModel rig = rig();
        ConvertedGltfAsset asset = asset(List.of(
                node("carrier", new Vector3f(1), new int[]{1}, -1, -1),
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0});
        GltfGunBodyRenderer renderer = renderer(asset, "mapped_panel", null,
                "{\"carrier\":\"carrier\",\"panel\":\"panel\"}", rig);
        float[] original = renderer.preparedAtTime(0).getFirst().geometry().positions();
        scale(rig.getNode("carrier"), 0);
        rig.getNode("panel").offsetX = 4;
        assertTrue(renderer.preparedAtTime(0).isEmpty());
        scale(rig.getNode("carrier"), 1);
        rig.getNode("panel").offsetX = 0;
        assertArrayEquals(original, renderer.preparedAtTime(0).getFirst().geometry().positions());
    }

    @Test
    void staticExactZeroIsAcceptedButEmptySceneIsNot() {
        GltfGunBodyRenderer hidden = renderer(asset(List.of(
                node("panel", new Vector3f(0), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0}), "static_panel", null, null, null);
        assertTrue(hidden.preparedAtTime(0).isEmpty());
        assertTrue(hidden.preparedFrameAtTime(0).allHidden());
        ConvertedGltfAsset empty = asset(List.of(
                node("empty", new Vector3f(0), new int[0], -1, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0});
        assertThrows(IllegalArgumentException.class, () -> renderer(empty, "empty", null, null, null));
    }

    @Test
    void partialZeroAndNearZeroRemainSingularWhileNegativeScaleStillRenders() {
        for (Vector3f invalid : List.of(new Vector3f(0, 1, 1), new Vector3f(1.0E-4f))) {
            GltfAnimationClip clip = scaleClip(0, invalid);
            GltfGunBodyRenderer renderer = renderer(asset(List.of(
                    node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(clip),
                    new float[0], new int[]{0}), "singular_panel", "blink", null, null);
            assertThrows(IllegalArgumentException.class, () -> renderer.preparedAtTime(1));
        }
        GltfAnimationClip negative = scaleClip(0, new Vector3f(-1, 1, 1));
        GltfGunBodyRenderer renderer = renderer(asset(List.of(
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(negative),
                new float[0], new int[]{0}), "mirrored_panel", "blink", null, null);
        assertArrayEquals(new float[]{0, 0, 0, 0, 1, 0, -1, 0, 0},
                renderer.preparedAtTime(1).getFirst().geometry().positions());
    }

    @Test
    void hiddenAncestorDoesNotBypassNonFiniteDescendantOverride() {
        ConvertedGltfAsset asset = asset(List.of(
                node("carrier", new Vector3f(0), new int[]{1}, -1, -1),
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0});
        assertThrows(IllegalArgumentException.class, () -> GltfGunBodyPose.sample(asset, null, 0,
                new Matrix4f[]{null, new Matrix4f().m30(Float.NaN)}));
    }

    @Test
    void nearZeroProductsThatUnderflowAreNotVisibilityInstructions() {
        ConvertedGltfAsset asset = asset(List.of(
                node("carrier", new Vector3f(Float.MIN_VALUE), new int[]{1}, -1, -1),
                node("panel", new Vector3f(Float.MIN_VALUE), new int[0], 0, -1)), rigid(),
                List.of(), List.of(), new float[0], new int[]{0});
        assertThrows(IllegalArgumentException.class, () -> renderer(asset, "underflow", null, null, null));
    }

    @Test
    void hiddenSubtreeDoesNotHideUnrelatedVisibleSibling() {
        ConvertedGltfAsset asset = asset(List.of(
                node("hidden", new Vector3f(0), new int[0], 0, -1),
                node("visible", new Vector3f(1), new int[0], 0, -1)), rigid(),
                List.of(), List.of(), new float[0], new int[]{0, 1});
        GltfGunBodyRenderer renderer = renderer(asset, "sibling", null, null, null);
        assertEquals(1, renderer.preparedAtTime(0).size());
        assertFalse(renderer.preparedFrameAtTime(0).allHidden());
    }

    @Test
    void hiddenMeshDoesNotBypassMorphWeightValidation() {
        GltfMeshPrimitive morph = new GltfMeshPrimitive(rigid().positions(), new float[0], new float[0],
                new int[0], new float[0], List.of(new GltfMorphTarget(new float[9], new float[0], new float[0])),
                new GltfMaterialReference("material"));
        for (float[] invalid : List.of(new float[]{1, 2}, new float[]{Float.NaN})) {
            ConvertedGltfAsset asset = asset(List.of(
                    node("panel", new Vector3f(0), new int[0], 0, -1)), morph, List.of(), List.of(),
                    invalid, new int[]{0});
            assertThrows(IllegalArgumentException.class, () -> renderer(asset, "bad_morph", null, null, null));
        }
    }

    @Test
    void allHiddenSubmissionIsHandledWithoutGeometryOrRendererDetachment() throws ReflectiveOperationException {
        BedrockGunModel rig = rig();
        GltfGunBodyRenderer renderer = renderer(asset(List.of(
                node("panel", new Vector3f(1), new int[0], 0, -1)), rigid(), List.of(), List.of(),
                new float[0], new int[]{0}), "submit_panel", null, "{\"panel\":\"panel\"}", rig);
        rig.setBodyRenderer(renderer);
        OrderedSubmitNodeCollector collector = (OrderedSubmitNodeCollector) Proxy.newProxyInstance(
                OrderedSubmitNodeCollector.class.getClassLoader(), new Class<?>[]{OrderedSubmitNodeCollector.class},
                (proxy, method, args) -> { throw new AssertionError("hidden frame must not queue " + method.getName()); });
        // Exercise the actual submit/fallback contract without a GPU, then restore global test state.
        Field renderThread = RenderSystem.class.getDeclaredField("renderThread");
        renderThread.setAccessible(true);
        Object previousThread = renderThread.get(null);
        renderThread.set(null, Thread.currentThread());
        try {
            scale(rig.getNode("panel"), 0);
            assertTrue(rig.submitCustomBody(collector, new PoseStack(), null,
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, 0, 0));
            assertSame(renderer, rig.getBodyRenderer());
            scale(rig.getNode("panel"), 1);
            assertEquals(1, renderer.preparedAtTime(0).size());
        } finally {
            renderThread.set(null, previousThread);
        }
    }

    @Test
    void zeroSkinnedMeshNodeDoesNotHideUnaffectedJoints() {
        GltfGunBodyRenderer renderer = renderer(skinnedAsset(new Vector3f(0), new Vector3f(1),
                new Vector3f(1), skinned(0.5f, 0.5f, 1)), "skinned_panel", null, null, null);
        assertEquals(1, renderer.preparedAtTime(0).size());
    }

    @Test
    void skinHidesOnlyWhenAllJointsCollapseAndOtherwiseKeepsDeformationGuards() {
        GltfGunBodyRenderer hidden = renderer(skinnedAsset(new Vector3f(1), new Vector3f(0),
                new Vector3f(0), skinned(0.5f, 0.5f, 1)), "collapsed_skin", null, null, null);
        assertTrue(hidden.preparedAtTime(0).isEmpty());
        GltfGunBodyRenderer partlyCollapsed = renderer(skinnedAsset(new Vector3f(1), new Vector3f(0),
                new Vector3f(1), skinned(0.5f, 0.5f, 1)), "blended_skin", null, null, null);
        assertEquals(1, partlyCollapsed.preparedAtTime(0).size());
        for (Vector3f invalid : List.of(new Vector3f(0, 1, 1), new Vector3f(1.0E-4f))) {
            assertThrows(IllegalArgumentException.class, () -> renderer(skinnedAsset(new Vector3f(1), invalid,
                    invalid, skinned(0.5f, 0.5f, 1)), "singular_skin", null, null, null));
        }
    }

    @Test
    void hiddenSkinDoesNotBypassInvalidWeightsOrJointIndices() {
        for (GltfMeshPrimitive primitive : List.of(skinned(-0.5f, 1.5f, 1),
                skinned(0, 0, 1), skinned(0.5f, 0.5f, 2))) {
            assertThrows(IllegalArgumentException.class, () -> renderer(skinnedAsset(new Vector3f(1),
                    new Vector3f(0), new Vector3f(0), primitive), "invalid_skin", null, null, null));
        }
    }

    private static GltfGunBodyRenderer renderer(ConvertedGltfAsset asset, String name, String animation,
                                                 String nodeMap, BedrockGunModel rig) {
        Identifier id = Identifier.fromNamespaceAndPath("test", "models/gltf/" + name + ".gltf");
        String json = "{\"type\":\"gltf\",\"location\":\"" + id + "\""
                + (animation == null ? "" : ",\"animation\":\"" + animation + "\"")
                + (nodeMap == null ? "" : ",\"node_map\":" + nodeMap) + "}";
        return new GltfGunBodyRenderer(id, asset, new GltfModelManager(), 0,
                GSON.fromJson(json, GunRenderModelConfig.class), rig);
    }

    private static GltfAnimationClip scaleClip(int node, Vector3f scale) {
        return new GltfAnimationClip("blink", 2, new GltfAnimation(List.of(new GltfAnimationChannel(node,
                GltfAnimationSampler.scale(GltfInterpolation.LINEAR, new float[]{0, 1, 2},
                        new float[]{1, 1, 1, scale.x, scale.y, scale.z, 1, 1, 1})))));
    }

    private static GltfNode node(String name, Vector3f scale, int[] children, int mesh, int skin) {
        return new GltfNode(name, new GltfNodeTransform(new Vector3f(), new Quaternionf(), scale),
                children, mesh, skin);
    }

    private static ConvertedGltfAsset skinnedAsset(Vector3f meshScale, Vector3f firstScale,
                                                  Vector3f secondScale, GltfMeshPrimitive primitive) {
        return asset(List.of(node("mesh", meshScale, new int[0], 0, 0),
                        node("first", firstScale, new int[0], -1, -1),
                        node("second", secondScale, new int[0], -1, -1)), primitive,
                List.of(new GltfSkin(new int[]{1, 2}, List.of(new Matrix4f(), new Matrix4f()))),
                List.of(), new float[0], new int[]{0, 1, 2});
    }

    private static ConvertedGltfAsset asset(List<GltfNode> nodes, GltfMeshPrimitive primitive,
                                            List<GltfSkin> skins, List<GltfAnimationClip> clips,
                                            float[] morphWeights, int[] roots) {
        List<float[]> nodeWeights = new ArrayList<>();
        for (GltfNode node : nodes) {
            nodeWeights.add(node.meshIndex() < 0 ? new float[0] : morphWeights);
        }
        return new ConvertedGltfAsset(new GltfScene(nodes, List.of(new GltfMesh(List.of(primitive))), skins, roots),
                List.of(new GltfRenderMesh("mesh", List.of(new GltfRenderPrimitive(primitive,
                        new int[]{0, 1, 2}, new float[0], new float[0], -1)), new float[0])),
                List.of(), List.of(), clips, nodeWeights, List.of(new float[0]),
                List.of(new GltfSceneData("scene", roots)), 0, 0);
    }

    private static GltfMeshPrimitive rigid() {
        return primitive(new int[0], new float[0]);
    }

    private static GltfMeshPrimitive skinned(float firstWeight, float secondWeight, int secondJoint) {
        return primitive(new int[]{0, secondJoint, 0, 0, 0, secondJoint, 0, 0, 0, secondJoint, 0, 0},
                new float[]{firstWeight, secondWeight, 0, 0, firstWeight, secondWeight, 0, 0,
                        firstWeight, secondWeight, 0, 0});
    }

    private static GltfMeshPrimitive primitive(int[] joints, float[] weights) {
        return new GltfMeshPrimitive(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[0],
                new float[0], joints, weights, List.of(), new GltfMaterialReference("material"));
    }

    private static BedrockGunModel rig() {
        return rig(false);
    }

    private static BedrockGunModel rig(boolean nested) {
        String json = """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"carrier","parent":"root","pivot":[0,24,0]},
                    {"name":"panel","parent":"root","pivot":[0,24,0]}]}]}
                """;
        if (nested) json = json.replace("\"panel\",\"parent\":\"root\"", "\"panel\",\"parent\":\"carrier\"");
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static void scale(BedrockPart node, float scale) {
        node.xScale = scale;
        node.yScale = scale;
        node.zScale = scale;
    }
}
