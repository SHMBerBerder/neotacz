package com.tacz.guns.api.client.other;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 用来在收物品时，让其保持一段时间渲染的接口
 */
public interface KeepingItemRenderer {
    KeepingItemRenderer NO_PLAYER = new KeepingItemRenderer() {
        @Override
        public void keep(ItemStack itemStack, long timeMs) {
        }

        @Override
        public ItemStack getCurrentItem() {
            return ItemStack.EMPTY;
        }
    };

    /**
     * 物品保持渲染的时间
     *
     * @param itemStack 保持的物品
     * @param timeMs    时间，单位毫秒
     */
    void keep(ItemStack itemStack, long timeMs);

    /**
     * 获取当前主手正在渲染的物品
     */
    ItemStack getCurrentItem();

    /**
     * FirstPersonHandsAndItems 通过 Mixin 的方式实现了此接口。
     * @return 返回当前玩家的持物状态，无玩家时返回空状态
     */
    static KeepingItemRenderer getRenderer(){
        LocalPlayer player = Minecraft.getInstance().player;
        // The player's state is replaced on respawn/dimension change; never retain it globally.
        return player == null ? NO_PLAYER : (KeepingItemRenderer) player.firstPersonHandsAndItems();
    }
}
