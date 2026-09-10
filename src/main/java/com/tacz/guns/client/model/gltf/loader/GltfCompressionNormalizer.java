package com.tacz.guns.client.model.gltf.loader;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.model.io.GltfAsset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.nio.ByteBuffer;
import de.javagl.jgltf.model.io.IO;

/** Consumes supported compression extensions into core data before JglTF builds accessors. */
final class GltfCompressionNormalizer {
    static final String BASIS = "KHR_texture_basisu";
    static final String DRACO = "KHR_draco_mesh_compression";
    static final String MESHOPT = "EXT_meshopt_compression";
    private static final Set<String> CONSUMED = Set.of(BASIS, DRACO, MESHOPT);

    private GltfCompressionNormalizer() { }

    static GltfAsset normalize(GltfAsset asset, BooleanSupplier cancelled) throws GltfLoadException {
        GlTF gltf = (GlTF) asset.getGltf();
        List<String> used = gltf.getExtensionsUsed() == null ? List.of() : gltf.getExtensionsUsed();
        validateDeclarations(gltf, used);
        if (used.stream().noneMatch(CONSUMED::contains)) return asset;
        GltfDecodeContext context = new GltfDecodeContext(asset, cancelled);
        if (used.contains(MESHOPT)) GltfMeshoptDecoder.decode(context);
        if (used.contains(DRACO)) GltfDracoNormalizer.decode(context);
        if (used.contains(BASIS)) {
            normalizeTextures(context, asset);
            retireUnusedImages(gltf);
        }
        context.checkCancelled();
        // Downstream extension validation remains fail-closed. Only this successful normalization
        // may consume these declarations; attribute quantization stays declared for the converter.
        gltf.setExtensionsUsed(withoutConsumed(gltf.getExtensionsUsed()));
        gltf.setExtensionsRequired(withoutConsumed(gltf.getExtensionsRequired()));
        return context.finish();
    }

    private static void normalizeTextures(GltfDecodeContext context, GltfAsset asset) throws GltfLoadException {
        GlTF gltf = context.gltf();
        if (gltf.getTextures() == null) return;
        for (var texture : gltf.getTextures()) {
            if (texture.getExtensions() == null || !texture.getExtensions().containsKey(BASIS)) continue;
            Map<?, ?> extension = object(texture.getExtensions().get(BASIS), BASIS);
            int source = integer(extension.get("source"), BASIS + " source");
            var image = GltfDecodeContext.at(gltf.getImages(), source, "Basis image");
            if (image.getMimeType() != null && !image.getMimeType().equals("image/ktx2")) {
                throw new GltfLoadException("KHR_texture_basisu requires an image/ktx2 image");
            }
            if (image.getBufferView() != null && image.getMimeType() == null) {
                throw new GltfLoadException("Embedded Basis image requires image/ktx2 MIME type");
            }
            if (image.getUri() == null && image.getBufferView() == null) {
                throw new GltfLoadException("Basis texture has no image payload");
            }
            ByteBuffer bytes;
            if (image.getBufferView() != null) bytes = context.sourceBufferView(image.getBufferView());
            else if (IO.isDataUriString(image.getUri())) {
                if (image.getUri().length() > GltfResourcePolicy.DEFAULT_MAX_RESOURCE_BYTES) {
                    throw new GltfLoadException("Basis data URI exceeds input budget");
                }
                bytes = ByteBuffer.wrap(IO.readDataUri(image.getUri()));
            } else bytes = asset.getReferenceData(image.getUri());
            byte[] magic = {(byte) 0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};
            if (bytes == null || bytes.remaining() < magic.length) throw new GltfLoadException("Truncated KTX2 image");
            for (int i = 0; i < magic.length; i++) {
                if (bytes.get(bytes.position() + i) != magic[i]) throw new GltfLoadException("Basis image is not KTX2");
            }
            texture.setSource(source);
            image.setMimeType("image/ktx2");
            GltfDecodeContext.removeExtension(texture, BASIS);
        }
    }

    private static void validateDeclarations(GlTF gltf, List<String> used) throws GltfLoadException {
        if (gltf.getExtensionsRequired() != null && !used.containsAll(gltf.getExtensionsRequired())) {
            throw new GltfLoadException("extensionsRequired must also occur in extensionsUsed");
        }
        if (gltf.getBufferViews() != null) for (var view : gltf.getBufferViews()) requireDeclared(view, MESHOPT, used);
        if (gltf.getTextures() != null) for (var texture : gltf.getTextures()) requireDeclared(texture, BASIS, used);
        if (gltf.getMeshes() != null) for (var mesh : gltf.getMeshes()) {
            if (mesh.getPrimitives() != null) for (var primitive : mesh.getPrimitives()) requireDeclared(primitive, DRACO, used);
        }
    }

    private static void retireUnusedImages(GlTF gltf) throws GltfLoadException {
        var images = gltf.getImages();
        if (images == null) return;
        boolean[] used = new boolean[images.size()];
        if (gltf.getTextures() != null) for (var texture : gltf.getTextures()) {
            Integer source = texture.getSource();
            if (source != null) {
                GltfDecodeContext.at(images, source, "texture image");
                used[source] = true;
            }
        }
        int[] remap = new int[images.size()];
        var live = new ArrayList<de.javagl.jgltf.impl.v2.Image>();
        for (int i = 0; i < images.size(); i++) if (used[i]) {
            remap[i] = live.size(); live.add(images.get(i));
        }
        if (gltf.getTextures() != null) for (var texture : gltf.getTextures()) {
            if (texture.getSource() != null) texture.setSource(remap[texture.getSource()]);
        }
        gltf.setImages(live.isEmpty() ? null : live);
    }

    private static void requireDeclared(de.javagl.jgltf.impl.v2.GlTFProperty property, String name, List<String> used)
            throws GltfLoadException {
        if (property.getExtensions() != null && property.getExtensions().containsKey(name) && !used.contains(name)) {
            throw new GltfLoadException(name + " must be declared in extensionsUsed");
        }
    }

    static Map<?, ?> object(Object value, String role) throws GltfLoadException {
        if (!(value instanceof Map<?, ?> object)) throw new GltfLoadException(role + " must be an object");
        return object;
    }

    static int integer(Object value, String role) throws GltfLoadException {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE) {
            throw new GltfLoadException(role + " must be a non-negative bounded integer");
        }
        return number.intValue();
    }

    private static List<String> withoutConsumed(List<String> names) {
        if (names == null) return null;
        List<String> result = new ArrayList<>(names);
        result.removeIf(CONSUMED::contains);
        return result.isEmpty() ? null : result;
    }
}
