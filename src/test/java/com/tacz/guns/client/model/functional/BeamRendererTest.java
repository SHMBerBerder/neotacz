package com.tacz.guns.client.model.functional;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BeamRendererTest {
    @Test
    void ordinaryAndEntityBeamsRetainOldAdditiveDepthAndLayeringWithoutSharingShaders() {
        var ordinary = BeamRenderer.LaserBeamRenderState.getLaserBeam();
        var entity = BeamRenderer.LaserBeamRenderState.getLaserBeamEntity();
        assertNotSame(ordinary, entity);
        for (var type : List.of(ordinary, entity)) {
            var pipeline = type.pipeline();
            var blend = pipeline.getColorTargetStates().getFirst().blendFunction().orElseThrow();
            assertEquals(BlendFactor.SRC_ALPHA, blend.color().sourceFactor());
            assertEquals(BlendFactor.ONE, blend.color().destFactor());
            assertEquals(BlendFactor.ONE, blend.alpha().sourceFactor());
            assertEquals(BlendFactor.ZERO, blend.alpha().destFactor());
            assertEquals(BlendOp.ADD, blend.color().op());
            assertEquals(BlendOp.ADD, blend.alpha().op());
            assertEquals(CompareOp.GREATER_THAN_OR_EQUAL, pipeline.getDepthStencilState().depthTest());
            assertTrue(pipeline.getDepthStencilState().writeDepth());
            assertFalse(pipeline.isCull());
            assertTrue(type.outline().isEmpty());
            assertTrue(type.sortOnUpload());
            assertTrue(type.toString().contains("view_offset_z_layering"));
            assertTrue(type.toString().contains(BeamRenderer.LASER_BEAM_TEXTURE.toString()));
            BindGroupLayout.ensureCompatible(pipeline.getBindGroupLayouts());
        }
        assertSame(DefaultVertexFormat.POSITION_TEX_COLOR, ordinary.format());
        assertEquals(Identifier.withDefaultNamespace("core/position_tex_color"), ordinary.pipeline().getShaders().get(ShaderType.VERTEX));
        assertEquals(Identifier.fromNamespaceAndPath("tacz", "core/laser_beam"), ordinary.pipeline().getShaders().get(ShaderType.FRAGMENT));
        assertSame(DefaultVertexFormat.ENTITY, entity.format());
        assertEquals(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE.getShaders(), entity.pipeline().getShaders());
        assertEquals(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE.getShaderDefines(), entity.pipeline().getShaderDefines());
    }

    @Test
    void bothBeamTypesKeepAllNativeOitAttachmentsAndDepthContracts() {
        for (OitPipelineSet set : List.of(BeamRenderer.LaserBeamRenderState.LASER_BEAM_OIT,
                BeamRenderer.LaserBeamRenderState.LASER_BEAM_ENTITY_OIT)) {
            for (OitStage stage : List.of(OitStage.DEPTH_BOUNDS, OitStage.TRANSMITTANCE, OitStage.ACCUMULATE)) {
                RenderPipeline pipeline = set.getPipeline(stage);
                RenderPipeline nativeStage = RenderPipelines.OIT_ENTITY_EMISSIVE.getPipeline(stage);
                assertTrue(pipeline.getShaderDefines().flags().contains("OIT_ADDITIVE"));
                assertEquals(nativeStage.getColorTargetStates(), pipeline.getColorTargetStates());
                assertEquals(nativeStage.getDepthStencilState(), pipeline.getDepthStencilState());
                BindGroupLayout.ensureCompatible(pipeline.getBindGroupLayouts());
            }
        }
    }

    @Test
    void ordinaryVertexLayoutIgnoresUnusedEntityAttributesAndKeepsFade() {
        try (var storage = new ByteBufferBuilder(1024)) {
            var builder = new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            BeamRenderer.stringVertex(-10, 0.25F, builder, new PoseStack().last(), 17, 34, 51, true);
            try (var mesh = builder.buildOrThrow()) {
                assertEquals(16, mesh.drawState().vertexCount());
                assertSame(DefaultVertexFormat.POSITION_TEX_COLOR, mesh.drawState().format());
                var vertices = mesh.vertexBuffer().order(ByteOrder.nativeOrder());
                assertEquals(16 * 24, vertices.remaining());
                assertEquals(-0.125F, vertices.getFloat(0));
                assertEquals(17, Byte.toUnsignedInt(vertices.get(20)));
                assertEquals(34, Byte.toUnsignedInt(vertices.get(21)));
                assertEquals(51, Byte.toUnsignedInt(vertices.get(22)));
                assertEquals(255, Byte.toUnsignedInt(vertices.get(23)));
                assertEquals(0, Byte.toUnsignedInt(vertices.get(2 * 24 + 23)));
            }
        }
    }
}
