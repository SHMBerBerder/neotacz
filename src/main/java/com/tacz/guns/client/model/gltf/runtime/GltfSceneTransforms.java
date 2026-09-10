package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;

public final class GltfSceneTransforms {
    private GltfSceneTransforms() {
    }

    public static Matrix4f[] computeWorldTransforms(GltfScene scene) {
        Matrix4f[] transforms = new Matrix4f[scene.nodes().size()];
        byte[] visitState = new byte[transforms.length];
        int[] parents = computeParents(scene);
        Matrix4f identity = new Matrix4f();

        for (int rootNode : scene.rootNodesUnsafe()) {
            if (parents[rootNode] != -1) {
                throw new IllegalArgumentException("scene root node has a parent: " + rootNode);
            }
            visit(scene, rootNode, -1, identity, transforms, visitState, parents);
        }
        for (int nodeIndex = 0; nodeIndex < transforms.length; nodeIndex++) {
            if (parents[nodeIndex] == -1 && visitState[nodeIndex] == 0) {
                visit(scene, nodeIndex, -1, identity, transforms, visitState, parents);
            }
        }
        // A remaining component has no parentless root, so visiting it deterministically exposes
        // the cycle instead of leaving a null transform behind.
        for (int nodeIndex = 0; nodeIndex < transforms.length; nodeIndex++) {
            if (visitState[nodeIndex] == 0) {
                visit(scene, nodeIndex, parents[nodeIndex], identity, transforms, visitState, parents);
            }
        }
        return transforms;
    }

    private static int[] computeParents(GltfScene scene) {
        int[] parents = new int[scene.nodes().size()];
        Arrays.fill(parents, -1);
        for (int parent = 0; parent < scene.nodes().size(); parent++) {
            for (int child : scene.nodes().get(parent).childrenUnsafe()) {
                if (parents[child] != -1) {
                    throw new IllegalArgumentException("node has multiple parents: " + child);
                }
                parents[child] = parent;
            }
        }
        return parents;
    }

    private static void visit(
            GltfScene scene,
            int nodeIndex,
            int parentIndex,
            Matrix4fc parentTransform,
            Matrix4f[] transforms,
            byte[] visitState,
            int[] parents
    ) {
        if (visitState[nodeIndex] == 1) {
            throw new IllegalArgumentException("cycle in glTF node graph at node " + nodeIndex);
        }
        if (visitState[nodeIndex] == 2) {
            if (parents[nodeIndex] != parentIndex) {
                throw new IllegalArgumentException("node has multiple parents: " + nodeIndex);
            }
            return;
        }

        if (parents[nodeIndex] != parentIndex) {
            throw new IllegalArgumentException("node parent mismatch: " + nodeIndex);
        }
        GltfNode node = scene.nodes().get(nodeIndex);
        Matrix4f worldTransform = new Matrix4f(parentTransform).mul(node.localTransform().toMatrix());
        transforms[nodeIndex] = worldTransform;
        visitState[nodeIndex] = 1;
        for (int child : node.childrenUnsafe()) {
            visit(scene, child, nodeIndex, worldTransform, transforms, visitState, parents);
        }
        visitState[nodeIndex] = 2;
    }
}
