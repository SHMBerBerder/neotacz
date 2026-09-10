package com.tacz.guns.client.model.gltf.render;

import com.tacz.guns.client.model.BedrockAnimatedModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationPath;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.tacz.guns.client.model.GunModelConstant.ATTACHMENT_ADAPTER_NODE;
import static com.tacz.guns.client.model.GunModelConstant.ATTACHMENT_POS_SUFFIX;
import static com.tacz.guns.client.model.GunModelConstant.BULLET_CHAIN;
import static com.tacz.guns.client.model.GunModelConstant.BULLET_IN_BARREL;
import static com.tacz.guns.client.model.GunModelConstant.BULLET_IN_MAG;
import static com.tacz.guns.client.model.GunModelConstant.CARRY;
import static com.tacz.guns.client.model.GunModelConstant.DEFAULT_ATTACHMENT_SUFFIX;
import static com.tacz.guns.client.model.GunModelConstant.FIXED_ORIGIN_NODE;
import static com.tacz.guns.client.model.GunModelConstant.GROUND_ORIGIN_NODE;
import static com.tacz.guns.client.model.GunModelConstant.HANDGUARD_DEFAULT_NODE;
import static com.tacz.guns.client.model.GunModelConstant.HANDGUARD_TACTICAL_NODE;
import static com.tacz.guns.client.model.GunModelConstant.IDLE_VIEW_NODE;
import static com.tacz.guns.client.model.GunModelConstant.IRON_VIEW_NODE;
import static com.tacz.guns.client.model.GunModelConstant.LEFTHAND_POS_NODE;
import static com.tacz.guns.client.model.GunModelConstant.MAG_ADDITIONAL_NODE;
import static com.tacz.guns.client.model.GunModelConstant.MAG_EXTENDED_1;
import static com.tacz.guns.client.model.GunModelConstant.MAG_EXTENDED_2;
import static com.tacz.guns.client.model.GunModelConstant.MAG_EXTENDED_3;
import static com.tacz.guns.client.model.GunModelConstant.MAG_NORMAL_NODE;
import static com.tacz.guns.client.model.GunModelConstant.MAG_STANDARD;
import static com.tacz.guns.client.model.GunModelConstant.MOUNT;
import static com.tacz.guns.client.model.GunModelConstant.MUZZLE_FLASH_ORIGIN_NODE;
import static com.tacz.guns.client.model.GunModelConstant.REFIT_VIEW_NODE;
import static com.tacz.guns.client.model.GunModelConstant.REFIT_VIEW_PREFIX;
import static com.tacz.guns.client.model.GunModelConstant.REFIT_VIEW_SUFFIX;
import static com.tacz.guns.client.model.GunModelConstant.RIGHTHAND_POS_NODE;
import static com.tacz.guns.client.model.GunModelConstant.ROOT_NODE;
import static com.tacz.guns.client.model.GunModelConstant.SHELL_ORIGIN_NODE;
import static com.tacz.guns.client.model.GunModelConstant.SHELL_ORIGIN_NODE_PREFIX;
import static com.tacz.guns.client.model.GunModelConstant.SIGHT;
import static com.tacz.guns.client.model.GunModelConstant.SIGHT_FOLDED;
import static com.tacz.guns.client.model.GunModelConstant.THIRD_PERSON_HAND_ORIGIN_NODE;

/**
 * Narrow, one-way visual bridge from selected TaCZ Bedrock bones to glTF node worlds.
 * Bedrock remains authoritative for gameplay anchors and the bridge never mutates either rig.
 */
final class GltfNodeMapBridge {
    private static final int MAX_MAPPINGS = 8;
    private static final int MAX_SOURCE_DEPTH = 32;
    private static final int MAX_NODE_NAME_LENGTH = 128;
    private static final float INVERTIBILITY_EPSILON = 1.0E-8f;

    private static final Set<String> PROTECTED_EXACT_SOURCES = Set.of(
            BedrockAnimatedModel.CAMERA_NODE_NAME,
            BedrockAnimatedModel.CONSTRAINT_NODE,
            ROOT_NODE,
            "view",
            IRON_VIEW_NODE,
            IDLE_VIEW_NODE,
            REFIT_VIEW_NODE,
            THIRD_PERSON_HAND_ORIGIN_NODE,
            FIXED_ORIGIN_NODE,
            GROUND_ORIGIN_NODE,
            SHELL_ORIGIN_NODE,
            "muzzle",
            MUZZLE_FLASH_ORIGIN_NODE,
            "hands",
            LEFTHAND_POS_NODE,
            RIGHTHAND_POS_NODE,
            "attachment",
            ATTACHMENT_ADAPTER_NODE,
            "laser",
            "laser_beam",
            BULLET_IN_BARREL,
            BULLET_IN_MAG,
            BULLET_CHAIN,
            "ammo",
            CARRY,
            MOUNT,
            SIGHT,
            SIGHT_FOLDED,
            MAG_EXTENDED_1,
            MAG_EXTENDED_2,
            MAG_EXTENDED_3,
            MAG_STANDARD,
            "extended_mag",
            HANDGUARD_DEFAULT_NODE,
            HANDGUARD_TACTICAL_NODE
    );

    private final int nodeCount;
    private final Matrix4f inverseBasis;
    private final List<Binding> bindings;

    private GltfNodeMapBridge(int nodeCount, Matrix4fc inverseBasis, List<Binding> bindings) {
        this.nodeCount = nodeCount;
        this.inverseBasis = new Matrix4f(inverseBasis);
        this.bindings = List.copyOf(bindings);
    }

    static GltfNodeMapBridge create(
            ConvertedGltfAsset asset,
            Map<String, String> nodeMappings,
            Set<String> additionalProtectedSources,
            @Nullable BedrockGunModel bedrockRig,
            float scale,
            @Nullable GltfAnimationClip selectedClip
    ) {
        Objects.requireNonNull(asset, "asset");
        GltfScene scene = asset.runtimeScene();
        Map<String, String> mappings = new LinkedHashMap<>(
                Objects.requireNonNull(nodeMappings, "nodeMappings")
        );
        Set<String> dynamicProtectedSources = Set.copyOf(
                Objects.requireNonNull(additionalProtectedSources, "additionalProtectedSources")
        );
        if (mappings.size() > MAX_MAPPINGS) {
            throw new IllegalArgumentException("node map must contain at most " + MAX_MAPPINGS + " entries");
        }
        if (!Float.isFinite(scale) || scale <= 0.0f) {
            throw new IllegalArgumentException("node map scale must be finite and positive");
        }

        Matrix4f basis = new Matrix4f().scaling(-scale, -scale, scale);
        Matrix4f inverseBasis = invert(basis, "glTF outer basis");
        if (mappings.isEmpty()) {
            return new GltfNodeMapBridge(scene.nodes().size(), inverseBasis, List.of());
        }
        if (bedrockRig == null) {
            throw new IllegalArgumentException("a Bedrock rig is required for a non-empty node map");
        }
        if (!bedrockRig.hasUniqueNodeName(ROOT_NODE)) {
            throw new IllegalArgumentException("Bedrock root bone is missing or ambiguous");
        }

        Matrix4f[] targetBindWorlds = GltfGunBodyPose.sample(asset, null, 0).worldTransforms();
        int[] targetParents = buildParentTable(scene);
        int[] overrideParents = buildLodParentTable(asset, targetParents, true);
        boolean[] targetAffectsGeometry = buildGeometryInfluenceTable(asset, overrideParents);
        List<Binding> bindings = new ArrayList<>(mappings.size());
        Set<Integer> mappedTargets = new HashSet<>();

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String sourceName = requireNodeName(mapping.getKey(), "Bedrock source");
            String targetName = requireNodeName(mapping.getValue(), "glTF target");
            if (isProtectedSource(sourceName) || dynamicProtectedSources.contains(sourceName)) {
                throw new IllegalArgumentException("protected Bedrock source node cannot be mapped: " + sourceName);
            }

            List<BedrockPart> fullSourcePath = bedrockRig.getNodePath(sourceName);
            if (fullSourcePath == null) {
                throw new IllegalArgumentException("Bedrock source node is missing: " + sourceName);
            }
            if (!bedrockRig.hasUniqueNodeName(sourceName)) {
                throw new IllegalArgumentException(
                        "Bedrock source node is ambiguous or duplicate: " + sourceName
                );
            }
            List<BedrockPart> sourcePath = requireRootRelativePath(fullSourcePath, sourceName);
            rejectProtectedSourcePath(sourcePath, sourceName, bedrockRig, dynamicProtectedSources);
            int targetNode = findUniqueTarget(scene, targetName);
            if (!targetAffectsGeometry[targetNode]) {
                throw new IllegalArgumentException(
                        "glTF target node does not affect active rigid geometry or an active skin joint: " + targetName
                );
            }
            if (!mappedTargets.add(targetNode)) {
                throw new IllegalArgumentException("glTF target node is mapped more than once: " + targetName);
            }

            Matrix4f sourceBindWorld = sourceWorld(sourcePath, "Bedrock bind transform for " + sourceName);
            Matrix4f inverseSourceBind = invert(sourceBindWorld, "Bedrock bind transform for " + sourceName);
            Matrix4f targetBindWorld = new Matrix4f(targetBindWorlds[targetNode]);
            requireFinite(targetBindWorld, "glTF bind transform for " + targetName);
            Matrix4f correction = inverseSourceBind.mul(basis).mul(targetBindWorld);
            requireFinite(correction, "node map bind correction for " + sourceName);
            bindings.add(new Binding(
                    sourceName,
                    targetName,
                    targetNode,
                    sourcePath.toArray(BedrockPart[]::new),
                    correction
            ));
        }

        rejectHierarchyReversal(bindings, overrideParents);
        rejectAnimationConflicts(bindings, buildLodParentTable(asset, targetParents, false),
                scene.nodes().size(), selectedClip);
        return new GltfNodeMapBridge(scene.nodes().size(), inverseBasis, bindings);
    }

    boolean isEmpty() {
        return bindings.isEmpty();
    }

    Matrix4f[] snapshotWorldOverrides() {
        Matrix4f[] overrides = new Matrix4f[nodeCount];
        for (Binding binding : bindings) {
            Matrix4f sourceCurrentWorld = sourceWorld(binding.sourcePath, "current Bedrock mapped transform");
            Matrix4f desiredWorld = new Matrix4f(inverseBasis)
                    .mul(sourceCurrentWorld)
                    .mul(binding.correction);
            requireFinite(desiredWorld, "mapped glTF world transform");
            // A nonzero scale chain can underflow to an all-zero matrix. Only an
            // authored all-axis collapse is a visibility command, not numeric loss.
            if (GltfGunBodyPose.hasZeroLinearTransform(desiredWorld)) {
                boolean authoredCollapse = false;
                for (BedrockPart part : binding.sourcePath) {
                    authoredCollapse |= part.xScale == 0.0f && part.yScale == 0.0f && part.zScale == 0.0f;
                }
                if (!authoredCollapse) {
                    throw new IllegalArgumentException("mapped transform is singular without an authored zero scale");
                }
            }
            overrides[binding.targetNode] = desiredWorld;
        }
        return overrides;
    }

    private static List<BedrockPart> requireRootRelativePath(List<BedrockPart> fullPath, String sourceName) {
        if (fullPath.size() < 2 || !ROOT_NODE.equals(fullPath.getFirst().name)) {
            throw new IllegalArgumentException(
                    "Bedrock source node must be a descendant of root: " + sourceName
            );
        }
        int sourceDepth = fullPath.size() - 1;
        if (sourceDepth > MAX_SOURCE_DEPTH) {
            throw new IllegalArgumentException(
                    "Bedrock source path depth exceeds " + MAX_SOURCE_DEPTH + ": " + sourceName
            );
        }
        return List.copyOf(fullPath.subList(1, fullPath.size()));
    }

    private static void rejectProtectedSourcePath(
            List<BedrockPart> sourcePath,
            String sourceName,
            BedrockGunModel bedrockRig,
            Set<String> additionalProtectedSources
    ) {
        for (BedrockPart part : sourcePath) {
            if (part.name == null || !bedrockRig.hasUniqueNodeName(part.name)) {
                throw new IllegalArgumentException(
                        "Bedrock mapped source path contains an ambiguous or duplicate node for " + sourceName
                );
            }
            if (isProtectedSource(part.name) || additionalProtectedSources.contains(part.name)) {
                throw new IllegalArgumentException(
                        "protected Bedrock node occurs in mapped source path for " + sourceName + ": " + part.name
                );
            }
        }
    }

    private static int findUniqueTarget(GltfScene scene, String targetName) {
        int targetNode = -1;
        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            if (!targetName.equals(scene.nodes().get(nodeIndex).name())) {
                continue;
            }
            if (targetNode != -1) {
                throw new IllegalArgumentException("ambiguous glTF target node name: " + targetName);
            }
            targetNode = nodeIndex;
        }
        if (targetNode == -1) {
            throw new IllegalArgumentException("glTF target node is missing: " + targetName);
        }
        return targetNode;
    }

    private static Matrix4f sourceWorld(List<BedrockPart> sourcePath, String role) {
        Matrix4f world = new Matrix4f();
        for (BedrockPart part : sourcePath) {
            part.translateAndRotateAndScale(world);
            requireFinite(world, role);
        }
        return world;
    }

    private static Matrix4f sourceWorld(BedrockPart[] sourcePath, String role) {
        Matrix4f world = new Matrix4f();
        for (BedrockPart part : sourcePath) {
            part.translateAndRotateAndScale(world);
            requireFinite(world, role);
        }
        return world;
    }

    private static int[] buildParentTable(GltfScene scene) {
        int[] parents = new int[scene.nodes().size()];
        Arrays.fill(parents, -1);
        for (int parent = 0; parent < scene.nodes().size(); parent++) {
            for (int child : scene.nodes().get(parent).children()) {
                if (child < 0 || child >= parents.length) {
                    throw new IllegalArgumentException("glTF child node index out of range: " + child);
                }
                if (parents[child] != -1) {
                    throw new IllegalArgumentException("glTF node has multiple parents: " + child);
                }
                parents[child] = parent;
            }
        }
        return parents;
    }

    private static int[] buildLodParentTable(ConvertedGltfAsset asset, int[] parents, boolean externalOverride) {
        int[] selectedParents = parents.clone();
        for (var chain : asset.lods().nodeChains().entrySet()) {
            for (int lower : chain.getValue()) {
                if (asset.selectedNodeIndices().contains(lower)) {
                    // External overrides retain the high authority. Native animation replaces
                    // that node and inherits only its parent, not the high node's own channels.
                    selectedParents[lower] = externalOverride ? chain.getKey() : parents[chain.getKey()];
                }
            }
        }
        return selectedParents;
    }

    private static boolean[] buildGeometryInfluenceTable(ConvertedGltfAsset asset, int[] influenceParents) {
        GltfScene scene = asset.runtimeScene();
        Set<Integer> active = asset.selectedNodeIndices();

        boolean[] affectsGeometry = new boolean[scene.nodes().size()];
        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            if (!active.contains(nodeIndex)) {
                continue;
            }
            int meshIndex = scene.nodes().get(nodeIndex).meshIndex();
            if (meshIndex < 0) {
                continue;
            }
            int skinIndex = scene.nodes().get(nodeIndex).skinIndex();
            if (skinIndex < 0) {
                markNodeAndAncestors(nodeIndex, influenceParents, affectsGeometry);
                continue;
            }
            GltfSkin skin = scene.skins().get(skinIndex);
            GltfMesh mesh = scene.meshes().get(meshIndex);
            GltfRenderMesh renderMesh = asset.renderMeshes().get(meshIndex);
            for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                GltfMeshPrimitive primitive = mesh.primitives().get(primitiveIndex);
                GltfRenderPrimitive renderPrimitive = renderMesh.primitives().get(primitiveIndex);
                int[] joints = primitive.joints0();
                float[] weights = primitive.weights0();
                for (int vertexIndex : renderPrimitive.indices()) {
                    for (int slot = 0; slot < 4; slot++) {
                        int attributeIndex = vertexIndex * 4 + slot;
                        if (weights[attributeIndex] <= 0.0f) {
                            continue;
                        }
                        int jointIndex = joints[attributeIndex];
                        if (jointIndex >= skin.jointCount()) {
                            throw new IllegalArgumentException(
                                    "joint index out of active skin range: " + jointIndex
                            );
                        }
                        markNodeAndAncestors(
                                skin.jointNodeIndex(jointIndex),
                                influenceParents,
                                affectsGeometry
                        );
                    }
                }
            }
        }
        return affectsGeometry;
    }

    private static void markNodeAndAncestors(int nodeIndex, int[] parents, boolean[] marked) {
        for (int current = nodeIndex; current != -1; current = parents[current]) {
            marked[current] = true;
        }
    }

    private static void rejectHierarchyReversal(List<Binding> bindings, int[] targetParents) {
        for (int left = 0; left < bindings.size(); left++) {
            for (int right = left + 1; right < bindings.size(); right++) {
                Binding first = bindings.get(left);
                Binding second = bindings.get(right);
                boolean sourceFirstAboveSecond = isStrictSourceAncestor(first, second);
                boolean sourceSecondAboveFirst = isStrictSourceAncestor(second, first);
                boolean targetFirstAboveSecond = isStrictTargetAncestor(
                        first.targetNode, second.targetNode, targetParents
                );
                boolean targetSecondAboveFirst = isStrictTargetAncestor(
                        second.targetNode, first.targetNode, targetParents
                );
                if ((sourceFirstAboveSecond && targetSecondAboveFirst)
                        || (sourceSecondAboveFirst && targetFirstAboveSecond)) {
                    throw new IllegalArgumentException(
                            "node map reverses source and target ancestor direction: "
                                    + first.sourceName + " -> " + first.targetName + ", "
                                    + second.sourceName + " -> " + second.targetName
                    );
                }
            }
        }
    }

    private static boolean isStrictSourceAncestor(Binding ancestor, Binding descendant) {
        if (ancestor.sourcePath.length >= descendant.sourcePath.length) {
            return false;
        }
        for (int index = 0; index < ancestor.sourcePath.length; index++) {
            if (ancestor.sourcePath[index] != descendant.sourcePath[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStrictTargetAncestor(int ancestor, int descendant, int[] parents) {
        for (int node = parents[descendant]; node != -1; node = parents[node]) {
            if (node == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void rejectAnimationConflicts(
            List<Binding> bindings,
            int[] targetParents,
            int nodeCount,
            @Nullable GltfAnimationClip selectedClip
    ) {
        if (selectedClip == null) {
            return;
        }
        for (GltfAnimationChannel channel : selectedClip.animation().channels()) {
            if (channel.nodeIndex() < 0 || channel.nodeIndex() >= nodeCount) {
                throw new IllegalArgumentException(
                        "selected glTF animation targets node index out of range: " + channel.nodeIndex()
                );
            }
            if (channel.path() == GltfAnimationPath.WEIGHTS) {
                continue;
            }
            for (Binding binding : bindings) {
                if (isTargetAncestorOrSelf(channel.nodeIndex(), binding.targetNode, targetParents)) {
                    throw new IllegalArgumentException(
                            "selected glTF animation " + selectedClip.name()
                                    + " has a TRS channel on mapped target or ancestor node "
                                    + channel.nodeIndex()
                    );
                }
            }
        }
    }

    private static boolean isTargetAncestorOrSelf(int ancestor, int descendant, int[] parents) {
        if (ancestor == descendant) {
            return true;
        }
        return isStrictTargetAncestor(ancestor, descendant, parents);
    }

    private static boolean isProtectedSource(String sourceName) {
        String normalized = sourceName.toLowerCase(Locale.ROOT);
        if (MAG_NORMAL_NODE.equals(normalized) || MAG_ADDITIONAL_NODE.equals(normalized)) {
            return false;
        }
        return PROTECTED_EXACT_SOURCES.contains(normalized)
                || normalized.startsWith(SHELL_ORIGIN_NODE_PREFIX)
                || normalized.endsWith(ATTACHMENT_POS_SUFFIX)
                || normalized.endsWith(DEFAULT_ATTACHMENT_SUFFIX)
                || (normalized.startsWith(REFIT_VIEW_PREFIX) && normalized.endsWith(REFIT_VIEW_SUFFIX));
    }

    private static String requireNodeName(@Nullable String name, String role) {
        if (name == null || name.isBlank() || !name.equals(name.strip())) {
            throw new IllegalArgumentException(role + " node name must be non-blank and exact");
        }
        if (name.length() > MAX_NODE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    role + " node name must contain at most " + MAX_NODE_NAME_LENGTH + " characters"
            );
        }
        return name;
    }

    private static Matrix4f invert(Matrix4fc matrix, String role) {
        requireFinite(matrix, role);
        float determinant = matrix.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= INVERTIBILITY_EPSILON) {
            throw new IllegalArgumentException(role + " must be invertible");
        }
        Matrix4f inverse = new Matrix4f(matrix).invert();
        requireFinite(inverse, role + " inverse");
        return inverse;
    }

    private static void requireFinite(Matrix4fc matrix, String role) {
        if (!matrix.isFinite()) {
            throw new IllegalArgumentException(role + " must be finite");
        }
    }

    private record Binding(
            String sourceName,
            String targetName,
            int targetNode,
            BedrockPart[] sourcePath,
            Matrix4f correction
    ) {
        private Binding {
            sourcePath = sourcePath.clone();
            correction = new Matrix4f(correction);
        }
    }
}
