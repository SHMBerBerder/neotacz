package com.tacz.guns.client.resource;

import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tacz.guns.client.resource.GunDisplayInstance.*;
import static com.tacz.guns.client.resource.QualityReloadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientIndexQualityReloadTest {
    private Map<Identifier, GunDisplayInstance> originalMap;
    private Map<Identifier, GunDisplayInstance> originalEntries;

    @BeforeEach
    void configAndTable() {
        loadConfig();
        originalMap = ClientIndexManager.GUN_DISPLAY;
        originalEntries = new HashMap<>(originalMap);
        originalMap.clear();
    }

    @AfterEach
    void restoreTable() throws Exception {
        ClientIndexManager.GUN_DISPLAY.values().forEach(GunDisplayInstance::invalidate);
        drainDispatcher();
        var field = ClientIndexManager.class.getDeclaredField("GUN_DISPLAY");
        if (!Modifier.isFinal(field.getModifiers())) field.set(null, originalMap);
        originalMap.clear();
        originalMap.putAll(originalEntries);
    }

    @Test
    void stagingDoesNotPublishInvalidateOrBeginLoading() throws Exception {
        var old = display("old");
        set(old, "lodLoaded", true);
        ClientIndexManager.GUN_DISPLAY.put(id("old"), old);
        var before = ClientIndexManager.GUN_DISPLAY;
        var staged = ClientIndexManager.stageQualityReload();
        assertSame(before, ClientIndexManager.GUN_DISPLAY);
        assertSame(old, ClientIndexManager.getOrCreateGunDisplay(id("old")));
        assertFalse((boolean) field(old, "invalidated"));
        assertNull(field(old, "modelWarmUpTask"));
        assertNull(field(old, "lodWarmUpTask"));
        staged.publish();
        var replacement = ClientIndexManager.getOrCreateGunDisplay(id("old"));
        assertNotSame(old, replacement);
        assertEquals(0, replacement.requestedLoads());
        assertTrue((boolean) field(old, "invalidated"));
    }

    @Test
    void metadataFailureLeavesTheEntireOriginalTableUsable() throws Exception {
        var bad = display("bad");
        var good = display("good");
        ClientIndexManager.GUN_DISPLAY.put(id("good"), good);
        ClientIndexManager.GUN_DISPLAY.put(id("bad"), bad);
        var before = ClientIndexManager.GUN_DISPLAY;
        AtomicInteger copies = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> ClientIndexManager.stageQualityReload(instance -> {
            if (copies.incrementAndGet() == 2) throw new IllegalArgumentException("controlled staging failure");
            return instance.deferredCopy();
        }));
        assertEquals(2, copies.get(), "Failure occurs after one successful staged copy");
        assertSame(before, ClientIndexManager.GUN_DISPLAY);
        assertEquals(Map.of(id("good"), good, id("bad"), bad), before);
        assertFalse((boolean) field(good, "invalidated"));
        assertFalse((boolean) field(bad, "invalidated"));
        assertEquals(0, good.requestedLoads());
        assertEquals(0, bad.requestedLoads());
    }

    @Test
    void publicationReplacesOneCompleteTableWithoutMutatingTheCapturedOldMap() throws Exception {
        var first = display("first");
        var second = display("second");
        ClientIndexManager.GUN_DISPLAY.put(id("first"), first);
        ClientIndexManager.GUN_DISPLAY.put(id("second"), second);
        var before = ClientIndexManager.GUN_DISPLAY;
        var gunIndex = ClientIndexManager.GUN_INDEX;
        var ammoIndex = ClientIndexManager.AMMO_INDEX;
        var attachments = ClientIndexManager.ATTACHMENT_INDEX;
        var blocks = ClientIndexManager.BLOCK_INDEX;
        var staged = ClientIndexManager.stageQualityReload();
        staged.publish();
        assertNull(field(staged, "previous"), "The operation must release old display/model owners");
        assertNotSame(before, ClientIndexManager.GUN_DISPLAY);
        assertEquals(before.keySet(), ClientIndexManager.GUN_DISPLAY.keySet());
        assertSame(first, before.get(id("first")), "Do not clear the old table before publication");
        assertSame(second, before.get(id("second")));
        for (var entry : before.entrySet()) {
            assertNotSame(entry.getValue(), ClientIndexManager.GUN_DISPLAY.get(entry.getKey()));
            assertTrue((boolean) field(entry.getValue(), "invalidated"));
        }
        assertSame(gunIndex, ClientIndexManager.GUN_INDEX);
        assertSame(ammoIndex, ClientIndexManager.AMMO_INDEX);
        assertSame(attachments, ClientIndexManager.ATTACHMENT_INDEX);
        assertSame(blocks, ClientIndexManager.BLOCK_INDEX);
    }

    @Test
    void warmupMergesPreviousAndInventoryRequestsWithoutLoadingUntouchedDisplays() throws Exception {
        var previous = display("previous");
        set(previous, "lodLoaded", true);
        ClientIndexManager.GUN_DISPLAY.put(id("previous"), previous);
        ClientIndexManager.GUN_DISPLAY.put(id("inventory"), display("inventory"));
        ClientIndexManager.GUN_DISPLAY.put(id("untouched"), display("untouched"));
        var staged = ClientIndexManager.stageQualityReload();
        assertThrows(IllegalStateException.class, () -> staged.warmUp(() -> { }, () -> { }));
        staged.publish();
        var release = blockDispatcher();
        AtomicInteger resets = new AtomicInteger();
        try {
            Map<Identifier, Integer> targets = staged.warmUp(
                    () -> ClientIndexManager.GUN_DISPLAY.get(id("inventory")).warmUpRuntime(),
                    resets::incrementAndGet);
            assertEquals(Map.of(id("previous"), LOAD_LOD, id("inventory"), LOAD_MODEL | LOAD_RUNTIME), targets);
            assertEquals(1, resets.get());
            assertNotNull(field(ClientIndexManager.GUN_DISPLAY.get(id("previous")), "lodWarmUpTask"));
            assertEquals(0, ClientIndexManager.GUN_DISPLAY.get(id("untouched")).requestedLoads());
        } finally {
            ClientIndexManager.GUN_DISPLAY.values().forEach(GunDisplayInstance::invalidate);
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void asyncFailureKeepsTheCompletePublishedTableAndRemainsObservable() throws Exception {
        var broken = new GunDisplayInstance(id("broken"), new GunDisplay() {
            @Override public String getModelType() {
                throw new IllegalArgumentException("controlled asynchronous model failure");
            }
        });
        set(broken, "modelLoadFailed", true);
        ClientIndexManager.GUN_DISPLAY.put(id("broken"), broken);
        ClientIndexManager.GUN_DISPLAY.put(id("other"), display("other"));
        var staged = ClientIndexManager.stageQualityReload();
        staged.publish();
        var published = ClientIndexManager.GUN_DISPLAY;
        staged.warmUp(() -> { }, () -> { });
        var replacement = ClientIndexManager.getOrCreateGunDisplay(id("broken"));
        assertThrows(ExecutionException.class,
                () -> all(replacement.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
        assertSame(published, ClientIndexManager.GUN_DISPLAY);
        assertEquals(2, published.size());
        assertNotNull(ClientIndexManager.getOrCreateGunDisplay(id("other")));
        assertFalse((boolean) field(replacement, "invalidated"));
    }

    @Test
    void cleanupFailureAfterPublicationDoesNotRevertOrPartiallyClearTheNewTable() {
        ClientIndexManager.GUN_DISPLAY.put(id("first"), display("first"));
        ClientIndexManager.GUN_DISPLAY.put(id("second"), display("second"));
        var staged = ClientIndexManager.stageQualityReload();
        staged.publish();
        var published = ClientIndexManager.GUN_DISPLAY;
        Runnable cleanup = () -> { throw new IllegalStateException("controlled GPU cleanup failure"); };
        assertThrows(IllegalStateException.class, cleanup::run);
        assertSame(published, ClientIndexManager.GUN_DISPLAY);
        assertEquals(2, published.size());
        assertNotNull(ClientIndexManager.getOrCreateGunDisplay(id("first")));
        assertNotNull(ClientIndexManager.getOrCreateGunDisplay(id("second")));
    }
}
