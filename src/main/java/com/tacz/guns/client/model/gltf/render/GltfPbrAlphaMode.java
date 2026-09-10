package com.tacz.guns.client.model.gltf.render;

public enum GltfPbrAlphaMode {
    OPAQUE("opaque"),
    MASK("mask"),
    BLEND("blend");

    private final String id;

    GltfPbrAlphaMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
