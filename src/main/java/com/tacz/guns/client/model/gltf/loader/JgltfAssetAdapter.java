package com.tacz.guns.client.model.gltf.loader;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.nio.ByteBuffer;
import java.util.List;

/** A local JglTF build view; the normalized source remains the extension authority. */
final class JgltfAssetAdapter {
    private static final String QUANTIZATION = "KHR_mesh_quantization";

    private JgltfAssetAdapter() { }

    static GltfAsset forModelCreation(GltfAsset source) throws GltfLoadException {
        GlTF original = (GlTF) source.getGltf();
        if (original.getExtensionsRequired() == null || original.getExtensionsUsed() == null
                || !original.getExtensionsRequired().contains(QUANTIZATION)
                || !original.getExtensionsUsed().contains(QUANTIZATION)) return source;

        // JglTF already reads integer accessors. Our converter, not its extension registry,
        // validates their quantization semantics against the unchanged normalized source.
        // Copy only the top-level declaration object; do not duplicate mesh/image payloads.
        GlTF view = new GlTF();
        view.setExtensionsUsed(withoutQuantization(original.getExtensionsUsed()));
        view.setExtensionsRequired(withoutQuantization(original.getExtensionsRequired()));
        view.setExtensions(original.getExtensions()); view.setExtras(original.getExtras());
        view.setAsset(original.getAsset()); view.setAccessors(original.getAccessors());
        view.setAnimations(original.getAnimations()); view.setBuffers(original.getBuffers());
        view.setBufferViews(original.getBufferViews()); view.setCameras(original.getCameras());
        view.setImages(original.getImages()); view.setMaterials(original.getMaterials());
        view.setMeshes(original.getMeshes()); view.setNodes(original.getNodes());
        view.setSamplers(original.getSamplers()); view.setScene(original.getScene());
        view.setScenes(original.getScenes()); view.setSkins(original.getSkins());
        view.setTextures(original.getTextures());
        GltfAssetV2 adapted = new GltfAssetV2(view, readOnlyView(source.getBinaryData()));
        for (var reference : adapted.getReferences()) {
            ByteBuffer data = source.getReferenceData(reference.getUri());
            if (data == null) throw new GltfLoadException("JglTF adapted resource missing: " + reference.getUri());
            reference.getTarget().accept(readOnlyView(data));
        }
        return adapted;
    }

    private static List<String> withoutQuantization(List<String> declarations) {
        List<String> result = declarations.stream().filter(name -> !QUANTIZATION.equals(name)).toList();
        return result.isEmpty() ? null : result;
    }

    private static ByteBuffer readOnlyView(ByteBuffer data) {
        return data == null ? null : data.asReadOnlyBuffer().order(data.order());
    }
}
