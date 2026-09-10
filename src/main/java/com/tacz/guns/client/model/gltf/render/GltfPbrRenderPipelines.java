package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.tacz.guns.GunMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
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
    // BLEND enters the translucent pass with depth writes off; it is not full per-triangle sorting.
    private static final DepthStencilState BLEND_DEPTH_STATE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
    private static final BindGroupLayout PBR_TEXTURES = BindGroupLayout.builder()
            .withSampler(BASE_COLOR_SAMPLER)
            .withSampler(METALLIC_ROUGHNESS_SAMPLER)
            .withSampler(NORMAL_SAMPLER)
            .withSampler(OCCLUSION_SAMPLER)
            .withSampler(EMISSIVE_SAMPLER)
            .withSampler(BASE_COLOR_FACTOR_SAMPLER)
            .withSampler(EMISSIVE_FACTOR_SAMPLER)
            .withSampler(PBR_PARAMETERS_SAMPLER)
            .build();

    private static final RenderPipeline OPAQUE_CULL = create(GltfPbrAlphaMode.OPAQUE, true);
    private static final RenderPipeline OPAQUE_NO_CULL = create(GltfPbrAlphaMode.OPAQUE, false);
    private static final RenderPipeline MASK_CULL = create(GltfPbrAlphaMode.MASK, true);
    private static final RenderPipeline MASK_NO_CULL = create(GltfPbrAlphaMode.MASK, false);
    private static final RenderPipeline BLEND_CULL = create(GltfPbrAlphaMode.BLEND, true);
    private static final RenderPipeline BLEND_NO_CULL = create(GltfPbrAlphaMode.BLEND, false);

    private GltfPbrRenderPipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(OPAQUE_CULL);
        event.registerPipeline(OPAQUE_NO_CULL);
        event.registerPipeline(MASK_CULL);
        event.registerPipeline(MASK_NO_CULL);
        event.registerPipeline(BLEND_CULL);
        event.registerPipeline(BLEND_NO_CULL);
    }

    static RenderPipeline pipeline(GltfPbrAlphaMode alphaMode, boolean cull) {
        return switch (alphaMode) {
            case OPAQUE -> cull ? OPAQUE_CULL : OPAQUE_NO_CULL;
            case MASK -> cull ? MASK_CULL : MASK_NO_CULL;
            case BLEND -> cull ? BLEND_CULL : BLEND_NO_CULL;
        };
    }

    private static RenderPipeline create(GltfPbrAlphaMode alphaMode, boolean cull) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(id("pipeline/gltf_pbr/" + alphaMode.id() + (cull ? "_cull" : "_no_cull")))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withBindGroupLayout(PBR_TEXTURES)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(cull);

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
