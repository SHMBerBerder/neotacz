package com.tacz.guns.mixin.client;

import com.tacz.guns.compat.playeranimator.animation.PlayerAnimationState;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractClientPlayer.class, remap = false)
public abstract class PlayerAnimatorPlayerMixin implements PlayerAnimationState.Access {
    @Unique
    private PlayerAnimationState tacz$playerAnimationState;

    @Override
    public PlayerAnimationState tacz$getPlayerAnimationState() {
        if (tacz$playerAnimationState == null) {
            tacz$playerAnimationState = new PlayerAnimationState(PlayerAnimatorAssetManager.get().generation());
        }
        return tacz$playerAnimationState;
    }

    @Inject(method = "tick()V", at = @At("HEAD"), remap = false, require = 1)
    private void tacz$tickPlayerAnimation(CallbackInfo ci) {
        if (tacz$playerAnimationState != null) {
            tacz$playerAnimationState.advance(((AbstractClientPlayer) (Object) this).tickCount,
                    PlayerAnimatorAssetManager.get().generation());
        }
    }
}
