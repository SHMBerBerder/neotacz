package com.tacz.guns.client.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.tacz.guns.client.resource.QualityReloadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentQualityOverlayTest {
    @Test
    void failedAttachmentPreparationFailsTheRealVideoReloadTarget() throws Exception {
        var attachment = AttachmentMeshLifecycleTest.attachment(true);
        set(attachment, "meshWarmUpTask", CompletableFuture.failedFuture(new IllegalArgumentException("bad mesh")));
        var target = ClientVideoSettings.attachmentTarget(id("bad_attachment"), attachment);
        var reload = new VideoQualityReload(() -> List.of(target), () -> true);
        reload.tick();
        assertFalse(reload.isDone());
        reload.tick();
        assertTrue(reload.done().isCompletedExceptionally());
        assertThrows(RuntimeException.class, reload::checkExceptions);
    }

    @Test
    void invalidatedQueuedAttachmentDoesNotCompleteTheOverlaySuccessfully() throws Exception {
        loadConfig();
        var attachment = AttachmentMeshLifecycleTest.attachment(true);
        var release = blockDispatcher();
        try {
            var target = ClientVideoSettings.attachmentTarget(id("queued_attachment"), attachment);
            var reload = new VideoQualityReload(() -> List.of(target), () -> true);
            reload.tick();
            reload.tick();
            assertFalse(reload.isDone());
            assertEquals(0, reload.getActualProgress());
            attachment.invalidate();
            reload.tick();
            assertTrue(reload.done().isCompletedExceptionally());
            assertNull(attachment.getMeshRenderer());
        } finally {
            attachment.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }
}
