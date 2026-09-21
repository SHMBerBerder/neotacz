package com.tacz.guns.compat.playeranimator.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.MathHelper;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/** Values only: deferred skin, equipment and hand submissions cannot observe a later animation tick. */
public record PlayerAnimationFrame(PartPose head, PartPose torso, PartPose rightArm, PartPose leftArm,
                                   PartPose rightLeg, PartPose leftLeg, BodyPose body) {
    public static PlayerAnimationFrame capture(HumanoidModel<?> model, PlayerAnimationState animation) {
        Vec3f position = animation.transform("body", TransformType.POSITION, Vec3f.ZERO);
        Vec3f rotation = animation.transform("body", TransformType.ROTATION, Vec3f.ZERO);
        return new PlayerAnimationFrame(capturePart(model.head, "head", animation),
                capturePart(model.body, "torso", animation), capturePart(model.rightArm, "rightArm", animation),
                capturePart(model.leftArm, "leftArm", animation), capturePart(model.rightLeg, "rightLeg", animation),
                capturePart(model.leftLeg, "leftLeg", animation),
                new BodyPose(position.getX(), position.getY(), position.getZ(), rotation.getX(), rotation.getY(), rotation.getZ()));
    }

    private static PartPose capturePart(ModelPart part, String name, PlayerAnimationState animation) {
        if (!animation.isActive()) {
            return new PartPose(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale);
        }
        Vec3f position = animation.transform(name, TransformType.POSITION, new Vec3f(part.x, part.y, part.z));
        Vec3f rotation = animation.transform(name, TransformType.ROTATION, new Vec3f(
                MathHelper.clampToRadian(part.xRot), MathHelper.clampToRadian(part.yRot), MathHelper.clampToRadian(part.zRot)));
        return new PartPose(position.getX(), position.getY(), position.getZ(), rotation.getX(), rotation.getY(), rotation.getZ(),
                part.xScale, part.yScale, part.zScale);
    }

    public void apply(HumanoidModel<?> model) {
        model.head.loadPose(head);
        model.body.loadPose(torso);
        model.rightArm.loadPose(rightArm);
        model.leftArm.loadPose(leftArm);
        model.rightLeg.loadPose(rightLeg);
        model.leftLeg.loadPose(leftLeg);
        // In 26.3 the clothing and hat are children, not separately positioned sibling parts.
    }

    public record BodyPose(float x, float y, float z, float xRot, float yRot, float zRot) {
        public void apply(PoseStack pose) {
            if (x == 0 && y == 0 && z == 0 && xRot == 0 && yRot == 0 && zRot == 0) {
                return;
            }
            // This is the old whole-player "body" pivot, not the model's "torso" part.
            pose.translate(x, y + 0.7, z);
            pose.rotate(Axis.ZP.rotation(zRot));
            pose.rotate(Axis.YP.rotation(yRot));
            pose.rotate(Axis.XP.rotation(xRot));
            pose.translate(0, -0.7, 0);
        }
    }
}
