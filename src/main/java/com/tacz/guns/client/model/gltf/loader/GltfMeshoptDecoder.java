package com.tacz.guns.client.model.gltf.loader;

import de.javagl.jgltf.impl.v2.BufferView;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Expands ratified EXT meshopt buffer views before the ordinary accessor reader runs. */
final class GltfMeshoptDecoder {
    private static final String EXTENSION = "EXT_meshopt_compression";

    private GltfMeshoptDecoder() {
    }

    static void decode(GltfDecodeContext context) throws GltfLoadException {
        var views = context.gltf().getBufferViews();
        if (views == null) {
            return;
        }
        int sourceBufferCount = context.gltf().getBuffers() == null ? 0 : context.gltf().getBuffers().size();
        for (int index = 0; index < views.size(); index++) {
            context.checkCancelled();
            BufferView view = views.get(index);
            if (view.getExtensions() == null || !view.getExtensions().containsKey(EXTENSION)) {
                continue;
            }
            Object value = view.getExtensions().get(EXTENSION);
            require(value instanceof Map<?, ?>, index, "extension must be an object");
            Parameters parameters = parameters(context, view, (Map<?, ?>) value, sourceBufferCount, index);
            ByteBuffer encoded = context.sourceBufferRange(parameters.buffer(), parameters.offset(), parameters.length());
            require(encoded.hasRemaining() && Byte.toUnsignedInt(encoded.get(encoded.position())) == parameters.header(),
                    index, "unsupported bitstream version for " + parameters.mode());
            context.reserveDecodedBytes(parameters.decodedLength());
            ByteBuffer decoded = decodeBuffer(context, parameters, encoded, index);
            context.checkCancelled();
            int buffer = context.appendDecodedBuffer(decoded);
            view.setBuffer(buffer);
            view.setByteOffset(0);
            view.setByteLength(parameters.decodedLength());
            context.removeExtension(view, EXTENSION);
        }
    }

    private static Parameters parameters(GltfDecodeContext context, BufferView view, Map<?, ?> extension,
                                         int sourceBufferCount, int index)
            throws GltfLoadException {
        int buffer = integer(extension, "buffer", false, 0, index);
        int offset = integer(extension, "byteOffset", true, 0, index);
        int length = integer(extension, "byteLength", false, 1, index);
        int stride = integer(extension, "byteStride", false, 2, index);
        int count = integer(extension, "count", false, 1, index);
        String mode = string(extension, "mode", null, index);
        String filter = string(extension, "filter", "NONE", index);
        require(stride <= 256, index, "byteStride exceeds 256");
        int header;
        switch (mode) {
            case "ATTRIBUTES" -> {
                require(stride % 4 == 0, index, "attribute byteStride must be divisible by four");
                header = 0xa0;
            }
            case "TRIANGLES", "INDICES" -> {
                require(stride == 2 || stride == 4, index, "index byteStride must be two or four");
                require(filter.equals("NONE"), index, "index streams cannot have a filter");
                require(!mode.equals("TRIANGLES") || count % 3 == 0, index, "triangle count must be divisible by three");
                header = mode.equals("TRIANGLES") ? 0xe1 : 0xd1;
            }
            default -> throw failure(index, "unsupported mode: " + mode);
        }
        switch (filter) {
            case "NONE" -> { }
            case "OCTAHEDRAL" -> require(stride == 4 || stride == 8, index, "octahedral byteStride must be four or eight");
            case "QUATERNION" -> require(stride == 8, index, "quaternion byteStride must be eight");
            case "EXPONENTIAL" -> require(stride % 4 == 0, index, "exponential byteStride must be divisible by four");
            default -> throw failure(index, "unsupported filter: " + filter);
        }
        long decodedLength = (long) count * stride;
        require(view.getByteLength() != null && decodedLength == view.getByteLength(), index,
                "parent byteLength must equal count times byteStride");
        require(view.getByteStride() == null || view.getByteStride() == stride, index,
                "parent and compressed byteStride disagree");
        var buffers = context.gltf().getBuffers();
        require(buffers != null && buffer < sourceBufferCount, index, "compressed source buffer is out of range");
        Integer parent = view.getBuffer();
        require(parent != null && parent >= 0 && parent < sourceBufferCount, index, "parent buffer is out of range");
        long parentOffset = view.getByteOffset() == null ? 0 : view.getByteOffset();
        Integer parentLength = buffers.get(parent).getByteLength();
        require(parentOffset >= 0 && parentLength != null && parentOffset + decodedLength <= parentLength, index,
                "parent buffer view exceeds its declared buffer");
        return new Parameters(buffer, offset, length, stride, count, (int) decodedLength, mode, filter, header);
    }

    private static ByteBuffer decodeBuffer(GltfDecodeContext context, Parameters parameters, ByteBuffer encoded, int index)
            throws GltfLoadException {
        ByteBuffer ownedEncoded = null;
        ByteBuffer nativeDecoded = null;
        try {
            context.checkCancelled();
            ByteBuffer source = encoded;
            if (!source.isDirect()) {
                ownedEncoded = MemoryUtil.memAlloc(source.remaining());
                ownedEncoded.put(source.duplicate()).flip();
                source = ownedEncoded;
            }
            nativeDecoded = MemoryUtil.memAlloc(parameters.decodedLength());
            context.checkCancelled();
            int status = switch (parameters.mode()) {
                case "ATTRIBUTES" -> MeshOptimizer.meshopt_decodeVertexBuffer(nativeDecoded,
                        parameters.count(), parameters.stride(), source);
                case "TRIANGLES" -> MeshOptimizer.meshopt_decodeIndexBuffer(nativeDecoded,
                        parameters.count(), parameters.stride(), source);
                case "INDICES" -> MeshOptimizer.meshopt_decodeIndexSequence(nativeDecoded,
                        parameters.count(), parameters.stride(), source);
                default -> throw new AssertionError("validated meshopt mode");
            };
            require(status == 0, index, "corrupt compressed stream (decoder status " + status + ")");
            context.checkCancelled();
            switch (parameters.filter()) {
                case "OCTAHEDRAL" -> MeshOptimizer.meshopt_decodeFilterOct(nativeDecoded, parameters.count(), parameters.stride());
                case "QUATERNION" -> MeshOptimizer.meshopt_decodeFilterQuat(nativeDecoded, parameters.count(), parameters.stride());
                case "EXPONENTIAL" -> MeshOptimizer.meshopt_decodeFilterExp(nativeDecoded, parameters.count(), parameters.stride());
                case "NONE" -> { }
                default -> throw new AssertionError("validated meshopt filter");
            }
            context.checkCancelled();
            // Only heap storage escapes; both native allocations are released on every path.
            return ByteBuffer.allocate(parameters.decodedLength()).order(ByteOrder.LITTLE_ENDIAN)
                    .put(nativeDecoded).flip();
        } catch (LinkageError exception) {
            throw new GltfLoadException("The native meshopt decoder is unavailable", exception);
        } finally {
            if (nativeDecoded != null) {
                MemoryUtil.memFree(nativeDecoded);
            }
            if (ownedEncoded != null) {
                MemoryUtil.memFree(ownedEncoded);
            }
        }
    }

    private static int integer(Map<?, ?> extension, String key, boolean optional, int minimum, int index)
            throws GltfLoadException {
        if (optional && !extension.containsKey(key)) {
            return 0;
        }
        Object value = extension.get(key);
        require(value instanceof Number, index, key + " must be an integer");
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number) && number == Math.rint(number) && number >= minimum
                && number <= Integer.MAX_VALUE, index, key + " is outside the supported integer range");
        return (int) number;
    }

    private static String string(Map<?, ?> extension, String key, String defaultValue, int index)
            throws GltfLoadException {
        if (defaultValue != null && !extension.containsKey(key)) {
            return defaultValue;
        }
        Object value = extension.get(key);
        require(value instanceof String, index, key + " must be a string");
        return (String) value;
    }

    private static void require(boolean condition, int index, String detail) throws GltfLoadException {
        if (!condition) {
            throw failure(index, detail);
        }
    }

    private static GltfLoadException failure(int index, String detail) {
        return new GltfLoadException("Invalid " + EXTENSION + " bufferView " + index + ": " + detail);
    }

    private record Parameters(int buffer, int offset, int length, int stride, int count, int decodedLength,
                              String mode, String filter, int header) {
    }
}
