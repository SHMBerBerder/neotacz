package com.tacz.guns.client.model.gltf.loader;

import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Isolates the decoder's input-controlled allocations from the client JVM. */
public final class GltfDracoDecoder {
    // Deliberately narrower than general source capacity: the isolated worker keeps its 256 MiB heap.
    static final long MAX_DECODED_VERTICES = 250_000L;
    static final long MAX_DECODED_INDICES = 300_000L;
    private static final int INPUT_MAGIC = 0x44524931;
    private static final int OUTPUT_MAGIC = 0x44524f31;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String WORKER = "/META-INF/neotacz/draco-worker.jar";
    // One transient worker for the whole client, not one process per visible primitive.
    private static final Semaphore WORKER_SLOT = new Semaphore(1, true);

    public record AttributeSpec(int uniqueId, int componentType, int componentCount, boolean normalized) {
    }

    public record Request(ByteBuffer encoded, int vertexCount, int indexCount, int indexComponentType,
                          List<AttributeSpec> attributes) {
        public Request {
            encoded = Objects.requireNonNull(encoded, "encoded").slice().asReadOnlyBuffer();
            attributes = List.copyOf(attributes);
        }

        @Override
        public ByteBuffer encoded() {
            return encoded.asReadOnlyBuffer();
        }
    }

    public record DecodedPrimitive(ByteBuffer indices, List<ByteBuffer> attributes) {
        public DecodedPrimitive {
            indices = readOnly(indices);
            attributes = attributes.stream().map(GltfDracoDecoder::readOnly).toList();
        }

        @Override
        public ByteBuffer indices() {
            return readOnly(indices);
        }

        @Override
        public List<ByteBuffer> attributes() {
            return attributes.stream().map(GltfDracoDecoder::readOnly).toList();
        }
    }

    private GltfDracoDecoder() {
    }

    public static List<DecodedPrimitive> decodeBatch(List<Request> requests, BooleanSupplier cancelled)
            throws GltfLoadException {
        return decodeBatch(requests, cancelled, null, Path.of(System.getProperty("java.io.tmpdir")), TIMEOUT);
    }

    static List<DecodedPrimitive> decodeBatch(List<Request> requests, BooleanSupplier cancelled,
                                            Path workerJar, Path tempParent, Duration timeout)
            throws GltfLoadException {
        Objects.requireNonNull(cancelled, "cancelled");
        List<Request> batch = List.copyOf(requests);
        long expectedOutput = validate(batch);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(TIMEOUT) > 0) {
            throw new GltfLoadException("Invalid Draco timeout");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        check(cancelled, deadline);
        if (batch.isEmpty()) {
            return List.of();
        }
        boolean acquired = false;
        Process process = null;
        try {
            while (!(acquired = WORKER_SLOT.tryAcquire(100, TimeUnit.MILLISECONDS))) {
                check(cancelled, deadline);
            }
            check(cancelled, deadline);
            try (OwnedDirectory directory = OwnedDirectory.create(tempParent)) {
                Path jar = directory.path.resolve("worker.jar");
                if (workerJar == null) {
                    try (InputStream resource = GltfDracoDecoder.class.getResourceAsStream(WORKER)) {
                        if (resource == null) {
                            throw new GltfLoadException("The bundled Draco decoder worker is missing");
                        }
                        Files.copy(resource, jar);
                    }
                } else {
                    Files.copy(workerJar, jar);
                }
                Path input = directory.path.resolve("input.bin");
                Path output = directory.path.resolve("output.bin");
                writeInput(input, batch, cancelled, deadline);
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-Xmx256m", "-XX:MaxDirectMemorySize=16m", "-jar", jar.toString(),
                        input.toString(), output.toString());
                builder.directory(directory.path.toFile());
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
                    builder.environment().remove(variable);
                }
                check(cancelled, deadline);
                process = builder.start();
                try {
                    process.getOutputStream().close();
                    while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        check(cancelled, deadline);
                        if (Files.exists(output) && Files.size(output) > expectedOutput) {
                            throw new GltfLoadException("Draco worker output exceeds its declared budget");
                        }
                    }
                    check(cancelled, deadline);
                    if (process.exitValue() != 0) {
                        throw new GltfLoadException("Draco worker exited with status " + process.exitValue());
                    }
                    return readOutput(output, batch, expectedOutput, cancelled, deadline);
                } finally {
                    terminate(process);
                    process = null;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Draco decode interrupted");
        } catch (IOException exception) {
            if (exception instanceof GltfLoadException loadException) {
                throw loadException;
            }
            throw new GltfLoadException("Draco worker I/O failed", exception);
        } finally {
            terminate(process);
            if (acquired) {
                WORKER_SLOT.release();
            }
        }
    }

    private static long validate(List<Request> requests) throws GltfLoadException {
        require(requests.size() <= GltfAssetLimits.MAX_PRIMITIVES, "too many primitives");
        long vertices = 0, indices = 0, attributes = 0, components = 0, encoded = 0, decoded = 0, wire = 8;
        for (Request request : requests) {
            require(request.vertexCount > 0 && request.indexCount > 0 && request.indexCount % 3 == 0,
                    "invalid TRIANGLES counts");
            require(request.indexComponentType == 5121 || request.indexComponentType == 5123
                    || request.indexComponentType == 5125, "unsupported index component type");
            vertices += request.vertexCount;
            indices += request.indexCount;
            attributes += request.attributes.size();
            require(vertices <= MAX_DECODED_VERTICES && indices <= MAX_DECODED_INDICES,
                    "mesh count budget exceeded");
            require(!request.attributes.isEmpty() && attributes <= GltfAssetLimits.MAX_ACCESSORS,
                    "attribute count budget exceeded");
            encoded += request.encoded.remaining();
            require(request.encoded.hasRemaining()
                            && request.encoded.remaining() <= GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES
                            && encoded <= GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES,
                    "compressed byte budget exceeded");
            long indexBytes = (long) request.indexCount * componentBytes(request.indexComponentType);
            decoded += indexBytes;
            wire += 20 + indexBytes;
            HashSet<Integer> ids = new HashSet<>();
            for (AttributeSpec attribute : request.attributes) {
                require(attribute.uniqueId >= 0 && attribute.uniqueId <= 65535 && ids.add(attribute.uniqueId),
                        "duplicate or unsupported attribute unique ID");
                require(attribute.componentCount >= 1 && attribute.componentCount <= 4,
                        "unsupported attribute component count");
                int width = componentBytes(attribute.componentType);
                require(!attribute.normalized || (attribute.componentType != 5125 && attribute.componentType != 5126),
                        "invalid normalized component type");
                long count = (long) request.vertexCount * attribute.componentCount;
                components += count;
                long bytes = count * width;
                require(bytes <= GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES, "single decoded buffer budget exceeded");
                decoded += bytes;
                wire += 17 + bytes;
            }
            require(decoded <= GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES
                    && components <= GltfAssetLimits.MAX_ACCESSOR_COMPONENTS, "decoded byte/component budget exceeded");
        }
        return wire;
    }

    private static void writeInput(Path path, List<Request> requests, BooleanSupplier cancelled, long deadline)
            throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            output.writeInt(INPUT_MAGIC);
            output.writeInt(requests.size());
            byte[] chunk = new byte[65536];
            for (Request request : requests) {
                check(cancelled, deadline);
                output.writeInt(request.vertexCount);
                output.writeInt(request.indexCount);
                output.writeInt(request.indexComponentType);
                output.writeInt(request.attributes.size());
                for (AttributeSpec attribute : request.attributes) {
                    writeAttribute(output, attribute);
                }
                ByteBuffer encoded = request.encoded();
                output.writeInt(encoded.remaining());
                while (encoded.hasRemaining()) {
                    check(cancelled, deadline);
                    int count = Math.min(encoded.remaining(), chunk.length);
                    encoded.get(chunk, 0, count);
                    output.write(chunk, 0, count);
                }
            }
        }
    }

    private static List<DecodedPrimitive> readOutput(Path path, List<Request> requests, long expectedSize,
                                                    BooleanSupplier cancelled, long deadline) throws IOException {
        require(Files.size(path) == expectedSize, "worker output length mismatch");
        List<DecodedPrimitive> results = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            require(input.readInt() == OUTPUT_MAGIC && input.readInt() == requests.size(), "worker protocol mismatch");
            for (Request request : requests) {
                check(cancelled, deadline);
                require(input.readInt() == request.vertexCount && input.readInt() == request.indexCount
                        && input.readInt() == request.indexComponentType && input.readInt() == request.attributes.size(),
                        "worker decoded shape mismatch");
                ByteBuffer indices = readBytes(input, request.indexCount * componentBytes(request.indexComponentType),
                        cancelled, deadline);
                for (int i = 0; i < request.indexCount; i++) {
                    long index = switch (request.indexComponentType) {
                        case 5121 -> Byte.toUnsignedInt(indices.get(i));
                        case 5123 -> Short.toUnsignedInt(indices.getShort(i * 2));
                        default -> Integer.toUnsignedLong(indices.getInt(i * 4));
                    };
                    require(index < request.vertexCount, "decoded index is outside the vertex range");
                }
                List<ByteBuffer> attributes = new ArrayList<>();
                for (AttributeSpec expected : request.attributes) {
                    require(input.readInt() == expected.uniqueId && input.readInt() == expected.componentType
                            && input.readInt() == expected.componentCount && input.readBoolean() == expected.normalized,
                            "worker attribute shape mismatch");
                    ByteBuffer data = readBytes(input, request.vertexCount * expected.componentCount
                            * componentBytes(expected.componentType), cancelled, deadline);
                    if (expected.componentType == 5126) {
                        for (int i = 0; i < data.remaining(); i += 4) {
                            require(Float.isFinite(data.getFloat(i)), "decoded attribute contains non-finite values");
                        }
                    }
                    attributes.add(data);
                }
                results.add(new DecodedPrimitive(indices, attributes));
            }
            require(input.read() == -1, "worker output has trailing bytes");
        }
        check(cancelled, deadline);
        return List.copyOf(results);
    }

    private static ByteBuffer readBytes(DataInputStream input, int expected, BooleanSupplier cancelled, long deadline)
            throws IOException {
        require(input.readInt() == expected, "worker byte length mismatch");
        byte[] bytes = new byte[expected];
        for (int offset = 0; offset < expected;) {
            check(cancelled, deadline);
            int count = Math.min(65536, expected - offset);
            input.readFully(bytes, offset, count);
            offset += count;
        }
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void writeAttribute(DataOutputStream output, AttributeSpec attribute) throws IOException {
        output.writeInt(attribute.uniqueId);
        output.writeInt(attribute.componentType);
        output.writeInt(attribute.componentCount);
        output.writeBoolean(attribute.normalized);
    }

    private static int componentBytes(int componentType) throws GltfLoadException {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new GltfLoadException("Unsupported Draco accessor component type " + componentType);
        };
    }

    private static ByteBuffer readOnly(ByteBuffer buffer) {
        return buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void require(boolean condition, String message) throws GltfLoadException {
        if (!condition) {
            throw new GltfLoadException("Draco " + message);
        }
    }

    private static void check(BooleanSupplier cancelled, long deadline) throws GltfLoadException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Draco decode cancelled");
        }
        if (System.nanoTime() - deadline >= 0) {
            throw new GltfLoadException("Draco worker timed out");
        }
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        process.destroyForcibly();
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record OwnedDirectory(Path path) implements AutoCloseable {
        static OwnedDirectory create(Path parent) throws IOException {
            parent = parent.toAbsolutePath();
            Path path;
            if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                path = Files.createTempDirectory(parent, "neotacz-draco-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                path = Files.createTempDirectory(parent, "neotacz-draco-");
                try {
                    AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
                    if (acl == null) {
                        throw new IOException("The filesystem cannot create a private Draco directory");
                    }
                    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                            .setPrincipal(acl.getOwner()).setPermissions(AclEntryPermission.values())
                            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));
                } catch (IOException | RuntimeException exception) {
                    Files.deleteIfExists(path);
                    throw exception;
                }
            }
            return new OwnedDirectory(path);
        }

        @Override
        public void close() throws IOException {
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
