package com.tacz.guns.client.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VideoQualityReloadTest {
    @Test
    void doesNotStartBeforeOverlayCanBeInstalled() {
        AtomicInteger starts = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> {
            starts.incrementAndGet();
            return List.of();
        }, () -> true);
        assertEquals(0, starts.get());
        assertEquals(0, reload.getActualProgress());
        assertFalse(reload.isDone());
        reload.tick();
        assertEquals(1, starts.get());
        reload.tick();
        reload.tick();
        assertEquals(1, starts.get());
        assertTrue(reload.isDone());
        assertEquals(1, reload.getActualProgress());
    }

    @Test
    void waitsForRealPreparationAndEachGpuBatchWithoutBlocking() {
        var model = new CompletableFuture<Void>();
        var animation = new CompletableFuture<Void>();
        AtomicInteger uploads = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> List.of(new VideoQualityReload.Target(
                List.of(model, animation), () -> List.of(uploads::incrementAndGet, uploads::incrementAndGet))), () -> true);
        reload.tick();
        for (int i = 0; i < 20; i++) reload.tick();
        assertFalse(reload.isDone());
        assertEquals(0, reload.getActualProgress());
        model.complete(null);
        float partial = reload.getActualProgress();
        assertTrue(partial > 0 && partial < 1);
        reload.tick();
        assertEquals(0, uploads.get());
        animation.complete(null);
        reload.tick();
        assertEquals(1, uploads.get(), "At most one GPU batch per tick");
        assertFalse(reload.isDone());
        assertTrue(reload.getActualProgress() > partial && reload.getActualProgress() < 1);
        reload.tick();
        assertEquals(2, uploads.get());
        assertTrue(reload.isDone());
        assertDoesNotThrow(reload::checkExceptions);
        assertEquals(1, reload.getActualProgress());
    }

    @Test
    void preparationFailureDoesNotUploadOrReportSuccess() {
        AtomicInteger uploads = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> List.of(new VideoQualityReload.Target(
                List.of(CompletableFuture.failedFuture(new IllegalArgumentException("bad asset"))),
                () -> List.of(uploads::incrementAndGet))), () -> true);
        reload.tick();
        reload.tick();
        assertTrue(reload.isDone());
        assertTrue(reload.done().isCompletedExceptionally());
        assertThrows(CompletionException.class, reload::checkExceptions);
        assertEquals(0, uploads.get());
        assertTrue(reload.getActualProgress() < 1);
    }

    @Test
    void gpuFailureAndStagingFailureBothTerminate() {
        VideoQualityReload gpu = new VideoQualityReload(() -> List.of(new VideoQualityReload.Target(
                List.of(CompletableFuture.completedFuture(null)),
                () -> List.of(() -> { throw new IllegalStateException("GPU budget"); }))), () -> true);
        gpu.tick();
        gpu.tick();
        assertTrue(gpu.done().isCompletedExceptionally());
        assertThrows(CompletionException.class, gpu::checkExceptions);
        assertTrue(gpu.getActualProgress() < 1);

        VideoQualityReload staging = new VideoQualityReload(() -> { throw new IllegalArgumentException("metadata"); }, () -> true);
        staging.tick();
        assertTrue(staging.done().isCompletedExceptionally());
        assertThrows(CompletionException.class, staging::checkExceptions);
    }

    @Test
    void staleGenerationCannotUploadOrCommitSuccess() {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger uploads = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> List.of(new VideoQualityReload.Target(
                List.of(CompletableFuture.completedFuture(null)),
                () -> List.of(uploads::incrementAndGet))), current::get);
        reload.tick();
        current.set(false);
        reload.tick();
        assertTrue(reload.done().isCompletedExceptionally());
        assertEquals(0, uploads.get());
    }

    @Test
    void cancellationDoesNotCancelSharedModelPreparation() {
        var model = new CompletableFuture<Void>();
        AtomicInteger uploads = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> List.of(new VideoQualityReload.Target(
                List.of(model), () -> List.of(uploads::incrementAndGet))), () -> true);
        reload.tick();
        reload.cancel();
        model.complete(null);
        reload.tick();
        assertTrue(reload.done().isCompletedExceptionally());
        assertFalse(model.isCancelled());
        assertEquals(0, uploads.get());
    }

    @Test
    void allTargetsMustFinishAndLaterReadyTargetCanMakeProgress() {
        var pending = new CompletableFuture<Void>();
        AtomicInteger uploads = new AtomicInteger();
        VideoQualityReload reload = new VideoQualityReload(() -> List.of(
                new VideoQualityReload.Target(List.of(pending), () -> List.of(uploads::incrementAndGet)),
                new VideoQualityReload.Target(List.of(CompletableFuture.completedFuture(null)),
                        () -> List.of(uploads::incrementAndGet))), () -> true);
        reload.tick();
        reload.tick();
        assertEquals(1, uploads.get());
        assertFalse(reload.isDone());
        pending.complete(null);
        reload.tick();
        assertTrue(reload.isDone());
        assertEquals(2, uploads.get());
    }
}
