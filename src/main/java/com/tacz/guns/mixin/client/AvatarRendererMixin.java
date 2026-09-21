package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimationFrame;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorRenderBridge;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AvatarRenderer.class, remap = false)
public abstract class AvatarRendererMixin {
    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("RETURN"), remap = false, require = 1)
    private void tacz$applyPlayerBody(AvatarRenderState state, PoseStack pose, float bodyRot, float entityScale, CallbackInfo ci) {
        PlayerAnimationFrame frame = state.getRenderData(PlayerAnimatorRenderBridge.FRAME);
        if (frame != null) {
            frame.body().apply(pose);
        }
    }
}
