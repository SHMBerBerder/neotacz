package com.tacz.guns.testsupport;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;

/** Uses FML's real loaded config without claiming an FML package in the test mod. */
public final class LoadedConfigFixture {
    private LoadedConfigFixture() { }

    public static void accept(ModConfigSpec spec, CommentedConfig config) {
        // Pre-correction prevents acceptConfig from saving, so no file or live ModContainer is needed.
        spec.correct(config);
        if (!spec.isCorrect(config)) {
            throw new AssertionError("Loaded config fixture must not enter the save path");
        }
        try {
            Class<?> loadedConfig = Class.forName("net.neoforged.fml.config.LoadedConfig", true,
                    IConfigSpec.class.getClassLoader());
            var constructor = loadedConfig.getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
            constructor.setAccessible(true);
            spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot create FML's actual loaded config", exception);
        }
    }
}
