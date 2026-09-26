package com.tacz.guns.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.tacz.guns.client.renderer.VulkanStencilPipelineSupport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanRenderPipeline.class, remap = false)
public abstract class VulkanRenderPipelineMixin {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final @Mutable private long withDepthStencilPipeline;

    @ModifyArg(method = "compile(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$CreateInfo;)Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline;<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;JJJJJLit/unimi/dsi/fastutil/longs/LongList;Ljava/util/List;)V"),
            index = 1, require = 1, allow = 1, remap = false)
    private static long tacz$completeStencilPipeline(long existing,
                                                     @Local(argsOnly = true) VulkanDevice device,
                                                     @Local MemoryStack stack,
                                                     @Local VkPipelineRenderingCreateInfoKHR renderingInfo,
                                                     @Local VkGraphicsPipelineCreateInfo.Buffer createInfo) {
        // Keep the compiler's entire pipeline description, including disabled depth/stencil tests for GUI draws.
        return VulkanStencilPipelineSupport.complete(existing, renderingInfo, () -> {
            var pointer = stack.callocLong(1);
            VulkanUtils.crashIfFailure(device,
                    VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0L, createInfo, null, pointer),
                    "Can't compile stencil-compatible pipeline");
            return pointer.get(0);
        });
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), require = 1, allow = 1, remap = false)
    private void tacz$destroyStencilPipeline(CallbackInfo ci) {
        // Release only at the native deferred-destruction boundary, never while a queued draw can use it.
        long handle = this.withDepthStencilPipeline;
        this.withDepthStencilPipeline = 0L;
        if (handle != 0L) {
            VK12.vkDestroyPipeline(this.device.vkDevice(), handle, null);
        }
    }
}
