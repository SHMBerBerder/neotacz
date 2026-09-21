package com.tacz.guns.compat.playeranimator.animation;

import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** The animation clock belongs to the client player, never to a model or render pass. */
public final class PlayerAnimationState {
    private final Map<Identifier, ModifierLayer<IAnimation>> layers = new HashMap<>();
    private AnimationStack stack;
    private long generation;
    private int lastAdvancedTick = Integer.MIN_VALUE;
    private int lastSelectionTick = Integer.MIN_VALUE;
    private AdjustmentYRotModifier adjustment = AdjustmentYRotModifier.NONE;
    private float partialTick;

    public PlayerAnimationState(long generation) {
        this.generation = generation;
        resetLayers();
    }

    private void resetLayers() {
        layers.clear();
        stack = new AnimationStack();
        adjustment = AdjustmentYRotModifier.NONE;
        AnimationDataRegisterFactory.registerData(stack, layers,
                AdjustmentYRotModifier.getModifier(() -> adjustment));
        invalidateSelection();
    }

    public void ensureGeneration(long currentGeneration) {
        if (generation != currentGeneration) {
            generation = currentGeneration;
            resetLayers();
        }
    }

    public void advance(int entityTick, long currentGeneration) {
        ensureGeneration(currentGeneration);
        if (lastAdvancedTick != entityTick) {
            lastAdvancedTick = entityTick;
            stack.tick();
        }
    }

    public boolean selectOnce(int entityTick) {
        if (lastSelectionTick == entityTick) {
            return false;
        }
        lastSelectionTick = entityTick;
        return true;
    }

    public void invalidateSelection() {
        lastSelectionTick = Integer.MIN_VALUE;
    }

    public ModifierLayer<IAnimation> layer(Identifier id) {
        return layers.get(id);
    }

    public void playLoop(Identifier id, KeyframeAnimation animation) {
        ModifierLayer<IAnimation> layer = layer(id);
        if (layer == null) {
            return;
        }
        if (layer.getAnimation() instanceof KeyframeAnimationPlayer current
                && current.isActive() && current.getData() == animation) {
            return;
        }
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE),
                new KeyframeAnimationPlayer(animation));
    }

    public void playOnce(Identifier id, KeyframeAnimation animation) {
        ModifierLayer<IAnimation> layer = layer(id);
        if (layer != null && (layer.getAnimation() == null || !layer.getAnimation().isActive())) {
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE),
                    new KeyframeAnimationPlayer(animation));
        }
    }

    public void stop(Identifier id, int fadeTime) {
        ModifierLayer<IAnimation> layer = layer(id);
        // A null target already means fading to vanilla; do not restart that fade each frame.
        if (layer != null && (layer.getAnimation() != null || fadeTime <= 0)) {
            if (fadeTime <= 0) {
                while (layer.size() > (id.equals(PlayerAnimatorCompat.ROTATION_ANIMATION) ? 1 : 0)) {
                    layer.removeModifier(layer.size() - 1);
                }
                layer.setAnimation(null);
            } else {
                layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fadeTime, Ease.INOUTSINE), null);
            }
        }
    }

    public void stopAll(int fadeTime) {
        for (Identifier id : layers.keySet()) {
            stop(id, fadeTime);
        }
    }

    public void prepareSample(AdjustmentYRotModifier adjustment, float partialTick) {
        this.adjustment = adjustment;
        this.partialTick = partialTick;
        stack.setupAnim(partialTick);
    }

    public Vec3f transform(String partName, TransformType type, Vec3f base) {
        return stack.get3DTransform(partName, type, partialTick, base);
    }

    public boolean isActive() {
        return stack.isActive();
    }

    public interface Access {
        PlayerAnimationState tacz$getPlayerAnimationState();
    }
}
