package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfLodMetadata;
import com.tacz.guns.client.model.gltf.convert.GltfPbrMaterialData;
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
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GltfMeshSimplifierTest {
    private static final GltfRenderQuality HALF = new GltfRenderQuality(2048, 1, .5f);

    @Test
    void cancelledGenerationStopsBeforePublishingPartialGeometry() {
        var primitive = grid(16, true, -1);
        var source = asset(List.of(List.of(primitive, primitive)));
        int[] original = primitive.indices();
        assertThrows(CancellationException.class, () -> GltfMeshSimplifier.apply(source,
                new GltfRenderQuality(8192, 0, 1), () -> true));
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class,
                () -> GltfMeshSimplifier.apply(source, HALF, () -> checks.incrementAndGet() >= 4));
        assertEquals(4, checks.get());
        assertArrayEquals(original, primitive.indices());
        assertTrue(GltfMeshSimplifier.apply(source, HALF).renderMeshes().getFirst()
                .primitives().getFirst().indices().length < original.length);
    }

    @Test
    void originalQualityDoesNotCopyTheAsset() {
        ConvertedGltfAsset source = asset(List.of(List.of(grid(16, true, -1))));
        assertSame(source, GltfMeshSimplifier.apply(source, new GltfRenderQuality(8192, 0, 1)));
    }

    @Test
    void nativeAttributeAwareSimplificationRetainsSourceVerticesAppearanceAndAllBorders() {
        int cells = 16;
        GltfRenderPrimitive primitive = grid(cells, true, -1);
        int[] sourceIndices = primitive.indices();
        float[] positions = primitive.runtimePrimitive().positions();
        float[] normals = primitive.runtimePrimitive().normals();
        float[] uvs = primitive.texCoords0();
        float[] colors = primitive.colors0();
        ConvertedGltfAsset source = asset(List.of(List.of(primitive)));
        ConvertedGltfAsset result = GltfMeshSimplifier.apply(source, HALF);
        GltfRenderPrimitive simplified = result.renderMeshes().getFirst().primitives().getFirst();
        assertNotSame(source, result);
        assertTrue(simplified.indices().length < sourceIndices.length);
        assertEquals(0, simplified.indices().length % 3);
        assertTrue(simplified.indices().length >= 3);
        assertSame(primitive.runtimePrimitive(), simplified.runtimePrimitive());
        assertEquals(primitive.materialIndex(), simplified.materialIndex());
        assertArrayEquals(positions, simplified.runtimePrimitive().positions());
        assertArrayEquals(normals, simplified.runtimePrimitive().normals());
        assertArrayEquals(uvs, simplified.texCoords0());
        assertArrayEquals(colors, simplified.colors0());
        assertArrayEquals(sourceIndices, primitive.indices());
        Set<Integer> referenced = Arrays.stream(simplified.indices()).boxed().collect(Collectors.toSet());
        for (int y = 0; y <= cells; y++) {
            for (int x = 0; x <= cells; x++) {
                if (x == 0 || x == cells || y == 0 || y == cells) {
                    assertTrue(referenced.contains(y * (cells + 1) + x), "Border vertex removed");
                }
            }
        }
        assertTrue(referenced.stream().allMatch(i -> i >= 0 && i < primitive.runtimePrimitive().vertexCount()));
        int[] exposed = simplified.indices();
        exposed[0] = -1;
        assertTrue(simplified.indices()[0] >= 0);
    }

    @Test
    void geometryWithoutOptionalAttributesUsesTheSameIndexOnlyBoundaryPolicy() {
        GltfRenderPrimitive primitive = grid(16, false, -1);
        GltfRenderPrimitive result = GltfMeshSimplifier.apply(asset(List.of(List.of(primitive))), HALF)
                .renderMeshes().getFirst().primitives().getFirst();
        assertTrue(result.indices().length < primitive.indices().length);
        assertSame(primitive.runtimePrimitive(), result.runtimePrimitive());
        assertEquals(0, result.texCoords0().length);
        assertEquals(0, result.colors0().length);
    }

    @Test
    void farAwayUnreferencedVerticesCannotIncreaseTheReferencedSubsetErrorBudget() {
        GltfRenderQuality coarse = new GltfRenderQuality(512, 3, .1f);
        for (boolean attributes : List.of(false, true)) {
            GltfRenderPrimitive grid = grid(16, attributes, -1);
            float[] positions = grid.runtimePrimitive().positions();
            Random random = new Random(517);
            for (int vertex = 0; vertex < grid.runtimePrimitive().vertexCount(); vertex++) {
                positions[vertex * 3 + 2] = random.nextFloat() * .2f;
            }
            GltfMeshPrimitive detail = new GltfMeshPrimitive(positions, grid.runtimePrimitive().normals(),
                    new float[0], new int[0], new float[0], List.of(), grid.runtimePrimitive().material());
            GltfRenderPrimitive compact = new GltfRenderPrimitive(detail, grid.indices(),
                    grid.texCoords0(), grid.colors0(), -1);
            float[] paddedPositions = padVertices(positions, 3);
            paddedPositions[0] = 1_000_000;
            paddedPositions[paddedPositions.length - 3] = -1_000_000;
            GltfMeshPrimitive sparseRuntime = new GltfMeshPrimitive(paddedPositions,
                    padVertices(detail.normals(), 3), new float[0], new int[0], new float[0], List.of(),
                    detail.material());
            GltfRenderPrimitive sparse = new GltfRenderPrimitive(sparseRuntime,
                    Arrays.stream(compact.indices()).map(i -> i + 1).toArray(),
                    padVertices(compact.texCoords0(), 2), padVertices(compact.colors0(), 4), -1);
            ConvertedGltfAsset result = GltfMeshSimplifier.apply(asset(List.of(List.of(compact), List.of(sparse))),
                    coarse);
            int[] compactIndices = result.renderMeshes().get(0).primitives().getFirst().indices();
            int[] sparseIndices = result.renderMeshes().get(1).primitives().getFirst().indices();
            assertTrue(compactIndices.length > compact.indices().length * coarse.triangleRatio(),
                    "Fixture must be constrained by error, not only the triangle target");
            assertArrayEquals(compactIndices, Arrays.stream(sparseIndices).map(i -> i - 1).toArray(),
                    "Unused positions must not change subset normalization or remapped output indices");
            assertSame(sparseRuntime, result.renderMeshes().get(1).primitives().getFirst().runtimePrimitive());
            assertTrue(Arrays.stream(sparseIndices).allMatch(i -> i >= 1 && i <= detail.vertexCount()));
        }
    }

    @Test
    void nonAffineNormalUvAndColorDetailConstrainTheNativeSimplifierNotJustTheOutputCopy() {
        GltfRenderPrimitive plain = grid(16, false, -1);
        float[] normals = new float[plain.runtimePrimitive().vertexCount() * 3];
        float[] uvs = new float[plain.runtimePrimitive().vertexCount() * 2];
        float[] colors = new float[plain.runtimePrimitive().vertexCount() * 4];
        Random random = new Random(173);
        for (int vertex = 0; vertex < plain.runtimePrimitive().vertexCount(); vertex++) {
            float color = random.nextFloat();
            normals[vertex * 3] = (float) Math.sin(color * 2 * Math.PI);
            normals[vertex * 3 + 2] = (float) Math.cos(color * 2 * Math.PI);
            uvs[vertex * 2] = color;
            Arrays.fill(colors, vertex * 4, vertex * 4 + 3, color);
            colors[vertex * 4 + 3] = 1;
        }
        GltfRenderPrimitive normalDetail = new GltfRenderPrimitive(new GltfMeshPrimitive(
                plain.runtimePrimitive().positions(), normals, new float[0], new int[0], new float[0],
                List.of(), plain.runtimePrimitive().material()), plain.indices(), new float[0], new float[0], -1);
        GltfRenderPrimitive uvDetail = new GltfRenderPrimitive(plain.runtimePrimitive(), plain.indices(),
                uvs, new float[0], -1);
        GltfRenderPrimitive colorDetail = new GltfRenderPrimitive(plain.runtimePrimitive(), plain.indices(),
                new float[0], colors, -1);
        for (GltfRenderPrimitive detailed : List.of(normalDetail, uvDetail, colorDetail)) {
            ConvertedGltfAsset result = GltfMeshSimplifier.apply(asset(List.of(List.of(plain), List.of(detailed))), HALF);
            assertTrue(result.renderMeshes().get(1).primitives().getFirst().indices().length
                    > result.renderMeshes().get(0).primitives().getFirst().indices().length);
            assertArrayEquals(detailed.colors0(), result.renderMeshes().get(1).primitives().getFirst().colors0());
        }
    }

    @Test
    void partsAndMaterialsRemainSeparateAndRepeatedNodesReuseTheSelectedMesh() {
        GltfRenderPrimitive first = grid(16, true, 0);
        GltfRenderPrimitive second = grid(16, true, 1);
        GltfRenderPrimitive moving = grid(16, true, 2);
        ConvertedGltfAsset source = asset(List.of(List.of(first, second), List.of(moving)),
                List.of(node("fixed", 0, -1), node("bolt", 1, -1), node("instance", 0, -1)), List.of());
        ConvertedGltfAsset result = GltfMeshSimplifier.apply(source, HALF);
        assertEquals(2, result.renderMeshes().size());
        assertEquals(2, result.renderMeshes().get(0).primitives().size());
        assertEquals(1, result.renderMeshes().get(1).primitives().size());
        assertSame(source.runtimeScene().nodes().get(1), result.runtimeScene().nodes().get(1));
        for (int mesh = 0; mesh < source.renderMeshes().size(); mesh++) {
            for (int index = 0; index < source.renderMeshes().get(mesh).primitives().size(); index++) {
                GltfRenderPrimitive before = source.renderMeshes().get(mesh).primitives().get(index);
                GltfRenderPrimitive after = result.renderMeshes().get(mesh).primitives().get(index);
                assertEquals(before.materialIndex(), after.materialIndex());
                assertSame(before.runtimePrimitive(), after.runtimePrimitive());
                assertTrue(after.indices().length < before.indices().length);
            }
        }
    }

    @Test
    void bordersCanPreventTheRequestedRatioRatherThanDroppingSmallParts() {
        GltfRenderPrimitive panel = grid(1, true, -1);
        ConvertedGltfAsset source = asset(List.of(List.of(panel)));
        assertSame(source, GltfMeshSimplifier.apply(source, new GltfRenderQuality(512, 3, .1f)));
    }

    @Test
    void degenerateResultCannotReplaceARequiredNonemptyPrimitive() {
        GltfMeshPrimitive runtime = new GltfMeshPrimitive(new float[12], new float[0], new float[0],
                new int[0], new float[0], List.of(), new GltfMaterialReference("degenerate"));
        GltfRenderPrimitive primitive = new GltfRenderPrimitive(runtime, new int[]{0, 1, 2, 0, 2, 3},
                new float[0], new float[0], -1);
        ConvertedGltfAsset source = asset(List.of(List.of(primitive)));
        assertSame(source, GltfMeshSimplifier.apply(source, HALF));
    }

    @Test
    void skinAndMorphTopologyRemainUnchangedUntilAnimationErrorIsSupported() {
        GltfRenderPrimitive plain = grid(16, true, -1);
        GltfRenderPrimitive skin = deformable(plain, true);
        GltfRenderPrimitive morph = deformable(plain, false);
        ConvertedGltfAsset source = asset(List.of(List.of(skin), List.of(morph), List.of(plain)),
                List.of(node("skin", 0, 0), node("morph", 1, -1), node("rigid", 2, -1),
                        node("joint", -1, -1)),
                List.of(new GltfSkin(new int[]{3}, List.of(new Matrix4f()))));
        ConvertedGltfAsset result = GltfMeshSimplifier.apply(source, HALF);
        assertSame(source.renderMeshes().get(0), result.renderMeshes().get(0));
        assertSame(source.renderMeshes().get(1), result.renderMeshes().get(1));
        assertArrayEquals(skin.indices(), result.renderMeshes().get(0).primitives().getFirst().indices());
        assertArrayEquals(morph.indices(), result.renderMeshes().get(1).primitives().getFirst().indices());
        assertTrue(result.renderMeshes().get(2).primitives().getFirst().indices().length < plain.indices().length);
        assertArrayEquals(source.nodeMorphWeights(1), result.nodeMorphWeights(1));
        assertArrayEquals(source.meshDefaultMorphWeights(1), result.meshDefaultMorphWeights(1));
    }

    @Test
    void authoredHighLowAndTheirSharedMeshStayUntouchedAndUnselectedMeshesAreNotProcessed() {
        ConvertedGltfAsset base = asset(List.of(List.of(grid(16, true, -1)), List.of(grid(16, true, -1)),
                        List.of(grid(16, true, -1)), List.of(grid(16, true, -1))),
                List.of(node("high", 0, -1), node("low", 1, -1), node("shared_low", 1, -1),
                        node("ungraded", 2, -1), node("unselected", 3, -1)), List.of());
        int[] roots = {0, 2, 3};
        GltfLodMetadata lods = new GltfLodMetadata(Map.of(0, List.of(1)), Map.of());
        ConvertedGltfAsset original = new ConvertedGltfAsset(new GltfScene(base.runtimeScene().nodes(),
                base.runtimeScene().meshes(), List.of(), roots), base.renderMeshes(), base.materials(), base.images(),
                base.animations(), base.nodeMorphWeights(), base.meshDefaultMorphWeights(),
                List.of(new GltfSceneData("scene", roots)), 0, 0, lods);
        for (int level : List.of(0, 1)) {
            ConvertedGltfAsset selected = original.withLodLevel(level);
            ConvertedGltfAsset result = GltfMeshSimplifier.apply(selected, HALF);
            for (int unchangedMesh : List.of(0, 1, 3)) {
                assertSame(selected.renderMeshes().get(unchangedMesh), result.renderMeshes().get(unchangedMesh));
            }
            assertTrue(result.renderMeshes().get(2).primitives().getFirst().indices().length
                    < selected.renderMeshes().get(2).primitives().getFirst().indices().length);
            assertSame(selected.lods(), result.lods());
            assertEquals(selected.selectedNodeIndices(), result.selectedNodeIndices());
            assertEquals(selected.selectedMeshIndices(), result.selectedMeshIndices());
        }
    }

    @Test
    void independentGenerationFromOriginalHasDeterministicIndices() {
        ConvertedGltfAsset source = asset(List.of(List.of(grid(16, true, -1))));
        ConvertedGltfAsset first = GltfMeshSimplifier.apply(source, HALF);
        ConvertedGltfAsset second = GltfMeshSimplifier.apply(source, HALF);
        assertArrayEquals(first.renderMeshes().getFirst().primitives().getFirst().indices(),
                second.renderMeshes().getFirst().primitives().getFirst().indices());
    }

    private static GltfRenderPrimitive deformable(GltfRenderPrimitive source, boolean skin) {
        GltfMeshPrimitive base = source.runtimePrimitive();
        int[] joints = skin ? new int[base.vertexCount() * 4] : new int[0];
        float[] weights = new float[joints.length];
        for (int i = 0; i < weights.length; i += 4) {
            weights[i] = 1;
        }
        List<GltfMorphTarget> targets = skin ? List.of() : List.of(new GltfMorphTarget(
                new float[base.vertexCount() * 3], new float[0], new float[0]));
        return new GltfRenderPrimitive(new GltfMeshPrimitive(base.positions(), base.normals(), new float[0],
                joints, weights, targets, base.material()), source.indices(), source.texCoords0(),
                source.colors0(), source.materialIndex());
    }

    private static float[] padVertices(float[] values, int components) {
        if (values.length == 0) {
            return values;
        }
        float[] padded = new float[values.length + components * 2];
        System.arraycopy(values, 0, padded, components, values.length);
        return padded;
    }

    private static GltfRenderPrimitive grid(int cells, boolean attributes, int material) {
        int side = cells + 1;
        int vertices = side * side;
        float[] positions = new float[vertices * 3];
        float[] normals = attributes ? new float[vertices * 3] : new float[0];
        float[] uvs = attributes ? new float[vertices * 2] : new float[0];
        float[] colors = attributes ? new float[vertices * 4] : new float[0];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int vertex = y * side + x;
                positions[vertex * 3] = x / (float) cells;
                positions[vertex * 3 + 1] = y / (float) cells;
                if (attributes) {
                    normals[vertex * 3 + 2] = 1;
                    uvs[vertex * 2] = x / (float) cells;
                    uvs[vertex * 2 + 1] = y / (float) cells;
                    Arrays.fill(colors, vertex * 4, vertex * 4 + 4, 1);
                }
            }
        }
        int[] indices = new int[cells * cells * 6];
        int offset = 0;
        for (int y = 0; y < cells; y++) {
            for (int x = 0; x < cells; x++) {
                int a = y * side + x;
                for (int vertex : new int[]{a, a + 1, a + side + 1, a, a + side + 1, a + side}) {
                    indices[offset++] = vertex;
                }
            }
        }
        return new GltfRenderPrimitive(new GltfMeshPrimitive(positions, normals, new float[0],
                new int[0], new float[0], List.of(), new GltfMaterialReference("material")),
                indices, uvs, colors, material);
    }

    private static GltfNode node(String name, int mesh, int skin) {
        return new GltfNode(name, GltfNodeTransform.identity(), new int[0], mesh, skin);
    }

    private static ConvertedGltfAsset asset(List<List<GltfRenderPrimitive>> meshes) {
        return asset(meshes, IntStream.range(0, meshes.size()).mapToObj(i -> node("part" + i, i, -1)).toList(),
                List.of());
    }

    private static ConvertedGltfAsset asset(List<List<GltfRenderPrimitive>> meshes, List<GltfNode> nodes,
                                            List<GltfSkin> skins) {
        List<GltfMesh> runtimeMeshes = new ArrayList<>();
        List<GltfRenderMesh> renderMeshes = new ArrayList<>();
        List<float[]> meshWeights = new ArrayList<>();
        for (List<GltfRenderPrimitive> primitives : meshes) {
            runtimeMeshes.add(new GltfMesh(primitives.stream().map(GltfRenderPrimitive::runtimePrimitive).toList()));
            float[] weights = new float[primitives.getFirst().runtimePrimitive().morphTargets().size()];
            meshWeights.add(weights);
            renderMeshes.add(new GltfRenderMesh("mesh" + renderMeshes.size(), primitives, weights));
        }
        int[] roots = IntStream.range(0, nodes.size()).toArray();
        int materialCount = meshes.stream().flatMap(List::stream).mapToInt(GltfRenderPrimitive::materialIndex)
                .max().orElse(-1) + 1;
        return new ConvertedGltfAsset(new GltfScene(nodes, runtimeMeshes, skins, roots), renderMeshes,
                IntStream.range(0, materialCount).mapToObj(i -> GltfPbrMaterialData.defaultMaterial()).toList(),
                List.of(), List.of(), nodes.stream()
                .map(n -> n.meshIndex() < 0 ? new float[0] : meshWeights.get(n.meshIndex())).toList(),
                meshWeights, List.of(new GltfSceneData("scene", roots)), 0, 0);
    }
}
