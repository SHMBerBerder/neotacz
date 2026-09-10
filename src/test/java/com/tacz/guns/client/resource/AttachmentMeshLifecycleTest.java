package com.tacz.guns.client.resource;

import com.google.gson.GsonBuilder;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.tacz.guns.client.resource.QualityReloadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentMeshLifecycleTest {
    @Test
    void meshAndSemanticReadsReturnImmediatelyWhileDecodeIsQueued() throws Exception {
        loadConfig();
        var attachment = attachment(true);
        var release = blockDispatcher();
        try {
            assertTrue(promptly(() -> {
                assertNull(attachment.getMeshRenderer());
                assertNull(attachment.getAttachmentModel());
                assertNull(attachment.getModelTexture());
                assertTrue(attachment.requestedMeshLoad());
                assertSame(attachment.guiMeshSnapshot().pendingIdentity(), attachment.guiMeshSnapshot().pendingIdentity());
                attachment.invalidate();
                return true;
            }, release::countDown));
            assertNull(attachment.getMeshRenderer());
            assertFalse(attachment.requestedMeshLoad());
        } finally {
            attachment.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void reloadObservesFailureWithoutRetryingEveryRenderFrame() throws Exception {
        var attachment = attachment(true);
        var failed = CompletableFuture.<Void>failedFuture(new IllegalArgumentException("controlled decode failure"));
        set(attachment, "meshWarmUpTask", failed);
        assertNull(attachment.getMeshRenderer());
        assertThrows(CompletionException.class, () -> all(attachment.warmUpMeshForReload()).join());
        assertSame(failed, field(attachment, "meshWarmUpTask"));
        var replacement = attachment.deferredCopy();
        assertFalse(replacement.requestedMeshLoad());
        assertNotSame(field(attachment, "guiPendingIdentity"), field(replacement, "guiPendingIdentity"));
    }

    @Test
    void qualityPublishesGunAndMeshAttachmentTablesTogetherAndKeepsLegacyModels() throws Exception {
        loadConfig();
        var originalGuns = ClientIndexManager.GUN_DISPLAY;
        var originalAttachments = ClientIndexManager.ATTACHMENT_INDEX;
        var mesh = attachment(true);
        var legacy = attachment(false);
        set(mesh, "meshWarmUpTask", CompletableFuture.completedFuture(null));
        var oldTable = new HashMap<>(Map.of(id("mesh"), mesh, id("legacy"), legacy));
        try {
            ClientIndexManager.GUN_DISPLAY = new HashMap<>();
            ClientIndexManager.ATTACHMENT_INDEX = oldTable;
            var staged = ClientIndexManager.stageQualityReload();
            assertSame(oldTable, ClientIndexManager.ATTACHMENT_INDEX);
            assertFalse((boolean) field(mesh, "invalidated"));
            staged.publish();
            assertNotSame(oldTable, ClientIndexManager.ATTACHMENT_INDEX);
            assertSame(legacy, ClientIndexManager.ATTACHMENT_INDEX.get(id("legacy")));
            assertNotSame(mesh, ClientIndexManager.ATTACHMENT_INDEX.get(id("mesh")));
            assertTrue((boolean) field(mesh, "invalidated"));
            assertFalse((boolean) field(legacy, "invalidated"));
            assertNull(field(staged, "previousAttachments"));
            var release = blockDispatcher();
            try {
                staged.warmUp(() -> { }, () -> { });
                assertEquals(java.util.Set.of(id("mesh")), staged.requestedAttachments().keySet());
                assertFalse(staged.requestedAttachments().get(id("mesh")).warmUpMeshForReload().getFirst().isDone());
            } finally {
                ClientIndexManager.ATTACHMENT_INDEX.values().forEach(ClientAttachmentIndex::invalidate);
                release.countDown();
                drainDispatcher();
            }
        } finally {
            ClientIndexManager.GUN_DISPLAY = originalGuns;
            ClientIndexManager.ATTACHMENT_INDEX = originalAttachments;
        }
    }

    @Test
    void staleAttachmentTableRejectsBothPublications() throws Exception {
        var originalGuns = ClientIndexManager.GUN_DISPLAY;
        var originalAttachments = ClientIndexManager.ATTACHMENT_INDEX;
        try {
            var guns = new HashMap<Identifier, GunDisplayInstance>();
            ClientIndexManager.GUN_DISPLAY = guns;
            ClientIndexManager.ATTACHMENT_INDEX = new HashMap<>(Map.of(id("mesh"), attachment(true)));
            var staged = ClientIndexManager.stageQualityReload();
            var newer = new HashMap<>(ClientIndexManager.ATTACHMENT_INDEX);
            ClientIndexManager.ATTACHMENT_INDEX = newer;
            assertThrows(IllegalStateException.class, staged::publish);
            assertSame(guns, ClientIndexManager.GUN_DISPLAY);
            assertSame(newer, ClientIndexManager.ATTACHMENT_INDEX);
            assertFalse((boolean) field(newer.get(id("mesh")), "invalidated"));
        } finally {
            ClientIndexManager.GUN_DISPLAY = originalGuns;
            ClientIndexManager.ATTACHMENT_INDEX = originalAttachments;
        }
    }

    static ClientAttachmentIndex attachment(boolean mesh) throws Exception {
        var constructor = ClientAttachmentIndex.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var index = constructor.newInstance();
        var gson = new GsonBuilder().registerTypeAdapter(Identifier.class, new IdentifierSerializer()).create();
        var display = gson.fromJson(mesh ? """
                {"render_model":{"type":"gltf","location":"test:models/gltf/grip.glb"}}
                """ : "{}", AttachmentDisplay.class);
        display.init();
        set(index, "display", display);
        set(index, "displayId", id("attachment"));
        set(index, "viewsFov", new float[]{70});
        set(index, "views", new int[]{1});
        set(index, "sounds", Map.of());
        return index;
    }

    @Test
    void fullResourceClearCannotReviveAStagedQualityTable() throws Exception {
        var originalGuns = ClientIndexManager.GUN_DISPLAY;
        var originalAttachments = ClientIndexManager.ATTACHMENT_INDEX;
        var guns = new HashMap<>(ClientIndexManager.GUN_INDEX);
        var ammo = new HashMap<>(ClientIndexManager.AMMO_INDEX);
        var blocks = new HashMap<>(ClientIndexManager.BLOCK_INDEX);
        var old = attachment(true);
        try {
            ClientIndexManager.GUN_DISPLAY = new HashMap<>();
            ClientIndexManager.ATTACHMENT_INDEX = new HashMap<>(Map.of(id("old"), old));
            var staged = ClientIndexManager.stageQualityReload();
            ClientIndexManager.clear();
            assertThrows(IllegalStateException.class, staged::publish);
            assertTrue(ClientIndexManager.GUN_DISPLAY.isEmpty());
            assertTrue(ClientIndexManager.ATTACHMENT_INDEX.isEmpty());
            assertTrue((boolean) field(old, "invalidated"));
        } finally {
            ClientIndexManager.GUN_DISPLAY = originalGuns;
            ClientIndexManager.ATTACHMENT_INDEX = originalAttachments;
            ClientIndexManager.GUN_INDEX.putAll(guns);
            ClientIndexManager.AMMO_INDEX.putAll(ammo);
            ClientIndexManager.BLOCK_INDEX.putAll(blocks);
        }
    }
}
