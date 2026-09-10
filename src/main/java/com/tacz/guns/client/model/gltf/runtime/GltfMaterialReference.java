package com.tacz.guns.client.model.gltf.runtime;

import java.util.Objects;

public record GltfMaterialReference(String id) {
    public GltfMaterialReference {
        Objects.requireNonNull(id, "id");
    }
}
