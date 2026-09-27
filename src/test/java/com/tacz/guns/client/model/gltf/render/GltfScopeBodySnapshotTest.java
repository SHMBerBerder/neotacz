package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.gltf.convert.*;
import com.tacz.guns.client.model.gltf.runtime.*;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GltfScopeBodySnapshotTest {
    @Test
    void capabilityUsesSelectedPrimitiveReferencesIncludingHiddenGeometryNotUnusedMaterials() {
        assertTrue(GltfGunBodyRenderer.supportsScopeStencil(asset(0, GltfAlphaMode.OPAQUE, false)));
        assertTrue(GltfGunBodyRenderer.supportsScopeStencil(asset(0, GltfAlphaMode.MASK, false)));
        assertTrue(GltfGunBodyRenderer.supportsScopeStencil(asset(-1, GltfAlphaMode.BLEND, false)));
        assertFalse(GltfGunBodyRenderer.supportsScopeStencil(asset(0, GltfAlphaMode.BLEND, false)));
        assertFalse(GltfGunBodyRenderer.supportsScopeStencil(asset(0, GltfAlphaMode.BLEND, true)));
    }

    @Test
    void snapshotKeepsOriginalRenderTypePoseNormalLightOverlayAndDrawOrder() {
        GltfModelManager manager = new GltfModelManager();
        RenderType first = renderType("first", GltfPbrAlphaMode.OPAQUE);
        RenderType second = renderType("second", GltfPbrAlphaMode.MASK);
        GltfPreparedGeometry geometry = geometry();
        List<GltfGunBodyRenderer.Submission> source = new ArrayList<>(List.of(
                new GltfGunBodyRenderer.Submission(0, geometry, first, GltfPbrAlphaMode.OPAQUE, 0),
                new GltfGunBodyRenderer.Submission(1, geometry, second, GltfPbrAlphaMode.MASK, 0)));
        PoseStack pose = new PoseStack();
        pose.translate(1, 2, 3);
        pose.scale(-2, 3, 4);
        Matrix4f expectedPose = new Matrix4f(pose.last().pose());
        Matrix3f expectedNormal = new Matrix3f(pose.last().normal());
        var snapshot = GltfGunBodyRenderer.snapshotScopeBody(source, pose, 0x00F000F0, 37, manager, 0);
        source.clear();
        pose.last().pose().zero();
        pose.last().normal().zero();
        List<RenderType> types = new ArrayList<>();
        int[] vertices = {0}, normals = {0}, lights = {0}, overlays = {0};
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addVertex" -> {
                            assertInstanceOf(PoseStack.Pose.class, args[0]);
                            assertTrue(expectedPose.equals(((PoseStack.Pose) args[0]).pose(), 1e-6F));
                            vertices[0]++;
                        }
                        case "setNormal" -> {
                            assertInstanceOf(PoseStack.Pose.class, args[0]);
                            assertTrue(expectedNormal.equals(((PoseStack.Pose) args[0]).normal(), 1e-6F));
                            normals[0]++;
                        }
                        case "setLight" -> { assertEquals(0x00F000F0, args[0]); lights[0]++; }
                        case "setOverlay" -> { assertEquals(37, args[0]); overlays[0]++; }
                    }
                    return proxy;
                });
        snapshot.emit(type -> { types.add(type); return consumer; });
        assertEquals(List.of(first, second), types);
        assertEquals(6, vertices[0]);
        assertEquals(6, normals[0]);
        assertEquals(6, lights[0]);
        assertEquals(6, overlays[0]);
        manager.clearCache();
        assertFalse(snapshot.isAvailable());
        snapshot.emit(type -> { fail("Retired GPU materials must not be requested"); return null; });
    }

    @Test
    void hiddenFrameIsHandledWithoutGpuDrawsAndDefaultBackendDoesNotOptIn() {
        GltfModelManager manager = new GltfModelManager();
        var empty = GltfGunBodyRenderer.snapshotScopeBody(List.of(), new PoseStack(), 17, 23, manager, 0);
        assertTrue(empty.isAvailable());
        empty.emit(type -> { fail("Hidden frame must not invent geometry"); return null; });
        GunBodyRenderer ordinary = (rig, pose, gun, context, collector, light, overlay) -> true;
        assertNull(ordinary.prepareScopeBody(null, null, null, null, 0, 0));
    }

    private static RenderType renderType(String name, GltfPbrAlphaMode mode) {
        return RenderType.create(name, RenderSetup.builder(GltfPbrRenderPipelines.pipeline(mode, false)).createRenderSetup());
    }

    private static GltfPreparedGeometry geometry() {
        var primitive = primitive();
        var render = new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, new float[0], new float[0], -1);
        return GltfPreparedGeometry.create(render, GltfVertexDeformer.deform(primitive, null, null, new float[0]),
                new Matrix4f(), false);
    }

    private static GltfMeshPrimitive primitive() {
        return new GltfMeshPrimitive(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[0], new int[0], new float[0],
                List.of(), new GltfMaterialReference("default"));
    }

    private static ConvertedGltfAsset asset(int reference, GltfAlphaMode alpha, boolean hidden) {
        GltfMeshPrimitive primitive = primitive();
        var selected = new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, new float[0], new float[0], reference);
        var unused = new GltfRenderPrimitive(primitive, new int[]{0, 1, 2}, new float[0], new float[0], 1);
        GltfScene scene = new GltfScene(List.of(new GltfNode("root", hidden
                ? new GltfNodeTransform(new Vector3f(), new Quaternionf(), new Vector3f())
                : GltfNodeTransform.identity(), new int[0], 0, -1)),
                List.of(new GltfMesh(List.of(primitive)), new GltfMesh(List.of(primitive))), List.of(), new int[]{0});
        return new ConvertedGltfAsset(scene,
                List.of(new GltfRenderMesh("selected", List.of(selected), new float[0]),
                        new GltfRenderMesh("unused", List.of(unused), new float[0])),
                List.of(material(alpha), material(GltfAlphaMode.BLEND)), List.of(), List.of(),
                List.of(new float[0]), List.of(new float[0], new float[0]),
                List.of(new GltfSceneData("scene", new int[]{0})), 0, 0);
    }

    private static GltfPbrMaterialData material(GltfAlphaMode alpha) {
        return new GltfPbrMaterialData("material", new float[]{1, 1, 1, 1}, 1, 1, new float[3], 1, 1,
                alpha, .5F, true, null, null, null, null, null);
    }
}
