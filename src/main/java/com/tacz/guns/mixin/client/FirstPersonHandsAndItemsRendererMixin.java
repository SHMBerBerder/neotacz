package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.event.BeforeRenderHandEvent;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FirstPersonHandsAndItemsRenderer.class, remap = false)
public class FirstPersonHandsAndItemsRendererMixin {
    @Inject(method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V", at = @At("HEAD"), remap = false)
    public void beforeHandRender(float partialTicks, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                 PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handState,
                                 CallbackInfo ci) {
        // Apply camera animation once before either hand consumes the shared pose stack.
        NeoForge.EVENT_BUS.post(new BeforeRenderHandEvent(poseStack));
    }
}
