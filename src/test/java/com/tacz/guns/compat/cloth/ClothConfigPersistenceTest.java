package com.tacz.guns.compat.cloth;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingException;
import com.tacz.guns.GunMod;
import com.tacz.guns.config.ClientConfig;
import com.tacz.guns.config.CommonConfig;
import com.tacz.guns.config.ServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClothConfigPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void screenSavePersistsClientAndCommonButNeverServer() throws Exception {
        var client = config(ModConfig.Type.CLIENT, "client.toml");
        var common = config(ModConfig.Type.COMMON, "common.toml");
        var server = config(ModConfig.Type.SERVER, "server.toml");
        client.spec().save();
        common.spec().save();
        server.spec().save();
        client.value().set(false);
        common.value().set(false);
        server.value().set(false);
        assertEquals(true, read(client.path(), "edited"));
        assertEquals(true, read(common.path(), "edited"));

        var originalClient = ClientConfig.SPEC;
        var originalCommon = CommonConfig.SPEC;
        var originalServer = ServerConfig.SERVER_CONFIG_SPEC;
        try {
            ClientConfig.SPEC = client.spec();
            CommonConfig.SPEC = common.spec();
            ServerConfig.SERVER_CONFIG_SPEC = server.spec();
            MenuIntegration.saveConfigs();
            assertEquals(false, read(client.path(), "edited"));
            assertEquals(false, read(common.path(), "edited"));
            assertEquals(true, read(server.path(), "edited"));
            assertEquals(77, ((Number) read(client.path(), "unexposed")).intValue());
            assertEquals(77, ((Number) read(common.path(), "unexposed")).intValue());
        } finally {
            ClientConfig.SPEC = originalClient;
            CommonConfig.SPEC = originalCommon;
            ServerConfig.SERVER_CONFIG_SPEC = originalServer;
        }
    }

    @Test
    void saveFailurePropagatesInsteadOfReportingSuccessfulPersistence() throws Exception {
        var client = config(ModConfig.Type.CLIENT, "blocked.toml");
        var common = config(ModConfig.Type.COMMON, "common.toml");
        Files.createDirectory(client.path());
        Files.createFile(client.path().resolve("blocker"));
        common.spec().save();
        common.value().set(false);

        var originalClient = ClientConfig.SPEC;
        var originalCommon = CommonConfig.SPEC;
        try {
            ClientConfig.SPEC = client.spec();
            CommonConfig.SPEC = common.spec();
            assertThrows(WritingException.class, MenuIntegration::saveConfigs);
            assertEquals(true, read(common.path(), "edited"));
        } finally {
            ClientConfig.SPEC = originalClient;
            CommonConfig.SPEC = originalCommon;
        }
    }

    private LocalConfig config(ModConfig.Type type, String fileName) throws Exception {
        var builder = new ModConfigSpec.Builder();
        var value = builder.define("edited", true);
        builder.define("unexposed", 77);
        var spec = builder.build();
        var data = CommentedConfig.inMemory();
        spec.correct(data);
        Path path = directory.resolve(fileName);
        var container = new ModContainer(ModList.get().getModContainerById(GunMod.MOD_ID).orElseThrow().getModInfo()) {
            @Override
            public IEventBus getEventBus() {
                return null;
            }
        };
        // Use FML's actual disk writer without registering fixture configs or firing live mod events.
        var modConstructor = ModConfig.class.getDeclaredConstructor(ModConfig.Type.class, IConfigSpec.class,
                ModContainer.class, String.class, ReentrantLock.class);
        modConstructor.setAccessible(true);
        var modConfig = modConstructor.newInstance(type, spec, container, fileName, new ReentrantLock());
        var loadedClass = Class.forName("net.neoforged.fml.config.LoadedConfig", true, IConfigSpec.class.getClassLoader());
        var loadedConstructor = loadedClass.getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        loadedConstructor.setAccessible(true);
        spec.acceptConfig((IConfigSpec.ILoadedConfig) loadedConstructor.newInstance(data, path, modConfig));
        return new LocalConfig(spec, value, path);
    }

    private static Object read(Path path, String key) {
        try (var config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            return config.get(key);
        }
    }

    private record LocalConfig(ModConfigSpec spec, ModConfigSpec.BooleanValue value, Path path) { }
}
