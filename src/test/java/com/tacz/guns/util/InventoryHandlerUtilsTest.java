package com.tacz.guns.util;

import com.tacz.guns.api.item.nbt.ItemStackNbtHelper;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.RangedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryHandlerUtilsTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void extractionPreservesSlotOrderAndCanBeSimulated() {
        var handler = inventory(new ItemStack(Items.ARROW, 2), new ItemStack(Items.STICK, 9), new ItemStack(Items.ARROW, 5));
        var slots = accesses(handler);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(4, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.ARROW), 4, transaction));
            assertEquals(0, handler.getAmountAsInt(0));
            assertEquals(9, handler.getAmountAsInt(1));
            assertEquals(3, handler.getAmountAsInt(2));
        }
        assertEquals(2, handler.getAmountAsInt(0));
        assertEquals(5, handler.getAmountAsInt(2));

        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(4, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.ARROW), 4, transaction));
            transaction.commit();
        }
        assertEquals(0, handler.getAmountAsInt(0));
        assertEquals(3, handler.getAmountAsInt(2));
    }

    @Test
    void repeatedRecipeIngredientsCannotReuseAlreadyConsumedItems() {
        var handler = inventory(new ItemStack(Items.IRON_INGOT, 3));
        var slots = accesses(handler);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(2, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.IRON_INGOT), 2, transaction));
            assertEquals(1, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.IRON_INGOT), 2, transaction));
        }
        assertEquals(3, handler.getAmountAsInt(0));
    }

    @Test
    void ingredientPredicateSeesTheActualRemainingStackCount() {
        var handler = inventory(new ItemStack(Items.IRON_INGOT, 3));
        var slots = accesses(handler);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(2, InventoryHandlerUtils.extractMatching(slots,
                    stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 3, 2, transaction));
            assertEquals(1, InventoryHandlerUtils.extractMatching(slots,
                    stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 1, 1, transaction));
            transaction.commit();
        }
        assertEquals(0, handler.getAmountAsInt(0));
    }

    @Test
    void returnedAmmoStacksInMainSlotsWithoutUsingOffhand() {
        var handler = new ItemStacksResourceHandler(41);
        handler.set(2, ItemResource.of(Items.ARROW), 63);
        handler.set(40, ItemResource.of(Items.ARROW), 20);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(3, ResourceHandlerUtil.insertStacking(RangedResourceHandler.of(handler, 0, 36),
                    ItemResource.of(Items.ARROW), 3, transaction));
            transaction.commit();
        }
        assertEquals(64, handler.getAmountAsInt(2));
        assertEquals(2, handler.getAmountAsInt(0));
        assertEquals(20, handler.getAmountAsInt(40));
    }

    @Test
    void craftingIncludesExtraEquipmentSlotsAndCommitsOriginalStacks() {
        var inventory = new SimpleContainer(43);
        ItemStack body = new ItemStack(Items.IRON_INGOT, 2);
        ItemStack saddle = new ItemStack(Items.IRON_INGOT, 3);
        inventory.setItem(41, body);
        inventory.setItem(42, saddle);
        var slots = InventoryHandlerUtils.forContainerContents(inventory);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(4, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.IRON_INGOT), 4, transaction));
            assertEquals(2, body.getCount());
            assertEquals(3, saddle.getCount());
            transaction.commit();
        }
        assertTrue(body.isEmpty());
        assertTrue(inventory.getItem(41).isEmpty());
        assertEquals(1, saddle.getCount());
        assertEquals(1, inventory.getItem(42).getCount());
    }

    @Test
    void craftingShortageLeavesEveryOriginalStackUnchanged() {
        var inventory = new SimpleContainer(43);
        ItemStack main = new ItemStack(Items.IRON_INGOT, 2);
        ItemStack body = new ItemStack(Items.IRON_INGOT, 1);
        inventory.setItem(0, main);
        inventory.setItem(41, body);
        var slots = InventoryHandlerUtils.forContainerContents(inventory);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(2, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.IRON_INGOT), 2, transaction));
            assertEquals(1, InventoryHandlerUtils.extractMatching(slots, stack -> stack.is(Items.IRON_INGOT), 2, transaction));
        }
        assertEquals(2, main.getCount());
        assertEquals(1, body.getCount());
        assertEquals(2, inventory.getItem(0).getCount());
        assertEquals(1, inventory.getItem(41).getCount());
    }

    @Test
    void componentReplacementChangesTheWholeSlotOnlyWhenCommitted() {
        ItemStack box = new ItemStack(Items.CHEST, 3);
        ItemStackNbtHelper.updateTag(box, tag -> tag.putInt("AmmoCount", 17));
        var handler = inventory(box);
        ItemStack snapshot = ItemUtil.getStack(handler, 0);
        ItemStackNbtHelper.updateTag(snapshot, tag -> tag.putInt("AmmoCount", 5));
        assertEquals(17, storedAmmo(handler));

        try (Transaction transaction = Transaction.openRoot()) {
            assertTrue(InventoryHandlerUtils.replaceStack(handler, 0, snapshot, transaction));
            assertEquals(5, storedAmmo(handler));
        }
        assertEquals(17, storedAmmo(handler));
        assertEquals(3, handler.getAmountAsInt(0));

        try (Transaction transaction = Transaction.openRoot()) {
            assertTrue(InventoryHandlerUtils.replaceStack(handler, 0, snapshot, transaction));
            transaction.commit();
        }
        assertEquals(5, storedAmmo(handler));
        assertEquals(3, handler.getAmountAsInt(0));
    }

    @Test
    void rejectedReplacementRestoresOriginalStackEvenIfParentCommits() {
        ItemStack box = new ItemStack(Items.CHEST, 3);
        ItemStackNbtHelper.updateTag(box, tag -> tag.putInt("AmmoCount", 17));
        var handler = new ItemStacksResourceHandler(NonNullList.of(ItemStack.EMPTY, box)) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return resource.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getIntOr("AmmoCount", 0) == 17;
            }
        };
        ItemStack replacement = ItemUtil.getStack(handler, 0);
        ItemStackNbtHelper.updateTag(replacement, tag -> tag.putInt("AmmoCount", 0));
        try (Transaction transaction = Transaction.openRoot()) {
            assertFalse(InventoryHandlerUtils.replaceStack(handler, 0, replacement, transaction));
            transaction.commit();
        }
        assertEquals(17, storedAmmo(handler));
        assertEquals(3, handler.getAmountAsInt(0));
    }

    @Test
    void partialExchangeCannotMutateAnyPartOfAStack() {
        var handler = new ItemStacksResourceHandler(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.CHEST, 3))) {
            @Override
            public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
                return super.extract(index, resource, Math.min(1, amount), transaction);
            }
        };
        ItemStack replacement = ItemUtil.getStack(handler, 0);
        ItemStackNbtHelper.updateTag(replacement, tag -> tag.putInt("AmmoCount", 5));
        try (Transaction transaction = Transaction.openRoot()) {
            assertFalse(InventoryHandlerUtils.replaceStack(handler, 0, replacement, transaction));
            transaction.commit();
        }
        assertEquals(3, handler.getAmountAsInt(0));
        assertEquals(0, storedAmmo(handler));
    }

    @Test
    void largeAmountsKeepTheLastPartialStack() {
        var handler = new ItemStacksResourceHandler(3);
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(129, handler.insert(ItemResource.of(Items.ARROW), 129, transaction));
            transaction.commit();
        }
        assertEquals(64, handler.getAmountAsInt(0));
        assertEquals(64, handler.getAmountAsInt(1));
        assertEquals(1, handler.getAmountAsInt(2));
    }

    private static ItemStacksResourceHandler inventory(ItemStack... stacks) {
        return new ItemStacksResourceHandler(NonNullList.of(ItemStack.EMPTY, stacks));
    }

    private static List<ItemAccess> accesses(ItemStacksResourceHandler handler) {
        return IntStream.range(0, handler.size()).mapToObj(slot -> ItemAccess.forHandlerIndexStrict(handler, slot)).toList();
    }

    private static int storedAmmo(ItemStacksResourceHandler handler) {
        return ItemStackNbtHelper.getTag(ItemUtil.getStack(handler, 0)).getIntOr("AmmoCount", 0);
    }
}
