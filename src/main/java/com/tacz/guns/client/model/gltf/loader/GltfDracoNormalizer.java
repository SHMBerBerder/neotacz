package com.tacz.guns.client.model.gltf.loader;

import com.tacz.guns.client.model.gltf.convert.GltfAssetLimits;
import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.BufferView;
import de.javagl.jgltf.impl.v2.MeshPrimitive;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps Draco unique attribute IDs to existing accessor IDs without touching rig or node indices. */
final class GltfDracoNormalizer {
    private GltfDracoNormalizer() { }

    static void decode(GltfDecodeContext context) throws GltfLoadException {
        if (context.gltf().getMeshes() == null) return;
        List<Pending> pending = new ArrayList<>();
        List<GltfDracoDecoder.Request> requests = new ArrayList<>();
        Set<Integer> writtenAccessors = new HashSet<>();
        long vertices = 0, indices = 0, decoded = 0;
        for (var mesh : context.gltf().getMeshes()) for (var primitive : mesh.getPrimitives()) {
            if (primitive.getExtensions() == null
                    || !primitive.getExtensions().containsKey(GltfCompressionNormalizer.DRACO)) continue;
            context.checkCancelled();
            if (primitive.getMode() != null && primitive.getMode() != 4) {
                throw new GltfLoadException("Draco render primitive must use TRIANGLES");
            }
            var extension = GltfCompressionNormalizer.object(
                    primitive.getExtensions().get(GltfCompressionNormalizer.DRACO), "Draco extension");
            int view = GltfCompressionNormalizer.integer(extension.get("bufferView"), "Draco bufferView");
            var mapping = GltfCompressionNormalizer.object(extension.get("attributes"), "Draco attributes");
            if (primitive.getAttributes() == null || !mapping.containsKey("POSITION")) {
                throw new GltfLoadException("Draco primitive requires a POSITION attribute");
            }
            if (primitive.getIndices() == null) throw new GltfLoadException("Draco triangle primitive requires indices");
            Accessor indexAccessor = accessor(context, primitive.getIndices(), writtenAccessors);
            if (!"SCALAR".equals(indexAccessor.getType()) || Boolean.TRUE.equals(indexAccessor.isNormalized())
                    || !(indexAccessor.getComponentType() == 5121 || indexAccessor.getComponentType() == 5123
                    || indexAccessor.getComponentType() == 5125)) {
                throw new GltfLoadException("Invalid Draco index accessor type");
            }
            List<Accessor> accessors = new ArrayList<>();
            List<GltfDracoDecoder.AttributeSpec> specs = new ArrayList<>();
            int vertexCount = -1;
            for (var entry : mapping.entrySet()) {
                if (!(entry.getKey() instanceof String semantic) || !primitive.getAttributes().containsKey(semantic)) {
                    throw new GltfLoadException("Draco semantic has no corresponding accessor");
                }
                int uniqueId = GltfCompressionNormalizer.integer(entry.getValue(), "Draco unique attribute ID");
                Accessor accessor = accessor(context, primitive.getAttributes().get(semantic), writtenAccessors);
                int count = accessor.getCount();
                if (vertexCount >= 0 && count != vertexCount) throw new GltfLoadException("Draco attribute counts disagree");
                vertexCount = count;
                int components = GltfDecodeContext.components(accessor.getType());
                specs.add(new GltfDracoDecoder.AttributeSpec(uniqueId, accessor.getComponentType(), components,
                        Boolean.TRUE.equals(accessor.isNormalized())));
                accessors.add(accessor);
                decoded += (long) count * alignedVertexStride(components * bytesPerComponent(accessor.getComponentType()));
            }
            if (mapping.isEmpty() || vertexCount <= 0) throw new GltfLoadException("Empty Draco primitive");
            vertices += vertexCount; indices += indexAccessor.getCount();
            decoded += (long) indexAccessor.getCount() * bytesPerComponent(indexAccessor.getComponentType());
            if (vertices > GltfDracoDecoder.MAX_DECODED_VERTICES || indices > GltfDracoDecoder.MAX_DECODED_INDICES
                    || pending.size() >= GltfAssetLimits.MAX_PRIMITIVES
                    || decoded > GltfAssetLimits.MAX_TOTAL_BUFFER_BYTES) {
                throw new GltfLoadException("Draco model decoded geometry budget exceeded");
            }
            requests.add(new GltfDracoDecoder.Request(context.sourceBufferView(view), vertexCount,
                    indexAccessor.getCount(), indexAccessor.getComponentType(), specs));
            pending.add(new Pending(primitive, indexAccessor, accessors));
        }
        List<GltfDracoDecoder.DecodedPrimitive> result = GltfDracoDecoder.decodeBatch(requests, context.cancelled());
        for (int i = 0; i < pending.size(); i++) {
            context.checkCancelled();
            Pending item = pending.get(i);
            bind(context, item.indices(), result.get(i).indices(), false);
            var attributes = result.get(i).attributes();
            for (int j = 0; j < attributes.size(); j++) bind(context, item.attributes().get(j), attributes.get(j), true);
            GltfDecodeContext.removeExtension(item.primitive(), GltfCompressionNormalizer.DRACO);
        }
    }

    private static Accessor accessor(GltfDecodeContext context, int index, Set<Integer> written) throws GltfLoadException {
        Accessor accessor = GltfDecodeContext.at(context.gltf().getAccessors(), index, "Draco accessor");
        if (!written.add(index)) throw new GltfLoadException("Multiple Draco attributes/primitives write the same accessor");
        if (accessor.getSparse() != null) throw new GltfLoadException("Sparse Draco accessors are not supported");
        return accessor;
    }

    private static void bind(GltfDecodeContext context, Accessor accessor, ByteBuffer data, boolean vertexAttribute)
            throws GltfLoadException {
        int packedStride = GltfDecodeContext.components(accessor.getType()) * bytesPerComponent(accessor.getComponentType());
        int stride = vertexAttribute ? alignedVertexStride(packedStride) : packedStride;
        if (stride != packedStride) {
            // Draco returns packed integer vectors. Internal core bufferViews still need glTF's
            // four-byte vertex alignment; add padding, never change the decoded component values.
            int bytes = Math.multiplyExact(accessor.getCount(), stride);
            context.reserveDecodedBytes(bytes);
            ByteBuffer padded = ByteBuffer.allocate(bytes);
            ByteBuffer source = data.duplicate();
            byte[] element = new byte[packedStride];
            for (int vertex = 0; vertex < accessor.getCount(); vertex++) {
                if ((vertex & 1023) == 0) context.checkCancelled();
                source.get(element);
                padded.position(vertex * stride).put(element);
            }
            data = padded.clear();
        }
        int buffer = context.appendDecodedBuffer(data);
        BufferView view = new BufferView(); view.setBuffer(buffer); view.setByteOffset(0); view.setByteLength(data.remaining());
        if (stride != packedStride) view.setByteStride(stride);
        var views = context.gltf().getBufferViews();
        if (views == null) { views = new ArrayList<>(); context.gltf().setBufferViews(views); views = context.gltf().getBufferViews(); }
        if (views.size() >= GltfAssetLimits.MAX_BUFFER_VIEWS) throw new GltfLoadException("Decoded bufferView budget exceeded");
        int index = views.size();
        context.gltf().addBufferViews(view);
        accessor.setBufferView(index); accessor.setByteOffset(0);
    }

    private static int alignedVertexStride(int bytes) {
        return (bytes + 3) & ~3;
    }

    private static int bytesPerComponent(int type) throws GltfLoadException {
        return switch (type) {
            case 5120, 5121 -> 1; case 5122, 5123 -> 2; case 5125, 5126 -> 4;
            default -> throw new GltfLoadException("Unsupported Draco component type " + type);
        };
    }

    private record Pending(MeshPrimitive primitive, Accessor indices, List<Accessor> attributes) { }
}
