package com.tacz.guns.client.model.gltf.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.model.BufferModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.GltfModels;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.GltfReference;
import de.javagl.jgltf.model.io.JacksonUtils;
import de.javagl.jgltf.model.io.JsonError;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JgltfModelLoader {
    private static final int GLB_HEADER_BYTES = 12;
    private static final int GLB_CHUNK_HEADER_BYTES = 8;
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_JSON_CHUNK = 0x4E4F534A;

    public NormalizedGltfModel load(InputStream inputStream) throws IOException {
        return load(inputStream, null, GltfLoaderOptions.DEFAULT);
    }

    public NormalizedGltfModel load(InputStream inputStream, GltfResourceResolver resourceResolver) throws IOException {
        return load(inputStream, resourceResolver, GltfLoaderOptions.DEFAULT);
    }

    public NormalizedGltfModel load(
            InputStream inputStream,
            GltfResourceResolver resourceResolver,
            GltfLoaderOptions options
    ) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        Objects.requireNonNull(options, "options");

        byte[] sourceBytes = GltfResourcePolicy.readAllBytesLimited(inputStream, GltfResourcePolicy.DEFAULT_MAX_RESOURCE_BYTES);
        validateJsonSchema(jsonBytes(sourceBytes));
        GltfAsset asset = new GltfAssetReader().readWithoutReferences(new ByteArrayInputStream(sourceBytes));
        GlTF gltf = requireGltf2(asset);
        rejectUnsupportedRequiredExtensions(gltf, options.supportedRequiredExtensions());
        resolveExternalReferences(asset, resourceResolver);
        asset = GltfCompressionNormalizer.normalize(asset, options.cancelled());
        gltf = requireGltf2(asset);
        GltfModel model = GltfModels.create(JgltfAssetAdapter.forModelCreation(asset));
        validateResolvedBuffers(model);
        return NormalizedGltfModel.create(asset, gltf, model);
    }

    private static void validateJsonSchema(byte[] jsonBytes) throws IOException {
        List<String> jsonErrors = new ArrayList<>();
        int[] jsonErrorCount = {0};
        ObjectMapper mapper = JacksonUtils.createObjectMapper(
                error -> collectJsonError(error, jsonErrors, jsonErrorCount)
        );
        mapper.readValue(jsonBytes, GlTF.class);
        if (jsonErrorCount[0] == 0) {
            return;
        }
        String omitted = jsonErrorCount[0] > jsonErrors.size()
                ? "; " + (jsonErrorCount[0] - jsonErrors.size()) + " more omitted"
                : "";
        throw new GltfLoadException("glTF JSON schema validation failed ("
                + jsonErrorCount[0] + " error(s)): " + String.join("; ", jsonErrors) + omitted);
    }

    private static byte[] jsonBytes(byte[] source) throws GltfLoadException {
        if (source.length < Integer.BYTES || littleEndianInt(source, 0) != GLB_MAGIC) {
            return source;
        }
        if (source.length < GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES) {
            throw new GltfLoadException("GLB header or JSON chunk is truncated");
        }
        int version = littleEndianInt(source, 4);
        long declaredLength = Integer.toUnsignedLong(littleEndianInt(source, 8));
        if (version != 2 || declaredLength != source.length) {
            throw new GltfLoadException("Invalid GLB version or declared length");
        }
        long jsonLength = Integer.toUnsignedLong(littleEndianInt(source, GLB_HEADER_BYTES));
        int chunkType = littleEndianInt(source, GLB_HEADER_BYTES + 4);
        long jsonEnd = GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES + jsonLength;
        if (chunkType != GLB_JSON_CHUNK || jsonEnd > source.length) {
            throw new GltfLoadException("GLB must start with a complete JSON chunk");
        }
        int start = GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES;
        int end = (int) jsonEnd;
        while (end > start && (source[end - 1] == 0x20 || source[end - 1] == 0x00)) {
            end--;
        }
        byte[] json = new byte[end - start];
        System.arraycopy(source, start, json, 0, json.length);
        return json;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return bytes[offset] & 0xFF
                | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16
                | (bytes[offset + 3] & 0xFF) << 24;
    }

    private static void collectJsonError(JsonError error, List<String> errors, int[] count) {
        count[0]++;
        if (errors.size() >= 8) {
            return;
        }
        String path = error.getJsonPathString();
        errors.add(error.getMessage() + (path == null || path.isBlank() ? "" : " at " + path));
    }

    private static GlTF requireGltf2(GltfAsset asset) throws GltfLoadException {
        Object gltf = asset.getGltf();
        if (gltf instanceof GlTF gltf2) {
            return gltf2;
        }
        throw new GltfLoadException("Only glTF 2.0 assets are supported");
    }

    private static void rejectUnsupportedRequiredExtensions(GlTF gltf, Set<String> supported) throws GltfLoadException {
        List<String> required = gltf.getExtensionsRequired();
        if (required == null || required.isEmpty()) {
            return;
        }

        List<String> unsupported = new ArrayList<>();
        for (String extension : required) {
            if (!supported.contains(extension)) {
                unsupported.add(extension);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new GltfLoadException("Unsupported required glTF extensions: " + unsupported);
        }
    }

    private static void resolveExternalReferences(
            GltfAsset asset,
            GltfResourceResolver resourceResolver
    ) throws IOException {
        List<GltfReference> references = asset.getReferences();
        if (references.isEmpty()) {
            return;
        }
        if (resourceResolver == null) {
            throw new GltfLoadException("External glTF references require a resolver: " + describeReferences(references));
        }
        for (GltfReference reference : references) {
            ByteBuffer data = resourceResolver.resolve(reference.getUri());
            if (data == null) {
                throw new GltfLoadException("Resolver returned no data for glTF reference "
                        + reference.getName() + ": " + reference.getUri());
            }
            reference.getTarget().accept(data.slice().order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    private static void validateResolvedBuffers(GltfModel model) throws GltfLoadException {
        List<BufferModel> buffers = model.getBufferModels();
        for (int i = 0; i < buffers.size(); i++) {
            BufferModel buffer = buffers.get(i);
            ByteBuffer data = buffer.getBufferData();
            if (data == null) {
                throw new GltfLoadException("glTF buffer " + i + " has no resolved data");
            }
            if (data.capacity() < buffer.getByteLength()) {
                throw new GltfLoadException("glTF buffer " + i + " resolved "
                        + data.capacity() + " bytes but declares " + buffer.getByteLength());
            }
        }
    }

    private static String describeReferences(List<GltfReference> references) {
        List<String> descriptions = new ArrayList<>(references.size());
        for (GltfReference reference : references) {
            descriptions.add(reference.getName() + "=" + reference.getUri());
        }
        return descriptions.toString();
    }
}
