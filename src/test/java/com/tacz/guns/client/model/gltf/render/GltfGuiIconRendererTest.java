package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
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
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GltfGuiIconRendererTest {
    private static final float EPSILON = 2.0E-5f;
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath("test", "models/gltf/icon.glb");
    private static final Identifier OLD_ICON = Identifier.fromNamespaceAndPath("test", "textures/old_rifle.png");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer()).create();

    @Test
    void staticIconReusesPreparedGeometryAndIdentityDoesNotOwnTheRenderer() {
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of()), "", null, new GltfModelManager());
        assertSame(renderer.preparedFrameAtTime(0), renderer.guiFrame().prepared());
        assertSame(renderer.guiFrame(), renderer.guiFrame());
        assertSame(renderer.guiIconIdentity(), renderer.guiIconIdentity());
        assertEquals(Object.class, renderer.guiIconIdentity().getClass());
        assertNotSame(renderer, renderer.guiIconIdentity());
        Matrix4f expected = renderer.guiFrame().transform();
        renderer.guiFrame().transform().zero();
        assertEquals(expected, renderer.guiFrame().transform());
    }

    @Test
    void iconIgnoresLiveMappedBonesAndBedrockRootWithoutChangingEither() {
        BedrockGunModel rig = rig();
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of()),
                ",\"node_map\":{\"moving\":\"visual\"}", rig, new GltfModelManager());
        float[] iconPositions = renderer.guiFrame().prepared().primitives().getFirst().geometry().positions();
        rig.getNode("moving").offsetX = 3;
        rig.getRootNode().offsetY = 7;
        assertNotEquals(iconPositions[0], renderer.preparedAtTime(0).getFirst().geometry().positions()[0]);
        assertArrayEquals(iconPositions, renderer.guiFrame().prepared().primitives().getFirst().geometry().positions());
        assertEquals(3, rig.getNode("moving").offsetX);
        assertEquals(7, rig.getRootNode().offsetY);
    }

    @Test
    void iconUsesAuthoredDefaultPoseRatherThanAnimationFirstKey() {
        GltfAnimationSampler sampler = GltfAnimationSampler.translation(GltfInterpolation.LINEAR,
                new float[]{0, 1}, new float[]{10, 0, 0, 20, 0, 0});
        GltfAnimationClip animation = new GltfAnimationClip("moving", 1, new GltfAnimation(List.of(
                new GltfAnimationChannel(0, sampler))));
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of(animation)),
                ",\"animation\":\"moving\"", null, new GltfModelManager());
        assertEquals(10, renderer.preparedAtTime(0).getFirst().geometry().positions()[0], EPSILON);
        assertEquals(0, renderer.guiFrame().prepared().primitives().getFirst().geometry().positions()[0], EPSILON);
    }

    @Test
    void fixedViewBoundsFitTheSlotForTranslatedMirroredAndNonuniformAssets() {
        GltfNodeTransform transform = new GltfNodeTransform(new Vector3f(123, -234, 5),
                new Quaternionf().rotationXYZ(0.3f, -0.7f, 0.2f), new Vector3f(-4, 2, 0.5f));
        GltfGunBodyRenderer renderer = renderer(asset(transform, List.of()), "", null, new GltfModelManager());
        GltfGuiIconRenderer.Frame icon = renderer.guiFrame();
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Matrix4f fit = icon.transform();
        icon.prepared().primitives().forEach(primitive -> primitive.geometry().visitPositions(point -> {
            Vector3f projected = new Vector3f(point).mulPosition(fit);
            assertTrue(projected.isFinite());
            minimum.min(projected);
            maximum.max(projected);
        }));
        assertTrue(minimum.x >= 0.0499f && minimum.y >= 0.0499f && minimum.z >= 0.0499f);
        assertTrue(maximum.x <= 0.9501f && maximum.y <= 0.9501f && maximum.z <= 0.9501f);
        assertEquals(1, minimum.x + maximum.x, EPSILON);
        assertEquals(1, minimum.y + maximum.y, EPSILON);
        assertEquals(0.9f, Math.max(maximum.x - minimum.x,
                Math.max(maximum.y - minimum.y, maximum.z - minimum.z)), EPSILON);

        PoseStack vanilla = new PoseStack();
        net.minecraft.client.resources.model.cuboid.ItemTransform.NO_TRANSFORM.apply(false, vanilla.last());
        vanilla.mulPose(fit);
        minimum.set(Float.POSITIVE_INFINITY);
        maximum.set(Float.NEGATIVE_INFINITY);
        icon.prepared().primitives().forEach(primitive -> primitive.geometry().visitPositions(point -> {
            Vector3f projected = new Vector3f(point).mulPosition(vanilla.last().pose());
            minimum.min(projected);
            maximum.max(projected);
        }));
        assertTrue(minimum.x >= -0.4501f && minimum.y >= -0.4501f && minimum.z >= -0.4501f);
        assertTrue(maximum.x <= 0.4501f && maximum.y <= 0.4501f && maximum.z <= 0.4501f);
        assertEquals(0, minimum.x + maximum.x, EPSILON);
        assertEquals(0, minimum.y + maximum.y, EPSILON);
    }

    @Test
    void invalidDefaultIconDoesNotRejectGeometryValidAfterLiveRigScaling() {
        BedrockGunModel rig = rig();
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of(), 0.00002f),
                ",\"node_map\":{\"moving\":\"visual\"}", rig, new GltfModelManager());
        assertNull(renderer.guiFrame());
        assertFalse(renderer.isGuiIconAvailable());
        assertTrue(renderer.isAvailable());
        rig.getNode("moving").xScale = rig.getNode("moving").yScale = rig.getNode("moving").zScale = 0.01f;
        assertFalse(renderer.preparedAtTime(0).isEmpty());
    }

    @Test
    void metadataSnapshotChangesIdentityAtReadinessAndNeverShowsAnOldMeshIcon() {
        Object pendingIdentity = new Object();
        GltfGuiIconRenderer.Snapshot pending = GltfGuiIconRenderer.capture(new GunDisplayInstance.GuiModelSnapshot(
                pendingIdentity, null, OLD_ICON, true));
        assertSame(pendingIdentity, pending.cacheIdentity());
        assertNull(pending.renderer());
        assertEquals(MissingTextureAtlasSprite.getLocation(), pending.fallbackTexture());
        GltfGuiIconRenderer.Snapshot bedrock = GltfGuiIconRenderer.capture(new GunDisplayInstance.GuiModelSnapshot(
                pendingIdentity, null, OLD_ICON, false));
        assertEquals(OLD_ICON, bedrock.fallbackTexture());

        BedrockGunModel rig = rig();
        GltfModelManager manager = new GltfModelManager();
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of()), "", rig, manager);
        rig.setBodyRenderer(renderer);
        var metadata = new GunDisplayInstance.GuiModelSnapshot(pendingIdentity, rig, OLD_ICON, true);
        GltfGuiIconRenderer.Snapshot ready = GltfGuiIconRenderer.capture(metadata);
        assertNotSame(pending.cacheIdentity(), ready.cacheIdentity());
        assertSame(renderer.guiIconIdentity(), ready.cacheIdentity());
        assertSame(renderer, ready.renderer());
        manager.clearCache();
        assertNull(GltfGuiIconRenderer.capture(metadata).renderer());
        assertFalse(GltfGuiIconRenderer.submit(ready, new PoseStack(), null, 0, 0));
        assertEquals(MissingTextureAtlasSprite.getLocation(), ready.fallbackTexture());
    }

    @Test
    void attachmentSnapshotUsesOnlyReadinessTokensAndRetiresWithItsResourceGeneration() {
        Object pendingIdentity = new Object();
        GltfGuiIconRenderer.Snapshot pending = GltfGuiIconRenderer.captureAttachment(
                new ClientAttachmentIndex.GuiMeshSnapshot(pendingIdentity, null));
        assertSame(pendingIdentity, pending.cacheIdentity());
        assertNull(pending.renderer());
        assertEquals(MissingTextureAtlasSprite.getLocation(), pending.fallbackTexture());

        GltfModelManager manager = new GltfModelManager();
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of()), "", null, manager);
        var metadata = new ClientAttachmentIndex.GuiMeshSnapshot(pendingIdentity, renderer);
        GltfGuiIconRenderer.Snapshot ready = GltfGuiIconRenderer.captureAttachment(metadata);
        assertSame(renderer, ready.renderer());
        assertSame(renderer.guiIconIdentity(), ready.cacheIdentity());
        assertEquals(Object.class, ready.cacheIdentity().getClass());
        assertNotSame(pendingIdentity, ready.cacheIdentity());
        manager.clearCache();
        GltfGuiIconRenderer.Snapshot retired = GltfGuiIconRenderer.captureAttachment(metadata);
        assertSame(pendingIdentity, retired.cacheIdentity());
        assertNull(retired.renderer());
        assertEquals(MissingTextureAtlasSprite.getLocation(), retired.fallbackTexture());
        assertFalse(GltfGuiIconRenderer.submit(ready, new PoseStack(), null, 0, 0));
    }

    @Test
    void positionVisitorDoesNotExposeOrMutateGeometryArrays() {
        GltfGunBodyRenderer renderer = renderer(asset(GltfNodeTransform.identity(), List.of()), "", null, new GltfModelManager());
        GltfPreparedGeometry geometry = renderer.preparedAtTime(0).getFirst().geometry();
        float[] expected = geometry.positions();
        int[] count = {0};
        geometry.visitPositions(point -> {
            count[0]++;
            ((Vector3f) point).set(1_000);
        });
        assertEquals(geometry.vertexCount(), count[0]);
        assertArrayEquals(expected, geometry.positions());
    }

    @Test
    void staticIconKeepsDefaultMorphWeightsAndSkinTransforms() {
        GltfMeshPrimitive runtime = new GltfMeshPrimitive(new float[]{0, 0, 0, 8, 0, 0, 0, 1, 1},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[0], new int[12],
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0},
                List.of(new GltfMorphTarget(new float[]{0, 2, 0, 0, 2, 0, 0, 2, 0}, new float[0], new float[0])),
                new GltfMaterialReference("default"));
        GltfRenderPrimitive render = new GltfRenderPrimitive(runtime, new int[]{0, 1, 2}, new float[0], new float[0], -1);
        GltfScene scene = new GltfScene(List.of(
                new GltfNode("visual", GltfNodeTransform.identity(), new int[0], 0, 0),
                new GltfNode("joint", new GltfNodeTransform(new Vector3f(3, 0, 0), new Quaternionf(), new Vector3f(1)),
                        new int[0], -1, -1)), List.of(new GltfMesh(List.of(runtime))),
                List.of(new GltfSkin(new int[]{1}, List.of(new Matrix4f()))), new int[]{0, 1});
        ConvertedGltfAsset asset = new ConvertedGltfAsset(scene,
                List.of(new GltfRenderMesh("mesh", List.of(render), new float[]{0.5f})), List.of(), List.of(), List.of(),
                List.of(new float[0], new float[0]), List.of(new float[]{0.5f}),
                List.of(new GltfSceneData("scene", new int[]{0, 1})), 0, 0);
        GltfGunBodyRenderer renderer = renderer(asset, "", null, new GltfModelManager());
        float[] positions = renderer.guiFrame().prepared().primitives().getFirst().geometry().positions();
        assertArrayEquals(new float[]{3, 1, 0, 11, 1, 0, 3, 2, 1}, positions, EPSILON);
        assertSame(renderer.preparedFrameAtTime(0), renderer.guiFrame().prepared());
    }

    private static GltfGunBodyRenderer renderer(ConvertedGltfAsset asset, String extra, BedrockGunModel rig, GltfModelManager manager) {
        GunRenderModelConfig config = GSON.fromJson("{\"type\":\"gltf\",\"location\":\"" + MODEL + "\"" + extra + "}",
                GunRenderModelConfig.class);
        return new GltfGunBodyRenderer(MODEL, asset, manager, manager.getGeneration(), config, rig);
    }

    private static ConvertedGltfAsset asset(GltfNodeTransform transform, List<GltfAnimationClip> animations) {
        return asset(transform, animations, 1);
    }

    private static ConvertedGltfAsset asset(GltfNodeTransform transform, List<GltfAnimationClip> animations, float normalMagnitude) {
        GltfMeshPrimitive runtime = new GltfMeshPrimitive(new float[]{0, 0, 0, 8, 0, 0, 0, 1, 1},
                new float[]{0, 0, normalMagnitude, 0, 0, normalMagnitude, 0, 0, normalMagnitude},
                new float[0], new int[0], new float[0],
                List.of(), new GltfMaterialReference("default"));
        GltfRenderPrimitive render = new GltfRenderPrimitive(runtime, new int[]{0, 1, 2}, new float[0], new float[0], -1);
        GltfScene scene = new GltfScene(List.of(new GltfNode("visual", transform, new int[0], 0, -1)),
                List.of(new GltfMesh(List.of(runtime))), List.of(), new int[]{0});
        return new ConvertedGltfAsset(scene, List.of(new GltfRenderMesh("mesh", List.of(render), new float[0])),
                List.of(), List.of(), animations, List.of(new float[0]), List.of(new float[0]),
                List.of(new GltfSceneData("scene", new int[]{0})), 0, 0);
    }

    private static BedrockGunModel rig() {
        return new BedrockGunModel(GSON.fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"moving","parent":"root","pivot":[0,24,0]}]}]}
                """, BedrockModelPOJO.class), BedrockVersion.NEW);
    }
}
