package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.GltfRuntimePolicy;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
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
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSceneTransforms;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.ElementType;
import de.javagl.jgltf.model.GltfConstants;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.ImageModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.PbrMaterialModel;
import de.javagl.jgltf.model.PbrMetallicRoughnessModel;
import de.javagl.jgltf.model.SceneModel;
import de.javagl.jgltf.model.SkinModel;
import de.javagl.jgltf.model.TextureInfoModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.Animation;
import de.javagl.jgltf.impl.v2.AnimationChannel;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JgltfRuntimeConverter {
    private static final Set<Integer> FLOAT = Set.of(GltfConstants.GL_FLOAT);
    private static final Set<Integer> TEX_COORD_COMPONENTS = Set.of(
            GltfConstants.GL_FLOAT,
            GltfConstants.GL_UNSIGNED_BYTE,
            GltfConstants.GL_UNSIGNED_SHORT
    );
    private static final Set<Integer> COLOR_COMPONENTS = TEX_COORD_COMPONENTS;
    private static final Set<Integer> JOINT_COMPONENTS = Set.of(
            GltfConstants.GL_UNSIGNED_BYTE,
            GltfConstants.GL_UNSIGNED_SHORT
    );
    private static final Set<Integer> WEIGHT_COMPONENTS = TEX_COORD_COMPONENTS;
    private static final Set<Integer> ANIMATION_ROTATION_WEIGHT_COMPONENTS = Set.of(
            GltfConstants.GL_BYTE,
            GltfConstants.GL_UNSIGNED_BYTE,
            GltfConstants.GL_SHORT,
            GltfConstants.GL_UNSIGNED_SHORT,
            GltfConstants.GL_FLOAT
    );
    private static final Set<Integer> INDEX_COMPONENTS = Set.of(
            GltfConstants.GL_UNSIGNED_BYTE,
            GltfConstants.GL_UNSIGNED_SHORT,
            GltfConstants.GL_UNSIGNED_INT
    );

    public ConvertedGltfAsset convert(NormalizedGltfModel source) throws GltfConversionException {
        return convert(source, GltfRuntimePolicy.DEFAULT);
    }

    public ConvertedGltfAsset convert(NormalizedGltfModel source, GltfRuntimePolicy.ResourceBudgets budgets)
            throws GltfConversionException {
        Objects.requireNonNull(budgets, "budgets");
        return convert(source, budgets.maxSingleImageBytes(), budgets.maxTotalImageBytes());
    }

    private ConvertedGltfAsset convert(
            NormalizedGltfModel source,
            long maxSingleImageBytes,
            long maxTotalImageBytes
    ) throws GltfConversionException {
        Objects.requireNonNull(source, "source");
        try {
            GltfRuntimePolicy.rejectRetiredOverride();
            return convertInternal(source, maxSingleImageBytes, maxTotalImageBytes);
        } catch (GltfConversionException exception) {
            throw exception;
        } catch (ArithmeticException | IllegalArgumentException | IllegalStateException exception) {
            throw new GltfConversionException("Invalid glTF 2.0 core asset: " + exception.getMessage(), exception);
        }
    }

    private ConvertedGltfAsset convertInternal(
            NormalizedGltfModel source,
            long maxSingleImageBytes,
            long maxTotalImageBytes
    ) throws GltfConversionException {
        GltfLodMetadata lods = GltfLodExtensions.read(source);
        GltfMeshAttributeRules attributeRules = GltfMeshAttributeRules.read(source);
        GltfAssetLimits.enforce(source, maxSingleImageBytes, maxTotalImageBytes);
        validateRawCoreSchema(source);
        GltfModel model = source.model();

        List<ImageModel> imageModels = model.getImageModels();
        List<MaterialModel> materialModels = model.getMaterialModels();
        List<MeshModel> meshModels = model.getMeshModels();
        List<NodeModel> nodeModels = model.getNodeModels();
        List<SkinModel> skinModels = model.getSkinModels();
        List<SceneModel> sceneModels = model.getSceneModels();

        IdentityHashMap<ImageModel, Integer> imageIndices = indexByIdentity(imageModels);
        IdentityHashMap<MaterialModel, Integer> materialIndices = indexByIdentity(materialModels);
        IdentityHashMap<MeshModel, Integer> meshIndices = indexByIdentity(meshModels);
        IdentityHashMap<NodeModel, Integer> nodeIndices = indexByIdentity(nodeModels);
        IdentityHashMap<SkinModel, Integer> skinIndices = indexByIdentity(skinModels);

        List<GltfImageData> images = convertImages(imageModels);
        List<GltfPbrMaterialData> materials = convertMaterials(materialModels, imageIndices);
        List<MeshResult> convertedMeshes = convertMeshes(meshModels, materialIndices, materials, attributeRules);
        List<GltfMesh> runtimeMeshes = convertedMeshes.stream().map(MeshResult::runtime).toList();
        List<GltfRenderMesh> renderMeshes = convertedMeshes.stream().map(MeshResult::render).toList();
        List<float[]> meshWeights = convertedMeshes.stream().map(MeshResult::weights).toList();
        List<GltfSkin> skins = convertSkins(skinModels, nodeIndices);
        NodeResult convertedNodes = convertNodes(nodeModels, nodeIndices, meshIndices, skinIndices, convertedMeshes);
        SceneResult convertedScenes = convertScenes(source, sceneModels, nodeModels, nodeIndices);

        GltfScene runtimeScene = new GltfScene(
                convertedNodes.nodes(),
                runtimeMeshes,
                skins,
                convertedScenes.activeRootNodes()
        );
        GltfSceneTransforms.computeWorldTransforms(runtimeScene);

        List<GltfAnimationClip> animations = convertAnimations(
                model.getAnimationModels(),
                nodeIndices,
                convertedNodes.nodeMorphTargetCounts()
        );
        return new ConvertedGltfAsset(
                runtimeScene,
                renderMeshes,
                materials,
                images,
                animations,
                convertedNodes.weights(),
                meshWeights,
                convertedScenes.scenes(),
                convertedScenes.defaultSceneIndex(),
                convertedScenes.activeSceneIndex(),
                lods
        );
    }

    private static void validateRawCoreSchema(NormalizedGltfModel source) throws GltfConversionException {
        List<Accessor> accessors = source.gltf().getAccessors();
        if (accessors != null) {
            for (int accessorIndex = 0; accessorIndex < accessors.size(); accessorIndex++) {
                if (accessors.get(accessorIndex).getSparse() != null) {
                    throw new GltfConversionException("sparse accessor " + accessorIndex
                            + " is fail-closed because JglTF 3.0.1 cannot preserve every base offset/stride case");
                }
            }
        }

        List<de.javagl.jgltf.impl.v2.Node> nodes = source.gltf().getNodes();
        if (nodes == null) {
            nodes = List.of();
        }
        boolean[] matrixNodes = new boolean[nodes.size()];
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            de.javagl.jgltf.impl.v2.Node node = nodes.get(nodeIndex);
            boolean hasMatrix = node.getMatrix() != null;
            boolean hasTrs = node.getTranslation() != null || node.getRotation() != null || node.getScale() != null;
            if (hasMatrix && hasTrs) {
                throw new GltfConversionException("node " + nodeIndex
                        + " declares matrix together with translation/rotation/scale");
            }
            matrixNodes[nodeIndex] = hasMatrix;
        }

        List<Animation> animations = source.gltf().getAnimations();
        if (animations != null) {
            for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++) {
                List<AnimationChannel> channels = animations.get(animationIndex).getChannels();
                if (channels == null) {
                    continue;
                }
                for (int channelIndex = 0; channelIndex < channels.size(); channelIndex++) {
                    AnimationChannel channel = channels.get(channelIndex);
                    Integer targetNode = channel.getTarget() == null ? null : channel.getTarget().getNode();
                    if (targetNode != null
                            && targetNode >= 0
                            && targetNode < matrixNodes.length
                            && matrixNodes[targetNode]) {
                        throw new GltfConversionException("animation " + animationIndex + " channel " + channelIndex
                                + " targets node " + targetNode + " that declares matrix instead of TRS");
                    }
                }
            }
        }
    }

    private static List<GltfImageData> convertImages(List<ImageModel> models) throws GltfConversionException {
        List<GltfImageData> result = new ArrayList<>(models.size());
        for (int index = 0; index < models.size(); index++) {
            ImageModel model = models.get(index);
            ByteBuffer encoded = model.getImageData();
            if (encoded == null || !encoded.hasRemaining()) {
                throw new GltfConversionException("image " + index + " has no resolved encoded bytes");
            }
            ByteBuffer copy = encoded.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            result.add(new GltfImageData(model.getName(), model.getUri(), model.getMimeType(), bytes));
        }
        return List.copyOf(result);
    }

    private static List<GltfPbrMaterialData> convertMaterials(
            List<MaterialModel> models,
            IdentityHashMap<ImageModel, Integer> imageIndices
    ) throws GltfConversionException {
        List<GltfPbrMaterialData> result = new ArrayList<>(models.size());
        for (int index = 0; index < models.size(); index++) {
            MaterialModel model = models.get(index);
            if (!(model instanceof PbrMaterialModel material)) {
                throw new GltfConversionException("material " + index + " is not a glTF 2.0 PBR material");
            }
            PbrMetallicRoughnessModel pbr = material.getPbrMetallicRoughnessModel();
            double[] baseColor = pbr == null ? null : pbr.getBaseColorFactor();
            float[] baseColorFactor = factor(baseColor, new float[]{1.0f, 1.0f, 1.0f, 1.0f}, 0.0f, 1.0f,
                    "material " + index + " baseColorFactor");
            float metallic = bounded(value(pbr == null ? null : pbr.getMetallicFactor(), 1.0), 0.0f, 1.0f,
                    "material " + index + " metallicFactor");
            float roughness = bounded(value(pbr == null ? null : pbr.getRoughnessFactor(), 1.0), 0.0f, 1.0f,
                    "material " + index + " roughnessFactor");
            float[] emissive = factor(material.getEmissiveFactor(), new float[3], 0.0f, 1.0f,
                    "material " + index + " emissiveFactor");
            float normalScale = finite(value(material.getNormalScale(), 1.0),
                    "material " + index + " normalScale");
            float occlusionStrength = bounded(value(material.getOcclusionStrength(), 1.0), 0.0f, 1.0f,
                    "material " + index + " occlusionStrength");
            float alphaCutoff = finite(value(material.getAlphaCutoff(), 0.5),
                    "material " + index + " alphaCutoff");
            if (alphaCutoff < 0.0f) {
                throw new GltfConversionException("material " + index + " alphaCutoff must be non-negative");
            }

            result.add(new GltfPbrMaterialData(
                    model.getName(),
                    baseColorFactor,
                    metallic,
                    roughness,
                    emissive,
                    normalScale,
                    occlusionStrength,
                    alphaMode(material.getAlphaMode()),
                    alphaCutoff,
                    Boolean.TRUE.equals(material.isDoubleSided()),
                    binding(pbr == null ? null : pbr.getBaseColorTexture(), imageIndices,
                            "material " + index + " baseColorTexture"),
                    binding(pbr == null ? null : pbr.getMetallicRoughnessTexture(), imageIndices,
                            "material " + index + " metallicRoughnessTexture"),
                    binding(material.getNormalTexture(), imageIndices, "material " + index + " normalTexture"),
                    binding(material.getOcclusionTexture(), imageIndices, "material " + index + " occlusionTexture"),
                    binding(material.getEmissiveTexture(), imageIndices, "material " + index + " emissiveTexture")
            ));
        }
        return List.copyOf(result);
    }

    private static GltfTextureBinding binding(
            TextureInfoModel info,
            IdentityHashMap<ImageModel, Integer> imageIndices,
            String role
    ) throws GltfConversionException {
        if (info == null) {
            return null;
        }
        int texCoord = info.getTexCoord() == null ? 0 : info.getTexCoord();
        if (texCoord != 0) {
            throw new GltfConversionException(role + " uses TEXCOORD_" + texCoord + "; only TEXCOORD_0 is supported");
        }
        TextureModel texture = info.getTextureModel();
        if (texture == null || texture.getImageModel() == null) {
            throw new GltfConversionException(role + " has no core image source");
        }
        Integer imageIndex = imageIndices.get(texture.getImageModel());
        if (imageIndex == null) {
            throw new GltfConversionException(role + " refers to an image outside the glTF image table");
        }
        int magFilter = texture.getMagFilter() == null ? GltfConstants.GL_LINEAR : texture.getMagFilter();
        int minFilter = texture.getMinFilter() == null ? GltfConstants.GL_LINEAR : texture.getMinFilter();
        int wrapS = texture.getWrapS() == null ? GltfConstants.GL_REPEAT : texture.getWrapS();
        int wrapT = texture.getWrapT() == null ? GltfConstants.GL_REPEAT : texture.getWrapT();
        validateSampler(magFilter, minFilter, wrapS, wrapT, role);
        return new GltfTextureBinding(imageIndex, texCoord, new GltfSamplerData(magFilter, minFilter, wrapS, wrapT));
    }

    private static void validateSampler(int mag, int min, int wrapS, int wrapT, String role)
            throws GltfConversionException {
        if (mag != GltfConstants.GL_NEAREST && mag != GltfConstants.GL_LINEAR) {
            throw new GltfConversionException(role + " has invalid magFilter " + mag);
        }
        if (min != GltfConstants.GL_NEAREST
                && min != GltfConstants.GL_LINEAR
                && min != GltfConstants.GL_NEAREST_MIPMAP_NEAREST
                && min != GltfConstants.GL_LINEAR_MIPMAP_NEAREST
                && min != GltfConstants.GL_NEAREST_MIPMAP_LINEAR
                && min != GltfConstants.GL_LINEAR_MIPMAP_LINEAR) {
            throw new GltfConversionException(role + " has invalid minFilter " + min);
        }
        validateWrap(wrapS, role + " wrapS");
        validateWrap(wrapT, role + " wrapT");
    }

    private static void validateWrap(int wrap, String role) throws GltfConversionException {
        if (wrap != GltfConstants.GL_REPEAT
                && wrap != GltfConstants.GL_MIRRORED_REPEAT
                && wrap != GltfConstants.GL_CLAMP_TO_EDGE) {
            throw new GltfConversionException(role + " has invalid wrap mode " + wrap);
        }
    }

    private static List<MeshResult> convertMeshes(
            List<MeshModel> models,
            IdentityHashMap<MaterialModel, Integer> materialIndices,
            List<GltfPbrMaterialData> materials,
            GltfMeshAttributeRules attributeRules
    ) throws GltfConversionException {
        List<MeshResult> result = new ArrayList<>(models.size());
        for (int meshIndex = 0; meshIndex < models.size(); meshIndex++) {
            MeshModel model = models.get(meshIndex);
            List<MeshPrimitiveModel> primitives = model.getMeshPrimitiveModels();
            int morphTargetCount = uniformMorphTargetCount(primitives, meshIndex);
            float[] weights = morphWeights(model.getWeights(), morphTargetCount, "mesh " + meshIndex + " weights");
            List<GltfMeshPrimitive> runtimePrimitives = new ArrayList<>(primitives.size());
            List<GltfRenderPrimitive> renderPrimitives = new ArrayList<>(primitives.size());
            for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++) {
                PrimitiveResult primitive = convertPrimitive(
                        primitives.get(primitiveIndex),
                        materialIndices,
                        materials,
                        attributeRules,
                        "mesh " + meshIndex + " primitive " + primitiveIndex
                );
                runtimePrimitives.add(primitive.runtime());
                renderPrimitives.add(primitive.render());
            }
            result.add(new MeshResult(
                    new GltfMesh(runtimePrimitives),
                    new GltfRenderMesh(model.getName(), renderPrimitives, weights),
                    weights,
                    morphTargetCount
            ));
        }
        return List.copyOf(result);
    }

    private static PrimitiveResult convertPrimitive(
            MeshPrimitiveModel model,
            IdentityHashMap<MaterialModel, Integer> materialIndices,
            List<GltfPbrMaterialData> materials,
            GltfMeshAttributeRules attributeRules,
            String role
    ) throws GltfConversionException {
        if (model.getMode() != GltfConstants.GL_TRIANGLES) {
            throw new GltfConversionException(role + " mode must be TRIANGLES, not " + model.getMode());
        }
        Map<String, AccessorModel> attributes = model.getAttributes();
        AccessorModel positionsAccessor = attributes.get("POSITION");
        if (positionsAccessor == null) {
            throw new GltfConversionException(role + " is missing required POSITION");
        }
        rejectSecondSkinSet(attributes, role);
        for (String semantic : attributes.keySet()) {
            if (semantic.startsWith("TEXCOORD_") && !semantic.equals("TEXCOORD_0")) {
                throw new GltfConversionException(role + " has unsupported semantic " + semantic);
            }
        }
        if (attributes.containsKey("TANGENT")) {
            throw new GltfConversionException(role
                    + " TANGENT is fail-closed in V1 because the renderer does not consume explicit tangents");
        }
        int materialIndex = materialIndex(model.getMaterialModel(), materialIndices, role);
        AccessorModel texCoordsAccessor = attributes.get("TEXCOORD_0");
        if (materialIndex >= 0 && hasTextureBinding(materials.get(materialIndex)) && texCoordsAccessor == null) {
            throw new GltfConversionException(role + " material " + materialIndex + " requires TEXCOORD_0");
        }
        int vertexCount = positionsAccessor.getCount();
        float[] positions = attributeRules.read(positionsAccessor, "POSITION", false, vertexCount, role + " POSITION");
        float[] normals = attributeRules.read(attributes.get("NORMAL"), "NORMAL", false, vertexCount, role + " NORMAL");
        float[] tangents = new float[0];
        float[] texCoords = attributeRules.read(texCoordsAccessor, "TEXCOORD_0", false, vertexCount, role + " TEXCOORD_0");
        float[] colors = readColors(attributes.get("COLOR_0"), vertexCount, role + " COLOR_0");
        int[] joints = optionalInts(attributes.get("JOINTS_0"), ElementType.VEC4, JOINT_COMPONENTS,
                vertexCount, role + " JOINTS_0");
        float[] weights = optionalFloats(attributes.get("WEIGHTS_0"), ElementType.VEC4,
                WEIGHT_COMPONENTS, true, vertexCount, role + " WEIGHTS_0");
        if ((joints.length == 0) != (weights.length == 0)) {
            throw new GltfConversionException(role + " JOINTS_0 and WEIGHTS_0 must both be present or both be absent");
        }
        List<GltfMorphTarget> morphTargets = convertMorphTargets(
                model.getTargets(), vertexCount, attributes.containsKey("NORMAL"), attributeRules, role
        );
        GltfMeshPrimitive runtime = new GltfMeshPrimitive(
                positions,
                normals,
                tangents,
                joints,
                weights,
                morphTargets,
                new GltfMaterialReference(materialIndex < 0 ? "default" : "material:" + materialIndex)
        );
        int[] indices = readIndices(model.getIndices(), vertexCount, role);
        return new PrimitiveResult(
                runtime,
                new GltfRenderPrimitive(runtime, indices, texCoords, colors, materialIndex)
        );
    }

    private static boolean hasTextureBinding(GltfPbrMaterialData material) {
        return material.baseColorTexture() != null
                || material.metallicRoughnessTexture() != null
                || material.normalTexture() != null
                || material.occlusionTexture() != null
                || material.emissiveTexture() != null;
    }

    private static void rejectSecondSkinSet(Map<String, AccessorModel> attributes, String role)
            throws GltfConversionException {
        if (attributes.containsKey("JOINTS_1") || attributes.containsKey("WEIGHTS_1")) {
            throw new GltfConversionException(role + " uses JOINTS_1/WEIGHTS_1; only four JOINTS_0 weights are supported");
        }
    }

    private static List<GltfMorphTarget> convertMorphTargets(
            List<Map<String, AccessorModel>> targets,
            int vertexCount,
            boolean hasBaseNormals,
            GltfMeshAttributeRules attributeRules,
            String role
    ) throws GltfConversionException {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<GltfMorphTarget> result = new ArrayList<>(targets.size());
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            Map<String, AccessorModel> target = targets.get(targetIndex);
            for (String semantic : target.keySet()) {
                if (!semantic.equals("POSITION") && !semantic.equals("NORMAL") && !semantic.equals("TANGENT")) {
                    throw new GltfConversionException(role + " morph target " + targetIndex
                            + " has unsupported semantic " + semantic);
                }
            }
            String targetRole = role + " morph target " + targetIndex;
            if (target.containsKey("TANGENT")) {
                throw new GltfConversionException(targetRole
                        + " TANGENT is fail-closed in V1 because the renderer does not consume explicit tangents");
            }
            if (target.containsKey("NORMAL") && !hasBaseNormals) {
                throw new GltfConversionException(targetRole + " NORMAL requires base NORMAL");
            }
            result.add(new GltfMorphTarget(
                    attributeRules.read(target.get("POSITION"), "POSITION", true, vertexCount, targetRole + " POSITION"),
                    attributeRules.read(target.get("NORMAL"), "NORMAL", true, vertexCount, targetRole + " NORMAL"),
                    new float[0]
            ));
        }
        return List.copyOf(result);
    }

    private static int[] readIndices(AccessorModel accessor, int vertexCount, String role)
            throws GltfConversionException {
        int[] indices;
        if (accessor == null) {
            indices = new int[vertexCount];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i;
            }
        } else {
            indices = GltfAccessorReader.readUnsignedInts(
                    accessor, ElementType.SCALAR, INDEX_COMPONENTS, role + " indices"
            );
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new GltfConversionException(role + " TRIANGLES index count must be a non-empty multiple of 3");
        }
        for (int index : indices) {
            if (index >= vertexCount) {
                throw new GltfConversionException(role + " index " + index
                        + " is outside POSITION count " + vertexCount);
            }
        }
        return indices;
    }

    private static float[] optionalFloats(
            AccessorModel accessor,
            ElementType type,
            Set<Integer> components,
            boolean normalizedIntegers,
            int count,
            String role
    ) throws GltfConversionException {
        if (accessor == null) {
            return new float[0];
        }
        GltfAccessorReader.requireCount(accessor, count, role);
        return GltfAccessorReader.readFloats(accessor, type, components, normalizedIntegers, role);
    }

    private static int[] optionalInts(
            AccessorModel accessor,
            ElementType type,
            Set<Integer> components,
            int count,
            String role
    ) throws GltfConversionException {
        if (accessor == null) {
            return new int[0];
        }
        GltfAccessorReader.requireCount(accessor, count, role);
        return GltfAccessorReader.readUnsignedInts(accessor, type, components, role);
    }

    private static float[] readColors(AccessorModel accessor, int count, String role)
            throws GltfConversionException {
        if (accessor == null) {
            return new float[0];
        }
        GltfAccessorReader.requireCount(accessor, count, role);
        ElementType type = accessor.getElementType();
        if (type != ElementType.VEC3 && type != ElementType.VEC4) {
            throw new GltfConversionException(role + " must have type VEC3 or VEC4");
        }
        float[] source = GltfAccessorReader.readFloats(accessor, type, COLOR_COMPONENTS, true, role);
        if (type == ElementType.VEC4) {
            return source;
        }
        float[] rgba = new float[count * 4];
        for (int vertex = 0; vertex < count; vertex++) {
            System.arraycopy(source, vertex * 3, rgba, vertex * 4, 3);
            rgba[vertex * 4 + 3] = 1.0f;
        }
        return rgba;
    }

    private static int materialIndex(
            MaterialModel material,
            IdentityHashMap<MaterialModel, Integer> materialIndices,
            String role
    ) throws GltfConversionException {
        if (material == null) {
            return -1;
        }
        Integer index = materialIndices.get(material);
        if (index == null) {
            throw new GltfConversionException(role + " material is outside the material table");
        }
        return index;
    }

    private static int uniformMorphTargetCount(List<MeshPrimitiveModel> primitives, int meshIndex)
            throws GltfConversionException {
        int targetCount = -1;
        for (MeshPrimitiveModel primitive : primitives) {
            int count = primitive.getTargets() == null ? 0 : primitive.getTargets().size();
            if (targetCount < 0) {
                targetCount = count;
            } else if (targetCount != count) {
                throw new GltfConversionException("mesh " + meshIndex
                        + " primitives do not have a uniform morph target count");
            }
        }
        return Math.max(targetCount, 0);
    }

    private static List<GltfSkin> convertSkins(
            List<SkinModel> models,
            IdentityHashMap<NodeModel, Integer> nodeIndices
    ) throws GltfConversionException {
        List<GltfSkin> result = new ArrayList<>(models.size());
        for (int skinIndex = 0; skinIndex < models.size(); skinIndex++) {
            SkinModel model = models.get(skinIndex);
            List<NodeModel> jointModels = model.getJoints();
            if (jointModels == null || jointModels.isEmpty()) {
                throw new GltfConversionException("skin " + skinIndex + " contains no joints");
            }
            int[] joints = new int[jointModels.size()];
            for (int joint = 0; joint < joints.length; joint++) {
                joints[joint] = requireIdentityIndex(
                        nodeIndices, jointModels.get(joint), "skin " + skinIndex + " joint " + joint
                );
            }
            List<Matrix4f> inverseBindMatrices = inverseBindMatrices(model, joints.length, skinIndex);
            result.add(new GltfSkin(joints, inverseBindMatrices));
        }
        return List.copyOf(result);
    }

    private static List<Matrix4f> inverseBindMatrices(SkinModel skin, int jointCount, int skinIndex)
            throws GltfConversionException {
        AccessorModel accessor = skin.getInverseBindMatrices();
        if (accessor == null) {
            List<Matrix4f> identities = new ArrayList<>(jointCount);
            for (int i = 0; i < jointCount; i++) {
                identities.add(new Matrix4f());
            }
            return identities;
        }
        float[] matrices = GltfAccessorReader.readFloatPrefix(
                accessor,
                ElementType.MAT4,
                FLOAT,
                false,
                jointCount,
                "skin " + skinIndex + " inverseBindMatrices"
        );
        List<Matrix4f> result = new ArrayList<>(jointCount);
        for (int joint = 0; joint < jointCount; joint++) {
            result.add(new Matrix4f().set(matrices, joint * 16));
        }
        return result;
    }

    private static NodeResult convertNodes(
            List<NodeModel> models,
            IdentityHashMap<NodeModel, Integer> nodeIndices,
            IdentityHashMap<MeshModel, Integer> meshIndices,
            IdentityHashMap<SkinModel, Integer> skinIndices,
            List<MeshResult> meshes
    ) throws GltfConversionException {
        List<GltfNode> result = new ArrayList<>(models.size());
        List<float[]> weights = new ArrayList<>(models.size());
        int[] targetCounts = new int[models.size()];
        for (int nodeIndex = 0; nodeIndex < models.size(); nodeIndex++) {
            NodeModel model = models.get(nodeIndex);
            int meshIndex = nodeMeshIndex(model, meshIndices, nodeIndex);
            int skinIndex = model.getSkinModel() == null
                    ? -1
                    : requireIdentityIndex(skinIndices, model.getSkinModel(), "node " + nodeIndex + " skin");
            int[] children = new int[model.getChildren().size()];
            for (int child = 0; child < children.length; child++) {
                children[child] = requireIdentityIndex(
                        nodeIndices, model.getChildren().get(child), "node " + nodeIndex + " child " + child
                );
            }
            int morphTargetCount = meshIndex < 0 ? 0 : meshes.get(meshIndex).morphTargetCount();
            float[] nodeWeights = morphWeights(model.getWeights(), morphTargetCount, "node " + nodeIndex + " weights");
            if (meshIndex < 0 && nodeWeights.length != 0) {
                throw new GltfConversionException("node " + nodeIndex + " has morph weights but no mesh");
            }
            result.add(new GltfNode(
                    model.getName(),
                    GltfTransformConverter.convert(model, "node " + nodeIndex),
                    children,
                    meshIndex,
                    skinIndex
            ));
            weights.add(nodeWeights);
            targetCounts[nodeIndex] = morphTargetCount;
        }
        return new NodeResult(List.copyOf(result), List.copyOf(weights), targetCounts);
    }

    private static int nodeMeshIndex(
            NodeModel node,
            IdentityHashMap<MeshModel, Integer> meshIndices,
            int nodeIndex
    ) throws GltfConversionException {
        List<MeshModel> meshes = node.getMeshModels();
        if (meshes == null || meshes.isEmpty()) {
            return -1;
        }
        if (meshes.size() != 1) {
            throw new GltfConversionException("node " + nodeIndex + " refers to multiple meshes");
        }
        return requireIdentityIndex(meshIndices, meshes.getFirst(), "node " + nodeIndex + " mesh");
    }

    private static SceneResult convertScenes(
            NormalizedGltfModel source,
            List<SceneModel> models,
            List<NodeModel> nodes,
            IdentityHashMap<NodeModel, Integer> nodeIndices
    ) throws GltfConversionException {
        List<GltfSceneData> scenes = new ArrayList<>(models.size());
        for (int sceneIndex = 0; sceneIndex < models.size(); sceneIndex++) {
            SceneModel model = models.get(sceneIndex);
            int[] roots = new int[model.getNodeModels().size()];
            for (int root = 0; root < roots.length; root++) {
                NodeModel rootNode = model.getNodeModels().get(root);
                if (rootNode.getParent() != null) {
                    throw new GltfConversionException("scene " + sceneIndex + " root node has a parent");
                }
                roots[root] = requireIdentityIndex(nodeIndices, rootNode, "scene " + sceneIndex + " root " + root);
            }
            scenes.add(new GltfSceneData(model.getName(), roots));
        }
        Integer declared = source.gltf().getScene();
        int defaultScene = declared == null ? -1 : declared;
        if (defaultScene < -1 || defaultScene >= scenes.size()) {
            throw new GltfConversionException("default scene index is out of range: " + defaultScene);
        }
        int activeScene = defaultScene >= 0 ? defaultScene : (scenes.isEmpty() ? -1 : 0);
        int[] activeRoots = activeScene >= 0
                ? scenes.get(activeScene).rootNodes()
                : parentlessNodes(nodes, nodeIndices);
        return new SceneResult(List.copyOf(scenes), defaultScene, activeScene, activeRoots);
    }

    private static int[] parentlessNodes(
            List<NodeModel> nodes,
            IdentityHashMap<NodeModel, Integer> nodeIndices
    ) throws GltfConversionException {
        int[] roots = new int[(int) nodes.stream().filter(node -> node.getParent() == null).count()];
        int cursor = 0;
        for (NodeModel node : nodes) {
            if (node.getParent() == null) {
                roots[cursor++] = requireIdentityIndex(nodeIndices, node, "root node");
            }
        }
        return roots;
    }

    private static List<GltfAnimationClip> convertAnimations(
            List<AnimationModel> models,
            IdentityHashMap<NodeModel, Integer> nodeIndices,
            int[] nodeMorphTargetCounts
    ) throws GltfConversionException {
        List<GltfAnimationClip> result = new ArrayList<>(models.size());
        for (int animationIndex = 0; animationIndex < models.size(); animationIndex++) {
            AnimationModel model = models.get(animationIndex);
            List<GltfAnimationChannel> channels = new ArrayList<>(model.getChannels().size());
            float duration = 0.0f;
            for (int channelIndex = 0; channelIndex < model.getChannels().size(); channelIndex++) {
                AnimationModel.Channel channel = model.getChannels().get(channelIndex);
                int nodeIndex = requireIdentityIndex(
                        nodeIndices, channel.getNodeModel(),
                        "animation " + animationIndex + " channel " + channelIndex + " target"
                );
                AnimationResult converted = convertAnimationChannel(
                        channel,
                        nodeIndex,
                        nodeMorphTargetCounts[nodeIndex],
                        "animation " + animationIndex + " channel " + channelIndex
                );
                channels.add(new GltfAnimationChannel(nodeIndex, converted.sampler()));
                duration = Math.max(duration, converted.duration());
            }
            String name = model.getName();
            if (name == null || name.isBlank()) {
                name = "animation_" + animationIndex;
            }
            result.add(new GltfAnimationClip(name, duration, new GltfAnimation(channels)));
        }
        return List.copyOf(result);
    }

    private static AnimationResult convertAnimationChannel(
            AnimationModel.Channel channel,
            int nodeIndex,
            int morphTargetCount,
            String role
    ) throws GltfConversionException {
        AnimationModel.Sampler source = channel.getSampler();
        if (source == null) {
            throw new GltfConversionException(role + " has no sampler");
        }
        AccessorModel input = source.getInput();
        float[] times = GltfAccessorReader.readFloats(input, ElementType.SCALAR, FLOAT, false, role + " input");
        GltfInterpolation interpolation = interpolation(source.getInterpolation());
        int multiplier = interpolation == GltfInterpolation.CUBICSPLINE ? 3 : 1;
        String pathName = channel.getPath();
        GltfAnimationPath path;
        int components;
        ElementType outputType;
        if ("translation".equals(pathName)) {
            path = GltfAnimationPath.TRANSLATION;
            components = 3;
            outputType = ElementType.VEC3;
        } else if ("rotation".equals(pathName)) {
            path = GltfAnimationPath.ROTATION;
            components = 4;
            outputType = ElementType.VEC4;
        } else if ("scale".equals(pathName)) {
            path = GltfAnimationPath.SCALE;
            components = 3;
            outputType = ElementType.VEC3;
        } else if ("weights".equals(pathName)) {
            path = GltfAnimationPath.WEIGHTS;
            if (morphTargetCount <= 0) {
                throw new GltfConversionException(role + " targets weights on node " + nodeIndex
                        + " without morph targets");
            }
            components = morphTargetCount;
            outputType = ElementType.SCALAR;
        } else {
            throw new GltfConversionException(role + " has unsupported target path " + pathName);
        }

        AccessorModel output = source.getOutput();
        Set<Integer> outputComponents = path == GltfAnimationPath.ROTATION || path == GltfAnimationPath.WEIGHTS
                ? ANIMATION_ROTATION_WEIGHT_COMPONENTS
                : FLOAT;
        boolean normalizedIntegerOutput = path == GltfAnimationPath.ROTATION || path == GltfAnimationPath.WEIGHTS;
        float[] values = GltfAccessorReader.readFloats(
                output,
                outputType,
                outputComponents,
                normalizedIntegerOutput,
                role + " output"
        );
        int expectedValueCount = Math.multiplyExact(Math.multiplyExact(times.length, components), multiplier);
        if (values.length != expectedValueCount) {
            throw new GltfConversionException(role + " " + pathName + " output arity/count is "
                    + values.length + " but expected " + expectedValueCount);
        }
        GltfAnimationSampler sampler = switch (path) {
            case TRANSLATION -> GltfAnimationSampler.translation(interpolation, times, values);
            case ROTATION -> GltfAnimationSampler.rotation(interpolation, times, values);
            case SCALE -> GltfAnimationSampler.scale(interpolation, times, values);
            case WEIGHTS -> GltfAnimationSampler.weights(components, interpolation, times, values);
        };
        float duration = times.length == 0 ? 0.0f : times[times.length - 1];
        return new AnimationResult(sampler, duration);
    }

    private static GltfInterpolation interpolation(AnimationModel.Interpolation interpolation) {
        if (interpolation == null || interpolation == AnimationModel.Interpolation.LINEAR) {
            return GltfInterpolation.LINEAR;
        }
        return switch (interpolation) {
            case STEP -> GltfInterpolation.STEP;
            case LINEAR -> GltfInterpolation.LINEAR;
            case CUBICSPLINE -> GltfInterpolation.CUBICSPLINE;
        };
    }

    private static GltfAlphaMode alphaMode(PbrMaterialModel.AlphaMode alphaMode) {
        if (alphaMode == null || alphaMode == PbrMaterialModel.AlphaMode.OPAQUE) {
            return GltfAlphaMode.OPAQUE;
        }
        return switch (alphaMode) {
            case OPAQUE -> GltfAlphaMode.OPAQUE;
            case MASK -> GltfAlphaMode.MASK;
            case BLEND -> GltfAlphaMode.BLEND;
        };
    }

    private static float[] morphWeights(double[] values, int targetCount, String role)
            throws GltfConversionException {
        if (values == null) {
            return new float[0];
        }
        if (values.length != targetCount) {
            throw new GltfConversionException(role + " count is " + values.length
                    + " but morph target count is " + targetCount);
        }
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = finite(values[i], role);
        }
        return result;
    }

    private static float[] factor(double[] values, float[] fallback, float min, float max, String role)
            throws GltfConversionException {
        if (values == null) {
            return fallback.clone();
        }
        if (values.length != fallback.length) {
            throw new GltfConversionException(role + " length must be " + fallback.length);
        }
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = bounded(values[i], min, max, role);
        }
        return result;
    }

    private static float bounded(double value, float min, float max, String role) throws GltfConversionException {
        float converted = finite(value, role);
        if (converted < min || converted > max) {
            throw new GltfConversionException(role + " must be in [" + min + ", " + max + "]");
        }
        return converted;
    }

    private static float finite(double value, String role) throws GltfConversionException {
        if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new GltfConversionException(role + " must be a finite float");
        }
        return (float) value;
    }

    private static double value(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static <T> IdentityHashMap<T, Integer> indexByIdentity(List<T> values) {
        IdentityHashMap<T, Integer> result = new IdentityHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            result.put(values.get(index), index);
        }
        return result;
    }

    private static <T> int requireIdentityIndex(
            IdentityHashMap<T, Integer> indices,
            T value,
            String role
    ) throws GltfConversionException {
        Integer index = indices.get(value);
        if (index == null) {
            throw new GltfConversionException(role + " is outside its glTF table");
        }
        return index;
    }

    private record PrimitiveResult(GltfMeshPrimitive runtime, GltfRenderPrimitive render) {
    }

    private record MeshResult(GltfMesh runtime, GltfRenderMesh render, float[] weights, int morphTargetCount) {
        private MeshResult {
            weights = weights.clone();
        }

        @Override
        public float[] weights() {
            return weights.clone();
        }
    }

    private record NodeResult(List<GltfNode> nodes, List<float[]> weights, int[] nodeMorphTargetCounts) {
        private NodeResult {
            nodeMorphTargetCounts = nodeMorphTargetCounts.clone();
        }

        @Override
        public int[] nodeMorphTargetCounts() {
            return nodeMorphTargetCounts.clone();
        }
    }

    private record SceneResult(
            List<GltfSceneData> scenes,
            int defaultSceneIndex,
            int activeSceneIndex,
            int[] activeRootNodes
    ) {
        private SceneResult {
            activeRootNodes = activeRootNodes.clone();
        }

        @Override
        public int[] activeRootNodes() {
            return activeRootNodes.clone();
        }
    }

    private record AnimationResult(GltfAnimationSampler sampler, float duration) {
    }
}
