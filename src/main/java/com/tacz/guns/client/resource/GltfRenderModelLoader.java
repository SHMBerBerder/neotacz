package com.tacz.guns.client.resource;

import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import com.tacz.guns.client.model.gltf.quality.GltfMeshSimplifier;
import com.tacz.guns.client.model.gltf.quality.GltfTextureVariants;
import com.tacz.guns.client.model.gltf.render.GltfGunBodyRenderer;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.config.client.ResourceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Owns the generation-bound CPU import; the display publishes the completed renderer. */
public final class GltfRenderModelLoader {
    private GltfRenderModelLoader() { }

    public static GltfGunBodyRenderer load(GunRenderModelConfig config, BooleanSupplier invalidated) throws Exception {
        Objects.requireNonNull(config, "config").validate();
        Objects.requireNonNull(invalidated, "invalidated");
        if (!config.isGltf()) throw new IllegalArgumentException("A mesh renderer requires render_model.type=gltf");
        if (!config.getNodeMap().isEmpty()) {
            throw new IllegalArgumentException("Attachment render_model cannot bind gun nodes");
        }
        return load(config, null, Set.of(), invalidated);
    }

    static GltfGunBodyRenderer load(GunRenderModelConfig config, BedrockGunModel rig,
                                    Set<String> protectedSources, BooleanSupplier invalidated) throws Exception {
        Identifier location = Objects.requireNonNull(config.getLocation(), "validated glTF location");
        GltfModelManager manager = ClientAssetsManager.INSTANCE.getGltfModelManager();
        JgltfRuntimeConverter converter = new JgltfRuntimeConverter();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (invalidated.getAsBoolean()) return null;
            long generation = manager.getGeneration();
            var quality = ClientVideoSettings.quality();
            BooleanSupplier cancelled = () -> invalidated.getAsBoolean() || manager.getGeneration() != generation;
            try {
                NormalizedGltfModel source = manager.getModelUncached(location, cancelled);
                if (cancelled.getAsBoolean()) continue;
                ConvertedGltfAsset asset = converter.convert(source, ResourceConfig.gltfResourceBudgets());
                source = null; // Raw GLB/8K source buffers must not outlive the selected derivative.
                asset = asset.withLodLevel(quality.lodLevel());
                asset = GltfMeshSimplifier.apply(asset, quality, cancelled);
                if (cancelled.getAsBoolean()) continue;
                asset = GltfTextureVariants.apply(asset, quality,
                        Minecraft.getInstance().gameDirectory.toPath().resolve("cache/neotacz/gltf-textures"), cancelled);
                if (cancelled.getAsBoolean()) continue;
                GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(location, asset, manager, generation,
                        config, rig, protectedSources);
                if (cancelled.getAsBoolean()) continue;
                com.tacz.guns.GunMod.LOGGER.info("NeoTaCZ mesh {} loaded with {} ({} images, generation {})",
                        location, quality, asset.images().size(), generation);
                return renderer;
            } catch (CancellationException exception) {
                if (invalidated.getAsBoolean()) return null;
                if (manager.getGeneration() == generation) throw exception;
            }
        }
        if (invalidated.getAsBoolean()) return null;
        throw new IllegalStateException("glTF resources changed repeatedly while constructing the renderer");
    }
}
