package com.tacz.guns.client.renderer;

import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;

import java.util.function.LongSupplier;

/**
 * Completes missing variants for the default D32S8 targets in NeoForge 26.3.
 * Remove when upstream creates and destroys every stencil-compatible variant;
 * this does not adapt the optional reduced-precision D24S8 target format.
 */
public final class VulkanStencilPipelineSupport {
    private VulkanStencilPipelineSupport() {
    }

    public static long complete(long existing, VkPipelineRenderingCreateInfoKHR renderingInfo, LongSupplier compile) {
        if (existing != 0L) {
            return existing;
        }
        int depthFormat = renderingInfo.depthAttachmentFormat();
        int stencilFormat = renderingInfo.stencilAttachmentFormat();
        try {
            // A stencil attachment needs a matching format even when its pipeline disables stencil testing.
            renderingInfo.depthAttachmentFormat(VK12.VK_FORMAT_D32_SFLOAT_S8_UINT);
            renderingInfo.stencilAttachmentFormat(VK12.VK_FORMAT_D32_SFLOAT_S8_UINT);
            long handle = compile.getAsLong();
            if (handle == 0L) {
                throw new IllegalStateException("Vulkan stencil-compatible pipeline has a null handle");
            }
            return handle;
        } finally {
            renderingInfo.depthAttachmentFormat(depthFormat);
            renderingInfo.stencilAttachmentFormat(stencilFormat);
        }
    }
}
