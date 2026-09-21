package com.tacz.guns.client.model;

import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.listener.constraint.ConstraintObject;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Submission-time Bedrock animation values, without copying static mesh data. */
final class ScopeModelState {
    private final List<PartState> parts;
    private final ConstraintObject constraint;
    private final Vector3f translation;
    private final Vector3f rotation;

    private ScopeModelState(List<PartState> parts, ConstraintObject constraint) {
        this.parts = parts;
        this.constraint = constraint;
        translation = constraint == null ? null : new Vector3f(constraint.translationConstraint);
        rotation = constraint == null ? null : new Vector3f(constraint.rotationConstraint);
    }

    static ScopeModelState capture(BedrockAnimatedModel model) {
        List<PartState> parts = new ArrayList<>();
        Set<BedrockPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BedrockPart root : model.getShouldRender()) capturePart(root, visited, parts);
        return new ScopeModelState(parts, model.getConstraintObject());
    }

    private static void capturePart(BedrockPart part, Set<BedrockPart> visited, List<PartState> parts) {
        if (!visited.add(part)) return;
        parts.add(new PartState(part));
        for (BedrockPart child : part.children) capturePart(child, visited, parts);
    }

    void withState(Runnable action) {
        List<PartState> previousParts = parts.stream().map(state -> new PartState(state.part)).toList();
        ScopeModelState previous = new ScopeModelState(previousParts, constraint);
        apply();
        try {
            action.run();
        } finally {
            previous.apply();
        }
    }

    private void apply() {
        parts.forEach(PartState::apply);
        if (constraint != null) {
            constraint.translationConstraint.set(translation);
            constraint.rotationConstraint.set(rotation);
        }
    }

    private record PartState(BedrockPart part, PartPose pose, Vector3f offset, Quaternionf rotation,
                             boolean visible, boolean illuminated) {
        private PartState(BedrockPart part) {
            this(part, new PartPose(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                            part.xScale, part.yScale, part.zScale),
                    new Vector3f(part.offsetX, part.offsetY, part.offsetZ),
                    new Quaternionf(part.additionalQuaternion), part.visible, part.illuminated);
        }

        private void apply() {
            part.x = pose.x();
            part.y = pose.y();
            part.z = pose.z();
            part.xRot = pose.xRot();
            part.yRot = pose.yRot();
            part.zRot = pose.zRot();
            part.xScale = pose.xScale();
            part.yScale = pose.yScale();
            part.zScale = pose.zScale();
            part.offsetX = offset.x();
            part.offsetY = offset.y();
            part.offsetZ = offset.z();
            part.additionalQuaternion.set(rotation);
            part.visible = visible;
            part.illuminated = illuminated;
        }
    }
}
