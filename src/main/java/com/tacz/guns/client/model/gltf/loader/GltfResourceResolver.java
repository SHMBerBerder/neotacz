package com.tacz.guns.client.model.gltf.loader;

import java.io.IOException;
import java.nio.ByteBuffer;

@FunctionalInterface
public interface GltfResourceResolver {
    ByteBuffer resolve(String uri) throws IOException;
}
