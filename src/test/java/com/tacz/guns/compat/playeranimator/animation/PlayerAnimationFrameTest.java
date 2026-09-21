package com.tacz.guns.compat.playeranimator.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.core.data.AnimationFormat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.MathHelper;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimationFrameTest {
    @Test
    void samplerUsesTheActualWideAndSlimVanillaModelPosesAndPreservesScales() {
        for (boolean slim : new boolean[]{false, true}) {
            var sampler = PlayerAnimatorRenderBridge.createSampler(slim);
            var actual = new PlayerModel(PlayerModel.createMesh(CubeDeformation.NONE, slim).getRoot().bake(64, 64), slim);
            var state = new AvatarRenderState();
            state.speedValue = 1;
            state.ageInTicks = 15;
            state.xRot = 37;
            state.yRot = -62;
            state.walkAnimationSpeed = 0.8F;
            state.walkAnimationPos = 13;
            var animation = new PlayerAnimationState(0);
            for (int pose = 0; pose < 4; pose++) {
                state.isCrouching = pose == 1;
                state.isPassenger = pose == 2;
                state.swimAmount = pose == 3 ? 1 : 0;
                sampler.setupAnim(state);
                actual.setupAnim(state);
                assertPosesEqual(sampler, actual);
            }
            sampler.rightArm.xScale = 0.7F;
            sampler.rightArm.yScale = 1.2F;
            sampler.rightArm.zScale = 0.9F;
            PlayerAnimationFrame frame = PlayerAnimationFrame.capture(sampler, animation);
            frame.apply(actual);
            assertEquals(0.7F, actual.rightArm.xScale);
            assertEquals(1.2F, actual.rightArm.yScale);
            assertEquals(0.9F, actual.rightArm.zScale);
        }
    }

    @Test
    void missingAxesAndAngularBoundariesUseTheSameNonzeroBaseAsTheOriginalCore() {
        var model = PlayerAnimatorRenderBridge.createSampler(false);
        model.rightArm.x = 2;
        model.rightArm.y = 3;
        model.rightArm.z = 4;
        model.rightArm.xRot = 0.3F;
        model.rightArm.yRot = (float) Math.PI + 0.2F;
        model.rightArm.zRot = -(float) Math.PI - 0.1F;
        var clip = PlayerAnimationStateTest.animation(0.8F);
        var direct = new KeyframeAnimationPlayer(clip);
        direct.setupAnim(0.3F);
        var animation = new PlayerAnimationState(0);
        animation.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, clip);
        animation.prepareSample(AdjustmentYRotModifier.NONE, 0.3F);
        var frame = PlayerAnimationFrame.capture(model, animation);
        var expected = direct.get3DTransform("rightArm", TransformType.ROTATION, 0.3F, new Vec3f(
                MathHelper.clampToRadian(model.rightArm.xRot), MathHelper.clampToRadian(model.rightArm.yRot),
                MathHelper.clampToRadian(model.rightArm.zRot)));
        assertEquals(expected.getX(), frame.rightArm().xRot());
        assertEquals(expected.getY(), frame.rightArm().yRot());
        assertEquals(expected.getZ(), frame.rightArm().zRot());
        assertEquals(2, frame.rightArm().x());
        assertEquals(3, frame.rightArm().y());
        assertEquals(4, frame.rightArm().z());
    }

    @Test
    void laterTicksCannotChangeAPublishedFrameAndApplyingItTwiceDoesNotMoveClothingTwice() {
        var model = new PlayerModel(PlayerModel.createMesh(CubeDeformation.NONE, false).getRoot().bake(64, 64), false);
        var animation = new PlayerAnimationState(0);
        animation.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, PlayerAnimationStateTest.animation(0.8F));
        animation.prepareSample(AdjustmentYRotModifier.NONE, 0.4F);
        var frame = PlayerAnimationFrame.capture(model, animation);
        float saved = frame.rightArm().xRot();
        animation.advance(1, 0);
        animation.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, PlayerAnimationStateTest.animation(1.8F));
        animation.ensureGeneration(1);
        frame.apply(model);
        frame.apply(model);
        assertEquals(saved, frame.rightArm().xRot());
        assertEquals(saved, model.rightArm.xRot);
        assertEquals(0, model.rightSleeve.xRot);
        assertEquals(0, model.rightSleeve.x);
        assertEquals(0, model.rightSleeve.y);
        assertEquals(0, model.rightSleeve.z);
        assertEquals(0, model.hat.y);
        assertEquals(0, model.jacket.y);
    }

    @Test
    void bodyAndTorsoRemainIndependentAndWholeBodyMatchesTheOldPivotAndRotationOrder() {
        var builder = new KeyframeAnimation.AnimationBuilder(AnimationFormat.JSON_MC_ANIM);
        builder.endTick = 20;
        builder.isLooped = true;
        builder.body.x.addKeyFrame(0, 0.2F, Ease.LINEAR);
        builder.torso.x.addKeyFrame(0, 7, Ease.LINEAR);
        var state = new PlayerAnimationState(0);
        state.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, builder.build());
        state.prepareSample(AdjustmentYRotModifier.NONE, 0.5F);
        var frame = PlayerAnimationFrame.capture(PlayerAnimatorRenderBridge.createSampler(false), state);
        assertEquals(0.2F, frame.body().x());
        assertEquals(7, frame.torso().x());

        var body = new PlayerAnimationFrame.BodyPose(0.2F, -0.1F, 0.3F, 0.4F, -0.5F, 0.6F);
        var pose = new PoseStack();
        body.apply(pose);
        Matrix4f expected = new Matrix4f().translate(0.2F, (float) (-0.1F + 0.7), 0.3F)
                .rotateZ(0.6F).rotateY(-0.5F).rotateX(0.4F).translate(0, -0.7F, 0);
        assertTrue(expected.equals(pose.last().pose(), 0.000001F));
    }

    private static void assertPosesEqual(HumanoidModel<?> expected, HumanoidModel<?> actual) {
        ModelPart[] left = {expected.head, expected.body, expected.rightArm, expected.leftArm, expected.rightLeg, expected.leftLeg};
        ModelPart[] right = {actual.head, actual.body, actual.rightArm, actual.leftArm, actual.rightLeg, actual.leftLeg};
        for (int i = 0; i < left.length; i++) {
            assertEquals(left[i].storePose(), right[i].storePose());
        }
    }
}
