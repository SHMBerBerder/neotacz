package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.tacz.guns.api.client.event.RenderItemInHandBobEvent;
import com.tacz.guns.api.client.event.RenderLevelBobEvent;
import com.tacz.guns.client.renderer.other.GunHurtBobTweak;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererMixin {
    @Unique
    private boolean tacz$renderingLevelBob;
    @Unique
    private float tacz$bobPartialTick;

    @ModifyArg(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;Lnet/minecraft/client/resources/model/ModelManager;Lnet/minecraft/client/renderer/item/ItemModelResolver;)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/TextureTarget;<init>(Ljava/lang/String;IILcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V",
            ordinal = 0), index = 4, require = 1, allow = 1, remap = false)
    private GpuFormat tacz$enableHudDepthStencil(GpuFormat depthFormat) {
        // First-person scopes can render against HUD depth instead of the main target.
        return GpuFormat.D32_FLOAT_S8_UINT;
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, remap = false)
    public void onBobHurt(CameraRenderState cameraState, PoseStack pMatrixStack, CallbackInfo ci) {
        // 取消受伤导致的视角摇晃
        if (Minecraft.getInstance().getCameraEntity() instanceof LocalPlayer player && !player.isDeadOrDying()) {
            if (GunHurtBobTweak.onHurtBobTweak(player, pMatrixStack, tacz$bobPartialTick)) {
                ci.cancel();
                return;
            }
        }
        // 触发其他事件
        boolean cancel;
        if (tacz$renderingLevelBob) {
            cancel = NeoForge.EVENT_BUS.post(new RenderLevelBobEvent.BobHurt()).isCanceled();
        } else {
            cancel = NeoForge.EVENT_BUS.post(new RenderItemInHandBobEvent.BobHurt()).isCanceled();
        }
        if (cancel) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, remap = false)
    public void onBobView(CameraRenderState cameraState, PoseStack pMatrixStack, CallbackInfo ci) {
        boolean cancel;
        if (tacz$renderingLevelBob) {
            cancel = NeoForge.EVENT_BUS.post(new RenderLevelBobEvent.BobView()).isCanceled();
        } else {
            cancel = NeoForge.EVENT_BUS.post(new RenderItemInHandBobEvent.BobView()).isCanceled();
        }
        if (cancel) {
            ci.cancel();
        }
    }

    /**
     * 26.1 将相机 bob 拆进 CameraRenderState；在两个真实入口处记录当前 bob 属于世界还是手部路径。
     */
    @Inject(method = "renderLevel()V", at = @At("HEAD"), remap = false)
    public void markLevelBob(CallbackInfo ci) {
        this.tacz$renderingLevelBob = true;
        this.tacz$bobPartialTick = ((GameRenderer) (Object) this).gameRenderState().levelRenderState.worldPartialTicks;
    }

    @Inject(method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", at = @At("HEAD"), remap = false)
    public void markItemInHandBob(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView, CallbackInfo ci) {
        this.tacz$renderingLevelBob = false;
        this.tacz$bobPartialTick = cameraState.cameraEntityPartialTicks;
    }
}
