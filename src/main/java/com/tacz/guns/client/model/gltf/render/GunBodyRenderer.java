package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Optional gun-body backend. The Bedrock model remains the animation and attachment rig.
 */
@FunctionalInterface
public interface GunBodyRenderer {
    /** Returns false when the backend can no longer participate in host routing. */
    default boolean isAvailable() {
        return true;
    }

    /** Opt-in only: preparation must finish without submitting any retained nodes. */
    @Nullable
    default PreparedScopeBody prepareScopeBody(BedrockGunModel rig, PoseStack pose, ItemStack gun,
                                               ItemDisplayContext context, int light, int overlay) {
        return null;
    }

    /** Immutable geometry, material and pose snapshot, valid only in its resource generation. */
    interface PreparedScopeBody {
        boolean isAvailable();

        void emit(Function<RenderType, VertexConsumer> buffers);
    }

    /**
     * @return {@code true} only after at least one render node has been submitted. Returning
     * {@code false} allows the caller to use the Bedrock body for the current frame.
     */
    boolean submit(BedrockGunModel bedrockRig, PoseStack poseStack, ItemStack gunItem,
                   ItemDisplayContext transformType, OrderedSubmitNodeCollector collector,
                   int light, int overlay);
}
