package com.tacz.guns.client.gameplay;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalPlayerSchedulerTest {
    @Test
    void bothGunWorkersCompleteTasksWithoutKeepingTheClientAlive() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Callable<Thread> work = () -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker release timed out");
            return Thread.currentThread();
        };
        var executor = LocalPlayerDataHolder.SCHEDULED_EXECUTOR_SERVICE;
        var first = executor.submit(work);
        var second = executor.submit(work);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        Thread firstWorker = first.get(5, TimeUnit.SECONDS);
        Thread secondWorker = second.get(5, TimeUnit.SECONDS);
        assertNotSame(firstWorker, secondWorker);
        for (Thread worker : new Thread[]{firstWorker, secondWorker}) {
            assertTrue(worker.isDaemon(), "idle gun workers must not retain a stopped client");
            assertTrue(worker.getName().startsWith("tacz-client-gun-"));
        }
    }
}
