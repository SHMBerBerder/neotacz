package com.tacz.guns.compat.playeranimator;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.animation.AnimationManager;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimationState;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;

public class PlayerAnimatorCompat {
    public static Identifier LOWER_ANIMATION = Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "lower_animation");
    public static Identifier LOOP_UPPER_ANIMATION = Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "loop_upper_animation");
    public static Identifier ONCE_UPPER_ANIMATION = Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "once_upper_animation");
    public static Identifier ROTATION_ANIMATION = Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "rotation");

    private static boolean backendReady;

    public static void init() {
        if (!backendReady) {
            NeoForge.EVENT_BUS.register(new AnimationManager());
            backendReady = true;
        }
    }

    public static boolean loadAnimationFromZip(ZipFile zipFile, String zipPath) {
        return backendReady && PlayerAnimatorLoader.load(zipFile, zipPath);
    }

    public static void loadAnimationFromFile(File file) {
        if (backendReady) {
            PlayerAnimatorLoader.load(file);
        }
    }

    public static void clearAllAnimationCache() {
        PlayerAnimatorAssetManager.get().clearAll();
    }

    public static boolean hasPlayerAnimator3rd(LivingEntity livingEntity, GunDisplayInstance display) {
        return backendReady && livingEntity instanceof AbstractClientPlayer && AnimationManager.hasPlayerAnimator3rd(display);
    }

    public static void stopAllAnimation(LivingEntity livingEntity) {
        stopAllAnimation(livingEntity, 8);
    }

    public static void stopAllAnimation(LivingEntity livingEntity, int fadeTime) {
        if (backendReady && livingEntity instanceof AbstractClientPlayer player) {
            state(player).stopAll(fadeTime);
        }
    }

    public static void playAnimation(LivingEntity livingEntity, GunDisplayInstance display, float limbSwingAmount) {
        if (backendReady && livingEntity instanceof AbstractClientPlayer player && state(player).selectOnce(player.tickCount)) {
            AnimationManager.playLowerAnimation(player, display, limbSwingAmount);
            AnimationManager.playLoopUpperAnimation(player, display, limbSwingAmount);
            AnimationManager.playRotationAnimation(player, display);
        }
    }

    public static boolean isInstalled() {
        // Kept for TACZ callers: this is our embedded backend, not an external mod-presence claim.
        return backendReady;
    }

    public static void registerReloadListener(BiConsumer<Identifier, PreparableReloadListener> register) {
        if (backendReady) {
            register.accept(Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "client/player_animations"), PlayerAnimatorAssetManager.get());
        }
    }

    public static PlayerAnimationState state(AbstractClientPlayer player) {
        PlayerAnimationState state = ((PlayerAnimationState.Access) player).tacz$getPlayerAnimationState();
        state.ensureGeneration(PlayerAnimatorAssetManager.get().generation());
        return state;
    }
}
