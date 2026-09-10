package com.tacz.guns.config.client;

import com.tacz.guns.client.model.gltf.GltfRuntimePolicy;
import net.neoforged.neoforge.common.ModConfigSpec;

public class ResourceConfig {
    public static ModConfigSpec.BooleanValue ENABLE_LAZY_CLIENT_ASSET_LOAD;
    public static ModConfigSpec.ConfigValue<Object> GLTF_MAX_ENCODED_IMAGE_MIB;
    public static ModConfigSpec.ConfigValue<Object> GLTF_MAX_MODEL_IMAGES_MIB;
    public static ModConfigSpec.ConfigValue<Object> GLTF_GLOBAL_GPU_TEXTURE_MIB;
    private static GltfRuntimePolicy.ResourceBudgets gltfBudgets;

    public static void init(ModConfigSpec.Builder builder) {
        GltfRuntimePolicy.rejectRetiredOverride();
        builder.push("resource");

        builder.comment("Build heavy TACZ client assets such as models and animation state machines on demand.",
                "Inventory items are pre-warmed in the background when possible.",
                "Pending assets are omitted until background loading finishes; rendering never waits for them.");
        ENABLE_LAZY_CLIENT_ASSET_LOAD = builder.define("EnableLazyClientAssetLoad", true);

        // Keep invalid values intact until our joint validation can reject them, rather than silently correcting them.
        GLTF_MAX_ENCODED_IMAGE_MIB = builder.comment("Maximum encoded bytes per glTF image, in MiB (integer 1..64).",
                        "Default 16; explicitly use 64 for high-resolution assets. Requires a game restart.")
                .gameRestart().<Object>define("GltfMaxEncodedImageMiB", 16, value -> value != null);
        GLTF_MAX_MODEL_IMAGES_MIB = builder.comment("Maximum total encoded image bytes per glTF model, in MiB (integer 1..384).",
                        "Must be at least GltfMaxEncodedImageMiB. Default 64; high-resolution 384. Requires a game restart.")
                .gameRestart().<Object>define("GltfMaxModelImagesMiB", 64, value -> value != null);
        GLTF_GLOBAL_GPU_TEXTURE_MIB = builder.comment("Global glTF RGBA8 texture budget across every model, in MiB (integer 1..2048).",
                        "Default 512; explicitly use 2048 for high-resolution assets. Requires a game restart.",
                        "Independent from per-model encoded resource read limits and video quality settings.")
                .gameRestart().<Object>define("GltfGlobalGpuTextureMiB", 512, value -> value != null);

        builder.pop();
    }

    public static synchronized GltfRuntimePolicy.ResourceBudgets gltfResourceBudgets() {
        GltfRuntimePolicy.rejectRetiredOverride();
        if (GLTF_MAX_ENCODED_IMAGE_MIB == null) {
            throw new IllegalStateException("Client resource config has not been registered");
        }
        if (gltfBudgets == null) {
            // get() throws before loading; only a complete, valid loaded tuple is frozen for this process.
            gltfBudgets = GltfRuntimePolicy.fromMiB(GLTF_MAX_ENCODED_IMAGE_MIB.get(),
                    GLTF_MAX_MODEL_IMAGES_MIB.get(), GLTF_GLOBAL_GPU_TEXTURE_MIB.get());
        }
        return gltfBudgets;
    }
}
