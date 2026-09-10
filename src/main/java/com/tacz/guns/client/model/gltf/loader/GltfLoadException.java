package com.tacz.guns.client.model.gltf.loader;

import java.io.IOException;

public class GltfLoadException extends IOException {
    public GltfLoadException(String message) {
        super(message);
    }

    public GltfLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
