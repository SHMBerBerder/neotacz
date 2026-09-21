package com.tacz.guns.testsupport;

import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.WorldDataConfiguration;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecraftTestEnvironment {
    private static boolean initialized;
    private static boolean initializing;
    private static HolderLookup.Provider loadedRegistries;

    private MinecraftTestEnvironment() {
    }

    public static synchronized void bootstrap() {
        if (initialized) {
            return;
        }
        if (initializing) {
            throw new IllegalStateException("Recursive Minecraft test initialization");
        }
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        if (!gameDirectory.equals(Path.of("").toAbsolutePath().normalize())
                || !gameDirectory.endsWith(Path.of("build", "minecraft-junit"))
                || !Files.isRegularFile(gameDirectory.resolve("../../src/main/java/com/tacz/guns/GunMod.java").normalize())) {
            throw new IllegalStateException("Minecraft tests require this project's build/minecraft-junit directory: " + gameDirectory);
        }
        initializing = true;
        try {
            // FML's JUnit bootstrap loads mods, but 26.3 binds item defaults during the server resource reload.
            var packs = ServerPacksSource.createVanillaTrustedRepository();
            var packConfig = new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, true);
            var config = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED,
                    PermissionSet.ALL_PERMISSIONS);
            loadedRegistries = WorldLoader.load(config,
                    context -> new WorldLoader.DataLoadOutput<>(null, context.datapackDimensions()),
                    (resources, managers, registries, cookie) -> {
                        try {
                            return managers.fullRegistries().lookup();
                        } finally {
                            resources.close();
                        }
                    }, Runnable::run, Runnable::run).join();
            initialized = true;
        } finally {
            initializing = false;
        }
    }

    public static HolderLookup.Provider registries() {
        bootstrap();
        return loadedRegistries;
    }
}
