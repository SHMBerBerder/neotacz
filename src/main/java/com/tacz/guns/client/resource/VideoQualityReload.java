package com.tacz.guns.client.resource;

import net.minecraft.server.packs.resources.ReloadInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Actual preparation and upload work consumed by Minecraft's resource-loading overlay. */
final class VideoQualityReload implements ReloadInstance {
    record Target(List<CompletableFuture<Void>> preparation, Supplier<List<Runnable>> uploads) {
        Target {
            preparation = List.copyOf(preparation);
            Objects.requireNonNull(uploads);
        }
    }

    private final Supplier<List<Target>> begin;
    private final BooleanSupplier current;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private List<PendingTarget> targets;

    VideoQualityReload(Supplier<List<Target>> begin, BooleanSupplier current) {
        this.begin = Objects.requireNonNull(begin);
        this.current = Objects.requireNonNull(current);
    }

    /** Called on the render thread by the native overlay, never from a preparation executor. */
    void tick() {
        if (completion.isDone()) return;
        try {
            if (!current.getAsBoolean()) {
                cancel();
                return;
            }
            if (targets == null) {
                // Install the input-blocking screen and overlay before even beginning the transaction.
                targets = new ArrayList<>();
                for (Target target : begin.get()) targets.add(new PendingTarget(target));
                return;
            }
            for (PendingTarget target : targets) {
                if (target.finished || !target.prepared.isDone()) continue;
                // Only join a completed future: pending parsing must not block drawing the progress bar.
                target.prepared.join();
                if (target.uploads == null) target.uploads = List.copyOf(target.target.uploads().get());
                if (target.uploaded < target.uploads.size()) {
                    target.uploads.get(target.uploaded).run();
                    if (!current.getAsBoolean()) {
                        cancel();
                        return;
                    }
                    target.uploaded++;
                }
                target.finished = target.uploaded == target.uploads.size();
                // One material batch per tick; a native decode within that batch is not preemptible.
                break;
            }
            if (targets.stream().allMatch(target -> target.finished)) completion.complete(null);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    void cancel() {
        // Preparation futures belong to shared display instances; abandoning this UI must not cancel them.
        completion.completeExceptionally(new CancellationException("Video quality reload superseded"));
    }

    @Override
    public CompletableFuture<Void> done() {
        return completion;
    }

    @Override
    public float getActualProgress() {
        if (targets == null) return 0;
        if (completion.isDone() && !completion.isCompletedExceptionally()) return 1;
        if (targets.isEmpty()) return 0;
        double progress = 0;
        for (PendingTarget target : targets) {
            if (target.finished) {
                progress++;
                continue;
            }
            var preparation = target.target.preparation();
            long prepared = preparation.stream().filter(task -> task.isDone() && !task.isCompletedExceptionally()).count();
            // Fixed phase weights, like vanilla's task-weighted reload, not an estimate of time remaining.
            progress += 0.75 * (preparation.isEmpty() ? 1 : (double) prepared / preparation.size());
            if (target.uploads != null && !target.uploads.isEmpty()) {
                progress += 0.25 * target.uploaded / target.uploads.size();
            }
        }
        return (float) (progress / targets.size());
    }

    @Override
    public boolean isDone() {
        return completion.isDone();
    }

    @Override
    public void checkExceptions() {
        if (completion.isCompletedExceptionally()) completion.join();
    }

    private static final class PendingTarget {
        private final Target target;
        private final CompletableFuture<Void> prepared;
        private List<Runnable> uploads;
        private int uploaded;
        private boolean finished;

        private PendingTarget(Target target) {
            this.target = target;
            this.prepared = CompletableFuture.allOf(target.preparation().toArray(CompletableFuture[]::new));
        }
    }
}
