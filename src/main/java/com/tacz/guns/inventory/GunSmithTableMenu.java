package com.tacz.guns.inventory;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ServerMessageCraft;
import com.tacz.guns.resource.filter.RecipeFilter;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.GunMod;
import com.tacz.guns.resource.index.CommonBlockIndex;
import com.tacz.guns.util.GunSmithTableBlockIds;
import com.tacz.guns.util.InventoryHandlerUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import javax.annotation.Nullable;
import java.util.List;

public class GunSmithTableMenu extends AbstractContainerMenu {
    public static final MenuType<GunSmithTableMenu> TYPE = IMenuTypeExtension.create((windowId, inv, data) -> {
        Identifier blockId = data.readIdentifier();
        return new GunSmithTableMenu(windowId, inv, blockId);
    });

    @Nullable
    private final Identifier blockId;
    private final RecipeFilter filter;

    public GunSmithTableMenu(int id, Inventory inventory, @Nullable Identifier resourceLocation) {
        super(TYPE, id);
        this.blockId = GunSmithTableBlockIds.normalize(resourceLocation);
        this.filter = TimelessAPI.getCommonBlockIndex(getBlockId()).map(CommonBlockIndex::getFilter).orElse(null);
    }

    @Nullable
    public Identifier getBlockId() {
        return blockId;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int pIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    @Nullable
    private GunSmithTableRecipe getRecipe(Identifier recipeId, RecipeManager recipeManager) {
        if (!DefaultAssets.DEFAULT_BLOCK_ID.equals(getBlockId()) || SyncConfig.ENABLE_TABLE_FILTER.get()) {
            if (filter != null && !filter.contains(recipeId)) {
                return null;
            }
        }

        Recipe<?> recipe = recipeManager.byKey(ResourceKey.create(Registries.RECIPE, recipeId)).map(holder -> holder.value()).orElse(null);
        if (recipe == null) {
            CommonAssetsManager assets = CommonAssetsManager.getInstance();
            recipe = assets == null ? null : assets.getServerRecipe(recipeId);
        }
        if (recipe instanceof GunSmithTableRecipe gunSmithTableRecipe) {
            try {
                gunSmithTableRecipe.init();
                if (gunSmithTableRecipe.getOutput().isEmpty() || gunSmithTableRecipe.getTab() == null) {
                    return null;
                }
            } catch (RuntimeException exception) {
                GunMod.LOGGER.warn("Failed to initialize requested gun smith table recipe {}", recipeId, exception);
                return null;
            }
            boolean flag = TimelessAPI.getCommonBlockIndex(getBlockId()).map(blockIndex -> {
                return blockIndex.getData().getTabs().stream().noneMatch(tab -> tab.id().equals(gunSmithTableRecipe.getTab()));
            }).orElse(true);
            if (DefaultAssets.DEFAULT_BLOCK_ID.equals(getBlockId()) && !SyncConfig.ENABLE_TABLE_FILTER.get()) {
                flag = false;
            }
            if (flag) {
                return null;
            }
            return gunSmithTableRecipe;
        }
        return null;
    }

    public void doCraft(Identifier recipeId, Player player) {
        Level level = player.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        GunSmithTableRecipe recipe = getRecipe(recipeId, serverLevel.getServer().getRecipeManager());
        if (recipe == null) {
            return;
        }
        // 是创造模式，就不扣材料
        if (!player.isCreative()) {
            // Capture once: recipes include every inventory slot, including body armor and saddle.
            var slots = InventoryHandlerUtils.forContainerContents(player.getInventory());
            try (Transaction transaction = Transaction.openRoot()) {
                for (GunSmithTableIngredient ingredient : recipe.getInputs()) {
                    int extracted = InventoryHandlerUtils.extractMatching(slots, ingredient.getIngredient()::test,
                            ingredient.getCount(), transaction);
                    if (extracted != ingredient.getCount()) {
                        return;
                    }
                }
                transaction.commit();
            }
            player.getInventory().setChanged();
        }

        // 给玩家对应的物品
        if (!level.isClientSide()) {
            ItemEntity itemEntity = new ItemEntity(level, player.getX(), player.getY() + 0.5, player.getZ(), recipe.getResultItem(player.level().registryAccess()).copy());
            itemEntity.setPickUpDelay(0);
            level.addFreshEntity(itemEntity);
        }
        // 更新，否则客户端显示不正确
        player.inventoryMenu.broadcastFullState();
        NetworkHandler.sendToClientPlayer(new ServerMessageCraft(this.containerId), player);
    }
}
