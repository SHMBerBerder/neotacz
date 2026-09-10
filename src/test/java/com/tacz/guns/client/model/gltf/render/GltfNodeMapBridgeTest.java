package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationPath;
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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfNodeMapBridgeTest {
    private static final float EPSILON = 1.0E-5f;
    private static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath("tacz", "models/gltf/bridge.glb");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer())
            .create();

    @Test
    void bedrockLocalMatrixMatchesPoseStackAndLegacyPathIsRootToLeafImmutable() {
        BedrockPart part = new BedrockPart("bolt");
        part.offsetX = 0.25f;
        part.offsetY = -0.5f;
        part.setPos(16.0f, 8.0f, -4.0f);
        part.zRot = 0.3f;
        part.yRot = -0.2f;
        part.xRot = 0.7f;
        part.additionalQuaternion = new Quaternionf().rotationXYZ(0.1f, -0.4f, 0.2f);
        part.xScale = 1.25f;
        part.yScale = 0.75f;
        part.zScale = 1.5f;

        PoseStack poseStack = new PoseStack();
        part.translateAndRotateAndScale(poseStack);
        Matrix4f actual = new Matrix4f();
        part.translateAndRotateAndScale(actual);
        assertMatrixEquals(poseStack.last().pose(), actual);

        BedrockGunModel legacy = bedrockRig(BedrockVersion.LEGACY, "bolt");
        List<BedrockPart> path = legacy.getNodePath("bolt");
        assertEquals(List.of("root", "bolt"), path.stream().map(node -> node.name).toList());
        assertThrows(UnsupportedOperationException.class, () -> path.add(new BedrockPart("mutated")));
    }

    @Test
    void bindPosePreservesTargetGeometryAndCurrentBedrockPoseUsesOuterBasisAndScaleCorrection() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        GltfGunBodyRenderer renderer = renderer(singleRigidNodeAsset("BoltVisual"),
                config("{\"bolt\":\"BoltVisual\"}", null, 0.5f), rig);

        assertFirstVertex(renderer.preparedAtTime(0.0).getFirst(), 0.0f, 0.0f, 0.0f);

        rig.getNode("bolt").offsetX = 1.0f;
        assertFirstVertex(renderer.preparedAtTime(0.0).getFirst(), -2.0f, 0.0f, 0.0f);
    }

    @Test
    void bedrockRootIsExcludedFromBridgeAndAppliedExactlyOnceByOuterRenderer() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        BedrockPart root = rig.getRootNode();
        BedrockPart bolt = rig.getNode("bolt");
        root.setPos(0.0f, 0.0f, 0.0f);
        bolt.setPos(0.0f, 0.0f, 0.0f);
        GltfGunBodyRenderer renderer = renderer(singleRigidNodeAsset("BoltVisual"),
                config("{\"bolt\":\"BoltVisual\"}"), rig);

        root.offsetX = 5.0f;
        bolt.offsetX = 1.0f;
        float[] prepared = firstVertex(renderer.preparedAtTime(0.0).getFirst());
        assertArrayEquals(new float[]{-1.0f, 0.0f, 0.0f}, prepared, EPSILON);

        Matrix4f outer = new Matrix4f();
        root.translateAndRotateAndScale(outer);
        outer.scale(-1.0f, -1.0f, 1.0f);
        Vector3f finalPosition = outer.transformPosition(new Vector3f(prepared));
        assertArrayEquals(
                new float[]{6.0f, 0.0f, 0.0f},
                new float[]{finalPosition.x, finalPosition.y, finalPosition.z},
                EPSILON
        );
    }

    @Test
    void bindCorrectionUsesSourceWorldDeltaInsteadOfCommutingPivotAndRotation() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        rig.getNode("bolt").setPos(16.0f, 0.0f, 0.0f);
        GltfGunBodyRenderer renderer = renderer(singleRigidNodeAsset("BoltVisual"),
                config("{\"bolt\":\"BoltVisual\"}", null, 1.0f), rig);

        rig.getNode("bolt").zRot = (float) (Math.PI / 2.0);
        Matrix4f sourceDelta = new Matrix4f()
                .translation(1.0f, 0.0f, 0.0f)
                .rotateZ((float) (Math.PI / 2.0))
                .translate(-1.0f, 0.0f, 0.0f);
        Matrix4f basis = new Matrix4f().scaling(-1.0f, -1.0f, 1.0f);
        Vector3f expected = new Matrix4f(basis).mul(sourceDelta).mul(basis).getTranslation(new Vector3f());

        assertFirstVertex(renderer.preparedAtTime(0.0).getFirst(), expected.x, expected.y, expected.z);
    }

    @Test
    void mappedParentPropagatesToUnmappedChildAndMappedChildOverrideIsOrderIndependent() {
        ConvertedGltfAsset asset = parentChildAsset();

        BedrockGunModel parentOnlyRig = bedrockRig(BedrockVersion.NEW, "bolt", "magazine");
        GltfGunBodyRenderer parentOnly = renderer(asset, config("{\"bolt\":\"BoltVisual\"}"), parentOnlyRig);
        parentOnlyRig.getNode("bolt").offsetX = 1.0f;
        assertFirstVertex(parentOnly.preparedAtTime(0.0).getFirst(), -1.0f, 0.0f, 0.0f);

        BedrockGunModel normalOrderRig = bedrockRig(BedrockVersion.NEW, "bolt", "magazine");
        GltfGunBodyRenderer normalOrder = renderer(asset,
                config("{\"bolt\":\"BoltVisual\",\"magazine\":\"MagazineVisual\"}"), normalOrderRig);
        normalOrderRig.getNode("bolt").offsetX = 1.0f;
        normalOrderRig.getNode("magazine").offsetY = 2.0f;

        BedrockGunModel reverseOrderRig = bedrockRig(BedrockVersion.NEW, "bolt", "magazine");
        GltfGunBodyRenderer reverseOrder = renderer(asset,
                config("{\"magazine\":\"MagazineVisual\",\"bolt\":\"BoltVisual\"}"), reverseOrderRig);
        reverseOrderRig.getNode("bolt").offsetX = 1.0f;
        reverseOrderRig.getNode("magazine").offsetY = 2.0f;

        float[] normal = firstVertex(normalOrder.preparedAtTime(0.0).getFirst());
        float[] reverse = firstVertex(reverseOrder.preparedAtTime(0.0).getFirst());
        assertArrayEquals(new float[]{-1.0f, -2.0f, 0.0f}, normal, EPSILON);
        assertArrayEquals(normal, reverse, EPSILON);
    }

    @Test
    void acceptsM95BoltHandleAndMagazineMappingWithoutDoubleApplyingChildOverrides() {
        ConvertedGltfAsset asset = m95SemanticThreeMapAsset();
        BedrockGunModel rig = m95SemanticBedrockRig();
        GunRenderModelConfig config = config("""
                {
                  "m95_bolt": "BoltAssemblyVisual",
                  "rotate": "BoltHandleVisual",
                  "mag_and_bullet": "MagazineVisual"
                }
                """);

        GltfGunBodyRenderer renderer = renderer(asset, config, rig);
        GltfNodeMapBridge bridge = GltfNodeMapBridge.create(
                asset,
                config.getNodeMap(),
                Set.of(),
                rig,
                config.getScale(),
                null
        );

        rig.getNode("m95_bolt").offsetX = 1.0f;
        rig.getNode("rotate").offsetY = 2.0f;
        rig.getNode("mag_and_bullet").offsetZ = 3.0f;

        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(
                asset,
                null,
                0.0,
                bridge.snapshotWorldOverrides()
        );
        assertTranslation(frame.worldTransform(1), -1.0f, 0.0f, 0.0f);
        assertTranslation(frame.worldTransform(2), -1.0f, -2.0f, 0.0f);
        assertTranslation(frame.worldTransform(3), 0.0f, 0.0f, 3.0f);

        List<GltfGunBodyRenderer.PreparedPrimitive> prepared = renderer.preparedAtTime(0.0);
        assertEquals(3, prepared.size());
        assertFirstVertex(prepared.get(0), -1.0f, 0.0f, 0.0f);
        assertFirstVertex(prepared.get(1), -1.0f, -2.0f, 0.0f);
        assertFirstVertex(prepared.get(2), 0.0f, 0.0f, 3.0f);
    }

    @Test
    void mappedJointDrivesSkinWhileMorphWeightsRemainAnimated() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        GltfGunBodyRenderer renderer = renderer(skinnedMorphAsset(),
                config("{\"bolt\":\"JointTip\"}", "morph", 1.0f), rig);

        rig.getNode("bolt").offsetX = 1.0f;

        // Morph contributes +Y before skinning, while the mapped Bedrock +X becomes glTF -X.
        assertFirstVertex(renderer.preparedAtTime(1.0).getFirst(), -1.0f, 1.0f, 0.0f);
    }

    @Test
    void rejectsInvalidMapEndpointsProtectedSourcesAndOverdeepBedrockPaths() {
        ConvertedGltfAsset asset = singleRigidNodeAsset("BoltVisual");

        assertAll(
                () -> assertRendererRejects(asset, config("{\"bolt\":\"BoltVisual\"}"),
                        duplicateSourceBedrockRig(), "ambiguous"),
                () -> assertRendererRejects(asset, config("{\"missing\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "bolt"), "bedrock"),
                () -> assertRendererRejects(asset, config("{\"bolt\":\"MissingVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "bolt"), "gltf"),
                () -> assertRendererRejects(duplicateTargetNameAsset(), config("{\"bolt\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "bolt"), "ambiguous"),
                () -> assertRendererRejects(asset, config("{\"lefthand_pos\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "lefthand_pos"), "protected"),
                () -> assertRendererRejects(asset, config("{\"righthand_pos\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "righthand_pos"), "protected"),
                () -> assertRendererRejects(asset, config("{\"shell_1\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "shell_1"), "protected"),
                () -> assertRendererRejects(asset, config("{\"scope_pos\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "scope_pos"), "protected"),
                () -> assertRendererRejects(asset, config("{\"muzzle_default\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "muzzle_default"), "protected"),
                () -> assertRendererRejects(asset, config("{\"refit_scope_view\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "refit_scope_view"), "protected"),
                () -> assertRendererRejects(asset, config("{\"bone_33\":\"BoltVisual\"}"),
                        deepBedrockRig(33), "depth")
        );
    }

    @Test
    void rejectsMappedTargetOrAncestorTrsAnimationButAllowsWeightsAndDescendantTrsAnimation() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");

        assertRendererRejects(animatedHierarchyAsset(1, GltfAnimationPath.TRANSLATION, "mapped_trs"),
                config("{\"bolt\":\"BoltVisual\"}", "mapped_trs", 1.0f), rig, "animation");
        assertRendererRejects(animatedHierarchyAsset(1, GltfAnimationPath.SCALE, "mapped_scale"),
                config("{\"bolt\":\"BoltVisual\"}", "mapped_scale", 1.0f), rig, "animation");
        assertRendererRejects(animatedHierarchyAsset(0, GltfAnimationPath.ROTATION, "ancestor_trs"),
                config("{\"bolt\":\"BoltVisual\"}", "ancestor_trs", 1.0f), rig, "animation");

        assertDoesNotThrow(() -> renderer(animatedHierarchyAsset(2, GltfAnimationPath.TRANSLATION, "child_trs"),
                config("{\"bolt\":\"BoltVisual\"}", "child_trs", 1.0f), rig));
        assertDoesNotThrow(() -> renderer(mappedMeshWeightsAsset(),
                config("{\"bolt\":\"BoltVisual\"}", "weights", 1.0f), rig));
    }

    @Test
    void rejectsSourceTargetHierarchyReversal() {
        assertRendererRejects(parentChildAsset(),
                config("{\"bolt\":\"MagazineVisual\",\"magazine\":\"BoltVisual\"}"),
                bedrockRig(BedrockVersion.NEW, "bolt", "magazine"),
                "reverses");
    }

    @Test
    void rejectsMappedSourceBelowProtectedBedrockAncestor() {
        assertRendererRejects(
                singleRigidNodeAsset("BoltVisual"),
                config("{\"bolt\":\"BoltVisual\"}"),
                protectedAncestorBedrockRig(),
                "protected"
        );
    }

    @Test
    void rejectsCyclicBedrockParentPathsForLegacyAndNewModels() {
        ConvertedGltfAsset asset = singleRigidNodeAsset("BoltVisual");

        assertAll(
                () -> assertRendererRejects(asset, config("{\"bolt\":\"BoltVisual\"}"),
                        cyclicBedrockRig(BedrockVersion.LEGACY), "cycle"),
                () -> assertRendererRejects(asset, config("{\"bolt\":\"BoltVisual\"}"),
                        cyclicBedrockRig(BedrockVersion.NEW), "cycle"),
                () -> assertMessageContains(GltfNodeMapBridgeTest::cyclicFunctionalAnchorBedrockRig, "cycle")
        );
    }

    @Test
    void rejectsDynamicFunctionalAnchorsAndAmbiguousAncestors() {
        ConvertedGltfAsset asset = singleRigidNodeAsset("BoltVisual");

        assertAll(
                () -> assertRendererRejects(asset, config("{\"bolt\":\"BoltVisual\"}"),
                        bedrockRig(BedrockVersion.NEW, "text_anchor", "bolt"),
                        Set.of("text_anchor"), "protected"),
                () -> assertRendererRejects(asset, config("{\"bolt\":\"BoltVisual\"}"),
                        duplicateAncestorBedrockRig(), "ambiguous")
        );
    }

    @Test
    void rejectsTargetsThatCannotAffectRenderedGeometry() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");

        assertRendererRejects(
                skinnedMorphAsset(),
                config("{\"bolt\":\"MeshNode\"}"),
                rig,
                "does not affect"
        );
        assertDoesNotThrow(() -> renderer(
                skinnedMorphAsset(),
                config("{\"bolt\":\"JointTip\"}"),
                rig
        ));

        ConvertedGltfAsset unusedJointAsset = skinnedUnusedJointAsset();
        assertRendererRejects(
                unusedJointAsset,
                config("{\"bolt\":\"UnusedJoint\"}"),
                rig,
                "does not affect"
        );
        assertDoesNotThrow(() -> renderer(
                unusedJointAsset,
                config("{\"bolt\":\"UsedJoint\"}"),
                rig
        ));
    }

    @Test
    void unavailableCustomRendererIsRemovedBeforeFallbackRouting() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        rig.setBodyRenderer(new GunBodyRenderer() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public boolean submit(BedrockGunModel bedrockRig, PoseStack poseStack,
                                  net.minecraft.world.item.ItemStack gunItem,
                                  net.minecraft.world.item.ItemDisplayContext transformType,
                                  net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                                  int light, int overlay) {
                throw new AssertionError("an unavailable renderer must not be submitted");
            }
        });

        assertFalse(rig.hasCustomBodyRenderer());
        assertNull(rig.getBodyRenderer());
    }

    @Test
    void rejectsNonFiniteCurrentBedrockPoseBeforePreparingGeometry() {
        BedrockGunModel rig = bedrockRig(BedrockVersion.NEW, "bolt");
        GltfGunBodyRenderer renderer = renderer(singleRigidNodeAsset("BoltVisual"),
                config("{\"bolt\":\"BoltVisual\"}"), rig);

        rig.getNode("bolt").offsetX = Float.NaN;

        assertMessageContains(() -> renderer.preparedAtTime(0.0), "finite");
    }

    @Test
    void poseWorldOverridesMustMatchNodeCountAndBeFinite() {
        ConvertedGltfAsset asset = singleRigidNodeAsset("BoltVisual");
        assertMessageContains(
                () -> GltfGunBodyPose.sample(asset, null, 0.0, new Matrix4f[0]),
                "length"
        );

        Matrix4f nonFinite = new Matrix4f();
        nonFinite.m00(Float.NaN);
        assertMessageContains(
                () -> GltfGunBodyPose.sample(asset, null, 0.0, new Matrix4f[]{nonFinite}),
                "finite"
        );
    }

    @Test
    void nonEmptyMapRequiresBedrockRigButEmptyMapKeepsStaticBakeCache() {
        ConvertedGltfAsset asset = singleRigidNodeAsset("BoltVisual");
        assertRendererRejectsWithoutRig(asset, config("{\"bolt\":\"BoltVisual\"}"), "rig");

        GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(
                MODEL_ID,
                asset,
                new GltfModelManager(),
                0L,
                config(null)
        );

        assertSame(renderer.preparedAtTime(0.0), renderer.preparedAtTime(100.0));
    }

    private static GltfGunBodyRenderer renderer(
            ConvertedGltfAsset asset,
            GunRenderModelConfig config,
            BedrockGunModel rig
    ) {
        return new GltfGunBodyRenderer(MODEL_ID, asset, new GltfModelManager(), 0L, config, rig);
    }

    private static void assertRendererRejects(
            ConvertedGltfAsset asset,
            GunRenderModelConfig config,
            BedrockGunModel rig,
            String messagePart
    ) {
        assertMessageContains(
                () -> new GltfGunBodyRenderer(MODEL_ID, asset, new GltfModelManager(), 0L, config, rig),
                messagePart
        );
    }

    private static void assertRendererRejects(
            ConvertedGltfAsset asset,
            GunRenderModelConfig config,
            BedrockGunModel rig,
            Set<String> additionalProtectedSources,
            String messagePart
    ) {
        assertMessageContains(
                () -> new GltfGunBodyRenderer(
                        MODEL_ID,
                        asset,
                        new GltfModelManager(),
                        0L,
                        config,
                        rig,
                        additionalProtectedSources
                ),
                messagePart
        );
    }

    private static void assertRendererRejectsWithoutRig(
            ConvertedGltfAsset asset,
            GunRenderModelConfig config,
            String messagePart
    ) {
        assertMessageContains(
                () -> new GltfGunBodyRenderer(MODEL_ID, asset, new GltfModelManager(), 0L, config),
                messagePart
        );
    }

    private static void assertMessageContains(Executable executable, String messagePart) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(exception.getMessage().toLowerCase(Locale.ROOT).contains(messagePart));
    }

    private static GunRenderModelConfig config(String nodeMapJson) {
        return config(nodeMapJson, null, 1.0f);
    }

    private static GunRenderModelConfig config(String nodeMapJson, String animation, float scale) {
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"gltf\",\"location\":\"")
                .append(MODEL_ID)
                .append("\",\"scale\":")
                .append(scale);
        if (nodeMapJson != null) {
            json.append(",\"node_map\":").append(nodeMapJson);
        }
        if (animation != null) {
            json.append(",\"animation\":\"").append(animation).append("\"");
        }
        json.append("}");
        return GSON.fromJson(json.toString(), GunRenderModelConfig.class);
    }

    private static BedrockGunModel bedrockRig(BedrockVersion version, String... descendants) {
        StringBuilder bones = new StringBuilder();
        appendBone(bones, "root", null);
        String parent = "root";
        for (String descendant : descendants) {
            appendBone(bones, descendant, parent);
            parent = descendant;
        }

        String json;
        if (version == BedrockVersion.LEGACY) {
            json = """
                    {
                      "format_version": "1.10.0",
                      "geometry.model": {
                        "texturewidth": 16,
                        "textureheight": 16,
                        "visible_bounds_width": 2,
                        "visible_bounds_height": 2,
                        "visible_bounds_offset": [0, 0, 0],
                        "bones": [%s]
                      }
                    }
                    """.formatted(bones);
        } else {
            json = """
                    {
                      "format_version": "1.12.0",
                      "minecraft:geometry": [{
                        "description": {
                          "identifier": "geometry.test",
                          "texture_width": 16,
                          "texture_height": 16,
                          "visible_bounds_width": 2,
                          "visible_bounds_height": 2,
                          "visible_bounds_offset": [0, 0, 0]
                        },
                        "bones": [%s]
                      }]
                    }
                    """.formatted(bones);
        }
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), version);
    }

    private static BedrockGunModel duplicateSourceBedrockRig() {
        String json = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 16,
                      "texture_height": 16,
                      "visible_bounds_width": 2,
                      "visible_bounds_height": 2,
                      "visible_bounds_offset": [0, 0, 0]
                    },
                    "bones": [
                      {"name": "root", "pivot": [0, 24, 0]},
                      {"name": "bolt", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "bolt", "parent": "root", "pivot": [0, 24, 0]}
                    ]
                  }]
                }
                """;
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static BedrockGunModel protectedAncestorBedrockRig() {
        String json = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 16,
                      "texture_height": 16,
                      "visible_bounds_width": 2,
                      "visible_bounds_height": 2,
                      "visible_bounds_offset": [0, 0, 0]
                    },
                    "bones": [
                      {"name": "root", "pivot": [0, 24, 0]},
                      {"name": "lefthand_pos", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "bolt", "parent": "lefthand_pos", "pivot": [0, 24, 0]}
                    ]
                  }]
                }
                """;
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static BedrockGunModel m95SemanticBedrockRig() {
        String json = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 16,
                      "texture_height": 16,
                      "visible_bounds_width": 2,
                      "visible_bounds_height": 2,
                      "visible_bounds_offset": [0, 0, 0]
                    },
                    "bones": [
                      {"name": "root", "pivot": [0, 24, 0]},
                      {"name": "m95_bolt", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "rotate", "parent": "m95_bolt", "pivot": [0, 24, 0]},
                      {"name": "mag_and_bullet", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "lefthand_pos", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "righthand_pos", "parent": "root", "pivot": [0, 24, 0]}
                    ]
                  }]
                }
                """;
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static BedrockGunModel cyclicBedrockRig(BedrockVersion version) {
        String bones = """
                {"name":"root","pivot":[0,24,0]},
                {"name":"bolt","parent":"cycle","pivot":[0,24,0]},
                {"name":"cycle","parent":"bolt","pivot":[0,24,0]}
                """;
        String json;
        if (version == BedrockVersion.LEGACY) {
            json = """
                    {
                      "format_version": "1.10.0",
                      "geometry.model": {
                        "texturewidth": 16,
                        "textureheight": 16,
                        "visible_bounds_width": 2,
                        "visible_bounds_height": 2,
                        "visible_bounds_offset": [0, 0, 0],
                        "bones": [%s]
                      }
                    }
                    """.formatted(bones);
        } else {
            json = """
                    {
                      "format_version": "1.12.0",
                      "minecraft:geometry": [{
                        "description": {
                          "identifier": "geometry.test",
                          "texture_width": 16,
                          "texture_height": 16,
                          "visible_bounds_width": 2,
                          "visible_bounds_height": 2,
                          "visible_bounds_offset": [0, 0, 0]
                        },
                        "bones": [%s]
                      }]
                    }
                    """.formatted(bones);
        }
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), version);
    }

    private static BedrockGunModel duplicateAncestorBedrockRig() {
        String json = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 16,
                      "texture_height": 16,
                      "visible_bounds_width": 2,
                      "visible_bounds_height": 2,
                      "visible_bounds_offset": [0, 0, 0]
                    },
                    "bones": [
                      {"name": "root", "pivot": [0, 24, 0]},
                      {"name": "carrier", "parent": "root", "pivot": [0, 24, 0]},
                      {"name": "bolt", "parent": "carrier", "pivot": [0, 24, 0]},
                      {"name": "carrier", "parent": "root", "pivot": [0, 24, 0]}
                    ]
                  }]
                }
                """;
        return new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static void cyclicFunctionalAnchorBedrockRig() {
        String json = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {
                      "identifier": "geometry.test",
                      "texture_width": 16,
                      "texture_height": 16,
                      "visible_bounds_width": 2,
                      "visible_bounds_height": 2,
                      "visible_bounds_offset": [0, 0, 0]
                    },
                    "bones": [
                      {"name": "root", "pivot": [0, 24, 0]},
                      {"name": "iron_view", "parent": "cycle", "pivot": [0, 24, 0]},
                      {"name": "cycle", "parent": "iron_view", "pivot": [0, 24, 0]}
                    ]
                  }]
                }
                """;
        new BedrockGunModel(GSON.fromJson(json, BedrockModelPOJO.class), BedrockVersion.NEW);
    }

    private static BedrockGunModel deepBedrockRig(int depth) {
        String[] descendants = new String[depth];
        for (int index = 0; index < depth; index++) {
            descendants[index] = "bone_" + (index + 1);
        }
        return bedrockRig(BedrockVersion.NEW, descendants);
    }

    private static void appendBone(StringBuilder bones, String name, String parent) {
        if (!bones.isEmpty()) {
            bones.append(',');
        }
        bones.append("{\"name\":\"").append(name).append("\",\"pivot\":[0,24,0]");
        if (parent != null) {
            bones.append(",\"parent\":\"").append(parent).append("\"");
        }
        bones.append('}');
    }

    private static ConvertedGltfAsset singleRigidNodeAsset(String nodeName) {
        GltfMeshPrimitive primitive = rigidPrimitive(List.of());
        return asset(
                List.of(new GltfNode(nodeName, GltfNodeTransform.identity(), new int[0], 0, -1)),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[0])),
                List.of(),
                List.of(),
                List.of(new float[0]),
                List.of(new float[0]),
                new int[]{0}
        );
    }

    private static ConvertedGltfAsset duplicateTargetNameAsset() {
        GltfMeshPrimitive primitive = rigidPrimitive(List.of());
        return asset(
                List.of(
                        new GltfNode("BoltVisual", GltfNodeTransform.identity(), new int[0], 0, -1),
                        new GltfNode("BoltVisual", GltfNodeTransform.identity(), new int[0], -1, -1)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[0])),
                List.of(),
                List.of(),
                List.of(new float[0], new float[0]),
                List.of(new float[0]),
                new int[]{0, 1}
        );
    }

    private static ConvertedGltfAsset parentChildAsset() {
        GltfMeshPrimitive primitive = rigidPrimitive(List.of());
        return asset(
                List.of(
                        new GltfNode("BoltVisual", GltfNodeTransform.identity(), new int[]{1}, -1, -1),
                        new GltfNode("MagazineVisual", GltfNodeTransform.identity(), new int[0], 0, -1)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[0])),
                List.of(),
                List.of(),
                List.of(new float[0], new float[0]),
                List.of(new float[0]),
                new int[]{0}
        );
    }

    private static ConvertedGltfAsset m95SemanticThreeMapAsset() {
        GltfMeshPrimitive boltAssembly = rigidPrimitive(List.of());
        GltfMeshPrimitive boltHandle = rigidPrimitive(List.of());
        GltfMeshPrimitive magazine = rigidPrimitive(List.of());
        return asset(
                List.of(
                        new GltfNode("BodyVisual", GltfNodeTransform.identity(), new int[]{1, 3}, -1, -1),
                        new GltfNode("BoltAssemblyVisual", GltfNodeTransform.identity(), new int[]{2}, 0, -1),
                        new GltfNode("BoltHandleVisual", GltfNodeTransform.identity(), new int[0], 1, -1),
                        new GltfNode("MagazineVisual", GltfNodeTransform.identity(), new int[0], 2, -1)
                ),
                List.of(
                        new GltfMesh(List.of(boltAssembly)),
                        new GltfMesh(List.of(boltHandle)),
                        new GltfMesh(List.of(magazine))
                ),
                List.of(
                        renderMesh("bolt_assembly", boltAssembly, new float[0]),
                        renderMesh("bolt_handle", boltHandle, new float[0]),
                        renderMesh("magazine", magazine, new float[0])
                ),
                List.of(),
                List.of(),
                List.of(new float[0], new float[0], new float[0], new float[0]),
                List.of(new float[0], new float[0], new float[0]),
                new int[]{0}
        );
    }

    private static ConvertedGltfAsset skinnedMorphAsset() {
        GltfMorphTarget target = new GltfMorphTarget(
                new float[]{
                        0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f
                },
                new float[0],
                new float[0]
        );
        GltfMeshPrimitive primitive = skinnedPrimitive(List.of(target));
        GltfAnimationClip clip = clip("morph", new GltfAnimationChannel(
                1,
                GltfAnimationSampler.weights(
                        1,
                        GltfInterpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new float[]{0.0f, 1.0f}
                )
        ));
        return asset(
                List.of(
                        new GltfNode("JointTip", GltfNodeTransform.identity(), new int[0], -1, -1),
                        new GltfNode("MeshNode", GltfNodeTransform.identity(), new int[0], 0, 0)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[]{0.0f})),
                List.of(new GltfSkin(new int[]{0}, List.of(new Matrix4f()))),
                List.of(clip),
                List.of(new float[0], new float[]{0.0f}),
                List.of(new float[]{0.0f}),
                new int[]{0, 1}
        );
    }

    private static ConvertedGltfAsset skinnedUnusedJointAsset() {
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                trianglePositions(),
                new float[0],
                new float[0],
                new int[]{
                        1, 0, 0, 0,
                        1, 0, 0, 0,
                        1, 0, 0, 0
                },
                new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f
                },
                List.of(),
                new GltfMaterialReference("default")
        );
        return asset(
                List.of(
                        new GltfNode("UnusedJoint", GltfNodeTransform.identity(), new int[0], -1, -1),
                        new GltfNode("UsedJoint", GltfNodeTransform.identity(), new int[0], -1, -1),
                        new GltfNode("MeshNode", GltfNodeTransform.identity(), new int[0], 0, 0)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[0])),
                List.of(new GltfSkin(
                        new int[]{0, 1},
                        List.of(new Matrix4f(), new Matrix4f())
                )),
                List.of(),
                List.of(new float[0], new float[0], new float[0]),
                List.of(new float[0]),
                new int[]{0, 1, 2}
        );
    }

    private static ConvertedGltfAsset animatedHierarchyAsset(
            int targetNode,
            GltfAnimationPath path,
            String animationName
    ) {
        GltfMeshPrimitive primitive = rigidPrimitive(List.of());
        GltfAnimationSampler sampler = switch (path) {
            case TRANSLATION -> GltfAnimationSampler.translation(
                    GltfInterpolation.LINEAR,
                    new float[]{0.0f, 1.0f},
                    new float[]{0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f}
            );
            case ROTATION -> GltfAnimationSampler.rotation(
                    GltfInterpolation.LINEAR,
                    new float[]{0.0f, 1.0f},
                    new float[]{0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.70710677f, 0.70710677f}
            );
            case SCALE -> GltfAnimationSampler.scale(
                    GltfInterpolation.LINEAR,
                    new float[]{0.0f, 1.0f},
                    new float[]{1.0f, 1.0f, 1.0f, 2.0f, 1.0f, 1.0f}
            );
            case WEIGHTS -> throw new IllegalArgumentException("use mappedMeshWeightsAsset for weights");
        };
        return asset(
                List.of(
                        new GltfNode("Armature", GltfNodeTransform.identity(), new int[]{1}, -1, -1),
                        new GltfNode("BoltVisual", GltfNodeTransform.identity(), new int[]{2}, -1, -1),
                        new GltfNode("BoltChild", GltfNodeTransform.identity(), new int[0], 0, -1)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[0])),
                List.of(),
                List.of(clip(animationName, new GltfAnimationChannel(targetNode, sampler))),
                List.of(new float[0], new float[0], new float[0]),
                List.of(new float[0]),
                new int[]{0}
        );
    }

    private static ConvertedGltfAsset mappedMeshWeightsAsset() {
        GltfMorphTarget target = new GltfMorphTarget(
                new float[]{
                        1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f
                },
                new float[0],
                new float[0]
        );
        GltfMeshPrimitive primitive = rigidPrimitive(List.of(target));
        GltfAnimationClip clip = clip("weights", new GltfAnimationChannel(
                0,
                GltfAnimationSampler.weights(
                        1,
                        GltfInterpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new float[]{0.0f, 1.0f}
                )
        ));
        return asset(
                List.of(new GltfNode("BoltVisual", GltfNodeTransform.identity(), new int[0], 0, -1)),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(renderMesh("mesh", primitive, new float[]{0.0f})),
                List.of(),
                List.of(clip),
                List.of(new float[]{0.0f}),
                List.of(new float[]{0.0f}),
                new int[]{0}
        );
    }

    private static ConvertedGltfAsset asset(
            List<GltfNode> nodes,
            List<GltfMesh> meshes,
            List<GltfRenderMesh> renderMeshes,
            List<GltfSkin> skins,
            List<GltfAnimationClip> animations,
            List<float[]> nodeMorphWeights,
            List<float[]> meshDefaultMorphWeights,
            int[] roots
    ) {
        return new ConvertedGltfAsset(
                new GltfScene(nodes, meshes, skins, roots),
                renderMeshes,
                List.of(),
                List.of(),
                animations,
                nodeMorphWeights,
                meshDefaultMorphWeights,
                List.of(new GltfSceneData("scene", roots)),
                0,
                0
        );
    }

    private static GltfAnimationClip clip(String name, GltfAnimationChannel channel) {
        return new GltfAnimationClip(name, 1.0f, new GltfAnimation(List.of(channel)));
    }

    private static GltfRenderMesh renderMesh(String name, GltfMeshPrimitive primitive, float[] defaultWeights) {
        return new GltfRenderMesh(
                name,
                List.of(new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, new float[0], new float[0], -1)),
                defaultWeights
        );
    }

    private static GltfMeshPrimitive rigidPrimitive(List<GltfMorphTarget> morphTargets) {
        return new GltfMeshPrimitive(
                trianglePositions(),
                new float[0],
                new float[0],
                new int[0],
                new float[0],
                morphTargets,
                new GltfMaterialReference("default")
        );
    }

    private static GltfMeshPrimitive skinnedPrimitive(List<GltfMorphTarget> morphTargets) {
        return new GltfMeshPrimitive(
                trianglePositions(),
                new float[0],
                new float[0],
                new int[]{
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0
                },
                new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f
                },
                morphTargets,
                new GltfMaterialReference("default")
        );
    }

    private static float[] trianglePositions() {
        return new float[]{
                0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
        };
    }

    private static void assertFirstVertex(GltfGunBodyRenderer.PreparedPrimitive primitive, float x, float y, float z) {
        assertArrayEquals(new float[]{x, y, z}, firstVertex(primitive), EPSILON);
    }

    private static float[] firstVertex(GltfGunBodyRenderer.PreparedPrimitive primitive) {
        return Arrays.copyOf(primitive.geometry().positions(), 3);
    }

    private static void assertTranslation(Matrix4fc matrix, float x, float y, float z) {
        Vector3f translation = matrix.getTranslation(new Vector3f());
        assertArrayEquals(new float[]{x, y, z}, new float[]{translation.x, translation.y, translation.z}, EPSILON);
    }

    private static void assertMatrixEquals(Matrix4fc expected, Matrix4fc actual) {
        assertArrayEquals(expected.get(new float[16]), actual.get(new float[16]), EPSILON);
    }
}
