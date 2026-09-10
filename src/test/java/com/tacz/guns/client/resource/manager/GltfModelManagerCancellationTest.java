package com.tacz.guns.client.resource.manager;

import com.tacz.guns.client.model.gltf.loader.GltfLoadException;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GltfModelManagerCancellationTest {
    private static final Identifier GOOD_MODEL = id("models/gltf/rifle/rifle.gltf");
    private static final Identifier GOOD_BUFFER = id("models/gltf/rifle/triangle.bin");

    @Test
    void staleGenerationCancellationDoesNotOpenRuntimeDescriptors() throws Exception {
        AtomicInteger mainReads = new AtomicInteger();
        AtomicInteger bufferReads = new AtomicInteger();
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), mainReads),
                GOOD_BUFFER, resource(createTriangleBuffer(), bufferReads)
        ));
        long callerGeneration = manager.getGeneration();
        manager.clearCache();

        assertThrows(CancellationException.class,
                () -> manager.getModelUncached(GOOD_MODEL, () -> manager.getGeneration() != callerGeneration));
        assertEquals(0, mainReads.get(), "A caller-retired generation must not open the source descriptor");
        assertEquals(0, bufferReads.get());
    }

    @Test
    void cancellationDuringMainReadDiscardsTheResultAndDoesNotResolveReferences() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger mainReads = new AtomicInteger();
        AtomicInteger bufferReads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Resource blockedMain = new Resource(null, () -> {
            mainReads.incrementAndGet();
            entered.countDown();
            await(release);
            return new ByteArrayInputStream(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8));
        });
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, blockedMain,
                GOOD_BUFFER, resource(createTriangleBuffer(), bufferReads)
        ));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var load = executor.submit(() -> manager.getModelUncached(GOOD_MODEL, cancelled::get));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            cancelled.set(true);
            release.countDown();

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> load.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, thrown.getCause());
            assertEquals(1, mainReads.get());
            assertEquals(0, bufferReads.get(), "Cancelled loads must stop before external resolver reads");
            assertTrue(manager.getFailure(GOOD_MODEL).isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void resolverReadFailureRemainsLoadFailureWhenGenerationIsCurrent() {
        AtomicInteger mainReads = new AtomicInteger();
        AtomicInteger bufferReads = new AtomicInteger();
        IOException resolverFailure = new IOException("controlled resolver read failure");
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), mainReads),
                GOOD_BUFFER, new Resource(null, () -> {
                    bufferReads.incrementAndGet();
                    throw resolverFailure;
                })
        ));

        GltfLoadException failure = assertThrows(GltfLoadException.class,
                () -> manager.getModelUncached(GOOD_MODEL, () -> false));
        assertTrue(hasCause(failure, resolverFailure));
        assertTrue(hasMessage(failure, "Failed to resolve " + GOOD_BUFFER));
        assertEquals(1, mainReads.get());
        assertEquals(1, bufferReads.get());
        assertTrue(manager.getFailure(GOOD_MODEL).isEmpty(), "Uncached failures must not populate the diagnostic cache");
    }

    @Test
    void resolverFailureAfterGenerationRetirementIsReportedAsCancellation() {
        AtomicInteger bufferReads = new AtomicInteger();
        GltfModelManager[] holder = new GltfModelManager[1];
        Resource retiringBuffer = new Resource(null, () -> {
            bufferReads.incrementAndGet();
            holder[0].clearCache();
            throw new IOException("old resource pack closed");
        });
        holder[0] = runtimeManager(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), new AtomicInteger()),
                GOOD_BUFFER, retiringBuffer
        ));

        assertThrows(CancellationException.class, () -> holder[0].getModelUncached(GOOD_MODEL, () -> false));
        assertEquals(1, bufferReads.get());
        assertEquals(2, holder[0].getGeneration());
        assertTrue(holder[0].getFailure(GOOD_MODEL).isEmpty());
    }

    @Test
    void cancellationBeforePublicationDiscardsSuccessfulUncachedModel() {
        AtomicInteger mainReads = new AtomicInteger();
        AtomicInteger bufferReads = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), mainReads),
                GOOD_BUFFER, new Resource(null, () -> {
                    bufferReads.incrementAndGet();
                    return new ByteArrayInputStream(createTriangleBuffer()) {
                        @Override public void close() throws IOException {
                            cancelled.set(true);
                            super.close();
                        }
                    };
                })
        ));

        assertThrows(CancellationException.class,
                () -> manager.getModelUncached(GOOD_MODEL, cancelled::get));
        assertEquals(1, mainReads.get());
        assertEquals(1, bufferReads.get());
        assertTrue(cancelled.get());
        assertTrue(manager.getFailure(GOOD_MODEL).isEmpty());
        assertDoesNotThrow(() -> {
            NormalizedGltfModel model = manager.getModelUncached(GOOD_MODEL, () -> false);
            assertEquals(1, model.counts().meshes());
        });
    }

    private static GltfModelManager runtimeManager(Map<Identifier, Resource> resources) {
        GltfModelManager manager = new GltfModelManager(GltfModelManager.DEFAULT_LIMITS, new JgltfModelLoader());
        manager.apply(GltfModelManager.Snapshot.capture(resources, GltfModelManager.DEFAULT_LIMITS), null, null);
        return manager;
    }

    private static Resource resource(byte[] bytes, AtomicInteger reads) {
        return new Resource(null, () -> {
            reads.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        });
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for cancellation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    private static boolean hasCause(Throwable root, Throwable expected) {
        for (Throwable cause = root; cause != null; cause = cause.getCause()) {
            if (cause == expected) return true;
        }
        return false;
    }

    private static boolean hasMessage(Throwable root, String text) {
        for (Throwable cause = root; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) return true;
        }
        return false;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("tacz", path);
    }

    private static String minimalGltfJson(String bufferUri) {
        return """
                {
                  "asset": {"version": "2.0"},
                  "buffers": [{"uri":"%s","byteLength":44}],
                  "bufferViews": [
                    {"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
                    {"buffer":0,"byteOffset":36,"byteLength":6,"target":34963}
                  ],
                  "accessors": [
                    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
                     "min":[0,0,0],"max":[1,1,0]},
                    {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}
                  ],
                  "meshes": [{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
                  "nodes": [{"mesh":0}],
                  "scenes": [{"nodes":[0]}],
                  "scene":0
                }
                """.formatted(bufferUri);
    }

    private static byte[] createTriangleBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : List.of(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        )) {
            buffer.putFloat(value);
        }
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 2);
        buffer.putShort((short) 0);
        return buffer.array();
    }
}
