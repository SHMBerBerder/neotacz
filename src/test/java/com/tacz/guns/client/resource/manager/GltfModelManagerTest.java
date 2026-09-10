package com.tacz.guns.client.resource.manager;

import com.tacz.guns.client.model.gltf.loader.GltfLoadException;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import com.tacz.guns.client.model.gltf.loader.ResourceManagerGltfResourceResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfModelManagerTest {
    @Test
    void qualityClearReportsDisposalFailureAfterRunningEveryHook() {
        GltfModelManager manager = new GltfModelManager();
        AtomicInteger disposed = new AtomicInteger();
        manager.addDisposalHook(() -> { throw new IllegalStateException("GPU cleanup"); });
        manager.addDisposalHook(disposed::incrementAndGet);
        long generation = manager.getGeneration();
        assertThrows(IllegalStateException.class, manager::clearCacheForQuality);
        assertEquals(generation + 1, manager.getGeneration());
        assertEquals(1, disposed.get());
        assertDoesNotThrow(manager::clearCache, "Ordinary resource reload keeps its existing tolerant contract");
        assertEquals(2, disposed.get());
    }

    private static final Identifier GOOD_MODEL = id("models/gltf/rifle/rifle.gltf");
    private static final Identifier GOOD_BUFFER = id("models/gltf/rifle/triangle.bin");
    private static final Identifier BAD_MODEL = id("models/gltf/broken/broken.gltf");
    @Test
    void resolvesOnlyResourcesInsideMainModelDirectory() {
        assertEquals(GOOD_BUFFER, GltfModelManager.resolveResourceId(GOOD_MODEL, "triangle.bin"));
        assertEquals(
                GltfModelManager.resolveResourceId(GOOD_MODEL, "triangle.bin"),
                ResourceManagerGltfResourceResolver.resolveResourceId(GOOD_MODEL, "triangle.bin")
        );
        assertEquals(
                id("models/gltf/rifle/textures/base.png"),
                GltfModelManager.resolveResourceId(GOOD_MODEL, "textures/material/../base.png")
        );

        for (String rejected : List.of(
                "../shared.bin",
                "/models/gltf/rifle/triangle.bin",
                "https://example.invalid/triangle.bin",
                "//other/triangle.bin",
                "triangle.bin?version=1",
                "triangle.bin#part",
                "textures\\base.png",
                "%2e%2e/shared.bin",
                "texture.tga",
                "texture.webp"
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GltfModelManager.resolveResourceId(GOOD_MODEL, rejected),
                    rejected
            );
        }
    }

    @Test
    void snapshotsBytesAndLoadsExternalReferencesLazily() throws Exception {
        byte[] sourceJson = minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8);
        byte[] sourceBuffer = createTriangleBuffer();
        Map<Identifier, byte[]> source = new HashMap<>();
        source.put(GOOD_MODEL, sourceJson);
        source.put(GOOD_BUFFER, sourceBuffer);

        GltfModelManager manager = managerWith(source);
        sourceJson[0] = '!';
        sourceBuffer[0] ^= 0x7f;

        NormalizedGltfModel first = manager.getModel(GOOD_MODEL);
        NormalizedGltfModel second = manager.getModel(GOOD_MODEL);

        assertSame(first, second);
        assertEquals(1, first.counts().meshes());
        assertEquals(44, first.model().getBufferModels().getFirst().getBufferData().capacity());
        assertEquals(2, manager.getResourceCount());
        assertEquals(1, manager.getGeneration());
    }

    @Test
    void returnedResourceBytesCannotMutateSnapshot() {
        byte[] expected = new byte[]{1, 2, 3};
        GltfModelManager manager = managerWith(Map.of(GOOD_BUFFER, expected));

        byte[] first = manager.getResourceBytes(GOOD_BUFFER).orElseThrow();
        first[0] = 99;

        assertArrayEquals(expected, manager.getResourceBytes(GOOD_BUFFER).orElseThrow());
    }

    @Test
    void oneBrokenMainModelDoesNotPoisonAnotherModel() throws Exception {
        GltfModelManager manager = managerWith(Map.of(
                GOOD_MODEL, minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8),
                GOOD_BUFFER, createTriangleBuffer(),
                BAD_MODEL, "{not valid gltf".getBytes(StandardCharsets.UTF_8)
        ));

        GltfLoadException firstFailure = assertThrows(GltfLoadException.class, () -> manager.getModel(BAD_MODEL));
        GltfLoadException secondFailure = assertThrows(GltfLoadException.class, () -> manager.getModel(BAD_MODEL));
        NormalizedGltfModel good = manager.getModel(GOOD_MODEL);

        assertSame(firstFailure, secondFailure);
        assertSame(firstFailure, manager.getFailure(BAD_MODEL).orElseThrow());
        assertEquals(1, good.counts().meshes());
        assertTrue(manager.getFailure(GOOD_MODEL).isEmpty());
    }

    @Test
    void reloadAndExplicitClearReplaceCacheAndRunDisposalHooks() throws Exception {
        GltfModelManager manager = managerWith(Map.of(
                GOOD_MODEL, minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8),
                GOOD_BUFFER, createTriangleBuffer()
        ));
        AtomicInteger disposals = new AtomicInteger();
        manager.addDisposalHook(disposals::incrementAndGet);
        NormalizedGltfModel beforeClear = manager.getModel(GOOD_MODEL);

        manager.clearCache();
        NormalizedGltfModel afterClear = manager.getModel(GOOD_MODEL);
        manager.apply(
                GltfModelManager.Snapshot.fromByteArrays(Map.of(
                        GOOD_MODEL, minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8),
                        GOOD_BUFFER, createTriangleBuffer()
                ), GltfModelManager.DEFAULT_LIMITS),
                null,
                null
        );

        assertNotSame(beforeClear, afterClear);
        assertEquals(2, disposals.get());
        assertEquals(3, manager.getGeneration());
    }

    @Test
    void descriptorCountLimitIsGlobalButByteLimitIsPerModelLoad() {
        GltfModelManager.Limits countLimits = new GltfModelManager.Limits(1, 1_024, 2_048);
        GltfModelManager.Snapshot countSnapshot = GltfModelManager.Snapshot.fromByteArrays(Map.of(
                GOOD_MODEL, minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8),
                GOOD_BUFFER, createTriangleBuffer()
        ), countLimits);
        GltfModelManager countManager = new GltfModelManager(countLimits, new com.tacz.guns.client.model.gltf.loader.JgltfModelLoader());
        countManager.apply(countSnapshot, null, null);

        assertEquals(0, countManager.getResourceCount());
        assertEquals(0, countManager.getSnapshotBytes());
        assertTrue(countManager.getManagerFailure().orElseThrow().getMessage().contains("resources, limit is 1"));
        assertTrue(countManager.getFailure(GOOD_MODEL).isPresent());
        assertThrows(GltfLoadException.class, () -> countManager.getModel(GOOD_MODEL));

        GltfModelManager.Limits totalLimits = new GltfModelManager.Limits(4, 8, 10);
        GltfModelManager.Snapshot totalSnapshot = GltfModelManager.Snapshot.fromByteArrays(Map.of(
                id("models/gltf/a/a.bin"), new byte[6],
                id("models/gltf/b/b.bin"), new byte[6]
        ), totalLimits);

        assertEquals(2, totalSnapshot.resourceCount());
        assertEquals(12, totalSnapshot.totalBytes());
        assertTrue(totalSnapshot.managerFailure() == null);
    }

    @Test
    void runtimeDescriptorsDoNotReadDuringCaptureOrRetainEncodedSnapshotBytes() throws Exception {
        AtomicInteger mainReads = new AtomicInteger(), bufferReads = new AtomicInteger();
        Map<Identifier, Resource> discovered = new HashMap<>(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), mainReads),
                GOOD_BUFFER, resource(createTriangleBuffer(), bufferReads),
                BAD_MODEL, new Resource(null, () -> { throw new AssertionError("Unrequested model was read"); })
        ));
        GltfModelManager manager = runtimeManager(discovered, GltfModelManager.DEFAULT_LIMITS);
        discovered.clear();
        assertEquals(0, mainReads.get());
        assertEquals(0, bufferReads.get());
        assertEquals(0, manager.getSnapshotBytes());
        assertEquals(3, manager.getResourceCount());
        NormalizedGltfModel first = manager.getModel(GOOD_MODEL);
        assertSame(first, manager.getModel(GOOD_MODEL));
        assertEquals(1, mainReads.get());
        assertEquals(1, bufferReads.get());
        assertEquals(0, manager.getSnapshotBytes());
    }

    @Test
    void uncachedModelsAndFailuresDoNotPopulateTheDiagnosticCache() throws Exception {
        AtomicInteger reads = new AtomicInteger(), failures = new AtomicInteger();
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, resource(minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8), reads),
                GOOD_BUFFER, resource(createTriangleBuffer(), new AtomicInteger()),
                BAD_MODEL, resource("{invalid".getBytes(StandardCharsets.UTF_8), failures)
        ), GltfModelManager.DEFAULT_LIMITS);
        assertNotSame(manager.getModelUncached(GOOD_MODEL), manager.getModelUncached(GOOD_MODEL));
        NormalizedGltfModel cached = manager.getModel(GOOD_MODEL);
        assertSame(cached, manager.getModel(GOOD_MODEL));
        assertEquals(3, reads.get());

        GltfLoadException first = assertThrows(GltfLoadException.class, () -> manager.getModelUncached(BAD_MODEL));
        assertNotSame(first, assertThrows(GltfLoadException.class, () -> manager.getModelUncached(BAD_MODEL)));
        assertTrue(manager.getFailure(BAD_MODEL).isEmpty());
        GltfLoadException cachedFailure = assertThrows(GltfLoadException.class, () -> manager.getModel(BAD_MODEL));
        assertSame(cachedFailure, assertThrows(GltfLoadException.class, () -> manager.getModel(BAD_MODEL)));
        assertEquals(3, failures.get());
    }

    @Test
    void repeatedCanonicalResourceReferencesReadAndChargeOnlyOncePerLoad() throws Exception {
        for (String alias : List.of("triangle.bin", "./triangle.bin", "folder/../triangle.bin")) {
            byte[] json = twoBufferGltfJson(alias);
            int uniqueBytes = json.length + createTriangleBuffer().length;
            AtomicInteger mainReads = new AtomicInteger(), bufferReads = new AtomicInteger();
            GltfModelManager manager = runtimeManager(Map.of(
                    GOOD_MODEL, resource(json, mainReads),
                    GOOD_BUFFER, resource(createTriangleBuffer(), bufferReads)
            ), new GltfModelManager.Limits(4, uniqueBytes, uniqueBytes));

            assertEquals(2, manager.getModelUncached(GOOD_MODEL).counts().buffers(), alias);
            assertEquals(1, bufferReads.get(), alias);
            assertEquals(2, manager.getModelUncached(GOOD_MODEL).counts().buffers(), alias);
            assertEquals(2, mainReads.get(), "A new uncached load must reopen its source");
            assertEquals(2, bufferReads.get(), "Reference sharing must not escape one load");
            assertEquals(0, manager.getSnapshotBytes());
        }
    }

    @Test
    void distinctResourceIdsWithIdenticalContentStillConsumeSeparateReadBudget() {
        byte[] json = twoBufferGltfJson("other.bin");
        int uniqueBytes = json.length + createTriangleBuffer().length;
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, resource(json, new AtomicInteger()),
                GOOD_BUFFER, resource(createTriangleBuffer(), new AtomicInteger()),
                id("models/gltf/rifle/other.bin"), resource(createTriangleBuffer(), new AtomicInteger())
        ), new GltfModelManager.Limits(4, uniqueBytes, uniqueBytes));
        assertThrows(GltfLoadException.class, () -> manager.getModelUncached(GOOD_MODEL));
        assertTrue(manager.getFailure(GOOD_MODEL).isEmpty());
    }

    private static byte[] twoBufferGltfJson(String secondUri) {
        return minimalGltfJson("triangle.bin").replace(
                "\"buffers\": [{\"uri\":\"triangle.bin\",\"byteLength\":44}]",
                "\"buffers\": [{\"uri\":\"triangle.bin\",\"byteLength\":44},"
                        + "{\"uri\":\"" + secondUri + "\",\"byteLength\":44}]"
        ).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void runtimeReadLimitsAreBoundedPerFileAndPerModelAndFailuresStayIsolated() throws Exception {
        byte[] json = minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8);
        int modelBytes = json.length + createTriangleBuffer().length;
        AtomicInteger closed = new AtomicInteger();
        Identifier secondModel = id("models/gltf/rifle/second.gltf");
        Map<Identifier, Resource> descriptors = Map.of(
                GOOD_MODEL, resource(json, new AtomicInteger()),
                secondModel, resource(json, new AtomicInteger()),
                GOOD_BUFFER, resource(createTriangleBuffer(), new AtomicInteger()),
                BAD_MODEL, new Resource(null, () -> new ByteArrayInputStream(new byte[modelBytes + 1]) {
                    @Override public void close() throws IOException { closed.incrementAndGet(); super.close(); }
                })
        );
        GltfModelManager bounded = runtimeManager(descriptors,
                new GltfModelManager.Limits(4, modelBytes, modelBytes));
        assertThrows(GltfLoadException.class, () -> bounded.getModel(BAD_MODEL));
        assertEquals(1, closed.get());
        assertEquals(1, bounded.getModel(GOOD_MODEL).counts().meshes());
        assertEquals(1, bounded.getModel(secondModel).counts().meshes());
        assertTrue(bounded.getManagerFailure().isEmpty());

        GltfModelManager tooSmall = runtimeManager(descriptors,
                new GltfModelManager.Limits(4, modelBytes, modelBytes - 1));
        assertThrows(GltfLoadException.class, () -> tooSmall.getModel(GOOD_MODEL));
        assertTrue(tooSmall.getFailure(GOOD_MODEL).isPresent());
        assertTrue(tooSmall.getManagerFailure().isEmpty());
        assertEquals(0, tooSmall.getSnapshotBytes());
        assertEquals(64L << 20, GltfModelManager.DEFAULT_LIMITS.resourceBytes());
        assertEquals(512L << 20, GltfModelManager.DEFAULT_LIMITS.totalBytes());
    }

    @Test
    void staleResultsAndClosedDescriptorFailuresCannotCrossReloadOrClearGenerations() throws Exception {
        for (boolean cached : List.of(false, true)) {
            for (boolean oldFailure : List.of(false, true)) {
                for (boolean clear : List.of(false, true)) staleLoadRace(cached, oldFailure, clear);
            }
        }
    }

    private static void staleLoadRace(boolean cached, boolean oldFailure, boolean clear) throws Exception {
        byte[] oldJson = minimalGltfJson("triangle.bin").getBytes(StandardCharsets.UTF_8);
        byte[] newJson = minimalGltfJson("triangle.bin")
                .replace("\"version\": \"2.0\"", "\"version\": \"2.0\", \"generator\": \"new\"")
                .getBytes(StandardCharsets.UTF_8);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger(), disposals = new AtomicInteger();
        Resource oldMain = new Resource(null, () -> {
            if (reads.incrementAndGet() != 1) return new ByteArrayInputStream(newJson);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for reload");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (oldFailure) throw new IOException("Old resource pack closed during reload");
            return new ByteArrayInputStream(oldJson);
        });
        GltfModelManager manager = runtimeManager(Map.of(
                GOOD_MODEL, oldMain,
                GOOD_BUFFER, resource(createTriangleBuffer(), new AtomicInteger())
        ), GltfModelManager.DEFAULT_LIMITS);
        manager.addDisposalHook(disposals::incrementAndGet);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> cached ? manager.getModel(GOOD_MODEL) : manager.getModelUncached(GOOD_MODEL));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            if (clear) manager.clearCache();
            else manager.apply(GltfModelManager.Snapshot.capture(Map.of(
                    GOOD_MODEL, resource(newJson, new AtomicInteger()),
                    GOOD_BUFFER, resource(createTriangleBuffer(), new AtomicInteger())
            ), GltfModelManager.DEFAULT_LIMITS), null, null);
            release.countDown();
            NormalizedGltfModel result = task.get(10, TimeUnit.SECONDS);
            assertEquals("new", result.gltf().getAsset().getGenerator());
            assertEquals(2, manager.getGeneration());
            assertEquals(1, disposals.get());
            assertTrue(manager.getFailure(GOOD_MODEL).isEmpty());
            if (cached) assertSame(result, manager.getModel(GOOD_MODEL));
            else assertNotSame(result, manager.getModel(GOOD_MODEL));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static GltfModelManager runtimeManager(Map<Identifier, Resource> resources, GltfModelManager.Limits limits) {
        GltfModelManager manager = new GltfModelManager(limits, new JgltfModelLoader());
        manager.apply(GltfModelManager.Snapshot.capture(resources, limits), null, null);
        return manager;
    }

    private static Resource resource(byte[] bytes, AtomicInteger reads) {
        return new Resource(null, () -> {
            reads.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        });
    }

    @Test
    void snapshotPerFileLimitStaysIsolatedToThatResource() {
        GltfModelManager.Limits limits = new GltfModelManager.Limits(4, 8, 10);
        GltfModelManager oversizedManager = new GltfModelManager(
                limits,
                new com.tacz.guns.client.model.gltf.loader.JgltfModelLoader()
        );
        oversizedManager.apply(
                GltfModelManager.Snapshot.fromByteArrays(Map.of(GOOD_MODEL, new byte[9]), limits),
                null,
                null
        );
        assertTrue(oversizedManager.getManagerFailure().isEmpty());
        assertTrue(oversizedManager.getFailure(GOOD_MODEL).isPresent());
        assertThrows(GltfLoadException.class, () -> oversizedManager.getModel(GOOD_MODEL));
    }

    private static GltfModelManager managerWith(Map<Identifier, byte[]> resources) {
        GltfModelManager manager = new GltfModelManager();
        manager.apply(
                GltfModelManager.Snapshot.fromByteArrays(resources, GltfModelManager.DEFAULT_LIMITS),
                null,
                null
        );
        return manager;
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
