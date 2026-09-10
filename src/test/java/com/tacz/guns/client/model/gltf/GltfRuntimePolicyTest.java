package com.tacz.guns.client.model.gltf;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.tacz.guns.config.client.ResourceConfig;
import net.neoforged.fml.config.LoadedConfigFixture;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GltfRuntimePolicyTest {
    @Test
    void defaultsAndExplicitLimitsArePureAndBounded() {
        assertEquals(new GltfRuntimePolicy.ResourceBudgets(16L << 20, 64L << 20, 512L << 20),
                GltfRuntimePolicy.DEFAULT);
        assertEquals(new GltfRuntimePolicy.ResourceBudgets(64L << 20, 384L << 20, 2048L << 20),
                GltfRuntimePolicy.fromMiB(64L, 384L, 2048L));
        for (Object invalid : new Object[]{null, true, "64", 64.0, 0, -1, 65, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> GltfRuntimePolicy.fromMiB(invalid, 384, 2048));
        }
        assertThrows(IllegalArgumentException.class, () -> GltfRuntimePolicy.fromMiB(64, 63, 2048));
        assertThrows(IllegalArgumentException.class, () -> GltfRuntimePolicy.fromMiB(64, 385, 2048));
        assertThrows(IllegalArgumentException.class, () -> GltfRuntimePolicy.fromMiB(64, 384, 2049));
        assertThrows(IllegalArgumentException.class, () -> new GltfRuntimePolicy.ResourceBudgets(1, 1, Long.MAX_VALUE));
    }

    @Test
    void retiredPropertyFailsEvenWhenEmpty() {
        String key = GltfRuntimePolicy.RETIRED_MODEL_PROPERTY;
        Object previous = System.getProperties().get(key);
        try {
            System.setProperty(key, "");
            assertTrue(assertThrows(IllegalStateException.class, GltfRuntimePolicy::rejectRetiredOverride)
                    .getMessage().contains("retired"));
            assertThrows(IllegalStateException.class, () -> ResourceConfig.init(new ModConfigSpec.Builder()));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.getProperties().put(key, previous);
        }
    }

    @Test
    void clientTupleRequiresLoadedConfigRejectsInvalidRawValuesAndFreezesTogether() {
        var builder = new ModConfigSpec.Builder();
        ResourceConfig.init(builder);
        ModConfigSpec spec = builder.build();
        assertThrows(IllegalStateException.class, ResourceConfig::gltfResourceBudgets);
        assertEquals(ModConfigSpec.RestartType.GAME, ResourceConfig.GLTF_MAX_ENCODED_IMAGE_MIB.getSpec().restartType());
        assertEquals(ModConfigSpec.RestartType.GAME, ResourceConfig.GLTF_MAX_MODEL_IMAGES_MIB.getSpec().restartType());
        assertEquals(ModConfigSpec.RestartType.GAME, ResourceConfig.GLTF_GLOBAL_GPU_TEXTURE_MIB.getSpec().restartType());

        CommentedConfig config = CommentedConfig.inMemory();
        LoadedConfigFixture.accept(spec, config);
        assertEquals(16, (Object) config.get("resource.GltfMaxEncodedImageMiB"));
        assertEquals(64, (Object) config.get("resource.GltfMaxModelImagesMiB"));
        assertEquals(512, (Object) config.get("resource.GltfGlobalGpuTextureMiB"));
        config.set("resource.GltfMaxEncodedImageMiB", 65);
        config.set("resource.GltfMaxModelImagesMiB", 384);
        config.set("resource.GltfGlobalGpuTextureMiB", 2048);
        LoadedConfigFixture.accept(spec, config);
        assertEquals(65, (Object) config.get("resource.GltfMaxEncodedImageMiB"));
        assertThrows(IllegalArgumentException.class, ResourceConfig::gltfResourceBudgets);

        config.set("resource.GltfMaxEncodedImageMiB", 64);
        config.set("resource.GltfMaxModelImagesMiB", 63);
        ResourceConfig.GLTF_MAX_ENCODED_IMAGE_MIB.clearCache();
        ResourceConfig.GLTF_MAX_MODEL_IMAGES_MIB.clearCache();
        assertThrows(IllegalArgumentException.class, ResourceConfig::gltfResourceBudgets);

        config.set("resource.GltfMaxModelImagesMiB", 384);
        ResourceConfig.GLTF_MAX_MODEL_IMAGES_MIB.clearCache();
        var frozen = ResourceConfig.gltfResourceBudgets();
        assertEquals(GltfRuntimePolicy.fromMiB(64, 384, 2048), frozen);
        config.set("resource.GltfMaxEncodedImageMiB", 16);
        config.set("resource.GltfMaxModelImagesMiB", 64);
        config.set("resource.GltfGlobalGpuTextureMiB", 512);
        LoadedConfigFixture.accept(spec, config);
        assertSame(frozen, ResourceConfig.gltfResourceBudgets());
    }
}
