package net.neoforged.fml.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Test-only access to FML's actual permitted, package-private loaded config implementation. */
public final class LoadedConfigFixture {
    private LoadedConfigFixture() { }

    public static void accept(ModConfigSpec spec, CommentedConfig config) {
        // Pre-correction prevents acceptConfig from saving, so no file or live ModContainer is needed.
        spec.correct(config);
        if (!spec.isCorrect(config)) {
            throw new AssertionError("Loaded config fixture must not enter the save path");
        }
        spec.acceptConfig(new LoadedConfig(config, null, null));
    }
}
