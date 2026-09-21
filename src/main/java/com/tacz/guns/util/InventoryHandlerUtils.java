package com.tacz.guns.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.LivingEntityEquipmentWrapper;
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class InventoryHandlerUtils {
    private InventoryHandlerUtils() {
    }

    public static Optional<ResourceHandler<ItemResource>> of(LivingEntity entity) {
        if (entity instanceof Player player) {
            return Optional.of(PlayerInventoryWrapper.of(player));
        }
        return Optional.of(new CombinedResourceHandler<>(
                LivingEntityEquipmentWrapper.of(entity, EquipmentSlot.Type.HAND),
                LivingEntityEquipmentWrapper.of(entity, EquipmentSlot.Type.HUMANOID_ARMOR)));
    }

    public static List<ItemAccess> forContainerContents(Container container) {
        List<ItemAccess> slots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                slots.add(ItemAccess.forStack(stack));
            }
        }
        return slots;
    }

    public static int extractMatching(List<ItemAccess> slots, Predicate<ItemStack> predicate,
                                      int amount, TransactionContext transaction) {
        if (amount <= 0) {
            return 0;
        }
        int extracted = 0;
        for (ItemAccess slot : slots) {
            if (extracted == amount) {
                break;
            }
            ItemResource resource = slot.getResource();
            if (!resource.isEmpty() && predicate.test(resource.toStack(slot.getAmount()))) {
                extracted += slot.extract(resource, amount - extracted, transaction);
            }
        }
        return extracted;
    }

    public static boolean replaceStack(ResourceHandler<ItemResource> handler, int slot, ItemStack replacement,
                                       TransactionContext transaction) {
        int count = handler.getAmountAsInt(slot);
        if (count <= 0 || replacement.isEmpty() || replacement.getCount() != count) {
            return false;
        }
        // Component changes must replace the entire slot, not mutate an ItemResource snapshot.
        try (Transaction nested = Transaction.open(transaction)) {
            int exchanged = ItemAccess.forHandlerIndexStrict(handler, slot)
                    .exchange(ItemResource.of(replacement), count, nested);
            if (exchanged != count) {
                return false;
            }
            nested.commit();
            return true;
        }
    }
}
