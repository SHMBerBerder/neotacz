package com.tacz.guns.client.model.gltf.loader;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class ResourceManagerGltfResourceResolver implements GltfResourceResolver {
    private final ResourceManager resourceManager;
    private final Identifier gltfResource;
    private final long maxResourceBytes;

    public ResourceManagerGltfResourceResolver(ResourceManager resourceManager, Identifier gltfResource) {
        this(resourceManager, gltfResource, GltfResourcePolicy.DEFAULT_MAX_RESOURCE_BYTES);
    }

    public ResourceManagerGltfResourceResolver(
            ResourceManager resourceManager,
            Identifier gltfResource,
            long maxResourceBytes
    ) {
        this.resourceManager = Objects.requireNonNull(resourceManager, "resourceManager");
        this.gltfResource = Objects.requireNonNull(gltfResource, "gltfResource");
        GltfResourcePolicy.requireMainModelId(gltfResource);
        if (maxResourceBytes <= 0) {
            throw new IllegalArgumentException("glTF resource byte limit must be positive");
        }
        this.maxResourceBytes = maxResourceBytes;
    }

    @Override
    public ByteBuffer resolve(String uri) throws IOException {
        Identifier resourceId = resolveResourceId(gltfResource, uri);
        try (InputStream inputStream = resourceManager.open(resourceId)) {
            return ByteBuffer.wrap(GltfResourcePolicy.readAllBytesLimited(inputStream, maxResourceBytes));
        }
    }

    public static Identifier resolveResourceId(Identifier gltfResource, String uri) {
        return GltfResourcePolicy.resolveResourceId(gltfResource, uri);
    }
}
