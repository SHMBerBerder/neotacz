package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.BufferModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.ImageModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SkinModel;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public final class GltfAssetLimits {
    public static final int MAX_ACCESSORS = 8_192;
    public static final long MAX_ACCESSOR_COMPONENTS = 16_000_000L;
    public static final int MAX_BUFFERS = 256;
    public static final int MAX_BUFFER_VIEWS = 16_384;
    public static final int MAX_MESHES = 4_096;
    public static final int MAX_PRIMITIVES = 8_192;
    // Source allocation capacity includes unselected scenes/LODs and pre-simplification geometry.
    // These are not draw limits. Byte/component/morph limits independently bound expanded arrays.
    public static final long MAX_SOURCE_VERTICES = 1_000_000L;
    public static final long MAX_SOURCE_INDICES = 3_000_000L;
    public static final long MAX_DRAW_VERTICES = 300_000L;
    public static final long MAX_DRAW_TRIANGLES = 100_000L;
    public static final int MAX_NODES = 4_096;
    public static final int MAX_NODE_CHILD_REFERENCES = 16_384;
    public static final int MAX_SCENES = 128;
    public static final int MAX_SCENE_ROOT_REFERENCES = 8_192;
    public static final int MAX_SKINS = 128;
    public static final int MAX_TOTAL_JOINTS = 2_048;
    public static final int MAX_MORPH_TARGETS_PER_PRIMITIVE = 64;
    public static final int MAX_TOTAL_MORPH_TARGETS = 1_024;
    public static final long MAX_MORPH_VERTEX_INSTANCES = 1_000_000L;
    public static final int MAX_MATERIALS = 2_048;
    public static final int MAX_IMAGES = 1_024;
    public static final int MAX_TEXTURES = 2_048;
    public static final long MAX_SINGLE_IMAGE_BYTES = 16L * 1024L * 1024L;
    public static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_SINGLE_BUFFER_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_TOTAL_BUFFER_BYTES = 128L * 1024L * 1024L;
    public static final int MAX_ANIMATIONS = 512;
    public static final int MAX_ANIMATION_CHANNELS = 512;
    public static final long MAX_ANIMATION_INPUT_SAMPLES = 131_072L;
    public static final long MAX_ANIMATION_OUTPUT_COMPONENTS = 1_000_000L;

    private GltfAssetLimits() {
    }

    static void enforce(NormalizedGltfModel source) throws GltfConversionException {
        enforce(source, MAX_SINGLE_IMAGE_BYTES, MAX_TOTAL_IMAGE_BYTES);
    }

    static void enforce(
            NormalizedGltfModel source,
            long maxSingleImageBytes,
            long maxTotalImageBytes
    ) throws GltfConversionException {
        if (maxSingleImageBytes < 0L || maxTotalImageBytes < maxSingleImageBytes) {
            throw new GltfConversionException("Invalid glTF image byte budgets");
        }
        GltfModel model = source.model();
        requireAtMost("accessors", model.getAccessorModels().size(), MAX_ACCESSORS);
        requireAtMost("buffers", model.getBufferModels().size(), MAX_BUFFERS);
        requireAtMost("buffer views", model.getBufferViewModels().size(), MAX_BUFFER_VIEWS);
        requireAtMost("meshes", model.getMeshModels().size(), MAX_MESHES);
        requireAtMost("nodes", model.getNodeModels().size(), MAX_NODES);
        requireAtMost("scenes", model.getSceneModels().size(), MAX_SCENES);
        requireAtMost("skins", model.getSkinModels().size(), MAX_SKINS);
        requireAtMost("materials", model.getMaterialModels().size(), MAX_MATERIALS);
        requireAtMost("images", model.getImageModels().size(), MAX_IMAGES);
        requireAtMost("textures", model.getTextureModels().size(), MAX_TEXTURES);
        requireAtMost("animations", model.getAnimationModels().size(), MAX_ANIMATIONS);

        long accessorComponents = 0L;
        for (AccessorModel accessor : model.getAccessorModels()) {
            long components = multiply(accessor.getCount(), accessor.getElementType().getNumComponents(),
                    "accessor components");
            accessorComponents = add(accessorComponents, components, "accessor components");
        }
        requireAtMost("accessor components", accessorComponents, MAX_ACCESSOR_COMPONENTS);

        MeshBudget meshBudget = measureMeshes(model.getMeshModels());
        requireAtMost("mesh primitives", meshBudget.primitives(), MAX_PRIMITIVES);
        requireAtMost("source vertices", meshBudget.vertices(), MAX_SOURCE_VERTICES);
        requireAtMost("source indices", meshBudget.indices(), MAX_SOURCE_INDICES);
        requireAtMost("morph targets", meshBudget.morphTargets(), MAX_TOTAL_MORPH_TARGETS);
        requireAtMost("morph vertex instances", meshBudget.morphVertexInstances(), MAX_MORPH_VERTEX_INSTANCES);

        long joints = 0L;
        for (SkinModel skin : model.getSkinModels()) {
            List<NodeModel> skinJoints = skin.getJoints();
            joints = add(joints, skinJoints == null ? 0L : skinJoints.size(), "skin joints");
        }
        requireAtMost("skin joints", joints, MAX_TOTAL_JOINTS);

        long childReferences = 0L;
        for (NodeModel node : model.getNodeModels()) {
            childReferences = add(childReferences, node.getChildren().size(), "node child references");
        }
        requireAtMost("node child references", childReferences, MAX_NODE_CHILD_REFERENCES);

        long sceneRoots = 0L;
        for (var scene : model.getSceneModels()) {
            sceneRoots = add(sceneRoots, scene.getNodeModels().size(), "scene root references");
        }
        requireAtMost("scene root references", sceneRoots, MAX_SCENE_ROOT_REFERENCES);

        long imageBytes = 0L;
        for (ImageModel image : model.getImageModels()) {
            ByteBuffer data = image.getImageData();
            long size = data == null ? 0L : data.remaining();
            requireAtMost("single image bytes", size, maxSingleImageBytes);
            imageBytes = add(imageBytes, size, "image bytes");
        }
        requireAtMost("image bytes", imageBytes, maxTotalImageBytes);

        long bufferBytes = 0L;
        for (BufferModel buffer : model.getBufferModels()) {
            long size = buffer.getByteLength();
            requireAtMost("single buffer bytes", size, MAX_SINGLE_BUFFER_BYTES);
            bufferBytes = add(bufferBytes, size, "buffer bytes");
        }
        requireAtMost("buffer bytes", bufferBytes, MAX_TOTAL_BUFFER_BYTES);

        long animationChannels = 0L;
        long animationInputSamples = 0L;
        long animationOutputComponents = 0L;
        for (AnimationModel animation : model.getAnimationModels()) {
            List<AnimationModel.Channel> channels = animation.getChannels();
            animationChannels = add(animationChannels, channels.size(), "animation channels");
            for (AnimationModel.Channel channel : channels) {
                AnimationModel.Sampler sampler = channel.getSampler();
                if (sampler == null) {
                    continue;
                }
                AccessorModel input = sampler.getInput();
                if (input != null) {
                    animationInputSamples = add(animationInputSamples, input.getCount(),
                            "animation input samples");
                }
                AccessorModel output = sampler.getOutput();
                if (output != null) {
                    long components = multiply(output.getCount(), output.getElementType().getNumComponents(),
                            "animation output components");
                    animationOutputComponents = add(animationOutputComponents, components,
                            "animation output components");
                }
            }
        }
        requireAtMost("animation channels", animationChannels, MAX_ANIMATION_CHANNELS);
        requireAtMost("animation input samples", animationInputSamples, MAX_ANIMATION_INPUT_SAMPLES);
        requireAtMost("animation output components", animationOutputComponents, MAX_ANIMATION_OUTPUT_COMPONENTS);
    }

    private static MeshBudget measureMeshes(List<MeshModel> meshes) throws GltfConversionException {
        long primitives = 0L;
        long vertices = 0L;
        long indices = 0L;
        long morphTargets = 0L;
        long morphVertexInstances = 0L;
        for (MeshModel mesh : meshes) {
            for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
                primitives = add(primitives, 1L, "mesh primitives");
                Map<String, AccessorModel> attributes = primitive.getAttributes();
                AccessorModel positions = attributes == null ? null : attributes.get("POSITION");
                long primitiveVertices = positions == null ? 0L : positions.getCount();
                long primitiveIndices = primitive.getIndices() == null
                        ? primitiveVertices
                        : primitive.getIndices().getCount();
                int primitiveMorphTargets = primitive.getTargets() == null ? 0 : primitive.getTargets().size();
                requireAtMost("morph targets per primitive", primitiveMorphTargets,
                        MAX_MORPH_TARGETS_PER_PRIMITIVE);
                vertices = add(vertices, primitiveVertices, "vertices");
                indices = add(indices, primitiveIndices, "indices");
                morphTargets = add(morphTargets, primitiveMorphTargets, "morph targets");
                morphVertexInstances = add(
                        morphVertexInstances,
                        multiply(primitiveVertices, primitiveMorphTargets, "morph vertex instances"),
                        "morph vertex instances"
                );
            }
        }
        return new MeshBudget(
                primitives,
                vertices,
                indices,
                morphTargets,
                morphVertexInstances
        );
    }

    /** Run after scene/LOD selection and simplification, before image derivation or GPU publication. */
    public static void enforceSelectedDraw(ConvertedGltfAsset asset) {
        long emittedVertices = 0L;
        long emittedTriangles = 0L;
        for (int node : asset.selectedNodeIndices()) {
            int mesh = asset.runtimeScene().nodes().get(node).meshIndex();
            if (mesh < 0) continue;
            for (GltfRenderPrimitive primitive : asset.renderMeshes().get(mesh).primitives()) {
                // The renderer expands indexed triangles per node instance, not per unique mesh.
                try {
                    emittedVertices = Math.addExact(emittedVertices, primitive.indexCount());
                    emittedTriangles = Math.addExact(emittedTriangles, primitive.indexCount() / 3L);
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("glTF selected draw budget overflow", exception);
                }
                if (emittedVertices > MAX_DRAW_VERTICES || emittedTriangles > MAX_DRAW_TRIANGLES) {
                    throw new IllegalArgumentException("glTF selected draw exceeds emitted geometry budget: "
                            + emittedVertices + " vertices / " + emittedTriangles + " triangles; max "
                            + MAX_DRAW_VERTICES + " / " + MAX_DRAW_TRIANGLES);
                }
            }
        }
    }

    private static long multiply(long left, long right, String name) throws GltfConversionException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw budgetExceeded(name, Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    private static long add(long left, long right, String name) throws GltfConversionException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw budgetExceeded(name, Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    private static void requireAtMost(String name, long actual, long limit) throws GltfConversionException {
        if (actual > limit) {
            throw budgetExceeded(name, actual, limit);
        }
    }

    private static GltfConversionException budgetExceeded(String name, long actual, long limit) {
        return new GltfConversionException(
                "glTF asset exceeds " + name + " budget: " + actual + " > " + limit
        );
    }

    private record MeshBudget(
            long primitives,
            long vertices,
            long indices,
            long morphTargets,
            long morphVertexInstances
    ) {
    }
}
