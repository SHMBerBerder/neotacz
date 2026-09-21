package com.tacz.guns.client.input;

import com.tacz.guns.GunMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.common.CommonHooks;

public final class TaczClientInteraction {
    private TaczClientInteraction() {
    }

    public static void startUseItem(Minecraft mc) {
        if (mc.gameMode == null || mc.player == null || mc.level == null || mc.gameMode.isDestroying() || mc.player.isHandsBusy()) {
            return;
        }
        if (mc.hitResult == null) {
            GunMod.LOGGER.warn("Null returned as 'hitResult', this shouldn't happen!");
        }

        for (InteractionHand hand : InteractionHand.values()) {
            // Keep the pre-interaction animation even if using the item replaces it.
            SwingAnimation swingAnimation = mc.player.getItemInHand(hand).getInteractAnimation();
            var inputEvent = ClientHooks.onClickInput(1, mc.options.keyUse, hand);
            if (inputEvent.isCanceled()) {
                if (inputEvent.shouldSwingHand()) {
                    mc.player.swing(hand, swingAnimation, false);
                }
                return;
            }

            ItemStack heldItem = mc.player.getItemInHand(hand);
            if (!heldItem.isItemEnabled(mc.level.enabledFeatures())) {
                return;
            }

            if (mc.hitResult != null) {
                switch (mc.hitResult.getType()) {
                    case ENTITY -> {
                        EntityHitResult entityHit = (EntityHitResult) mc.hitResult;
                        Entity entity = entityHit.getEntity();
                        if (!mc.level.getWorldBorder().isWithinBounds(entity.blockPosition())) {
                            return;
                        }
                        if (mc.player.isWithinEntityInteractionRange(entity, 0.0)
                                && mc.gameMode.interact(mc.player, entity, entityHit, hand) instanceof InteractionResult.Success success) {
                            swingIfPredicted(mc, hand, swingAnimation, inputEvent.shouldSwingHand(), success);
                            return;
                        }
                    }
                    case BLOCK -> {
                        BlockHitResult blockHit = (BlockHitResult) mc.hitResult;
                        int oldCount = heldItem.getCount();
                        InteractionResult useResult = mc.gameMode.useItemOn(mc.player, hand, blockHit);
                        if (useResult instanceof InteractionResult.Success success) {
                            if (success.swingSource() == InteractionResult.SwingSource.PREDICTED && inputEvent.shouldSwingHand()) {
                                mc.player.swing(hand, swingAnimation, false);
                                if (!heldItem.isEmpty() && (heldItem.getCount() != oldCount || mc.player.hasInfiniteMaterials())) {
                                    mc.player.itemUsed(hand);
                                }
                            }
                            return;
                        }
                        if (useResult instanceof InteractionResult.Fail) {
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }

            if (heldItem.isEmpty() && (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS)) {
                CommonHooks.onEmptyClick(mc.player, hand);
            }

            if (!heldItem.isEmpty() && mc.gameMode.useItem(mc.player, hand) instanceof InteractionResult.Success success) {
                swingIfPredicted(mc, hand, swingAnimation, inputEvent.shouldSwingHand(), success);
                mc.player.itemUsed(hand);
                return;
            }
        }
    }

    private static void swingIfPredicted(Minecraft mc, InteractionHand hand, SwingAnimation swingAnimation,
                                         boolean shouldSwingHand, InteractionResult.Success success) {
        if (success.swingSource() == InteractionResult.SwingSource.PREDICTED && shouldSwingHand) {
            mc.player.swing(hand, swingAnimation, false);
        }
    }
}
