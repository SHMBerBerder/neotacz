package com.tacz.guns.compat.playeranimator.animation;

import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import net.minecraft.resources.Identifier;

import java.util.Map;

public class AnimationDataRegisterFactory {
    public static void registerData(AnimationStack stack, Map<Identifier, ModifierLayer<IAnimation>> layers,
                                    AdjustmentModifier adjustment) {
        add(stack, layers, PlayerAnimatorCompat.LOWER_ANIMATION, 93, new ModifierLayer<>());
        add(stack, layers, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, 94, new ModifierLayer<>());
        add(stack, layers, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, 95, new ModifierLayer<>());
        add(stack, layers, PlayerAnimatorCompat.ROTATION_ANIMATION, 96, new ModifierLayer<>(null, adjustment));
    }

    private static void add(AnimationStack stack, Map<Identifier, ModifierLayer<IAnimation>> layers,
                            Identifier id, int priority, ModifierLayer<IAnimation> layer) {
        layers.put(id, layer);
        stack.addAnimLayer(priority, layer);
    }
}
