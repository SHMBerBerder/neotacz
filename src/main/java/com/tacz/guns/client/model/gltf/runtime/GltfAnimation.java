package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GltfAnimation {
    private final List<GltfAnimationChannel> channels;

    public GltfAnimation(List<GltfAnimationChannel> channels) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        Set<String> targets = new HashSet<>();
        for (GltfAnimationChannel channel : this.channels) {
            String target = channel.nodeIndex() + ":" + channel.path();
            if (!targets.add(target)) {
                throw new IllegalArgumentException("duplicate animation channel target: " + target);
            }
        }
    }

    public List<GltfAnimationChannel> channels() {
        return channels;
    }

    public GltfAnimationPose sample(float time) {
        Map<Integer, Vector3f> translations = new HashMap<>();
        Map<Integer, Quaternionf> rotations = new HashMap<>();
        Map<Integer, Vector3f> scales = new HashMap<>();
        Map<Integer, float[]> weights = new HashMap<>();
        for (GltfAnimationChannel channel : channels) {
            switch (channel.path()) {
                case TRANSLATION -> translations.put(channel.nodeIndex(), channel.sampler().sampleVector3(time));
                case ROTATION -> rotations.put(channel.nodeIndex(), channel.sampler().sampleRotation(time));
                case SCALE -> scales.put(channel.nodeIndex(), channel.sampler().sampleVector3(time));
                case WEIGHTS -> weights.put(channel.nodeIndex(), channel.sampler().sampleFloats(time));
            }
        }
        return new GltfAnimationPose(translations, rotations, scales, weights);
    }
}
