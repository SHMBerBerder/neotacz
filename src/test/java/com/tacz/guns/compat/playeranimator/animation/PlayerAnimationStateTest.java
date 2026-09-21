package com.tacz.guns.compat.playeranimator.animation;

import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.core.data.AnimationFormat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimationStateTest {
    @Test
    void selectionAndTickHaveIndependentOncePerEntityTickGates() {
        var state = new PlayerAnimationState(1);
        var animation = animation(1);
        assertTrue(state.selectOnce(5));
        assertFalse(state.selectOnce(5));
        state.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, animation);
        state.advance(5, 1);
        state.advance(5, 1);
        var player = (KeyframeAnimationPlayer) state.layer(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION).getAnimation();
        assertEquals(1, player.getTick());
        state.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, animation);
        assertSame(player, state.layer(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION).getAnimation());
        state.invalidateSelection();
        assertTrue(state.selectOnce(5));
        assertFalse(state.selectOnce(5));
        assertTrue(state.selectOnce(6));
    }

    @Test
    void onceEventsDoNotDependOnTheLoopSelectionGateAndDoNotInterruptAnActiveOnce() {
        var state = new PlayerAnimationState(1);
        state.selectOnce(3);
        var first = animation(1);
        state.playOnce(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, first);
        var player = state.layer(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION).getAnimation();
        state.playOnce(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, animation(2));
        assertSame(player, state.layer(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION).getAnimation());
    }

    @Test
    void generationAndEntityReplacementDoNotCarryLayersOrSelectionIntoTheNewSession() {
        var old = new PlayerAnimationState(1);
        old.selectOnce(12);
        old.playLoop(PlayerAnimatorCompat.LOWER_ANIMATION, animation(1));
        var replacement = new PlayerAnimationState(1);
        assertFalse(replacement.isActive());
        assertTrue(replacement.selectOnce(12));
        old.ensureGeneration(2);
        assertFalse(old.isActive());
        assertTrue(old.selectOnce(12));
    }

    @Test
    void repeatedStopsCannotRestartTheFadeAndAllFourLayersReturnToTheNonzeroBase() {
        var state = new PlayerAnimationState(1);
        for (var id : new net.minecraft.resources.Identifier[]{PlayerAnimatorCompat.LOWER_ANIMATION,
                PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION,
                PlayerAnimatorCompat.ROTATION_ANIMATION}) {
            state.playLoop(id, animation(1));
        }
        state.stopAll(8);
        int modifiers = state.layer(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION).size();
        for (int tick = 1; tick <= 10; tick++) {
            state.stopAll(8);
            assertTrue(state.layer(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION).size() <= modifiers);
            state.advance(tick, 1);
        }
        state.prepareSample(AdjustmentYRotModifier.NONE, 0.5F);
        Vec3f base = new Vec3f(0.2F, -0.4F, 0.7F);
        Vec3f result = state.transform("rightArm", TransformType.ROTATION, base);
        assertEquals(base.getX(), result.getX());
        assertEquals(base.getY(), result.getY());
        assertEquals(base.getZ(), result.getZ());
        assertFalse(state.isActive());
    }

    @Test
    void layerOrderMatchesTheOldPriorityAndFadeOutUsesTheCurrentVanillaBase() {
        var state = new PlayerAnimationState(1);
        state.playLoop(PlayerAnimatorCompat.LOWER_ANIMATION, animation(0.2F));
        state.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, animation(0.4F));
        state.playOnce(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, animation(0.6F));
        state.playLoop(PlayerAnimatorCompat.ROTATION_ANIMATION, animation(0.8F));
        state.prepareSample(AdjustmentYRotModifier.NONE, 0);
        assertEquals(0.8F, state.transform("rightArm", TransformType.ROTATION, Vec3f.ZERO).getX());
        state.stopAll(0);
        state.playLoop(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, animation(1));
        state.stopAll(8);
        for (int tick = 1; tick <= 4; tick++) {
            state.advance(tick, 1);
        }
        state.prepareSample(AdjustmentYRotModifier.NONE, 0);
        assertEquals(0.6F, state.transform("rightArm", TransformType.ROTATION, new Vec3f(0.2F, 0, 0)).getX(), 0.000001F);
        assertEquals(0.8F, state.transform("rightArm", TransformType.ROTATION, new Vec3f(0.6F, 0, 0)).getX(), 0.000001F);
        state.stopAll(0);
        assertFalse(state.isActive());
    }

    static KeyframeAnimation animation(float rotation) {
        var builder = new KeyframeAnimation.AnimationBuilder(AnimationFormat.JSON_MC_ANIM);
        builder.endTick = 20;
        builder.isLooped = true;
        builder.rightArm.pitch.addKeyFrame(0, rotation, Ease.LINEAR);
        return builder.build();
    }
}
