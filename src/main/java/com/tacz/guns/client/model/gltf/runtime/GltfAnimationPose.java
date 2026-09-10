package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

public final class GltfAnimationPose {
    private final Map<Integer, Vector3f> translations;
    private final Map<Integer, Quaternionf> rotations;
    private final Map<Integer, Vector3f> scales;
    private final Map<Integer, float[]> weights;

    GltfAnimationPose(
            Map<Integer, Vector3f> translations,
            Map<Integer, Quaternionf> rotations,
            Map<Integer, Vector3f> scales,
            Map<Integer, float[]> weights
    ) {
        this.translations = copyVectorMap(translations);
        this.rotations = copyQuaternionMap(rotations);
        this.scales = copyVectorMap(scales);
        this.weights = copyWeightsMap(weights);
    }

    public Map<Integer, Vector3f> translations() {
        return copyVectorMap(translations);
    }

    public Map<Integer, Quaternionf> rotations() {
        return copyQuaternionMap(rotations);
    }

    public Map<Integer, Vector3f> scales() {
        return copyVectorMap(scales);
    }

    public Map<Integer, float[]> weights() {
        return copyWeightsMap(weights);
    }

    private static Map<Integer, Quaternionf> copyQuaternionMap(Map<Integer, Quaternionf> source) {
        Map<Integer, Quaternionf> copy = new HashMap<>();
        source.forEach((node, value) -> copy.put(node, new Quaternionf(value)));
        return Map.copyOf(copy);
    }

    private static Map<Integer, float[]> copyWeightsMap(Map<Integer, float[]> source) {
        Map<Integer, float[]> copy = new HashMap<>();
        source.forEach((node, value) -> copy.put(node, value.clone()));
        return Map.copyOf(copy);
    }

    private static Map<Integer, Vector3f> copyVectorMap(Map<Integer, Vector3f> source) {
        Map<Integer, Vector3f> copy = new HashMap<>();
        source.forEach((node, value) -> copy.put(node, new Vector3f(value)));
        return Map.copyOf(copy);
    }
}
