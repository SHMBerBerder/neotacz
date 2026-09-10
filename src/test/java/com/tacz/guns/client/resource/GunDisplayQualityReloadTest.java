package com.tacz.guns.client.resource;

import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.client.resource.pojo.display.gun.LayerGunShow;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoParticle;
import com.tacz.guns.config.client.ResourceConfig;
import com.tacz.guns.resource.manager.ScriptManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.tacz.guns.client.resource.GunDisplayInstance.*;
import static com.tacz.guns.client.resource.QualityReloadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class GunDisplayQualityReloadTest {
    @BeforeEach
    void config() {
        loadConfig();
    }

    @Test
    void requestedLoadsHasNoSideEffectsAndIncludesPendingReadyAndStickyFailure() throws Exception {
        var instance = display("requests");
        assertEquals(0, instance.requestedLoads());
        assertNull(field(instance, "modelWarmUpTask"));
        assertNull(field(instance, "lodWarmUpTask"));
        assertNull(field(instance, "animationWarmUpTask"));
        set(instance, "animationWarmUpTask", new CompletableFuture<Void>());
        assertEquals(LOAD_RUNTIME | LOAD_MODEL, instance.requestedLoads());
        set(instance, "lodLoadFailed", true);
        assertEquals(LOAD_RUNTIME | LOAD_MODEL | LOAD_LOD, instance.requestedLoads());
        instance.invalidate();
        assertEquals(0, instance.requestedLoads());
        var ready = display("ready_requests");
        set(ready, "modelLoaded", true);
        set(ready, "lodLoaded", true);
        assertEquals(LOAD_MODEL | LOAD_LOD, ready.requestedLoads());
        set(ready, "animationLoadFailed", true);
        assertEquals(LOAD_MODEL | LOAD_LOD | LOAD_RUNTIME, ready.requestedLoads());
        ready.invalidate();
    }

    @Test
    void deferredCopyDoesNotLoadAndKeepsGettersAsyncWhenGlobalLazyIsDisabled() throws Exception {
        AtomicInteger heavyCalls = new AtomicInteger();
        var source = new GunDisplayInstance(id("deferred"), new GunDisplay() {
            @Override public Identifier getModelLocation() {
                heavyCalls.incrementAndGet();
                throw new AssertionError("Metadata staging must not load models");
            }
        });
        ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.set(false);
        var copy = promptly(source::deferredCopy, () -> { });
        assertNotSame(source, copy);
        assertEquals(0, copy.requestedLoads());
        assertEquals(0, heavyCalls.get());
        var release = blockDispatcher();
        try {
            assertNull(promptly(copy::getLodModel, release::countDown));
            assertNotNull(field(copy, "lodWarmUpTask"), "Instance override must also cover getters");
        } finally {
            source.invalidate();
            copy.invalidate();
            release.countDown();
            drainDispatcher();
            ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.set(true);
        }
    }

    @Test
    void deferredCopyUsesValidatedMetadataAndDoesNotShareMutableContainersOrLoadedAssets() throws Exception {
        AtomicBoolean rejectMetadataRead = new AtomicBoolean();
        var source = new GunDisplayInstance(id("copy_metadata"), new GunDisplay() {
            @Override public String getThirdPersonAnimation() {
                if (rejectMetadataRead.get()) throw new AssertionError("Validated metadata must not be re-read");
                return "fixture_animation";
            }
            @Override public com.tacz.guns.client.resource.pojo.display.gun.GunAmmo getGunAmmo() {
                if (rejectMetadataRead.get()) throw new AssertionError("Particle metadata must not be reparsed");
                return super.getGunAmmo();
            }
        });
        var sounds = new HashMap<String, Identifier>();
        sounds.put("draw", id("draw"));
        var preloads = new ArrayList<>(List.of(id("draw")));
        var hotbar = new Int2ObjectArrayMap<LayerGunShow>();
        hotbar.put(1, new LayerGunShow());
        var particle = new AmmoParticle();
        set(source, "sounds", sounds);
        set(source, "preloadSounds", preloads);
        set(source, "hotbarShow", hotbar);
        set(source, "particle", particle);
        set(source, "modelTexture", id("loaded_texture"));
        readyModel(source);
        set(source, "lodLoadFailed", true);
        rejectMetadataRead.set(true);
        var copy = source.deferredCopy();
        assertEquals("fixture_animation", copy.getThirdPersonAnimation());
        assertSame(particle, copy.getParticle());
        assertNull(particle.getParticleOptions());
        assertNotSame(sounds, field(copy, "sounds"));
        assertNotSame(preloads, copy.getPreloadSounds());
        assertNotSame(hotbar, copy.getHotbarShow());
        assertEquals(sounds, field(copy, "sounds"));
        assertEquals(preloads, copy.getPreloadSounds());
        assertEquals(hotbar, copy.getHotbarShow());
        copy.getPreloadSounds().clear();
        copy.getHotbarShow().clear();
        assertEquals(1, preloads.size());
        assertEquals(1, hotbar.size());
        assertNull(field(copy, "gunModel"));
        assertNull(field(copy, "modelTexture"));
        assertNull(field(copy, "animationStateMachine"));
        assertEquals(0, copy.requestedLoads());
        source.invalidate();
        copy.invalidate();
    }

    @Test
    void reloadWarmupOnlySchedulesRequestedStagesAndRuntimeImpliesModel() throws Exception {
        var instance = display("runtime");
        var release = blockDispatcher();
        try {
            var tasks = instance.warmUpForReload(LOAD_RUNTIME);
            assertEquals(2, tasks.size());
            assertNotNull(field(instance, "modelWarmUpTask"));
            assertNotNull(field(instance, "animationWarmUpTask"));
            assertNull(field(instance, "lodWarmUpTask"));
            Object model = field(instance, "modelWarmUpTask");
            Object animation = field(instance, "animationWarmUpTask");
            instance.warmUpForReload(LOAD_RUNTIME);
            assertSame(model, field(instance, "modelWarmUpTask"));
            assertSame(animation, field(instance, "animationWarmUpTask"));
        } finally {
            instance.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void cancellingAnObserverDoesNotCancelSharedAssetWork() throws Exception {
        var instance = display("observer_cancel");
        var shared = new CompletableFuture<Void>();
        set(instance, "modelWarmUpTask", shared);
        var observed = instance.warmUpForReload(LOAD_MODEL).getFirst();
        observed.cancel(false);
        assertFalse(shared.isCancelled(), "Reload observers do not own the shared loader task");
        readyModel(instance);
        shared.complete(null);
        all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS);
        instance.invalidate();
    }

    @Test
    void completedFutureWithoutReadyFlagIsAnObservableFailure() throws Exception {
        var instance = display("false_ready");
        set(instance, "modelWarmUpTask", CompletableFuture.completedFuture(null));
        assertThrows(ExecutionException.class,
                () -> all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
        assertFalse((boolean) field(instance, "modelLoaded"));
        instance.invalidate();
    }

    @Test
    void pendingWorkSucceedsOnlyAfterItsRealReadyPublication() throws Exception {
        var instance = display("pending_ready");
        var shared = new CompletableFuture<Void>();
        set(instance, "modelWarmUpTask", shared);
        var observed = all(instance.warmUpForReload(LOAD_MODEL));
        assertFalse(observed.isDone());
        readyModel(instance);
        assertFalse(observed.isDone());
        shared.complete(null);
        observed.get(5, TimeUnit.SECONDS);
        assertNotNull(instance.getGunModel());
        instance.invalidate();
    }

    @Test
    void invalidatedPendingWorkCannotReportReady() throws Exception {
        var instance = display("cancelled");
        var shared = new CompletableFuture<Void>();
        set(instance, "modelWarmUpTask", shared);
        var observed = all(instance.warmUpForReload(LOAD_MODEL));
        instance.invalidate();
        assertTrue(observed.isDone());
        assertTrue(observed.isCompletedExceptionally());
        assertNull(instance.getGunModel());
        assertTrue(all(instance.warmUpForReload(LOAD_MODEL)).isCompletedExceptionally());
    }

    @Test
    void actualLoadFailureRemainsStickyAndObservableOnLaterRequests() throws Exception {
        var failure = new IllegalArgumentException("controlled metadata model failure");
        AtomicInteger attempts = new AtomicInteger();
        var instance = new GunDisplayInstance(id("failure"), new GunDisplay() {
            @Override public String getModelType() {
                attempts.incrementAndGet();
                throw failure;
            }
        });
        try {
            assertThrows(ExecutionException.class,
                    () -> all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
            drainDispatcher();
            assertTrue((boolean) field(instance, "modelLoadFailed"));
            for (int i = 0; i < 3; i++) {
                assertThrows(ExecutionException.class,
                        () -> all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, attempts.get());
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void declaredGltfFailureIsObservableWithoutExposingOldBedrockModel() throws Exception {
        var failure = new IllegalArgumentException("controlled optional glTF failure");
        var config = new GunRenderModelConfig() {
            @Override public boolean isGltf() { return true; }
            @Override public void validate() { throw failure; }
        };
        var instance = new GunDisplayInstance(id("mesh_failure"), new GunDisplay() {
            @Override public GunRenderModelConfig getRenderModel() { return config; }
        });
        readyModel(instance);
        var attach = GunDisplayInstance.class.getDeclaredMethod("tryAttachGltfBody", GunRenderModelConfig.class, Set.class);
        attach.setAccessible(true);
        attach.invoke(instance, config, Set.of());
        var thrown = assertThrows(ExecutionException.class,
                () -> all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) causes.add(cause);
        assertTrue(causes.contains(failure), "Retain the actual mesh-renderer error for the reload result");
        assertNull(instance.getGunModel(), "A failed declared mesh must not expose its old rig geometry");
        instance.invalidate();
    }

    @Test
    void absentLodIsLegitimateCompletedWork() throws Exception {
        var instance = display("no_lod");
        try {
            all(instance.warmUpForReload(LOAD_LOD)).get(5, TimeUnit.SECONDS);
            assertTrue((boolean) field(instance, "lodLoaded"));
            assertNull(instance.getLodModel());
            assertNull(field(instance, "modelWarmUpTask"));
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void absentAnimationFileAndParamsStillAllowDefaultRuntime() throws Exception {
        Object previous = field(ClientAssetsManager.INSTANCE, "scriptManager");
        set(ClientAssetsManager.INSTANCE, "scriptManager", new ScriptManager(null, List.of()) {
            @Override public LuaTable getScript(Identifier id) { return new LuaTable(); }
        });
        var instance = display("default_runtime");
        readyModel(instance);
        try {
            all(instance.warmUpForReload(LOAD_RUNTIME)).get(5, TimeUnit.SECONDS);
            assertNotNull(instance.getAnimationStateMachine());
            assertNull(instance.getStateMachineParam());
        } finally {
            instance.invalidate();
            drainDispatcher();
            set(ClientAssetsManager.INSTANCE, "scriptManager", previous);
        }
    }
}
