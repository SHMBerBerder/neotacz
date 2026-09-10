package com.tacz.guns.client.model.gltf.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class GltfDracoDecoderTest {
    @TempDir
    Path temporary;

    @Test
    void decodesOfficialBoxByUniqueIdRatherThanAccessorOrAttributeOrder() throws Exception {
        var request = box();
        var decoded = GltfDracoDecoder.decodeBatch(List.of(request), () -> false).getFirst();
        assertEquals(72, decoded.indices().remaining());
        assertTrue(decoded.indices().isReadOnly());
        assertEquals(ByteOrder.LITTLE_ENDIAN, decoded.indices().order());
        ByteBuffer positions = decoded.attributes().getFirst();
        assertEquals(24 * 3 * 4, positions.remaining());
        boolean[] negative = new boolean[3];
        boolean[] positive = new boolean[3];
        for (int point = 0; point < 24; point++) {
            for (int axis = 0; axis < 3; axis++) {
                float value = positions.getFloat((point * 3 + axis) * 4);
                assertEquals(0.5f, Math.abs(value), 0.001f);
                negative[axis] |= value < 0;
                positive[axis] |= value > 0;
            }
        }
        assertArrayEquals(new boolean[]{true, true, true}, negative);
        assertArrayEquals(new boolean[]{true, true, true}, positive);
        for (int i = 0; i < 36; i++) {
            assertTrue(Short.toUnsignedInt(decoded.indices().getShort(i * 2)) < 24);
        }
        assertEquals(0, request.encoded().position(), "the caller's compressed slice is not consumed");
    }

    @Test
    void decodesOfficialSkinAndKeepsUnsignedJointIntegers() throws Exception {
        var request = new GltfDracoDecoder.Request(
                fixture("rigged-simple/RiggedSimple0.bin", 2328, 2191), 160, 564, 5123,
                List.of(attribute(0, 5123, 4), attribute(1, 5126, 3),
                        attribute(2, 5126, 3), attribute(3, 5126, 4)));
        var decoded = GltfDracoDecoder.decodeBatch(List.of(box(), request), () -> false);
        assertEquals(2, decoded.size(), "one batch preserves primitive order");
        ByteBuffer joints = decoded.get(1).attributes().getFirst();
        assertEquals(160 * 4 * 2, joints.remaining());
        boolean hasSecondJoint = false;
        for (int i = 0; i < 160 * 4; i++) {
            int joint = Short.toUnsignedInt(joints.getShort(i * 2));
            assertTrue(joint == 0 || joint == 1);
            hasSecondJoint |= joint == 1;
        }
        assertTrue(hasSecondJoint, "regression: the old decoder returned all-zero JOINTS_0");
        ByteBuffer weights = decoded.get(1).attributes().get(3);
        for (int point = 0; point < 160; point++) {
            float sum = 0;
            for (int axis = 0; axis < 4; axis++) {
                sum += weights.getFloat((point * 4 + axis) * 4);
            }
            assertEquals(1, sum, 0.005);
        }
    }

    @Test
    void rejectsTruncatedStreamAndDecodedShapeMismatch() throws Exception {
        var box = box();
        var truncated = new GltfDracoDecoder.Request(box.encoded().slice(0, 20), 24, 36, 5123,
                box.attributes());
        assertThrows(GltfLoadException.class, () -> GltfDracoDecoder.decodeBatch(List.of(truncated), () -> false));
        var wrongCount = new GltfDracoDecoder.Request(box.encoded(), 23, 36, 5123, box.attributes());
        assertThrows(GltfLoadException.class, () -> GltfDracoDecoder.decodeBatch(List.of(wrongCount), () -> false));
    }

    @Test
    void inputControlledDecoderAllocationCannotTakeDownTheParent() throws Exception {
        // Draco 2.2 sequential mesh: a tiny payload claims 2^28 faces before entropy decoding.
        // Openize allocates its internal index array before it can return a count for validation.
        byte[] allocationBomb = {'D', 'R', 'A', 'C', 'O', 2, 2, 1, 0, 0, 0,
                (byte) 128, (byte) 128, (byte) 128, (byte) 128, 1, 3, 0};
        var request = new GltfDracoDecoder.Request(ByteBuffer.wrap(allocationBomb), 3, 3, 5123,
                List.of(attribute(0, 5126, 3)));
        var error = assertThrows(GltfLoadException.class,
                () -> GltfDracoDecoder.decodeBatch(List.of(request), () -> false));
        assertTrue(error.getMessage().contains("worker exited"), error.getMessage());
        assertEquals(1, GltfDracoDecoder.decodeBatch(List.of(box()), () -> false).size());
    }

    @Test
    void rejectsDuplicateIdsAndOverBudgetBeforeCreatingAnyProcessFiles() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        Path nonexistentJar = temporary.resolve("not-created.jar");
        var duplicated = new GltfDracoDecoder.Request(ByteBuffer.wrap(new byte[]{0}), 3, 3, 5123,
                List.of(attribute(0, 5126, 3), attribute(0, 5126, 3)));
        assertThrows(GltfLoadException.class,
                () -> GltfDracoDecoder.decodeBatch(List.of(duplicated), () -> false,
                        nonexistentJar, work, Duration.ofSeconds(1)));
        var oversized = new GltfDracoDecoder.Request(ByteBuffer.wrap(new byte[]{0}), Integer.MAX_VALUE,
                3, 5123, List.of(attribute(0, 5126, 3)));
        assertThrows(GltfLoadException.class,
                () -> GltfDracoDecoder.decodeBatch(List.of(oversized), () -> false,
                        nonexistentJar, work, Duration.ofSeconds(1)));
        assertDirectoryEmpty(work);
    }

    @Test
    void cancelledBeforeStartDoesNotCreateFiles() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        assertThrows(CancellationException.class,
                () -> GltfDracoDecoder.decodeBatch(List.of(fakeRequest(0)), () -> true,
                        temporary.resolve("not-created.jar"), work, Duration.ofSeconds(1)));
        assertDirectoryEmpty(work);
    }

    @Test
    void timeoutKillsWorkerAndRemovesOwnedFiles() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        Path jar = fakeWorkerJar();
        AtomicLong pid = new AtomicLong();
        var error = assertThrows(GltfLoadException.class,
                () -> GltfDracoDecoder.decodeBatch(List.of(fakeRequest(1)), () -> {
                    observePid(work, pid);
                    return false;
                }, jar, work, Duration.ofSeconds(2)));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        assertTrue(pid.get() > 0, "the timeout must exercise a started child, not JVM startup alone");
        assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
        assertDirectoryEmpty(work);
    }

    @Test
    void malformedOutputAndFailedWorkerNeverPublishPartialResults() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        Path jar = fakeWorkerJar();
        for (int mode : new int[]{2, 3, 4, 6, 7, 8}) {
            assertThrows(GltfLoadException.class,
                    () -> GltfDracoDecoder.decodeBatch(List.of(fakeRequest(mode)), () -> false,
                            jar, work, Duration.ofSeconds(5)));
            assertDirectoryEmpty(work);
        }
    }

    @Test
    void keepsNormalizedIntegerBytesRawAcrossTheParentProtocol() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        var request = new GltfDracoDecoder.Request(ByteBuffer.wrap(new byte[]{0}), 3, 3, 5123,
                List.of(new GltfDracoDecoder.AttributeSpec(0, 5121, 4, true)));
        var result = GltfDracoDecoder.decodeBatch(List.of(request), () -> false,
                fakeWorkerJar(), work, Duration.ofSeconds(5)).getFirst();
        assertEquals(12, result.attributes().getFirst().remaining());
        assertEquals(128, Byte.toUnsignedInt(result.attributes().getFirst().get(0)));
        result.attributes().getFirst().position(5);
        assertEquals(0, result.attributes().getFirst().position(), "returned positions are independent");
        assertDirectoryEmpty(work);
    }

    @Test
    void cancellationStopsTheStartedWorkerAndQueuedCallsDoNotStartASecondWorker() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("work"));
        Path jar = fakeWorkerJar();
        AtomicBoolean cancelFirst = new AtomicBoolean();
        AtomicBoolean cancelSecond = new AtomicBoolean();
        AtomicLong pid = new AtomicLong();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                GltfDracoDecoder.decodeBatch(List.of(fakeRequest(1)), cancelFirst::get, jar, work,
                        Duration.ofSeconds(10));
            } catch (GltfLoadException exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<Void> second = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pid.get() == 0 && System.nanoTime() < deadline) {
                observePid(work, pid);
                Thread.sleep(10);
            }
            assertTrue(pid.get() > 0);
            second = CompletableFuture.runAsync(() -> {
                try {
                    GltfDracoDecoder.decodeBatch(List.of(fakeRequest(1)), cancelSecond::get, jar, work,
                            Duration.ofSeconds(10));
                } catch (GltfLoadException exception) {
                    throw new CompletionException(exception);
                }
            });
            Thread.sleep(200);
            try (var files = Files.list(work)) {
                assertEquals(1, files.count(), "the second request must wait before creating process files");
            }
            cancelSecond.set(true);
            CompletableFuture<Void> queued = second;
            assertInstanceOf(CancellationException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> queued.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
            cancelFirst.set(true);
            assertInstanceOf(CancellationException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> first.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
            assertDirectoryEmpty(work);
            assertEquals(1, GltfDracoDecoder.decodeBatch(List.of(fakeRequest(0)), () -> false,
                    jar, work, Duration.ofSeconds(5)).size(), "cancellation must release the global worker slot");
            assertDirectoryEmpty(work);
        } finally {
            cancelFirst.set(true);
            cancelSecond.set(true);
            first.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            if (second != null) {
                second.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }

    static GltfDracoDecoder.Request box() throws Exception {
        return new GltfDracoDecoder.Request(fixture("box/Box.bin", 0, 118), 24, 36, 5123,
                List.of(attribute(1, 5126, 3), attribute(0, 5126, 3)));
    }

    static GltfDracoDecoder.AttributeSpec attribute(int id, int type, int components) {
        return new GltfDracoDecoder.AttributeSpec(id, type, components, false);
    }

    static GltfDracoDecoder.Request fakeRequest(int mode) {
        return new GltfDracoDecoder.Request(ByteBuffer.wrap(new byte[]{(byte) mode}), 3, 3, 5123,
                List.of(attribute(0, 5126, 3)));
    }

    private static ByteBuffer fixture(String name, int offset, int length) throws Exception {
        try (InputStream input = GltfDracoDecoderTest.class.getResourceAsStream("/gltf/draco/" + name)) {
            assertNotNull(input, name);
            return ByteBuffer.wrap(input.readAllBytes()).slice(offset, length).asReadOnlyBuffer();
        }
    }

    private Path fakeWorkerJar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DracoTestWorker.class.getName());
        Path jar = temporary.resolve("worker.jar");
        String name = DracoTestWorker.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream input = DracoTestWorker.class.getResourceAsStream("/" + name)) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(name));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "owned process directory must be removed even on failure");
        }
    }

    private static void observePid(Path work, AtomicLong pid) {
        try (var files = Files.walk(work)) {
            Path marker = files.filter(path -> path.getFileName().toString().equals("started.pid"))
                    .findFirst().orElse(null);
            if (marker != null && Files.size(marker) > 0) {
                pid.set(Long.parseLong(Files.readString(marker)));
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
