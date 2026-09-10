package com.tacz.guns.client.model.gltf.loader;

import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;
import de.javagl.jgltf.impl.v2.Buffer;
import de.javagl.jgltf.impl.v2.BufferView;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.GlTFProperty;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.IO;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded, per-import compressed-buffer normalization; no global source or decoded cache. */
final class GltfDecodeContext {
    private final GlTF gltf;
    private final GltfAsset source;
    private final BooleanSupplier cancelled;
    private final List<ByteBuffer> buffers = new ArrayList<>();
    private long decodedBytes;

    GltfDecodeContext(GltfAsset source, BooleanSupplier cancelled) throws GltfLoadException {
        this.source = source;
        this.gltf = (GlTF) source.getGltf();
        this.cancelled = cancelled;
        checkCancelled();
        requireSize(gltf.getAccessors(), GltfAssetLimits.MAX_ACCESSORS, "accessors");
        requireSize(gltf.getBufferViews(), GltfAssetLimits.MAX_BUFFER_VIEWS, "buffer views");
        requireSize(gltf.getBuffers(), GltfAssetLimits.MAX_BUFFERS, "buffers");
        requireSize(gltf.getMeshes(), GltfAssetLimits.MAX_MESHES, "meshes");
        long components = 0;
        if (gltf.getAccessors() != null) for (var accessor : gltf.getAccessors()) {
            int count = accessor.getCount() == null ? 0 : accessor.getCount();
            int width = components(accessor.getType());
            if (count < 0 || (components += (long) count * width) > GltfAssetLimits.MAX_ACCESSOR_COMPONENTS) {
                throw new GltfLoadException("glTF accessor allocation budget exceeded before decompression");
            }
        }
        List<Buffer> declarations = gltf.getBuffers() == null ? List.of() : gltf.getBuffers();
        long total = 0;
        for (int i = 0; i < declarations.size(); i++) {
            checkCancelled();
            Buffer declaration = declarations.get(i);
            int length = declaration.getByteLength();
            if (length <= 0 || length > GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES
                    || (total += length) > GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES) {
                throw new GltfLoadException("glTF source buffer allocation budget exceeded");
            }
            String uri = declaration.getUri();
            ByteBuffer bytes;
            if (uri == null) {
                bytes = i == 0 ? source.getBinaryData() : null;
            } else if (IO.isDataUriString(uri)) {
                if (uri.length() > GltfResourcePolicy.DEFAULT_MAX_RESOURCE_BYTES) {
                    throw new GltfLoadException("glTF buffer data URI exceeds input budget");
                }
                try { bytes = ByteBuffer.wrap(IO.readDataUri(uri)); }
                catch (RuntimeException failure) { throw new GltfLoadException("Invalid buffer data URI", failure); }
            } else {
                bytes = source.getReferenceData(uri);
            }
            if (bytes != null) {
                if (bytes.remaining() < length || bytes.remaining() > GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES) {
                    throw new GltfLoadException("Resolved glTF buffer length does not match bounded declaration");
                }
                bytes = bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
                bytes.limit(length);
                bytes = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            }
            // An EXT fallback buffer may intentionally have no payload. It must lose every
            // reference during normalization, never be materialized as a declared-size zero array.
            buffers.add(bytes);
        }
    }

    GlTF gltf() { return gltf; }
    BooleanSupplier cancelled() { return cancelled; }
    void checkCancelled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("glTF compressed import retired");
        }
    }

    ByteBuffer sourceBufferRange(int index, long offset, long length) throws GltfLoadException {
        checkCancelled();
        if (index < 0 || index >= buffers.size() || offset < 0 || length <= 0) {
            throw new GltfLoadException("Invalid compressed glTF buffer range");
        }
        ByteBuffer data = buffers.get(index);
        if (data == null || offset > data.remaining() || length > data.remaining() - offset) {
            throw new GltfLoadException("Compressed glTF buffer range is unresolved or truncated");
        }
        return data.slice((int) offset, (int) length).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    ByteBuffer sourceBufferView(int index) throws GltfLoadException {
        BufferView view = at(gltf.getBufferViews(), index, "bufferView");
        return sourceBufferRange(view.getBuffer(), view.getByteOffset() == null ? 0 : view.getByteOffset(),
                view.getByteLength());
    }

    void reserveDecodedBytes(long bytes) throws GltfLoadException {
        checkCancelled();
        if (bytes <= 0 || bytes > GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES
                || bytes > GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES - decodedBytes) {
            throw new GltfLoadException("glTF decoded buffer allocation budget exceeded");
        }
    }

    int appendDecodedBuffer(ByteBuffer data) throws GltfLoadException {
        reserveDecodedBytes(data.remaining());
        int index = buffers.size();
        decodedBytes += data.remaining();
        buffers.add(data.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
        return index;
    }

    static void removeExtension(GlTFProperty property, String extension) {
        if (property.getExtensions() == null || !property.getExtensions().containsKey(extension)) return;
        Map<String, Object> remaining = new HashMap<>(property.getExtensions());
        remaining.remove(extension);
        property.setExtensions(remaining.isEmpty() ? null : remaining);
    }

    /** Retire consumed compression payloads, including ranges sharing a GLB BIN with live data. */
    GltfAsset finish() throws GltfLoadException {
        checkCancelled();
        List<BufferView> views = gltf.getBufferViews() == null ? List.of() : gltf.getBufferViews();
        boolean[] used = new boolean[views.size()];
        if (gltf.getAccessors() != null) for (var accessor : gltf.getAccessors()) {
            markView(views, used, accessor.getBufferView());
            if (accessor.getSparse() != null) {
                markView(views, used, accessor.getSparse().getIndices().getBufferView());
                markView(views, used, accessor.getSparse().getValues().getBufferView());
            }
        }
        if (gltf.getImages() != null) for (var image : gltf.getImages()) markView(views, used, image.getBufferView());
        int[] remap = new int[views.size()];
        List<BufferView> liveViews = new ArrayList<>();
        List<ByteBuffer> ranges = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < views.size(); i++) if (used[i]) {
            BufferView view = views.get(i);
            ByteBuffer range = sourceBufferView(i);
            total += (range.remaining() + 3L) & ~3L;
            if (total > GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES) throw new GltfLoadException("Normalized buffer byte budget exceeded");
            remap[i] = liveViews.size(); liveViews.add(view); ranges.add(range);
        }
        if (gltf.getAccessors() != null) for (var accessor : gltf.getAccessors()) {
            if (accessor.getBufferView() != null) accessor.setBufferView(remap[accessor.getBufferView()]);
            if (accessor.getSparse() != null) {
                var sparse = accessor.getSparse();
                sparse.getIndices().setBufferView(remap[sparse.getIndices().getBufferView()]);
                sparse.getValues().setBufferView(remap[sparse.getValues().getBufferView()]);
            }
        }
        if (gltf.getImages() != null) for (var image : gltf.getImages()) {
            if (image.getBufferView() != null) image.setBufferView(remap[image.getBufferView()]);
        }
        List<ByteBuffer> payloads = new ArrayList<>();
        int start = 0;
        while (start < liveViews.size()) {
            int end = start;
            int size = 0;
            while (end < ranges.size()) {
                int aligned = (ranges.get(end).remaining() + 3) & ~3;
                if ((long) size + aligned > GltfAssetLimits.MAX_SINGLE_BUFFER_BYTES) break;
                size += aligned; end++;
            }
            ByteBuffer data = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = start; i < end; i++) {
                BufferView view = liveViews.get(i);
                view.setBuffer(payloads.size()); view.setByteOffset(data.position());
                data.put(ranges.get(i).duplicate());
                data.position((data.position() + 3) & ~3);
            }
            data.flip(); payloads.add(data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
            start = end; checkCancelled();
        }
        if (payloads.size() > GltfAssetLimits.MAX_BUFFERS) throw new GltfLoadException("Normalized buffer count exceeded");
        List<Buffer> declarations = new ArrayList<>();
        Map<String, ByteBuffer> resources = new HashMap<>(source.getReferenceDatas());
        for (int i = 0; i < payloads.size(); i++) {
            ByteBuffer data = payloads.get(i);
            String uri = "__neotacz_decoded_" + i + ".bin";
            while (resources.containsKey(uri)) uri = "_" + uri;
            Buffer buffer = new Buffer(); buffer.setUri(uri); buffer.setByteLength(data.remaining());
            declarations.add(buffer); resources.put(uri, data);
        }
        gltf.setBufferViews(liveViews.isEmpty() ? null : liveViews);
        gltf.setBuffers(declarations.isEmpty() ? null : declarations);
        GltfAssetV2 normalized = new GltfAssetV2(gltf, null);
        for (var reference : normalized.getReferences()) {
            ByteBuffer data = resources.get(reference.getUri());
            if (data == null) throw new GltfLoadException("Normalized resource missing: " + reference.getUri());
            reference.getTarget().accept(data);
        }
        buffers.clear();
        return normalized;
    }

    private static void markView(List<BufferView> views, boolean[] used, Integer index) throws GltfLoadException {
        if (index != null) {
            at(views, index, "bufferView");
            used[index] = true;
        }
    }

    static <T> T at(List<T> values, int index, String role) throws GltfLoadException {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            throw new GltfLoadException(role + " index out of range: " + index);
        }
        return values.get(index);
    }

    static int components(String type) throws GltfLoadException {
        if (type == null) throw new GltfLoadException("Missing accessor type");
        return switch (type) {
            case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9; case "MAT4" -> 16;
            default -> throw new GltfLoadException("Unknown accessor type: " + type);
        };
    }

    private static void requireSize(List<?> values, int maximum, String role) throws GltfLoadException {
        if (values != null && values.size() > maximum) throw new GltfLoadException("glTF " + role + " budget exceeded");
    }
}
