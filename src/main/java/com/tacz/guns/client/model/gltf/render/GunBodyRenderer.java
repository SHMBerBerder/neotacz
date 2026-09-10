package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Optional gun-body backend. The Bedrock model remains the animation and attachment rig.
 */
@FunctionalInterface
public interface GunBodyRenderer {
    /** Returns false when the backend can no longer participate in host routing. */
    default boolean isAvailable() {
        return true;
    }

    /**
     * @return {@code true} only after at least one render node has been submitted. Returning
     * {@code false} allows the caller to use the Bedrock body for the current frame.
     */
    boolean submit(BedrockGunModel bedrockRig, PoseStack poseStack, ItemStack gunItem,
                   ItemDisplayContext transformType, OrderedSubmitNodeCollector collector,
                   int light, int overlay);
}
