package com.tacz.guns.api.event.common;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.fml.LogicalSide;

/**
 * 生物开始更换枪械弹药时触发的事件。
 */
public class GunReloadEvent extends Event implements KubeJSGunEventPoster<GunReloadEvent>, ICancellableEvent {
    private final LivingEntity entity;
    private final ItemStack gunItemStack;
    private final LogicalSide logicalSide;
    private long durationMs;

    public GunReloadEvent(LivingEntity entity, ItemStack gunItemStack, LogicalSide side) {
        this(entity, gunItemStack, side, 0L);
    }

    public GunReloadEvent(LivingEntity entity, ItemStack gunItemStack, LogicalSide side, long durationMs) {
        this.entity = entity;
        this.gunItemStack = gunItemStack;
        this.logicalSide = side;
        this.durationMs = Math.max(0L, durationMs);
        postEventToKubeJS(this);
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public ItemStack getGunItemStack() {
        return gunItemStack;
    }

    public LogicalSide getLogicalSide() {
        return logicalSide;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(0L, durationMs);
    }
}
