package com.tacz.guns.client.model.gltf.render;

import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationPose;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;

/** Pure-Java animation and scene-graph snapshot used by the retained gun-body renderer. */
final class GltfGunBodyPose {
    private GltfGunBodyPose() {
    }

    static Frame sample(ConvertedGltfAsset asset, @Nullable GltfAnimationClip clip, double sampleSeconds) {
        return sample(asset, clip, sampleSeconds, null);
    }

    static Frame sample(
            ConvertedGltfAsset asset,
            @Nullable GltfAnimationClip clip,
            double sampleSeconds,
            @Nullable Matrix4f[] worldOverrides
    ) {
        if (!Double.isFinite(sampleSeconds) || sampleSeconds < 0.0 || sampleSeconds > Float.MAX_VALUE) {
            throw new IllegalArgumentException("animation sample time must be finite and in float range");
        }
        GltfAnimationPose pose = clip == null ? null : clip.animation().sample((float) sampleSeconds);
        GltfScene scene = asset.runtimeScene();
        validateWorldOverrides(scene, worldOverrides);
        boolean[] hiddenNodes = new boolean[scene.nodes().size()];
        Matrix4f[] worldTransforms = computeWorldTransforms(scene, pose, worldOverrides, hiddenNodes);
        asset.lodSelection().applyToPose(worldTransforms, hiddenNodes, worldOverrides);
        boolean[] activeNodes = new boolean[scene.nodes().size()];
        for (int node : asset.selectedNodeIndices()) activeNodes[node] = true;
        float[][] morphWeights = new float[scene.nodes().size()][];
        Map<Integer, float[]> animatedWeights = pose == null ? Map.of() : pose.weights();

        for (Map.Entry<Integer, float[]> entry : animatedWeights.entrySet()) {
            int nodeIndex = entry.getKey();
            requireNodeIndex(scene, nodeIndex, "animated morph target");
            if (scene.nodes().get(nodeIndex).meshIndex() < 0) {
                throw new IllegalArgumentException("animated morph weights target node without a mesh: " + nodeIndex);
            }
        }
        for (int nodeIndex = 0; nodeIndex < scene.nodes().size(); nodeIndex++) {
            GltfNode node = scene.nodes().get(nodeIndex);
            if (node.meshIndex() < 0) {
                morphWeights[nodeIndex] = new float[0];
                continue;
            }
            float[] animated = animatedWeights.get(nodeIndex);
            if (animated != null) {
                morphWeights[nodeIndex] = animated.clone();
                continue;
            }
            float[] nodeWeights = asset.nodeMorphWeights(nodeIndex);
            morphWeights[nodeIndex] = nodeWeights.length == 0
                    ? asset.meshDefaultMorphWeights(node.meshIndex())
                    : nodeWeights;
        }
        return new Frame(worldTransforms, activeNodes, hiddenNodes, morphWeights);
    }

    static double animationTimeSeconds(
            long gameTicks,
            float partialTick,
            float speed,
            float durationSeconds,
            boolean loop
    ) {
        if (gameTicks < 0L) {
            throw new IllegalArgumentException("gameTicks must be non-negative");
        }
        if (!Float.isFinite(partialTick) || partialTick < 0.0f || partialTick > 1.0f) {
            throw new IllegalArgumentException("partialTick must be finite and in [0, 1]");
        }
        if (!Float.isFinite(speed) || speed < 0.0f) {
            throw new IllegalArgumentException("animation speed must be finite and non-negative");
        }
        if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
            throw new IllegalArgumentException("animation duration must be finite and non-negative");
        }
        if (durationSeconds == 0.0f || speed == 0.0f) {
            return 0.0;
        }

        double elapsed = ((double) gameTicks + partialTick) * speed / 20.0;
        if (!Double.isFinite(elapsed)) {
            throw new IllegalArgumentException("animation time overflow");
        }
        if (loop) {
            return elapsed % durationSeconds;
        }
        return Math.min(elapsed, durationSeconds);
    }

    private static Matrix4f[] computeWorldTransforms(
            GltfScene scene,
            @Nullable GltfAnimationPose pose,
            @Nullable Matrix4f[] worldOverrides,
            boolean[] hiddenNodes
    ) {
        int nodeCount = scene.nodes().size();
        Matrix4f[] transforms = new Matrix4f[nodeCount];
        byte[] visitState = new byte[nodeCount];
        int[] parents = buildParentTable(scene);

        Map<Integer, Vector3f> translations = pose == null ? Map.of() : pose.translations();
        Map<Integer, Quaternionf> rotations = pose == null ? Map.of() : pose.rotations();
        Map<Integer, Vector3f> scales = pose == null ? Map.of() : pose.scales();
        validatePoseNodes(scene, translations, "translation");
        validatePoseNodes(scene, rotations, "rotation");
        validatePoseNodes(scene, scales, "scale");

        Matrix4f identity = new Matrix4f();
        for (int rootNode : scene.rootNodes()) {
            requireNodeIndex(scene, rootNode, "scene root");
            if (parents[rootNode] != -1) {
                throw new IllegalArgumentException("selected scene root has a parent: " + rootNode);
            }
            visit(scene, rootNode, identity, false, translations, rotations, scales,
                    worldOverrides, transforms, hiddenNodes, visitState);
        }
        // Skin joints may sit outside the selected scene. Start at their real disconnected roots so
        // a child whose table index precedes its parent still inherits the complete parent transform.
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            if (parents[nodeIndex] == -1 && visitState[nodeIndex] == 0) {
                visit(scene, nodeIndex, identity, false, translations, rotations, scales,
                        worldOverrides, transforms, hiddenNodes, visitState);
            }
        }
        // A component with no parentless node is cyclic. Entering it here produces the precise cycle error.
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            if (visitState[nodeIndex] == 0) {
                visit(scene, nodeIndex, identity, false, translations, rotations, scales,
                        worldOverrides, transforms, hiddenNodes, visitState);
            }
        }
        return transforms;
    }

    private static int[] buildParentTable(GltfScene scene) {
        int[] parents = new int[scene.nodes().size()];
        Arrays.fill(parents, -1);
        for (int parentIndex = 0; parentIndex < scene.nodes().size(); parentIndex++) {
            for (int child : scene.nodes().get(parentIndex).children()) {
                requireNodeIndex(scene, child, "child");
                if (parents[child] != -1) {
                    throw new IllegalArgumentException("glTF node has multiple parents: " + child);
                }
                parents[child] = parentIndex;
            }
        }
        return parents;
    }

    private static void visit(
            GltfScene scene,
            int nodeIndex,
            Matrix4fc parentTransform,
            boolean parentHidden,
            Map<Integer, Vector3f> translations,
            Map<Integer, Quaternionf> rotations,
            Map<Integer, Vector3f> scales,
            @Nullable Matrix4f[] worldOverrides,
            Matrix4f[] transforms,
            boolean[] hiddenNodes,
            byte[] visitState
    ) {
        requireNodeIndex(scene, nodeIndex, "scene graph");
        if (visitState[nodeIndex] == 1) {
            throw new IllegalArgumentException("cycle in glTF node graph at node " + nodeIndex);
        }
        if (visitState[nodeIndex] == 2) {
            return;
        }

        GltfNode node = scene.nodes().get(nodeIndex);
        Matrix4f override = worldOverrides == null ? null : worldOverrides[nodeIndex];
        Matrix4f world;
        boolean collapsed;
        if (override != null) {
            world = new Matrix4f(override);
            collapsed = hasZeroLinearTransform(override);
        } else {
            GltfNodeTransform base = node.localTransform();
            Vector3f translation = translations.getOrDefault(nodeIndex, base.translation());
            Quaternionf rotation = rotations.getOrDefault(nodeIndex, base.rotation());
            Vector3f scale = scales.getOrDefault(nodeIndex, base.scale());
            collapsed = scale.x == 0.0f && scale.y == 0.0f && scale.z == 0.0f;
            Matrix4f local = new Matrix4f().translationRotateScale(translation, rotation, scale);
            world = new Matrix4f(parentTransform).mul(local);
        }
        requireFinite(world, "node world transform " + nodeIndex);
        transforms[nodeIndex] = world;
        // glTF 2.0 section 3.5.3 permits exact all-axis zero as animated subtree hiding.
        // Keep it separate from reachability and singularity: a child world override cannot
        // escape ancestor hiding, and small nonzero scales are not visibility instructions.
        hiddenNodes[nodeIndex] = parentHidden || collapsed;
        visitState[nodeIndex] = 1;
        for (int child : node.children()) {
            visit(scene, child, world, hiddenNodes[nodeIndex], translations, rotations, scales,
                    worldOverrides, transforms, hiddenNodes, visitState);
        }
        visitState[nodeIndex] = 2;
    }

    private static void validateWorldOverrides(GltfScene scene, @Nullable Matrix4f[] worldOverrides) {
        if (worldOverrides == null) {
            return;
        }
        if (worldOverrides.length != scene.nodes().size()) {
            throw new IllegalArgumentException(
                    "world override array length must match glTF node count: " + worldOverrides.length
            );
        }
        for (int nodeIndex = 0; nodeIndex < worldOverrides.length; nodeIndex++) {
            Matrix4f override = worldOverrides[nodeIndex];
            if (override != null) {
                requireFinite(override, "node world override " + nodeIndex);
            }
        }
    }

    private static void validatePoseNodes(GltfScene scene, Map<Integer, ?> values, String role) {
        for (int nodeIndex : values.keySet()) {
            requireNodeIndex(scene, nodeIndex, "animated " + role);
        }
    }

    private static void requireNodeIndex(GltfScene scene, int nodeIndex, String role) {
        if (nodeIndex < 0 || nodeIndex >= scene.nodes().size()) {
            throw new IllegalArgumentException(role + " node index out of range: " + nodeIndex);
        }
    }

    private static void requireFinite(Matrix4fc matrix, String role) {
        if (!matrix.isFinite()) {
            throw new IllegalArgumentException(role + " must be finite");
        }
    }

    static boolean hasZeroLinearTransform(Matrix4fc matrix) {
        return matrix.m00() == 0.0f && matrix.m01() == 0.0f && matrix.m02() == 0.0f
                && matrix.m10() == 0.0f && matrix.m11() == 0.0f && matrix.m12() == 0.0f
                && matrix.m20() == 0.0f && matrix.m21() == 0.0f && matrix.m22() == 0.0f;
    }

    record Frame(Matrix4f[] worldTransforms, boolean[] activeNodes, boolean[] hiddenNodes, float[][] morphWeights) {
        Frame {
            worldTransforms = copyMatrices(worldTransforms);
            activeNodes = activeNodes.clone();
            hiddenNodes = hiddenNodes.clone();
            morphWeights = copyWeights(morphWeights);
            if (worldTransforms.length != activeNodes.length || worldTransforms.length != hiddenNodes.length
                    || worldTransforms.length != morphWeights.length) {
                throw new IllegalArgumentException("frame arrays must be parallel");
            }
        }

        @Override
        public Matrix4f[] worldTransforms() {
            return copyMatrices(worldTransforms);
        }

        @Override
        public boolean[] hiddenNodes() {
            return hiddenNodes.clone();
        }

        Matrix4f worldTransform(int nodeIndex) {
            return new Matrix4f(worldTransforms[nodeIndex]);
        }

        boolean active(int nodeIndex) {
            return activeNodes[nodeIndex];
        }

        boolean hidden(int nodeIndex) {
            return hiddenNodes[nodeIndex];
        }

        float[] morphWeights(int nodeIndex) {
            return morphWeights[nodeIndex].clone();
        }

        private static Matrix4f[] copyMatrices(Matrix4f[] source) {
            Matrix4f[] copy = new Matrix4f[source.length];
            for (int index = 0; index < source.length; index++) {
                copy[index] = new Matrix4f(source[index]);
            }
            return copy;
        }

        private static float[][] copyWeights(float[][] source) {
            float[][] copy = new float[source.length][];
            for (int index = 0; index < source.length; index++) {
                copy[index] = source[index].clone();
            }
            return copy;
        }
    }
}
