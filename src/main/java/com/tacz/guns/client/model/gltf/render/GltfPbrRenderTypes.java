package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class GltfPbrRenderTypes {
    private static final Map<GltfPbrMaterial, RenderType> CACHE = new ConcurrentHashMap<>();
    // Factor/fallback texels remain single-level; each authored image slot carries its own sampler.
    private static final Supplier<GpuSampler> LINEAR_REPEAT =
            () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);

    private GltfPbrRenderTypes() {
    }

    public static RenderType renderType(GltfPbrMaterial material) {
        return CACHE.computeIfAbsent(material, GltfPbrRenderTypes::create);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static RenderType create(GltfPbrMaterial material) {
        RenderSetup setup = RenderSetup.builder(GltfPbrRenderPipelines.pipeline(material.alphaMode(), material.cull()))
                .withTexture(GltfPbrRenderPipelines.BASE_COLOR_SAMPLER, material.baseColorTexture(),
                        () -> GltfPbrSamplers.resolve(material.samplers().baseColor()))
                .withTexture(GltfPbrRenderPipelines.METALLIC_ROUGHNESS_SAMPLER, material.metallicRoughnessTexture(),
                        () -> GltfPbrSamplers.resolve(material.samplers().metallicRoughness()))
                .withTexture(GltfPbrRenderPipelines.NORMAL_SAMPLER, material.normalTexture(),
                        () -> GltfPbrSamplers.resolve(material.samplers().normal()))
                .withTexture(GltfPbrRenderPipelines.OCCLUSION_SAMPLER, material.occlusionTexture(),
                        () -> GltfPbrSamplers.resolve(material.samplers().occlusion()))
                .withTexture(GltfPbrRenderPipelines.EMISSIVE_SAMPLER, material.emissiveTexture(),
                        () -> GltfPbrSamplers.resolve(material.samplers().emissive()))
                .withTexture(GltfPbrRenderPipelines.BASE_COLOR_FACTOR_SAMPLER, material.baseColorFactorTexture(), LINEAR_REPEAT)
                .withTexture(GltfPbrRenderPipelines.EMISSIVE_FACTOR_SAMPLER, material.emissiveFactorTexture(), LINEAR_REPEAT)
                .withTexture(GltfPbrRenderPipelines.PBR_PARAMETERS_SAMPLER, material.pbrParametersTexture(), LINEAR_REPEAT)
                .useLightmap()
                .affectsCrumbling()
                .createRenderSetup();
        String name = "tacz_gltf_pbr_" + material.alphaMode().id() + (material.cull() ? "_cull" : "_no_cull");
        return RenderType.create(name, setup);
    }
}
