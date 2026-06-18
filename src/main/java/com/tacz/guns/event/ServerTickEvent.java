package com.tacz.guns.event;

import net.neoforged.fml.common.EventBusSubscriber;

import com.tacz.guns.util.CycleTaskHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

@EventBusSubscriber
public class ServerTickEvent {
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // 更新 CycleTaskHelper 中的任务
        CycleTaskHelper.tick();
    }
}
