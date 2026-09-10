package com.tacz.guns.client.model.gltf;

/** Explicit resource limits shared by the client and offline tools, independent of model IDs. */
public final class GltfRuntimePolicy {
    public static final String RETIRED_MODEL_PROPERTY =
            "tacz.gltf.highResolutionStressModel";
    public static final long MIB = 1024L * 1024L;
    public static final ResourceBudgets DEFAULT = fromMiB(16, 64, 512);

    private GltfRuntimePolicy() {
    }

    public static ResourceBudgets fromMiB(Object singleImage, Object modelImages, Object decodedTextures) {
        return new ResourceBudgets(
                integerMiB(singleImage, 64, "GltfMaxEncodedImageMiB") * MIB,
                integerMiB(modelImages, 384, "GltfMaxModelImagesMiB") * MIB,
                integerMiB(decodedTextures, 2048, "GltfGlobalGpuTextureMiB") * MIB
        );
    }

    private static long integerMiB(Object value, int maximum, String name) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(name + " must be an integer MiB value");
        }
        long number = ((Number) value).longValue();
        if (number < 1 || number > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum + " MiB");
        }
        return number;
    }

    public static void rejectRetiredOverride() {
        if (System.getProperties().containsKey(RETIRED_MODEL_PROPERTY)) {
            throw new IllegalStateException(RETIRED_MODEL_PROPERTY
                    + " is retired; configure the client resource budgets or explicit offline image budgets");
        }
    }

    public record ResourceBudgets(long maxSingleImageBytes, long maxTotalImageBytes, long maxDecodedTextureBytes) {
        public ResourceBudgets {
            if (maxSingleImageBytes <= 0 || maxSingleImageBytes > 64L * MIB
                    || maxTotalImageBytes < maxSingleImageBytes || maxTotalImageBytes > 384L * MIB
                    || maxDecodedTextureBytes <= 0 || maxDecodedTextureBytes > 2048L * MIB) {
                throw new IllegalArgumentException("Invalid glTF resource budgets: positive bounded values and "
                        + "maxTotalImageBytes >= maxSingleImageBytes are required");
            }
        }
    }
}
