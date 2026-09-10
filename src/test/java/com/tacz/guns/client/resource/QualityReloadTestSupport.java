package com.tacz.guns.client.resource;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.Gson;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.config.client.ResourceConfig;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.config.LoadedConfigFixture;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class QualityReloadTestSupport {
    private QualityReloadTestSupport() { }

    static void loadConfig() {
        var builder = new ModConfigSpec.Builder();
        ResourceConfig.init(builder);
        LoadedConfigFixture.accept(builder.build(), CommentedConfig.inMemory());
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("test", path);
    }

    static GunDisplayInstance display(String path) {
        return new GunDisplayInstance(id(path), new GunDisplay());
    }

    static void readyModel(GunDisplayInstance instance) throws Exception {
        var model = new BedrockGunModel(new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,0,0]}]}]}
                """, BedrockModelPOJO.class), BedrockVersion.NEW);
        set(instance, "gunModel", model);
        set(instance, "modelLoaded", true);
    }

    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static CompletableFuture<Void> all(List<CompletableFuture<Void>> tasks) {
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    static CountDownLatch blockDispatcher() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClientAssetLoadDispatcher.executor().execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    static void drainDispatcher() throws Exception {
        ClientAssetLoadDispatcher.executor().submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    static <T> T promptly(Callable<T> action, Runnable unblock) throws Exception {
        var caller = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().unstarted(r));
        try {
            return caller.submit(action).get(1, TimeUnit.SECONDS);
        } finally {
            unblock.run();
            caller.shutdown();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
