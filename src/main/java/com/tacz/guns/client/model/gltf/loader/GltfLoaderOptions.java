package com.tacz.guns.client.model.gltf.loader;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

public record GltfLoaderOptions(Set<String> supportedRequiredExtensions, BooleanSupplier cancelled) {
    public static final Set<String> SUPPORTED = Set.of("MSFT_lod", "KHR_texture_basisu",
            "KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_mesh_quantization");
    public static final GltfLoaderOptions DEFAULT = new GltfLoaderOptions(SUPPORTED);

    public GltfLoaderOptions(Set<String> supportedRequiredExtensions) {
        this(supportedRequiredExtensions, () -> false);
    }

    public GltfLoaderOptions {
        supportedRequiredExtensions = Set.copyOf(Objects.requireNonNull(
                supportedRequiredExtensions,
                "supportedRequiredExtensions"
        ));
        Objects.requireNonNull(cancelled, "cancelled");
    }
}
