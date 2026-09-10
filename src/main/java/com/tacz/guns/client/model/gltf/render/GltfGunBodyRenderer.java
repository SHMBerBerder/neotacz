package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.GunMod;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.config.client.ResourceConfig;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfPbrMaterialData;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.convert.GltfTextureBinding;
import com.tacz.guns.client.model.gltf.runtime.DeformedPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationPath;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.client.model.gltf.quality.TextureVariantPolicy;
import com.tacz.guns.client.model.gltf.quality.TextureImageFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/**
 * Optional glTF gun-body adapter. Construction is CPU-only; immutable geometry snapshots, texture
 * upload and RenderType resolution all finish before retained callbacks are queued. Static assets
 * are CPU-baked once; rigid authored-normal geometry shares mesh-local arrays across animated
 * transform snapshots. Skinning and morphing retain the CPU deformation path. The Bedrock model
 * remains the gameplay/attachment rig and fallback.
 */
public final class GltfGunBodyRenderer implements GunBodyRenderer {
    // This is a fail-safe for the CPU deformation/triangle-expansion path, not a performance
    // target. High-detail gun assets still need offline LOD/simplification and in-game profiling.
    static final long MAX_EMITTED_VERTICES = GltfAssetLimits.MAX_DRAW_VERTICES;
    static final long MAX_EMITTED_TRIANGLES = GltfAssetLimits.MAX_DRAW_TRIANGLES;

    @Nullable
    private static GltfPbrDynamicTextureCache textureCache;

    private final Identifier modelId;
    private final ConvertedGltfAsset asset;
    private final GltfModelManager manager;
    private final long generation;
    private final float scale;
    @Nullable
    private final GltfAnimationClip animation;
    private final boolean loopAnimation;
    private final float animationSpeed;
    private final GltfNodeMapBridge nodeMapBridge;
    @Nullable
    private final BedrockGunModel mappedRig;
    private record TextureSourceKey(int imageIndex, GltfPbrTextureSource.Settings settings) { }
    private final Map<TextureSourceKey, GltfPbrTextureSource> imageSources = new HashMap<>();
    private final List<PreparedMaterial> materials;
    private final PreparedMaterial defaultMaterial;
    private final Map<GltfRenderPrimitive, GltfPreparedGeometry> rigidGeometryCache = new IdentityHashMap<>();
    @Nullable
    private final PreparedFrame staticPreparedFrame;
    private final Object guiIconIdentity = new Object();
    @Nullable
    private final GltfGuiIconRenderer.Frame guiFrame;
    private boolean guiIconFailed;

    public GltfGunBodyRenderer(
            Identifier modelId,
            ConvertedGltfAsset asset,
            GltfModelManager manager,
            long generation,
            GunRenderModelConfig config
    ) {
        this(modelId, asset, manager, generation, config, null);
    }

    public GltfGunBodyRenderer(
            Identifier modelId,
            ConvertedGltfAsset asset,
            GltfModelManager manager,
            long generation,
            GunRenderModelConfig config,
            @Nullable BedrockGunModel rig
    ) {
        this(modelId, asset, manager, generation, config, rig, Set.of());
    }

    public GltfGunBodyRenderer(
            Identifier modelId,
            ConvertedGltfAsset asset,
            GltfModelManager manager,
            long generation,
            GunRenderModelConfig config,
            @Nullable BedrockGunModel rig,
            Set<String> additionalProtectedSources
    ) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.asset = Objects.requireNonNull(asset, "asset");
        this.manager = Objects.requireNonNull(manager, "manager");
        if (generation < 0L) {
            throw new IllegalArgumentException("glTF generation must be non-negative");
        }
        this.generation = generation;

        Objects.requireNonNull(config, "config").validate();
        Map<String, String> nodeMap = Map.copyOf(config.getNodeMap());
        if (!config.isGltf() || !modelId.equals(config.getLocation())) {
            throw new IllegalArgumentException("glTF renderer model id must match render_model.location");
        }
        if (!nodeMap.isEmpty() && rig == null) {
            throw new IllegalArgumentException("render_model.node_map requires its Bedrock rig");
        }
        this.scale = config.getScale();
        this.loopAnimation = config.isLoopAnimation();
        this.animationSpeed = config.getAnimationSpeed();
        if (config.getAnimation() != null && !loopAnimation) {
            throw new IllegalArgumentException(
                    "non-looping glTF animation needs per-instance trigger state and is not supported by V1"
            );
        }

        validateAssetStructure(asset);
        this.animation = findAnimation(asset, config.getAnimation());
        this.nodeMapBridge = GltfNodeMapBridge.create(
                asset,
                nodeMap,
                additionalProtectedSources,
                rig,
                scale,
                animation
        );
        // A mapped visual body may only sample the exact Bedrock rig whose bind correction was
        // validated at construction. Bedrock remains authoritative for gameplay and attachment data.
        this.mappedRig = nodeMapBridge.isEmpty() ? null : rig;
        // Validate the selected graph and bind/default morph state while construction is still
        // background-safe. GPU resources remain untouched until submit.
        GltfGunBodyPose.Frame initialFrame = samplePose(0.0);
        validateEmissionBudget(asset, initialFrame);
        // Static bodies already amortize world-space baking across every submit. Keep that path
        // free of per-vertex node transforms; local caching is only useful for changing poses.
        if (animation != null || !nodeMapBridge.isEmpty()) {
            initializeRigidGeometryCache(initialFrame);
        }
        this.materials = prepareMaterials(asset.materials());
        this.defaultMaterial = prepareMaterial(GltfPbrMaterialData.defaultMaterial(), -1);
        // With neither an embedded animation nor mapped Bedrock nodes, bind TRS, default morph
        // weights and skin matrices are immutable. Bake that path once instead of deforming per frame.
        this.staticPreparedFrame = animation == null && nodeMapBridge.isEmpty()
                ? prepareFrame(initialFrame)
                : null;
        // An icon must not capture a live bolt/root pose or even the first embedded animation key.
        // Static bodies share their existing geometry; mapped/animated bodies prepare this default pose once.
        this.guiFrame = prepareGuiFrame();
    }

    @Nullable
    private GltfGuiIconRenderer.Frame prepareGuiFrame() {
        try {
            return GltfGuiIconRenderer.prepare(staticPreparedFrame != null ? staticPreparedFrame
                    : prepareFrame(GltfGunBodyPose.sample(asset, null, 0.0)));
        } catch (RuntimeException failure) {
            // An optional default-pose icon must not reject geometry valid in its live rig pose.
            GunMod.LOGGER.warn("Unable to prepare static glTF GUI icon for {}", modelId, failure);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return manager.getGeneration() == generation;
    }

    /** A token with no back-reference: native atlas keys must not keep obsolete CPU assets alive. */
    public Object guiIconIdentity() {
        return guiIconIdentity;
    }

    boolean isGuiIconAvailable() {
        return isAvailable() && guiFrame != null && !guiIconFailed;
    }

    @Nullable
    GltfGuiIconRenderer.Frame guiFrame() {
        return guiFrame;
    }

    public boolean submitGuiIcon(PoseStack poseStack, @Nullable OrderedSubmitNodeCollector collector, int light, int overlay) {
        if (!isGuiIconAvailable() || collector == null) return false;
        RenderSystem.assertOnRenderThread();
        poseStack.pushPose();
        try {
            poseStack.mulPose(guiFrame.transform());
            return submitPreparedFrame(guiFrame.prepared(), poseStack, collector, light, overlay);
        } catch (RuntimeException failure) {
            guiIconFailed = true;
            GunMod.LOGGER.warn("Disabling failed glTF GUI icon for {}", modelId, failure);
            // A collector may already own nodes. Change the next frame's key rather than overdraw them now.
            return true;
        } finally {
            poseStack.popPose();
        }
    }

    /** Selected-LOD materials, including temporarily hidden parts, prepared before leaving a loading overlay. */
    public List<Runnable> prepareResourceUploads() {
        Set<Integer> selected = new TreeSet<>();
        for (int mesh : asset.selectedMeshIndices()) {
            for (GltfRenderPrimitive primitive : asset.renderMeshes().get(mesh).primitives()) {
                selected.add(primitive.materialIndex());
            }
        }
        List<Runnable> uploads = new ArrayList<>(selected.size());
        for (int material : selected) {
            uploads.add(() -> {
                if (!isAvailable()) throw new CancellationException("glTF resource generation changed before upload");
                resolveMaterial(textureCache(), material);
            });
        }
        return List.copyOf(uploads);
    }

    @Override
    public boolean submit(
            BedrockGunModel bedrockRig,
            PoseStack poseStack,
            ItemStack gunItem,
            ItemDisplayContext transformType,
            OrderedSubmitNodeCollector collector,
            int light,
            int overlay
    ) {
        // Resource reload invalidates both parsed bytes and GPU derivatives. Returning false keeps
        // the current frame on the ordinary Bedrock fallback without touching stale content.
        if (manager.getGeneration() != generation || collector == null) {
            return false;
        }
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(bedrockRig, "bedrockRig");
        RenderSystem.assertOnRenderThread();
        BedrockPart rootNode = bedrockRig.getRootNode();
        if (rootNode == null) {
            return false;
        }
        if (mappedRig != null && bedrockRig != mappedRig) {
            return false;
        }

        PreparedFrame frame = preparedFrameAtTime(currentAnimationTime());
        poseStack.pushPose();
        try {
            // The visual body inherits the live rig root; the static GUI path deliberately does not.
            applyBedrockRootTransform(rootNode, poseStack);
            // Match the existing Bedrock gun-body basis while config scale converts glTF metres.
            poseStack.scale(-scale, -scale, scale);
            return submitPreparedFrame(frame, poseStack, collector, light, overlay);
        } finally {
            poseStack.popPose();
        }
    }

    /** The caller supplies the mount origin, without the legacy Bedrock 24/16 model offset. */
    public boolean submitAttachment(PoseStack poseStack, ItemDisplayContext transformType,
                                    OrderedSubmitNodeCollector collector, int light, int overlay) {
        if (!isAvailable() || collector == null) return false;
        if (mappedRig != null) throw new IllegalStateException("An attachment must not bind a gun rig");
        RenderSystem.assertOnRenderThread();
        poseStack.pushPose();
        try {
            applyAttachmentBasis(poseStack, scale);
            return submitPreparedFrame(preparedFrameAtTime(currentAnimationTime()), poseStack, collector, light, overlay);
        } finally {
            poseStack.popPose();
        }
    }

    static void applyAttachmentBasis(PoseStack poseStack, float scale) {
        poseStack.scale(-scale, -scale, scale);
    }

    private boolean submitPreparedFrame(PreparedFrame frame, PoseStack poseStack,
                                        OrderedSubmitNodeCollector collector, int light, int overlay) {
        List<PreparedPrimitive> prepared = frame.primitives();
        if (prepared.isEmpty()) {
            // Intentional invisibility is a handled custom frame, not permission to draw the
            // Bedrock fallback. An actually empty asset remains a construction-time error.
            return frame.allHidden() && manager.getGeneration() == generation;
        }

        // Resolve every texture and RenderType before the first node is queued. A later failure can
        // therefore never leave a half-submitted glTF body in the retained collector.
        GltfPbrDynamicTextureCache dynamicTextures = textureCache();
        Map<Integer, ResolvedMaterial> resolvedMaterials = new HashMap<>();
        List<Submission> submissions = new ArrayList<>(prepared.size());
        for (PreparedPrimitive primitive : prepared) {
            int materialIndex = primitive.materialIndex();
            ResolvedMaterial resolved = resolvedMaterials.computeIfAbsent(
                    materialIndex,
                    ignored -> resolveMaterial(dynamicTextures, materialIndex)
            );
            submissions.add(new Submission(
                    primitive.sequence(),
                    primitive.geometry(),
                    resolved.renderType(),
                    resolved.alphaMode(),
                    0.0f
            ));
        }

        Matrix4f modelView = captureSortModelView(RenderSystem.getModelViewStack(), poseStack.last().pose());
        submissions = sortSubmissions(submissions, modelView);
        if (manager.getGeneration() != generation) {
            return false;
        }
        for (Submission submission : submissions) {
            GltfPreparedGeometry geometry = submission.geometry();
            collector.submitCustomGeometry(poseStack, submission.renderType(), (pose, buffer) ->
                    geometry.emit(pose, buffer, light, overlay));
        }
        return true;
    }

    /** Releases dynamic glTF textures. Off-thread reload hooks are marshalled to the client thread. */
    public static void clearTextureCache() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(GltfGunBodyRenderer::clearTextureCache);
            return;
        }
        if (textureCache != null) {
            try {
                textureCache.close();
            } finally {
                textureCache = null;
            }
        } else {
            GltfPbrRenderTypes.clearCache();
        }
    }

    static void validateSampler(GltfSamplerData sampler) {
        TextureVariantPolicy.validateSampler(sampler);
    }

    private double currentAnimationTime() {
        if (animation == null) {
            return 0.0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0.0;
        }
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return GltfGunBodyPose.animationTimeSeconds(
                minecraft.level.getGameTime(),
                partialTick,
                animationSpeed,
                animation.durationSeconds(),
                loopAnimation
        );
    }

    List<PreparedPrimitive> preparedAtTime(double sampleSeconds) {
        return preparedFrameAtTime(sampleSeconds).primitives();
    }

    PreparedFrame preparedFrameAtTime(double sampleSeconds) {
        if (staticPreparedFrame != null) {
            return staticPreparedFrame;
        }
        return prepareFrame(samplePose(sampleSeconds));
    }

    private GltfGunBodyPose.Frame samplePose(double sampleSeconds) {
        if (nodeMapBridge.isEmpty()) {
            return GltfGunBodyPose.sample(asset, animation, sampleSeconds);
        }
        return GltfGunBodyPose.sample(
                asset,
                animation,
                sampleSeconds,
                nodeMapBridge.snapshotWorldOverrides()
        );
    }

    static Matrix4f captureSortModelView(Matrix4fc renderModelView, Matrix4fc localPose) {
        Objects.requireNonNull(renderModelView, "renderModelView");
        Objects.requireNonNull(localPose, "localPose");
        Matrix4f snapshot = new Matrix4f(renderModelView).mul(localPose);
        if (!snapshot.isFinite()) {
            throw new IllegalArgumentException("captured model-view transform must be finite");
        }
        return snapshot;
    }

    static void applyBedrockRootTransform(BedrockPart rootNode, PoseStack poseStack) {
        Objects.requireNonNull(rootNode, "rootNode").translateAndRotateAndScale(
                Objects.requireNonNull(poseStack, "poseStack")
        );
    }

    private PreparedFrame prepareFrame(GltfGunBodyPose.Frame frame) {
        GltfScene scene = asset.runtimeScene();
        Matrix4f[] worldTransforms = frame.worldTransforms();
        List<PreparedPrimitive> prepared = new ArrayList<>();
        int sequence = 0;
        boolean hiddenPrimitive = false;

        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            if (!frame.active(nodeIndex)) {
                continue;
            }
            GltfNode node = scene.nodes().get(nodeIndex);
            if (node.meshIndex() < 0) {
                continue;
            }
            GltfMesh runtimeMesh = scene.meshes().get(node.meshIndex());
            GltfRenderMesh renderMesh = asset.renderMeshes().get(node.meshIndex());
            float[] morphWeights = frame.morphWeights(nodeIndex);
            GltfSkin skin = node.skinIndex() < 0 ? null : scene.skins().get(node.skinIndex());
            boolean hidden = skin == null ? frame.hidden(nodeIndex) : allSkinJointsCollapsed(skin, frame);

            for (int primitiveIndex = 0; primitiveIndex < runtimeMesh.primitives().size(); primitiveIndex++) {
                GltfMeshPrimitive runtimePrimitive = runtimeMesh.primitives().get(primitiveIndex);
                GltfRenderPrimitive renderPrimitive = renderMesh.primitives().get(primitiveIndex);
                validateMorphWeights(runtimePrimitive, morphWeights, nodeIndex, primitiveIndex);
                boolean hasSkinAttributes = runtimePrimitive.hasSkinAttributes();
                if ((skin != null) != hasSkinAttributes) {
                    throw new IllegalArgumentException(
                            "node " + nodeIndex + " primitive " + primitiveIndex
                                    + " must pair skin with JOINTS_0/WEIGHTS_0"
                    );
                }
                if (hidden) {
                    hiddenPrimitive = true;
                    continue;
                }
                if (rigidGeometryCache.containsKey(renderPrimitive)) {
                    GltfPreparedGeometry local = rigidGeometryCache.get(renderPrimitive);
                    if (local == null) {
                        local = GltfPreparedGeometry.createRigidLocal(renderPrimitive);
                        rigidGeometryCache.put(renderPrimitive, local);
                    }
                    prepared.add(new PreparedPrimitive(sequence++, renderPrimitive.materialIndex(),
                            local.captureTransform(worldTransforms[nodeIndex])));
                    continue;
                }
                DeformedPrimitive deformed = GltfVertexDeformer.deform(
                        runtimePrimitive,
                        skin,
                        skin == null ? null : worldTransforms,
                        morphWeights
                );
                Matrix4fc localToWorld = skin == null ? worldTransforms[nodeIndex] : null;
                boolean reverseWinding = localToWorld != null
                        && GltfPreparedGeometry.requiresWindingReversal(localToWorld);
                prepared.add(new PreparedPrimitive(
                        sequence++,
                        renderPrimitive.materialIndex(),
                        GltfPreparedGeometry.create(renderPrimitive, deformed, localToWorld, reverseWinding)
                ));
            }
        }
        return new PreparedFrame(List.copyOf(prepared), hiddenPrimitive && prepared.isEmpty());
    }

    private void initializeRigidGeometryCache(GltfGunBodyPose.Frame frame) {
        GltfScene scene = asset.runtimeScene();
        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            GltfNode node = scene.nodes().get(nodeIndex);
            if (!frame.active(nodeIndex) || node.meshIndex() < 0 || node.skinIndex() >= 0) {
                continue;
            }
            for (GltfRenderPrimitive primitive : asset.renderMeshes().get(node.meshIndex()).primitives()) {
                if (GltfPreparedGeometry.canCacheRigid(primitive.runtimePrimitive())) {
                    // Lazy expansion preserves the existing validation timing for initially hidden
                    // primitives, while repeated mesh instances share one immutable local payload.
                    rigidGeometryCache.put(primitive, null);
                }
            }
        }
    }

    private static boolean allSkinJointsCollapsed(GltfSkin skin, GltfGunBodyPose.Frame frame) {
        // glTF explicitly excludes a skinned mesh from node-scale omission unless every joint
        // is simultaneously collapsed. A zero mesh-node transform alone does not hide its skin.
        for (int joint : skin.joints()) {
            if (!frame.hidden(joint) || !GltfGunBodyPose.hasZeroLinearTransform(frame.worldTransform(joint))) {
                return false;
            }
        }
        return true;
    }

    private List<Submission> sortSubmissions(List<Submission> source, Matrix4fc modelView) {
        List<Submission> sorted = new ArrayList<>(source.size());
        for (Submission submission : source) {
            if (submission.alphaMode() == GltfPbrAlphaMode.BLEND) {
                GltfPreparedGeometry geometry = submission.geometry().sortedBackToFront(modelView);
                sorted.add(new Submission(
                        submission.sequence(),
                        geometry,
                        submission.renderType(),
                        submission.alphaMode(),
                        geometry.farthestTriangleViewDepth(modelView)
                ));
            } else {
                sorted.add(submission);
            }
        }
        // MC 26.2 separates solid and blended custom geometry into ordered phases, so OPAQUE/MASK
        // always precede BLEND. Within a RenderType batch insertion order is retained. Different
        // BLEND RenderTypes are HashMap-batched by Minecraft, so cross-material ordering is only a
        // best effort; per-material triangle order remains deterministic.
        sorted.sort(Comparator
                .comparingInt((Submission value) -> value.alphaMode().ordinal())
                .thenComparing((left, right) -> left.alphaMode() == GltfPbrAlphaMode.BLEND
                        ? Float.compare(right.sortViewDepth(), left.sortViewDepth())
                        : Integer.compare(left.sequence(), right.sequence()))
                .thenComparingInt(Submission::sequence));
        return List.copyOf(sorted);
    }

    private ResolvedMaterial resolveMaterial(GltfPbrDynamicTextureCache cache, int materialIndex) {
        PreparedMaterial prepared = materialIndex < 0 ? defaultMaterial : materials.get(materialIndex);
        GltfPbrMaterial material = cache.material(
                prepared.key(),
                prepared.input()
        );
        return new ResolvedMaterial(
                GltfPbrRenderTypes.renderType(material),
                prepared.input().alphaMode()
        );
    }

    private List<PreparedMaterial> prepareMaterials(List<GltfPbrMaterialData> source) {
        List<PreparedMaterial> result = new ArrayList<>(source.size());
        for (int materialIndex = 0; materialIndex < source.size(); materialIndex++) {
            result.add(prepareMaterial(source.get(materialIndex), materialIndex));
        }
        return List.copyOf(result);
    }

    private PreparedMaterial prepareMaterial(GltfPbrMaterialData material, int materialIndex) {
        GltfPbrTextureSlots textures = new GltfPbrTextureSlots(
                textureSource(material.baseColorTexture(), TextureImageFilter.Role.BASE_COLOR, material),
                textureSource(material.metallicRoughnessTexture(), TextureImageFilter.Role.ORM, material),
                textureSource(material.normalTexture(), TextureImageFilter.Role.NORMAL, material),
                textureSource(material.occlusionTexture(), TextureImageFilter.Role.OCCLUSION, material),
                textureSource(material.emissiveTexture(), TextureImageFilter.Role.EMISSIVE, material)
        );
        GltfPbrMaterialFactors factors = new GltfPbrMaterialFactors(
                material.baseColorFactor(),
                material.metallicFactor(),
                material.roughnessFactor(),
                material.normalScale(),
                material.occlusionStrength(),
                material.emissiveFactor(),
                material.alphaCutoff()
        );
        GltfPbrMaterialInput input = new GltfPbrMaterialInput(
                textures,
                factors,
                alphaMode(material.alphaMode()),
                !material.doubleSided()
        );
        String suffix = materialIndex < 0 ? "default" : Integer.toString(materialIndex);
        Identifier key = Identifier.fromNamespaceAndPath(
                modelId.getNamespace(),
                modelId.getPath() + "/material/" + suffix
        );
        return new PreparedMaterial(key, input);
    }

    @Nullable
    GltfPbrTextureSource textureSource(@Nullable GltfTextureBinding binding) {
        return textureSource(binding, TextureImageFilter.Role.BASE_COLOR, GltfPbrMaterialData.defaultMaterial());
    }

    @Nullable
    private GltfPbrTextureSource textureSource(@Nullable GltfTextureBinding binding,
                                             TextureImageFilter.Role role, GltfPbrMaterialData material) {
        if (binding == null) return null;
        boolean baseColor = role == TextureImageFilter.Role.BASE_COLOR;
        return textureSource(binding, new GltfPbrTextureSource.Settings(binding.sampler(), role,
                baseColor ? material.alphaMode() : GltfAlphaMode.OPAQUE,
                baseColor ? material.alphaCutoff() : 0.5f,
                baseColor ? material.baseColorFactor()[3] : 1f));
    }

    @Nullable
    GltfPbrTextureSource textureSource(@Nullable GltfTextureBinding binding,
                                     GltfPbrTextureSource.Settings settings) {
        if (binding == null) {
            return null;
        }
        if (!binding.sampler().equals(settings.sampler())) {
            throw new IllegalArgumentException("glTF source sampler must match its material binding");
        }
        validateSampler(binding.sampler());
        if (binding.imageIndex() >= asset.images().size()) {
            throw new IllegalArgumentException("glTF texture image index out of range: " + binding.imageIndex());
        }
        int imageIndex = binding.imageIndex();
        // One image may serve color and normal slots or both mipmapped and non-mip bindings.
        TextureSourceKey sourceKey = new TextureSourceKey(imageIndex, settings);
        GltfPbrTextureSource prepared = imageSources.get(sourceKey);
        if (prepared != null) {
            return prepared;
        }
        GltfImageData image = asset.images().get(imageIndex);
        Identifier key = Identifier.fromNamespaceAndPath(
                modelId.getNamespace(),
                modelId.getPath() + "/image/" + imageIndex
        );
        // GltfPbrTextureSource performs the one-time CPU header/hash preparation. The render hot
        // path therefore reaches the dynamic cache with a stable id and does not rescan image bytes.
        prepared = new GltfPbrTextureSource(key, image, settings);
        imageSources.put(sourceKey, prepared);
        return prepared;
    }

    private static void validateAssetStructure(ConvertedGltfAsset asset) {
        GltfScene scene = asset.runtimeScene();
        if (asset.renderMeshes().size() != scene.meshes().size()) {
            throw new IllegalArgumentException("render mesh table must be parallel to runtime meshes");
        }
        for (int meshIndex = 0; meshIndex < scene.meshes().size(); meshIndex++) {
            List<GltfMeshPrimitive> runtime = scene.meshes().get(meshIndex).primitives();
            List<GltfRenderPrimitive> render = asset.renderMeshes().get(meshIndex).primitives();
            if (runtime.size() != render.size()) {
                throw new IllegalArgumentException("render primitive table must be parallel at mesh " + meshIndex);
            }
            for (int primitiveIndex = 0; primitiveIndex < runtime.size(); primitiveIndex++) {
                GltfRenderPrimitive renderPrimitive = render.get(primitiveIndex);
                if (renderPrimitive.runtimePrimitive() != runtime.get(primitiveIndex)) {
                    throw new IllegalArgumentException(
                            "render primitive must reference its parallel runtime primitive at mesh "
                                    + meshIndex + " primitive " + primitiveIndex
                    );
                }
                int materialIndex = renderPrimitive.materialIndex();
                if (materialIndex >= asset.materials().size()) {
                    throw new IllegalArgumentException("material index out of range: " + materialIndex);
                }
            }
        }

        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            GltfNode node = scene.nodes().get(nodeIndex);
            if (node.meshIndex() >= scene.meshes().size() || node.skinIndex() >= scene.skins().size()) {
                throw new IllegalArgumentException("node indices out of range at node " + nodeIndex);
            }
            if (node.meshIndex() >= 0) {
                for (GltfMeshPrimitive primitive : scene.meshes().get(node.meshIndex()).primitives()) {
                    boolean hasSkinAttributes = primitive.hasSkinAttributes();
                    if ((node.skinIndex() >= 0) != hasSkinAttributes) {
                        throw new IllegalArgumentException(
                                "node " + nodeIndex + " must pair skin with JOINTS_0/WEIGHTS_0"
                        );
                    }
                    if (hasSkinAttributes) {
                        validateSkinAttributes(primitive, scene.skins().get(node.skinIndex()));
                    }
                }
            }
        }
        for (int skinIndex = 0; skinIndex < scene.skins().size(); skinIndex++) {
            GltfSkin skin = scene.skins().get(skinIndex);
            for (int jointIndex = 0; jointIndex < skin.jointCount(); jointIndex++) {
                int jointNode = skin.jointNodeIndex(jointIndex);
                if (jointNode < 0 || jointNode >= scene.nodes().size()) {
                    throw new IllegalArgumentException(
                            "skin " + skinIndex + " joint node index out of range: " + jointNode
                    );
                }
                if (!skin.inverseBindMatrix(jointIndex).isFinite()) {
                    throw new IllegalArgumentException("skin inverse bind matrix must be finite");
                }
            }
        }
    }

    private static void validateSkinAttributes(GltfMeshPrimitive primitive, GltfSkin skin) {
        // These source checks must not disappear when a valid zero-scale pose skips deformation.
        int[] joints = primitive.joints0();
        float[] weights = primitive.weights0();
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            float sum = 0.0f;
            for (int slot = vertex * 4; slot < vertex * 4 + 4; slot++) {
                float weight = weights[slot];
                if (!Float.isFinite(weight) || weight < 0.0f) {
                    throw new IllegalArgumentException("joint weight must be finite and non-negative");
                }
                if (weight > 0.0f && joints[slot] >= skin.jointCount()) {
                    throw new IllegalArgumentException("joint index out of skin range: " + joints[slot]);
                }
                sum += weight;
            }
            if (!Float.isFinite(sum) || sum <= 1.0E-8f) {
                throw new IllegalArgumentException("skinned vertex must have finite positive total joint weight: " + vertex);
            }
        }
    }

    static void validateEmissionBudget(ConvertedGltfAsset asset, GltfGunBodyPose.Frame frame) {
        GltfScene scene = asset.runtimeScene();
        long emittedVertices = 0L;
        long emittedTriangles = 0L;
        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            if (!frame.active(nodeIndex)) {
                continue;
            }
            int meshIndex = scene.nodes().get(nodeIndex).meshIndex();
            if (meshIndex < 0) {
                continue;
            }
            for (GltfRenderPrimitive primitive : asset.renderMeshes().get(meshIndex).primitives()) {
                long primitiveVertices = primitive.indices().length;
                try {
                    emittedVertices = Math.addExact(emittedVertices, primitiveVertices);
                    emittedTriangles = Math.addExact(emittedTriangles, primitiveVertices / 3L);
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("glTF emitted geometry budget overflow", exception);
                }
                if (emittedVertices > MAX_EMITTED_VERTICES
                        || emittedTriangles > MAX_EMITTED_TRIANGLES) {
                    throw new IllegalArgumentException(
                            "glTF active scene exceeds renderer emitted geometry budget: "
                                    + emittedVertices + " vertices / " + emittedTriangles + " triangles; max "
                                    + MAX_EMITTED_VERTICES + " / " + MAX_EMITTED_TRIANGLES
                    );
                }
            }
        }
        if (emittedTriangles == 0L) {
            throw new IllegalArgumentException("glTF active scene must contain renderable triangle geometry");
        }
    }

    private static void validateMorphWeights(
            GltfMeshPrimitive primitive,
            float[] weights,
            int nodeIndex,
            int primitiveIndex
    ) {
        int targetCount = primitive.morphTargets().size();
        if (weights.length != 0 && weights.length != targetCount) {
            throw new IllegalArgumentException(
                    "node " + nodeIndex + " primitive " + primitiveIndex
                            + " morph weight count " + weights.length + " does not match " + targetCount
            );
        }
        for (float weight : weights) {
            if (!Float.isFinite(weight)) {
                throw new IllegalArgumentException("morph weights must be finite");
            }
        }
    }

    @Nullable
    private static GltfAnimationClip findAnimation(ConvertedGltfAsset asset, @Nullable String name) {
        if (name == null) {
            return null;
        }
        List<GltfAnimationClip> animations = asset.animations();
        GltfAnimationClip match = null;
        for (GltfAnimationClip candidate : animations) {
            if (!name.equals(candidate.name())) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException("glTF animation name is ambiguous: " + name);
            }
            match = candidate;
        }
        if (match == null) {
            throw new IllegalArgumentException("glTF animation was not found: " + name);
        }
        validateAnimationTargets(match, asset.runtimeScene(), name);
        return match;
    }

    private static void validateAnimationTargets(
            GltfAnimationClip selected,
            GltfScene scene,
            String name
    ) {
        if (!Float.isFinite(selected.durationSeconds()) || selected.durationSeconds() < 0.0f) {
            throw new IllegalArgumentException("glTF animation has invalid duration: " + name);
        }
        for (GltfAnimationChannel channel : selected.animation().channels()) {
            int nodeIndex = channel.nodeIndex();
            if (nodeIndex < 0 || nodeIndex >= scene.nodes().size()) {
                throw new IllegalArgumentException(
                        "glTF animation " + name + " targets node out of range: " + nodeIndex
                );
            }
            float[] times = channel.sampler().times();
            if (times[times.length - 1] > selected.durationSeconds() + 1.0E-5f) {
                throw new IllegalArgumentException("glTF animation duration is shorter than its sampler: " + name);
            }
            if (channel.path() == GltfAnimationPath.WEIGHTS) {
                GltfNode node = scene.nodes().get(nodeIndex);
                if (node.meshIndex() < 0) {
                    throw new IllegalArgumentException("morph animation targets node without mesh: " + nodeIndex);
                }
                List<GltfMeshPrimitive> primitives = scene.meshes().get(node.meshIndex()).primitives();
                int targetCount = primitives.isEmpty() ? 0 : primitives.getFirst().morphTargets().size();
                if (channel.sampler().componentCount() != targetCount) {
                    throw new IllegalArgumentException(
                            "morph animation component count does not match mesh targets at node " + nodeIndex
                    );
                }
            }
        }
    }

    private static GltfPbrAlphaMode alphaMode(GltfAlphaMode alphaMode) {
        return switch (Objects.requireNonNull(alphaMode, "alphaMode")) {
            case OPAQUE -> GltfPbrAlphaMode.OPAQUE;
            case MASK -> GltfPbrAlphaMode.MASK;
            case BLEND -> GltfPbrAlphaMode.BLEND;
        };
    }

    private static GltfPbrDynamicTextureCache textureCache() {
        RenderSystem.assertOnRenderThread();
        if (textureCache == null) {
            textureCache = new GltfPbrDynamicTextureCache(Minecraft.getInstance().getTextureManager(),
                    ResourceConfig.gltfResourceBudgets().maxDecodedTextureBytes());
        }
        return textureCache;
    }

    private record PreparedMaterial(Identifier key, GltfPbrMaterialInput input) {
    }

    record PreparedPrimitive(int sequence, int materialIndex, GltfPreparedGeometry geometry) {
    }

    record PreparedFrame(List<PreparedPrimitive> primitives, boolean allHidden) {
    }

    private record ResolvedMaterial(RenderType renderType, GltfPbrAlphaMode alphaMode) {
    }

    private record Submission(
            int sequence,
            GltfPreparedGeometry geometry,
            RenderType renderType,
            GltfPbrAlphaMode alphaMode,
            float sortViewDepth
    ) {
    }
}
