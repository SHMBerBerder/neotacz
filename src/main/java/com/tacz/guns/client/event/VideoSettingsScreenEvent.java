package com.tacz.guns.client.event;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.gui.NeoTaczVideoSettingsScreen;
import com.tacz.guns.util.MinecraftGuiCompat;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(value = Dist.CLIENT, modid = GunMod.MOD_ID)
public final class VideoSettingsScreenEvent {
    private VideoSettingsScreenEvent() {
    }

    @SubscribeEvent
    public static void addVideoSettingsEntry(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof VideoSettingsScreen screen)) {
            return;
        }
        for (var listener : event.getListenersList()) {
            if (listener instanceof OptionsList optionsList) {
                optionsList.addBig(Button.builder(Component.translatable("gui.tacz.video.title"),
                        button -> MinecraftGuiCompat.setScreen(new NeoTaczVideoSettingsScreen(screen))).build());
                return;
            }
        }
    }
}
