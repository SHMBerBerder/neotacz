package com.tacz.guns.client.resource;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.Gson;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.statemachine.LuaStateMachineFactory;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.client.resource.pojo.display.gun.GunLod;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.config.client.ResourceConfig;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.config.LoadedConfigFixture;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.luaj.vm2.LuaTable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GunDisplayInstanceTest {
    private static final Identifier DISPLAY_ID = Identifier.fromNamespaceAndPath("test", "lazy_display");
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("test", "texture");

    @BeforeEach
    void loadClientConfig() {
        var builder = new ModConfigSpec.Builder();
        ResourceConfig.init(builder);
        LoadedConfigFixture.accept(builder.build(), CommentedConfig.inMemory());
    }

    @ParameterizedTest
    @EnumSource(AssetGetter.class)
    void pendingGetterReturnsPromptlyWithoutWaitingOrExposingPartialAssets(AssetGetter getter) throws Exception {
        GunDisplayInstance instance = fixture();
        setPendingTasks(instance);
        try {
            assertAbsent(getter, callPromptly(() -> getter.get(instance), () -> completeTasks(instance)));
            assertFalse((boolean) field(instance, "animationLoadFailed"), "Pending model is not animation failure");
        } finally {
            instance.invalidate();
            completeTasks(instance);
        }
    }

    @Test
    void completedFutureIsNotAReadinessFlag() throws Exception {
        GunDisplayInstance instance = fixture();
        setPendingTasks(instance);
        completeTasks(instance);
        try {
            assertAll(List.of(AssetGetter.values()).stream().map(getter -> (Executable) () ->
                    assertAbsent(getter, getter.get(instance))));
            assertFalse((boolean) field(instance, "animationLoadFailed"));
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void readyFlagsPublishCompletedAssetsToGetters() throws Exception {
        GunDisplayInstance instance = fixture();
        setPendingTasks(instance);
        CompletableFuture.runAsync(() -> {
            try {
                set(instance, "modelLoaded", true);
                set(instance, "lodLoaded", true);
                set(instance, "animationLoaded", true);
                completeTasks(instance);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).get(5, TimeUnit.SECONDS);
        try {
            assertSame(field(instance, "gunModel"), instance.getGunModel());
            assertSame(field(instance, "lodModel"), instance.getLodModel());
            assertSame(field(instance, "animationStateMachine"), instance.getAnimationStateMachine());
            assertSame(field(instance, "stateMachineParam"), instance.getStateMachineParam());
            assertEquals(TEXTURE_ID, instance.getModelTexture());
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void invalidationHidesPreviouslyReadyAssets() throws Exception {
        GunDisplayInstance instance = fixture();
        set(instance, "modelLoaded", true);
        set(instance, "lodLoaded", true);
        set(instance, "animationLoaded", true);
        instance.invalidate();
        assertAll(List.of(AssetGetter.values()).stream().map(getter -> (Executable) () ->
                assertAbsent(getter, getter.get(instance))));
    }

    @Test
    void runtimeWarmupDoesNotWaitForHeavyLoadLockAndSchedulesOnlyOnce() throws Exception {
        GunDisplayInstance instance = fixture();
        set(instance, "modelWarmUpTask", new CompletableFuture<Void>());
        Object loadLock = field(instance, "loadLock");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().daemon().start(() -> {
            synchronized (loadLock) {
                locked.countDown();
                await(release);
            }
        });
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            callPromptly(() -> {
                instance.warmUpRuntime();
                return null;
            }, release::countDown);
            Object scheduled = field(instance, "animationWarmUpTask");
            assertNotNull(scheduled);
            for (int i = 0; i < 20; i++) {
                instance.warmUpRuntime();
                assertSame(scheduled, field(instance, "animationWarmUpTask"));
            }
            assertFalse((boolean) field(instance, "animationLoadFailed"));
        } finally {
            instance.invalidate();
            release.countDown();
            completeTasks(instance);
            holder.join(5000);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    void eachWarmupKeepsOneTaskWhilePending() throws Exception {
        GunDisplayInstance instance = fixture();
        CountDownLatch release = blockDispatcher();
        try {
            instance.warmUpModel();
            instance.warmUpLod();
            instance.warmUpRuntime();
            Object model = field(instance, "modelWarmUpTask");
            Object lod = field(instance, "lodWarmUpTask");
            Object animation = field(instance, "animationWarmUpTask");
            assertNotNull(model);
            assertNotNull(lod);
            assertNotNull(animation);
            for (int i = 0; i < 20; i++) {
                instance.warmUpModel();
                instance.warmUpLod();
                instance.warmUpRuntime();
                assertSame(model, field(instance, "modelWarmUpTask"));
                assertSame(lod, field(instance, "lodWarmUpTask"));
                assertSame(animation, field(instance, "animationWarmUpTask"));
            }
        } finally {
            instance.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void failedAssetsAreNotRescheduledEveryFrame() throws Exception {
        GunDisplayInstance instance = fixture();
        set(instance, "modelLoadFailed", true);
        set(instance, "lodLoadFailed", true);
        set(instance, "animationLoadFailed", true);
        CountDownLatch release = blockDispatcher();
        try {
            for (int i = 0; i < 20; i++) {
                instance.warmUpModel();
                instance.warmUpLod();
                instance.warmUpRuntime();
            }
            assertNull(field(instance, "modelWarmUpTask"));
            assertNull(field(instance, "lodWarmUpTask"));
            assertNull(field(instance, "animationWarmUpTask"));
        } finally {
            instance.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void modelDependencyFailureBecomesStickyWithoutWaitingForHeavyLoadLock() throws Exception {
        GunDisplayInstance instance = fixture();
        CompletableFuture<Void> modelTask = new CompletableFuture<>();
        set(instance, "modelWarmUpTask", modelTask);
        instance.warmUpRuntime();
        Object loadLock = field(instance, "loadLock");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().daemon().start(() -> {
            synchronized (loadLock) {
                locked.countDown();
                await(release);
            }
        });
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            callPromptly(() -> modelTask.completeExceptionally(new IllegalArgumentException("fixture failure")),
                    release::countDown);
            assertTrue((boolean) field(instance, "animationLoadFailed"));
            assertNull(field(instance, "animationWarmUpTask"));
            instance.warmUpRuntime();
            assertNull(field(instance, "animationWarmUpTask"));
        } finally {
            instance.invalidate();
            release.countDown();
            holder.join(5000);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    void invalidationDuringLodLoadPreventsReadyPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GunDisplay display = new GunDisplay() {
            @Override
            public GunLod getGunLod() {
                entered.countDown();
                await(release);
                return null;
            }
        };
        GunDisplayInstance instance = new GunDisplayInstance(DISPLAY_ID, display);
        try {
            instance.warmUpLod();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            instance.invalidate();
            release.countDown();
            drainDispatcher();
            assertFalse((boolean) field(instance, "lodLoaded"));
            assertNull(instance.getLodModel());
        } finally {
            instance.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void eagerModeStillCompletesLodSynchronously() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(DISPLAY_ID, new GunDisplay());
        ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.set(false);
        try {
            assertNull(instance.getLodModel());
            assertTrue((boolean) field(instance, "lodLoaded"));
            assertNull(field(instance, "lodWarmUpTask"));
        } finally {
            instance.invalidate();
            ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.set(true);
        }
    }

    private static GunDisplayInstance fixture() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(DISPLAY_ID, new GunDisplay());
        BedrockGunModel model = new BedrockGunModel(new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,0,0]}]}]}
                """, BedrockModelPOJO.class), BedrockVersion.NEW);
        set(instance, "gunModel", model);
        set(instance, "lodModel", Pair.of(model, TEXTURE_ID));
        set(instance, "modelTexture", TEXTURE_ID);
        set(instance, "animationStateMachine", new LuaStateMachineFactory<GunAnimationStateContext>()
                .setController(new AnimationController(List.of(), model)).build());
        set(instance, "stateMachineParam", new LuaTable());
        return instance;
    }

    private static void setPendingTasks(GunDisplayInstance instance) throws Exception {
        set(instance, "modelWarmUpTask", new CompletableFuture<Void>());
        set(instance, "lodWarmUpTask", new CompletableFuture<Void>());
        set(instance, "animationWarmUpTask", new CompletableFuture<Void>());
    }

    @SuppressWarnings("unchecked")
    private static void completeTasks(GunDisplayInstance instance) throws Exception {
        for (String name : List.of("modelWarmUpTask", "lodWarmUpTask", "animationWarmUpTask")) {
            CompletableFuture<Void> task = (CompletableFuture<Void>) field(instance, name);
            if (task != null) task.complete(null);
        }
    }

    private static <T> T callPromptly(Callable<T> action, Executable unblock) throws Exception {
        var caller = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().unstarted(r));
        try {
            try {
                return caller.submit(action).get(1, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                throw new AssertionError("Lazy asset access must not wait for loading", exception);
            }
        } finally {
            try {
                unblock.execute();
            } catch (Throwable throwable) {
                throw new AssertionError("Failed to unblock controlled test work", throwable);
            } finally {
                caller.shutdown();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS), "Getter thread must be cleaned up");
            }
        }
    }

    private static CountDownLatch blockDispatcher() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClientAssetLoadDispatcher.executor().execute(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    private static void drainDispatcher() throws Exception {
        ClientAssetLoadDispatcher.executor().submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object field(GunDisplayInstance instance, String name) throws Exception {
        Field field = GunDisplayInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(GunDisplayInstance instance, String name, Object value) throws Exception {
        Field field = GunDisplayInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void assertAbsent(AssetGetter getter, Object value) {
        if (getter == AssetGetter.TEXTURE) assertEquals(MissingTextureAtlasSprite.getLocation(), value);
        else assertNull(value, getter.name());
    }

    private enum AssetGetter {
        MODEL, LOD, ANIMATION, PARAM, TEXTURE;

        Object get(GunDisplayInstance instance) {
            return switch (this) {
                case MODEL -> instance.getGunModel();
                case LOD -> instance.getLodModel();
                case ANIMATION -> instance.getAnimationStateMachine();
                case PARAM -> instance.getStateMachineParam();
                case TEXTURE -> instance.getModelTexture();
            };
        }
    }
}
