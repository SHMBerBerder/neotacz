package com.tacz.guns.client.model.gltf.loader;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.GltfAsset;

import java.nio.ByteBuffer;
import java.util.List;

public record NormalizedGltfModel(
        GltfAsset asset,
        GlTF gltf,
        GltfModel model,
        boolean binary,
        List<String> extensionsUsed,
        List<String> extensionsRequired,
        ElementCounts counts
) {
    static NormalizedGltfModel create(GltfAsset asset, GlTF gltf, GltfModel model) {
        ByteBuffer binaryData = asset.getBinaryData();
        return new NormalizedGltfModel(
                asset,
                gltf,
                model,
                binaryData != null && binaryData.capacity() > 0,
                immutableList(gltf.getExtensionsUsed()),
                immutableList(gltf.getExtensionsRequired()),
                new ElementCounts(
                        model.getAccessorModels().size(),
                        model.getAnimationModels().size(),
                        model.getBufferModels().size(),
                        model.getBufferViewModels().size(),
                        model.getImageModels().size(),
                        model.getMaterialModels().size(),
                        model.getMeshModels().size(),
                        model.getNodeModels().size(),
                        model.getSceneModels().size(),
                        model.getSkinModels().size(),
                        model.getTextureModels().size()
                )
        );
    }

    private static List<String> immutableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record ElementCounts(
            int accessors,
            int animations,
            int buffers,
            int bufferViews,
            int images,
            int materials,
            int meshes,
            int nodes,
            int scenes,
            int skins,
            int textures
    ) {
    }
}
