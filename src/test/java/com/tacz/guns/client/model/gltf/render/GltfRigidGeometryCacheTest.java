package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.convert.GltfSceneData;
import com.tacz.guns.client.model.gltf.runtime.GltfMaterialReference;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMorphTarget;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfRigidGeometryCacheTest {
    private static final float EPSILON = 2.0E-5f;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer()).create();

    @Test
    void staticAuthoredNormalGeometryStaysWorldBakedAndReusesItsPreparedFrame() throws Exception {
        GltfRenderPrimitive primitive = mesh(true, false, false);
        GltfNode transformed = new GltfNode("static_panel", new GltfNodeTransform(
                new org.joml.Vector3f(2, -3, 1), new Quaternionf().rotationY(0.7f),
                new org.joml.Vector3f(-2, 3, 0.5f)), new int[0], 0, -1);
        Identifier id = Identifier.fromNamespaceAndPath("test", "models/gltf/static_panel.gltf");
        GunRenderModelConfig config = GSON.fromJson("{\"type\":\"gltf\",\"location\":\"" + id + "\"}",
                GunRenderModelConfig.class);
        GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(id,
                asset(List.of(transformed), List.of(primitive), List.of(), new float[0]),
                new GltfModelManager(), 0, config);
        List<GltfGunBodyRenderer.PreparedPrimitive> first = renderer.preparedAtTime(0);
        assertSame(first, renderer.preparedAtTime(1000));
        assertSame(first.getFirst().geometry(), renderer.preparedAtTime(1000).getFirst().geometry());
        assertNull(storage(first.getFirst().geometry(), "localToWorld"));
        Matrix4f world = transformed.localTransform().toMatrix();
        GltfPreparedGeometry expected = GltfPreparedGeometry.create(primitive,
                GltfVertexDeformer.deform(primitive.runtimePrimitive(), null, null, new float[0]),
                world, GltfPreparedGeometry.requiresWindingReversal(world));
        assertGeometryEquals(expected, first.getFirst().geometry());
    }

    @Test
    void twoUnrelatedRigsShareExpandedArraysAcrossMovingFramesAndKeepOldSnapshots() throws Exception {
        for (String name : List.of("sliding_panel", "rotating_valve")) {
            Fixture fixture = fixture(name, mesh(true, false, false), false);
            GltfPreparedGeometry first = frame(fixture);
            float[] firstPositions = first.positions();
            float[] firstNormals = first.normals();
            CapturingConsumer oldEmission = new CapturingConsumer();
            first.emit(new PoseStack().last(), oldEmission, 0, 0);
            fixture.part().offsetX = 2;
            fixture.part().additionalQuaternion = new Quaternionf().rotationXYZ(0.2f, -0.4f, 0.7f);
            GltfPreparedGeometry moved = frame(fixture);
            for (String array : List.of("positions", "normals", "texCoords", "colors")) {
                assertSame(storage(first, array), storage(moved, array));
            }
            assertNotSame(first, moved);
            assertArrayEquals(firstPositions, first.positions());
            assertArrayEquals(firstNormals, first.normals());
            CapturingConsumer retainedEmission = new CapturingConsumer();
            first.emit(new PoseStack().last(), retainedEmission, 0, 0);
            assertArrayEquals(oldEmission.values(), retainedEmission.values());
            assertFalse(java.util.Arrays.equals(firstPositions, moved.positions()));
            float[] exposed = moved.positions();
            exposed[0] = 1000;
            assertArrayEquals(reference(fixture).positions(), moved.positions(), EPSILON);
        }
    }

    @Test
    void nonuniformNegativeTransformsMatchCpuBakeIncludingUvsColorsAndEmission() {
        Fixture fixture = fixture("reflecting_panel", mesh(true, false, false), false);
        fixture.part().xScale = -2;
        fixture.part().yScale = 3;
        fixture.part().zScale = 0.5f;
        fixture.part().offsetY = 0.7f;
        fixture.part().additionalQuaternion = new Quaternionf().rotationXYZ(0.3f, -0.8f, 0.5f);
        GltfPreparedGeometry cached = frame(fixture);
        GltfPreparedGeometry cpu = reference(fixture);
        assertGeometryEquals(cpu, cached);
        PoseStack outer = new PoseStack();
        outer.translate(2, -3, 1);
        outer.mulPose(new Quaternionf().rotationXYZ(-0.2f, 0.4f, 0.1f));
        outer.scale(0.7f, 1.3f, 1.1f);
        CapturingConsumer expected = new CapturingConsumer();
        CapturingConsumer actual = new CapturingConsumer();
        cpu.emit(outer.last(), expected, 0x00F000F0, 7 | (11 << 16));
        cached.emit(outer.last(), actual, 0x00F000F0, 7 | (11 << 16));
        assertArrayEquals(expected.values(), actual.values(), EPSILON);
    }

    @Test
    void multiMeshAndRepeatedMeshNodesCacheByPrimitiveWithoutCrossContaminatingTransforms() throws Exception {
        BedrockGunModel rig = rig();
        GltfRenderPrimitive shared = mesh(true, false, false);
        GltfRenderPrimitive other = mesh(true, false, false);
        List<GltfNode> nodes = List.of(node("moving", 0, -1), node("fixed", 0, -1), node("other", 1, -1));
        GltfGunBodyRenderer renderer = renderer("multiple_meshes", asset(nodes, List.of(shared, other),
                List.of(), new float[0]), rig, "moving");
        List<GltfGunBodyRenderer.PreparedPrimitive> before = renderer.preparedAtTime(0);
        rig.getNode("moving").offsetZ = 3;
        List<GltfGunBodyRenderer.PreparedPrimitive> after = renderer.preparedAtTime(0);
        assertEquals(3, after.size());
        assertSame(storage(before.get(0).geometry(), "positions"), storage(after.get(0).geometry(), "positions"));
        assertSame(storage(after.get(0).geometry(), "positions"), storage(after.get(1).geometry(), "positions"));
        assertNotSame(storage(after.get(0).geometry(), "positions"), storage(after.get(2).geometry(), "positions"));
        assertArrayEquals(before.get(1).geometry().positions(), after.get(1).geometry().positions());
        assertArrayEquals(before.get(2).geometry().positions(), after.get(2).geometry().positions());
    }

    @Test
    void transformedBlendSortMatchesCpuWorldSpaceSortAndRetainsSharedArrays() throws Exception {
        Fixture fixture = fixture("depth_layers", mesh(true, false, false), false);
        fixture.part().offsetX = 4;
        fixture.part().offsetZ = -2;
        fixture.part().xScale = -1;
        fixture.part().additionalQuaternion = new Quaternionf().rotationY(0.65f);
        Matrix4f modelView = new Matrix4f().rotateY(0.4f).translate(0.2f, -0.6f, -2);
        GltfPreparedGeometry captured = frame(fixture);
        GltfPreparedGeometry sorted = captured.sortedBackToFront(modelView);
        GltfPreparedGeometry expected = reference(fixture).sortedBackToFront(modelView);
        assertGeometryEquals(expected, sorted);
        assertEquals(expected.farthestTriangleViewDepth(modelView), sorted.farthestTriangleViewDepth(modelView), EPSILON);
        assertSame(storage(captured, "positions"), storage(sorted, "positions"));
        assertGeometryEquals(expected, sorted.sortedBackToFront(modelView));
    }

    @Test
    void hideRestoreRetainsGeometryAndSingularNonfiniteInputsStillFailBeforeSubmission() throws Exception {
        Fixture fixture = fixture("visible_panel", mesh(true, false, false), false);
        GltfPreparedGeometry initial = frame(fixture);
        fixture.part().xScale = fixture.part().yScale = fixture.part().zScale = 0;
        assertTrue(fixture.renderer().preparedFrameAtTime(0).allHidden());
        fixture.part().xScale = fixture.part().yScale = fixture.part().zScale = 1;
        GltfPreparedGeometry restored = frame(fixture);
        assertSame(storage(initial, "positions"), storage(restored, "positions"));
        assertGeometryEquals(initial, restored);
        fixture.part().xScale = 0;
        assertThrows(IllegalArgumentException.class, () -> frame(fixture));
        fixture.part().xScale = fixture.part().yScale = fixture.part().zScale = 0.0001f;
        assertThrows(IllegalArgumentException.class, () -> frame(fixture));
        fixture.part().xScale = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> frame(fixture));
    }

    @Test
    void skinMorphAndMissingNormalsKeepCpuDeformationPath() throws Exception {
        for (Fixture fixture : List.of(
                fixture("missing_normals", mesh(false, false, false), false),
                fixture("morph_panel", mesh(true, true, false), false),
                fixture("skin_panel", mesh(true, false, true), true))) {
            GltfPreparedGeometry first = frame(fixture);
            fixture.part().offsetX = 0.5f;
            GltfPreparedGeometry moved = frame(fixture);
            assertNotSame(storage(first, "positions"), storage(moved, "positions"));
            assertGeometryEquals(reference(fixture), moved);
        }
    }

    @Test
    void missingNormalsDegenerateFallbackAndSmallAuthoredNormalsAreNotReinterpreted() throws Exception {
        GltfMeshPrimitive degenerate = new GltfMeshPrimitive(new float[9], new float[0], new float[0],
                new int[0], new float[0], List.of(), new GltfMaterialReference("material"));
        Fixture fixture = fixture("degenerate_panel", new GltfRenderPrimitive(degenerate, new int[]{0, 1, 2},
                new float[0], new float[0], -1), false);
        fixture.part().additionalQuaternion = new Quaternionf().rotationX(1.2f);
        assertArrayEquals(new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0}, frame(fixture).normals(), EPSILON);
        GltfRenderPrimitive original = mesh(true, false, false);
        float[] smallNormals = original.runtimePrimitive().normals();
        for (int i = 0; i < smallNormals.length; i++) {
            smallNormals[i] *= 0.00002f;
        }
        GltfMeshPrimitive small = new GltfMeshPrimitive(original.runtimePrimitive().positions(), smallNormals,
                new float[0], new int[0], new float[0], List.of(), new GltfMaterialReference("material"));
        Fixture rescued = fixture("small_normal_panel", new GltfRenderPrimitive(small, original.indices(),
                original.texCoords0(), original.colors0(), -1), false);
        rescued.part().xScale = rescued.part().yScale = rescued.part().zScale = 0.01f;
        assertGeometryEquals(reference(rescued), frame(rescued));
        assertNotSame(storage(frame(rescued), "positions"), storage(frame(rescued), "positions"));
    }

    @Test
    void authoredNormalMagnitudeKeepsTheExistingDeformerThenWorldNormalizationOrder() {
        Fixture accepted = fixture("normalized_small", meshWithNormalMagnitude(0.001f), false);
        accepted.part().xScale = accepted.part().yScale = accepted.part().zScale = 1000;
        assertGeometryEquals(reference(accepted), frame(accepted));
        Fixture rejected = fixture("normalized_large", meshWithNormalMagnitude(100000), false);
        rejected.part().xScale = rejected.part().yScale = rejected.part().zScale = 100000;
        assertThrows(IllegalArgumentException.class, () -> reference(rejected));
        assertThrows(IllegalArgumentException.class, () -> frame(rejected));
    }

    private static GltfRenderPrimitive meshWithNormalMagnitude(float magnitude) {
        GltfRenderPrimitive original = mesh(true, false, false);
        float[] normals = new float[original.runtimePrimitive().positions().length];
        for (int offset = 0; offset < normals.length; offset += 3) {
            normals[offset] = magnitude;
        }
        return new GltfRenderPrimitive(new GltfMeshPrimitive(original.runtimePrimitive().positions(), normals,
                new float[0], new int[0], new float[0], List.of(), new GltfMaterialReference("material")),
                original.indices(), original.texCoords0(), original.colors0(), -1);
    }

    private static GltfPreparedGeometry frame(Fixture fixture) {
        return fixture.renderer().preparedAtTime(0).getFirst().geometry();
    }

    private static GltfPreparedGeometry reference(Fixture fixture) {
        Matrix4f world = new Matrix4f().scaling(-1, -1, 1);
        Matrix4f source = new Matrix4f();
        fixture.part().translateAndRotateAndScale(source);
        world.mul(source).scale(-1, -1, 1);
        GltfSkin skin = fixture.skin() ? new GltfSkin(new int[]{0}, List.of(new Matrix4f())) : null;
        return GltfPreparedGeometry.create(fixture.mesh(), GltfVertexDeformer.deform(
                        fixture.mesh().runtimePrimitive(), skin, skin == null ? null : new Matrix4f[]{world},
                        fixture.mesh().runtimePrimitive().morphTargets().isEmpty() ? new float[0] : new float[]{0.25f}),
                skin == null ? world : null,
                skin == null && GltfPreparedGeometry.requiresWindingReversal(world));
    }

    private static void assertGeometryEquals(GltfPreparedGeometry expected, GltfPreparedGeometry actual) {
        assertArrayEquals(expected.positions(), actual.positions(), EPSILON);
        assertArrayEquals(expected.normals(), actual.normals(), EPSILON);
        assertArrayEquals(expected.texCoords(), actual.texCoords(), EPSILON);
        assertArrayEquals(expected.colors(), actual.colors(), EPSILON);
    }

    private static Object storage(GltfPreparedGeometry geometry, String fieldName) throws Exception {
        Field field = GltfPreparedGeometry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(geometry);
    }

    private static Fixture fixture(String name, GltfRenderPrimitive mesh, boolean skin) {
        BedrockGunModel rig = rig();
        List<GltfNode> nodes = skin ? List.of(node("mesh", 0, 0), node("moving", -1, -1))
                : List.of(node("moving", 0, -1));
        List<GltfSkin> skins = skin ? List.of(new GltfSkin(new int[]{1}, List.of(new Matrix4f()))) : List.of();
        float[] weights = mesh.runtimePrimitive().morphTargets().isEmpty() ? new float[0] : new float[]{0.25f};
        GltfGunBodyRenderer renderer = renderer(name, asset(nodes, List.of(mesh), skins, weights), rig, "moving");
        return new Fixture(renderer, rig.getNode("moving"), mesh, skin);
    }

    private static GltfGunBodyRenderer renderer(String name, ConvertedGltfAsset asset, BedrockGunModel rig, String target) {
        Identifier id = Identifier.fromNamespaceAndPath("test", "models/gltf/" + name + ".gltf");
        GunRenderModelConfig config = GSON.fromJson("{\"type\":\"gltf\",\"location\":\"" + id
                + "\",\"node_map\":{\"moving\":\"" + target + "\"}}", GunRenderModelConfig.class);
        return new GltfGunBodyRenderer(id, asset, new GltfModelManager(), 0, config, rig);
    }

    private static ConvertedGltfAsset asset(List<GltfNode> nodes, List<GltfRenderPrimitive> primitives,
                                            List<GltfSkin> skins, float[] weights) {
        List<GltfMesh> meshes = primitives.stream().map(p -> new GltfMesh(List.of(p.runtimePrimitive()))).toList();
        List<GltfRenderMesh> renderMeshes = primitives.stream().map(p -> new GltfRenderMesh("mesh", List.of(p), weights)).toList();
        int[] roots = java.util.stream.IntStream.range(0, nodes.size()).toArray();
        return new ConvertedGltfAsset(new GltfScene(nodes, meshes, skins, roots), renderMeshes, List.of(), List.of(),
                List.of(), nodes.stream().map(n -> n.meshIndex() < 0 ? new float[0] : weights).toList(),
                primitives.stream().map(p -> weights).toList(), List.of(new GltfSceneData("scene", roots)), 0, 0);
    }

    private static GltfNode node(String name, int mesh, int skin) {
        return new GltfNode(name, GltfNodeTransform.identity(), new int[0], mesh, skin);
    }

    private static GltfRenderPrimitive mesh(boolean normals, boolean morph, boolean skin) {
        float[] positions = {0, 0, -1, 1, 0, -1, 0, 1, -1, 0, 0, -5, 1, 0, -5, 0, 1, -5};
        float[] authoredNormals = new float[positions.length];
        for (int i = 0; i < authoredNormals.length; i += 3) {
            authoredNormals[i] = 0.6f;
            authoredNormals[i + 2] = 0.8f;
        }
        int[] joints = skin ? new int[24] : new int[0];
        float[] weights = skin ? new float[24] : new float[0];
        for (int i = 0; i < weights.length; i += 4) {
            weights[i] = 1;
        }
        float[] deltas = new float[18];
        deltas[0] = 1;
        GltfMeshPrimitive runtime = new GltfMeshPrimitive(positions, normals ? authoredNormals : new float[0],
                new float[0], joints, weights,
                morph ? List.of(new GltfMorphTarget(deltas, new float[0], new float[0])) : List.of(),
                new GltfMaterialReference("material"));
        float[] colors = new float[24];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = (i % 4 + 1) * 0.25f;
        }
        return new GltfRenderPrimitive(runtime, new int[]{0, 1, 2, 3, 4, 5},
                new float[]{0, 0, 1, 0, 0, 1, 0.2f, 0.2f, 0.8f, 0.2f, 0.2f, 0.8f}, colors, -1);
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

    private record Fixture(GltfGunBodyRenderer renderer, BedrockPart part, GltfRenderPrimitive mesh, boolean skin) {
    }

    private static final class CapturingConsumer implements VertexConsumer {
        private final List<Float> data = new ArrayList<>();
        float[] values() {
            float[] result = new float[data.size()];
            for (int i = 0; i < result.length; i++) result[i] = data.get(i);
            return result;
        }
        private VertexConsumer add(float... values) {
            for (float value : values) data.add(value);
            return this;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { return add(x, y, z); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return add(r, g, b, a); }
        @Override public VertexConsumer setColor(int color) {
            return setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
        }
        @Override public VertexConsumer setUv(float u, float v) { return add(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return add(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return add(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return add(x, y, z); }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
