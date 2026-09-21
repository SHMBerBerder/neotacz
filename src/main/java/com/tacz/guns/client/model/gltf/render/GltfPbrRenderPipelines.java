package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.tacz.guns.GunMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

public final class GltfPbrRenderPipelines {
    static final String BASE_COLOR_SAMPLER = "BaseColorSampler";
    static final String METALLIC_ROUGHNESS_SAMPLER = "MetallicRoughnessSampler";
    static final String NORMAL_SAMPLER = "NormalSampler";
    static final String OCCLUSION_SAMPLER = "OcclusionSampler";
    static final String EMISSIVE_SAMPLER = "EmissiveSampler";
    static final String BASE_COLOR_FACTOR_SAMPLER = "BaseColorFactorSampler";
    static final String EMISSIVE_FACTOR_SAMPLER = "EmissiveFactorSampler";
    static final String PBR_PARAMETERS_SAMPLER = "PbrParametersSampler";

    private static final Identifier SHADER = id("pbr/gltf_pbr");
    // The ordinary BLEND path retains sorted triangles; improved transparency uses native OIT stages.
    private static final DepthStencilState BLEND_DEPTH_STATE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
    private static final BindGroupLayout PBR_TEXTURES = BindGroupLayout.builder()
            .withUniform(BASE_COLOR_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(METALLIC_ROUGHNESS_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(NORMAL_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(OCCLUSION_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(EMISSIVE_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(BASE_COLOR_FACTOR_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(EMISSIVE_FACTOR_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(PBR_PARAMETERS_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static final RenderPipeline OPAQUE_CULL = create(GltfPbrAlphaMode.OPAQUE, true);
    private static final RenderPipeline OPAQUE_NO_CULL = create(GltfPbrAlphaMode.OPAQUE, false);
    private static final RenderPipeline MASK_CULL = create(GltfPbrAlphaMode.MASK, true);
    private static final RenderPipeline MASK_NO_CULL = create(GltfPbrAlphaMode.MASK, false);
    private static final RenderPipeline BLEND_CULL = create(GltfPbrAlphaMode.BLEND, true);
    private static final RenderPipeline BLEND_NO_CULL = create(GltfPbrAlphaMode.BLEND, false);
    private static final OitPipelineSet OIT_BLEND_CULL = createOit(true);
    private static final OitPipelineSet OIT_BLEND_NO_CULL = createOit(false);

    private GltfPbrRenderPipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(OPAQUE_CULL);
        event.registerPipeline(OPAQUE_NO_CULL);
        event.registerPipeline(MASK_CULL);
        event.registerPipeline(MASK_NO_CULL);
        event.registerPipeline(BLEND_CULL);
        event.registerPipeline(BLEND_NO_CULL);
        registerOit(event, OIT_BLEND_CULL);
        registerOit(event, OIT_BLEND_NO_CULL);
    }

    static RenderPipeline pipeline(GltfPbrAlphaMode alphaMode, boolean cull) {
        return switch (alphaMode) {
            case OPAQUE -> cull ? OPAQUE_CULL : OPAQUE_NO_CULL;
            case MASK -> cull ? MASK_CULL : MASK_NO_CULL;
            case BLEND -> cull ? BLEND_CULL : BLEND_NO_CULL;
        };
    }

    static OitPipelineSet oitPipelines(boolean cull) {
        return cull ? OIT_BLEND_CULL : OIT_BLEND_NO_CULL;
    }

    private static void registerOit(RegisterRenderPipelinesEvent event, OitPipelineSet pipelines) {
        event.registerPipeline(pipelines.depthBoundsPipeline());
        event.registerPipeline(pipelines.transmittancePipeline());
        event.registerPipeline(pipelines.accumulatePipeline());
    }

    private static OitPipelineSet createOit(boolean cull) {
        // OIT snippets only fill missing color targets, so never copy a finished RGBA8 pipeline.
        return OitPipelineSet.builder(id("gltf_pbr_blend" + (cull ? "_cull" : "_no_cull")),
                baseBuilder(cull).withShaderDefine("GLTF_ALPHA_BLEND")).build();
    }

    private static RenderPipeline.Builder baseBuilder(boolean cull) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withBindGroupLayout(PBR_TEXTURES)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(cull);
    }

    private static RenderPipeline create(GltfPbrAlphaMode alphaMode, boolean cull) {
        RenderPipeline.Builder builder = baseBuilder(cull)
                .withLocation(id("pipeline/gltf_pbr/" + alphaMode.id() + (cull ? "_cull" : "_no_cull")))
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(DepthStencilState.DEFAULT);

        switch (alphaMode) {
            case OPAQUE -> builder.withShaderDefine("GLTF_ALPHA_OPAQUE");
            case MASK -> builder.withShaderDefine("GLTF_ALPHA_MASK");
            case BLEND -> builder.withShaderDefine("GLTF_ALPHA_BLEND")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(BLEND_DEPTH_STATE);
        }

        return builder.build();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(GunMod.MOD_ID, path);
    }
}
