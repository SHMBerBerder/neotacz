package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds index-only variants once during asset loading, never during frame preparation. */
public final class GltfMeshSimplifier {
    /** Geometry/attribute error normalized to the referenced primitive subset, not a pixel guarantee. */
    public static final float MAX_NORMALIZED_ERROR = .001f;
    // Sparse excludes unrelated vertices from normalization and remaps output to the original vertex indices.
    private static final int SIMPLIFY_OPTIONS = MeshOptimizer.meshopt_SimplifyLockBorder
            | MeshOptimizer.meshopt_SimplifySparse;

    private GltfMeshSimplifier() {
    }

    /** The ratio is best effort: authored LOD, deformation, boundaries and error limits take priority. */
    public static ConvertedGltfAsset apply(ConvertedGltfAsset asset, GltfRenderQuality quality) {
        return apply(asset, quality, () -> false);
    }

    public static ConvertedGltfAsset apply(ConvertedGltfAsset asset, GltfRenderQuality quality,
                                           BooleanSupplier cancelled) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);
        ConvertedGltfAsset result = derive(asset, quality, cancelled);
        checkCancelled(cancelled);
        GltfAssetLimits.enforceSelectedDraw(result);
        return result;
    }

    private static ConvertedGltfAsset derive(ConvertedGltfAsset asset, GltfRenderQuality quality,
                                              BooleanSupplier cancelled) {
        if (quality.triangleRatio() == 1f) {
            return asset;
        }
        Set<Integer> selected = asset.selectedMeshIndices();
        Set<Integer> protectedMeshes = new HashSet<>(asset.authoredLodMeshIndices());
        for (GltfNode node : asset.runtimeScene().nodes()) {
            if (node.skinIndex() >= 0 && node.meshIndex() >= 0) {
                protectedMeshes.add(node.meshIndex());
            }
        }
        List<GltfRenderMesh> meshes = new ArrayList<>(asset.renderMeshes());
        boolean changed = false;
        for (int index : selected) {
            checkCancelled(cancelled);
            if (protectedMeshes.contains(index)) {
                continue;
            }
            GltfRenderMesh original = meshes.get(index);
            List<GltfRenderPrimitive> primitives = new ArrayList<>(original.primitives().size());
            boolean meshChanged = false;
            for (GltfRenderPrimitive primitive : original.primitives()) {
                checkCancelled(cancelled);
                GltfRenderPrimitive replacement = simplify(primitive, quality.triangleRatio());
                checkCancelled(cancelled);
                primitives.add(replacement);
                meshChanged |= replacement != primitive;
            }
            if (meshChanged) {
                meshes.set(index, new GltfRenderMesh(original.name(), primitives, original.defaultMorphWeights()));
                changed = true;
            }
        }
        checkCancelled(cancelled);
        return changed ? asset.withRenderData(meshes, asset.materials(), asset.images()) : asset;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("glTF quality generation retired");
    }

    private static GltfRenderPrimitive simplify(GltfRenderPrimitive primitive, float ratio) {
        GltfMeshPrimitive runtime = primitive.runtimePrimitive();
        // Index-only changes still alter interpolation during deformation; that error is not bounded here.
        if (runtime.hasSkinAttributes() || !runtime.morphTargets().isEmpty()) {
            return primitive;
        }
        int[] indices = primitive.indices();
        int targetCount = Math.max(1, (int) Math.floor(indices.length / 3 * (double) ratio)) * 3;
        if (targetCount >= indices.length) {
            return primitive;
        }
        float[] positions = runtime.positions();
        float[] normals = runtime.normals();
        float[] uvs = primitive.texCoords0();
        float[] colors = primitive.colors0();
        int attributeCount = (normals.length == 0 ? 0 : 3) + (uvs.length == 0 ? 0 : 2)
                + (colors.length == 0 ? 0 : 4);
        IntBuffer source = null;
        IntBuffer destination = null;
        FloatBuffer vertexPositions = null;
        FloatBuffer attributes = null;
        FloatBuffer attributeWeights = null;
        FloatBuffer error = null;
        try {
            source = MemoryUtil.memAllocInt(indices.length);
            source.put(indices).flip();
            // The native API requires capacity for the original count, even for a lower target.
            destination = MemoryUtil.memAllocInt(indices.length);
            vertexPositions = MemoryUtil.memAllocFloat(positions.length);
            vertexPositions.put(positions).flip();
            error = MemoryUtil.memAllocFloat(1);
            long count;
            if (attributeCount == 0) {
                count = MeshOptimizer.meshopt_simplify(destination, source, vertexPositions,
                        runtime.vertexCount(), 3 * Float.BYTES, targetCount, MAX_NORMALIZED_ERROR,
                        SIMPLIFY_OPTIONS, error);
            } else {
                attributes = MemoryUtil.memAllocFloat(Math.multiplyExact(runtime.vertexCount(), attributeCount));
                for (int vertex = 0; vertex < runtime.vertexCount(); vertex++) {
                    putAttribute(attributes, normals, vertex, 3);
                    putAttribute(attributes, uvs, vertex, 2);
                    putAttribute(attributes, colors, vertex, 4);
                }
                attributes.flip();
                attributeWeights = MemoryUtil.memAllocFloat(attributeCount);
                int uvStart = normals.length == 0 ? 0 : 3;
                for (int i = 0; i < attributeCount; i++) {
                    // UVs use the lower end of meshoptimizer's recommended 10-100 weight range.
                    attributeWeights.put(uvs.length != 0 && i >= uvStart && i < uvStart + 2 ? 10f : 1f);
                }
                attributeWeights.flip();
                count = MeshOptimizer.meshopt_simplifyWithAttributes(destination, source, vertexPositions,
                        runtime.vertexCount(), 3 * Float.BYTES, attributes, attributeCount * Float.BYTES,
                        attributeWeights, null, targetCount, MAX_NORMALIZED_ERROR,
                        SIMPLIFY_OPTIONS, error);
            }
            if (count < 0 || count > indices.length || count % 3 != 0) {
                throw new IllegalStateException("Meshoptimizer returned an invalid triangle index count: " + count);
            }
            if (count < 3 || count == indices.length || !Float.isFinite(error.get(0))
                    || error.get(0) > MAX_NORMALIZED_ERROR) {
                return primitive;
            }
            int[] simplified = new int[(int) count];
            destination.get(simplified);
            return new GltfRenderPrimitive(runtime, simplified, uvs, colors, primitive.materialIndex());
        } finally {
            MemoryUtil.memFree(error);
            MemoryUtil.memFree(attributeWeights);
            MemoryUtil.memFree(attributes);
            MemoryUtil.memFree(vertexPositions);
            MemoryUtil.memFree(destination);
            MemoryUtil.memFree(source);
        }
    }

    private static void putAttribute(FloatBuffer destination, float[] values, int vertex, int components) {
        if (values.length != 0) {
            destination.put(values, vertex * components, components);
        }
    }
}
