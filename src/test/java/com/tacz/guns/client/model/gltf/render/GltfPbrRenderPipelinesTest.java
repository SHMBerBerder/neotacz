package com.tacz.guns.client.model.gltf.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GltfPbrRenderPipelinesTest {
    private static final Set<String> MATERIAL_SAMPLERS = Set.of("BaseColorSampler", "MetallicRoughnessSampler",
            "NormalSampler", "OcclusionSampler", "EmissiveSampler", "BaseColorFactorSampler",
            "EmissiveFactorSampler", "PbrParametersSampler", "Sampler2");

    @Test
    void ordinaryPipelinesPreserveColorDepthAlphaAndCulling() {
        for (boolean cull : new boolean[]{true, false}) {
            for (GltfPbrAlphaMode alpha : GltfPbrAlphaMode.values()) {
                RenderPipeline pipeline = GltfPbrRenderPipelines.pipeline(alpha, cull);
                assertEquals(cull, pipeline.isCull());
                assertEquals(PrimitiveTopology.TRIANGLES, pipeline.getPrimitiveTopology());
                assertEquals(1, pipeline.getColorTargetStates().size());
                ColorTargetState target = pipeline.getColorTargetStates().getFirst();
                assertEquals(GpuFormat.RGBA8_UNORM, target.format());
                assertEquals(ColorTargetState.WRITE_ALL, target.writeMask());
                assertEquals(alpha == GltfPbrAlphaMode.BLEND
                        ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(), target.blendFunction());
                assertNotNull(pipeline.getDepthStencilState());
                assertEquals(alpha != GltfPbrAlphaMode.BLEND, pipeline.getDepthStencilState().writeDepth());
                assertEquals(CompareOp.GREATER_THAN_OR_EQUAL, pipeline.getDepthStencilState().depthTest());
                assertTrue(pipeline.getShaderDefines().flags().contains("GLTF_ALPHA_" + alpha.name()));
                assertDoesNotThrow(() -> BindGroupLayout.ensureCompatible(pipeline.getBindGroupLayouts()));
                assertEquals(MATERIAL_SAMPLERS, samplerNames(pipeline));
            }
        }
    }

    @Test
    void oitStagesUseNativeAttachmentFormatsAndNeverWriteDepth() {
        for (boolean cull : new boolean[]{true, false}) {
            OitPipelineSet pipelines = GltfPbrRenderPipelines.oitPipelines(cull);
            assertStage(pipelines.depthBoundsPipeline(), cull, "OIT_DEPTH_BOUNDS",
                    1, GpuFormat.RGBA32_FLOAT, BlendFunction.MAX);
            assertStage(pipelines.transmittancePipeline(), cull, "OIT_TRANSMITTANCE",
                    LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT, GpuFormat.RGBA16_FLOAT, BlendFunction.ADDITIVE);
            assertStage(pipelines.accumulatePipeline(), cull, "OIT_ACCUMULATE",
                    1, GpuFormat.RGBA16_FLOAT, BlendFunction.ADDITIVE);
            assertNotEquals(pipelines.depthBoundsPipeline().getLocation(), pipelines.transmittancePipeline().getLocation());
            assertNotEquals(pipelines.accumulatePipeline().getLocation(), pipelines.transmittancePipeline().getLocation());
        }
    }

    @Test
    void onlyBlendedMaterialsAttachOitStagesToTheirRetainedRenderType() throws Exception {
        var stateField = RenderType.class.getDeclaredField("state");
        stateField.setAccessible(true);
        var oitField = RenderSetup.class.getDeclaredField("oitPipelineSet");
        oitField.setAccessible(true);
        try {
            for (boolean cull : new boolean[]{true, false}) {
                for (GltfPbrAlphaMode alpha : GltfPbrAlphaMode.values()) {
                    var material = new GltfPbrMaterial(null, null, null, null, null,
                            null, null, null, alpha, cull);
                    RenderType renderType = GltfPbrRenderTypes.renderType(material);
                    assertEquals(alpha == GltfPbrAlphaMode.BLEND, renderType.hasBlending());
                    assertSame(alpha == GltfPbrAlphaMode.BLEND ? GltfPbrRenderPipelines.oitPipelines(cull) : null,
                            oitField.get(stateField.get(renderType)));
                }
            }
        } finally {
            GltfPbrRenderTypes.clearCache();
        }
    }

    private static void assertStage(RenderPipeline pipeline, boolean cull, String phase,
                                    int targetCount, GpuFormat format, BlendFunction blend) {
        assertEquals(cull, pipeline.isCull());
        assertEquals(PrimitiveTopology.TRIANGLES, pipeline.getPrimitiveTopology());
        assertTrue(pipeline.getShaderDefines().flags().containsAll(List.of("OIT", "GLTF_ALPHA_BLEND", phase)));
        assertEquals(phase.equals("OIT_ACCUMULATE"), !pipeline.getShaderDefines().flags().contains("OIT_ALPHA_ONLY"));
        assertEquals(targetCount, pipeline.getColorTargetStates().size());
        for (ColorTargetState target : pipeline.getColorTargetStates()) {
            assertEquals(format, target.format());
            assertEquals(Optional.of(blend), target.blendFunction());
            assertEquals(ColorTargetState.WRITE_ALL, target.writeMask());
        }
        assertNotNull(pipeline.getDepthStencilState());
        assertFalse(pipeline.getDepthStencilState().writeDepth());
        assertEquals(CompareOp.GREATER_THAN_OR_EQUAL, pipeline.getDepthStencilState().depthTest());
        assertDoesNotThrow(() -> BindGroupLayout.ensureCompatible(pipeline.getBindGroupLayouts()));
        Set<String> expectedSamplers = new HashSet<>(MATERIAL_SAMPLERS);
        if (!phase.equals("OIT_DEPTH_BOUNDS")) expectedSamplers.add("DepthBoundsSampler");
        if (phase.equals("OIT_ACCUMULATE")) {
            for (int index = 0; index < LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT; index++) {
                expectedSamplers.add("Coeff" + index);
            }
        }
        Set<String> samplers = samplerNames(pipeline);
        assertEquals(expectedSamplers, samplers);
        assertTrue(samplers.size() <= 12, "The PBR OIT pipeline must retain its 12-sampler binding budget");
    }

    private static Set<String> samplerNames(RenderPipeline pipeline) {
        return BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream()
                .filter(uniform -> uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER)
                .map(BindGroupLayout.UniformDescription::name).collect(Collectors.toSet());
    }
}
