package com.tacz.guns.client.renderer;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;

import static org.junit.jupiter.api.Assertions.*;

class VulkanStencilPipelineSupportTest {
    @Test
    void missingVariantsCompileWithMatchingAttachmentsThenRestoreTheOriginalDescriptor() {
        // Zero is the GUI/no-depth branch; D32 is the ordinary depth-only branch.
        for (int depthFormat : new int[]{VK12.VK_FORMAT_UNDEFINED, VK12.VK_FORMAT_D32_SFLOAT}) {
            try (var stack = MemoryStack.stackPush()) {
                var info = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                        .depthAttachmentFormat(depthFormat)
                        .pColorAttachmentFormats(stack.ints(VK12.VK_FORMAT_R8G8B8A8_UNORM));
                long colorFormats = MemoryUtil.memAddress(info.pColorAttachmentFormats());
                long result = VulkanStencilPipelineSupport.complete(0L, info, () -> {
                    assertEquals(VK12.VK_FORMAT_D32_SFLOAT_S8_UINT, info.depthAttachmentFormat());
                    assertEquals(VK12.VK_FORMAT_D32_SFLOAT_S8_UINT, info.stencilAttachmentFormat());
                    assertEquals(colorFormats, MemoryUtil.memAddress(info.pColorAttachmentFormats()));
                    assertEquals(VK12.VK_FORMAT_R8G8B8A8_UNORM, info.pColorAttachmentFormats().get(0));
                    return 17L;
                });
                assertEquals(17L, result);
                assertEquals(depthFormat, info.depthAttachmentFormat());
                assertEquals(VK12.VK_FORMAT_UNDEFINED, info.stencilAttachmentFormat());
            }
        }
    }

    @Test
    void anExistingStencilVariantIsReturnedWithoutAccessingTheDescriptorOrRecompiling() {
        assertEquals(23L, VulkanStencilPipelineSupport.complete(23L, null, () -> {
            fail("Existing native variants must not be rebuilt");
            return 0L;
        }));
    }

    @Test
    void compilerFailureRestoresBothFormatsAndNeverReturnsANullPipeline() {
        try (var stack = MemoryStack.stackPush()) {
            var info = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                    .depthAttachmentFormat(VK12.VK_FORMAT_D32_SFLOAT)
                    .stencilAttachmentFormat(VK12.VK_FORMAT_S8_UINT);
            RuntimeException failure = new RuntimeException("native compilation failed");
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> VulkanStencilPipelineSupport.complete(0L, info, () -> { throw failure; })));
            assertEquals(VK12.VK_FORMAT_D32_SFLOAT, info.depthAttachmentFormat());
            assertEquals(VK12.VK_FORMAT_S8_UINT, info.stencilAttachmentFormat());
            assertThrows(IllegalStateException.class, () -> VulkanStencilPipelineSupport.complete(0L, info, () -> 0L));
            assertEquals(VK12.VK_FORMAT_D32_SFLOAT, info.depthAttachmentFormat());
            assertEquals(VK12.VK_FORMAT_S8_UINT, info.stencilAttachmentFormat());
        }
    }
}
