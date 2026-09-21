package com.tacz.guns.client.model;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.stencil.StencilOperation;
import net.neoforged.neoforge.client.stencil.StencilPerFaceTest;
import net.neoforged.neoforge.client.stencil.StencilTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ScopeStencilRenderTypesTest {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("test", "scope.png");

    @Test
    void fullscreenClearOnlyZerosStencilWithoutTouchingColorOrDepth() {
        var pipeline = ScopeStencilRenderTypes.clearStencilPipeline();
        assertEquals(PrimitiveTopology.TRIANGLES, pipeline.getPrimitiveTopology());
        assertTrue(pipeline.getVertexFormatBindings().stream().allMatch(java.util.Objects::isNull));
        assertEquals(Identifier.withDefaultNamespace("core/screenquad"), pipeline.getShaders().get(ShaderType.VERTEX));
        assertFalse(pipeline.isCull());
        assertEquals(1, pipeline.getColorTargetStates().size());
        assertEquals(ColorTargetState.WRITE_NONE, pipeline.getColorTargetStates().getFirst().writeMask());
        var depth = pipeline.getDepthStencilState();
        assertNotNull(depth);
        assertEquals(CompareOp.ALWAYS_PASS, depth.depthTest());
        assertFalse(depth.writeDepth());
        var stencil = depth.stencilTest();
        assertNotNull(stencil);
        assertEquals(0xFF, stencil.writeMask());
        assertEquals(0, stencil.referenceValue());
        for (var face : new StencilPerFaceTest[]{stencil.front(), stencil.back()}) {
            assertEquals(CompareOp.ALWAYS_PASS, face.compare());
            assertEquals(StencilOperation.ZERO, face.pass());
        }
    }

    @Test
    void addingStencilPreservesEveryDepthParameter() {
        var depth = new DepthStencilState(CompareOp.LESS_THAN, false, 1.25F, -2.0F);
        var face = new StencilPerFaceTest(StencilOperation.KEEP, StencilOperation.KEEP, StencilOperation.REPLACE, CompareOp.EQUAL);
        var stencil = new StencilTest(face, 0x7F, 0xFF, 17);
        var result = ScopeStencilRenderTypes.withStencil(depth, stencil);
        assertEquals(depth.depthTest(), result.depthTest());
        assertEquals(depth.writeDepth(), result.writeDepth());
        assertEquals(depth.depthBiasScaleFactor(), result.depthBiasScaleFactor());
        assertEquals(depth.depthBiasConstant(), result.depthBiasConstant());
        assertSame(stencil, result.stencilTest());
    }

    @Test
    void opticalReferencePipelinesRetainTheFullNormalAndInvertedRange() {
        for (int reference = 1; reference <= 128; reference++) {
            var ocular = ScopeStencilRenderTypes.ocularWrite(TEXTURE, reference).pipeline();
            assertEquals(ColorTargetState.WRITE_NONE, ocular.getColorTargetStates().getFirst().writeMask());
            assertFalse(ocular.getDepthStencilState().writeDepth());
            assertEquals(reference, ocular.getDepthStencilState().stencilTest().referenceValue());
            assertEquals(StencilOperation.REPLACE, ocular.getDepthStencilState().stencilTest().front().pass());

            var division = ScopeStencilRenderTypes.entityEqualRefNoDepth(TEXTURE, reference).pipeline();
            assertEquals(ColorTargetState.WRITE_ALL, division.getColorTargetStates().getFirst().writeMask());
            assertEquals(reference, division.getDepthStencilState().stencilTest().referenceValue());
            assertEquals(CompareOp.ALWAYS_PASS, division.getDepthStencilState().depthTest());
            assertFalse(division.getDepthStencilState().writeDepth());

            var inverted = ScopeStencilRenderTypes.entityEqualInvertedRefNoDepth(TEXTURE, reference).pipeline();
            assertEquals((~reference) & 0xFF, inverted.getDepthStencilState().stencilTest().referenceValue());
            assertEquals(0, inverted.getDepthStencilState().stencilTest().writeMask());
        }
    }

    @Test
    void coloredGunClippingHasExplicitColorOutputAndRetainsDepthWrites() {
        var pipeline = ScopeStencilRenderTypes.entityEqual0(TEXTURE).pipeline();
        assertEquals(ColorTargetState.DEFAULT, pipeline.getColorTargetStates().getFirst());
        assertEquals(CompareOp.GREATER_THAN_OR_EQUAL, pipeline.getDepthStencilState().depthTest());
        assertTrue(pipeline.getDepthStencilState().writeDepth());
        assertEquals(CompareOp.EQUAL, pipeline.getDepthStencilState().stencilTest().front().compare());
    }

    @Test
    void legacyGunStencilPreservesCustomShadersBindingsBlendAndDepthBias() {
        var original = customPipeline(Optional.of(new DepthStencilState(CompareOp.LESS_THAN, true, 1.25F, -2.0F)));
        var template = ScopeStencilRenderTypes.entityEqual0(TEXTURE).pipeline();
        var result = ScopeStencilRenderTypes.legacyPipeline(original, template, true);
        assertEquals(original.getShaders(), result.getShaders());
        assertEquals(original.getShaderDefines(), result.getShaderDefines());
        assertEquals(uniformDefinitions(original), uniformDefinitions(result));
        assertEquals(original.getVertexFormatBindings(), result.getVertexFormatBindings());
        assertEquals(original.getPrimitiveTopology(), result.getPrimitiveTopology());
        assertEquals(original.getColorTargetStates(), result.getColorTargetStates());
        assertEquals(CompareOp.LESS_THAN, result.getDepthStencilState().depthTest());
        assertTrue(result.getDepthStencilState().writeDepth());
        assertEquals(1.25F, result.getDepthStencilState().depthBiasScaleFactor());
        assertEquals(-2.0F, result.getDepthStencilState().depthBiasConstant());
        assertSame(result, ScopeStencilRenderTypes.legacyPipeline(original, template, true));
        assertNotSame(result, ScopeStencilRenderTypes.legacyPipeline(original.toBuilder().build(), template, true));
    }

    @Test
    void legacyPipelineWithoutDepthDoesNotGainDepthComparisonOrWrites() {
        var original = customPipeline(Optional.empty());
        assertNull(original.getDepthStencilState());
        var result = ScopeStencilRenderTypes.legacyPipeline(original, ScopeStencilRenderTypes.entityEqual0(TEXTURE).pipeline(), true);
        assertEquals(CompareOp.ALWAYS_PASS, result.getDepthStencilState().depthTest());
        assertFalse(result.getDepthStencilState().writeDepth());
        assertEquals(original.getColorTargetStates(), result.getColorTargetStates());
    }

    @Test
    void legacyOcularDisablesColorWritesWithoutChangingTheCallersBindings() {
        var original = customPipeline(Optional.of(DepthStencilState.DEFAULT));
        var scissor = new ScissorState();
        scissor.enable(3, 4, 50, 60);
        var textures = List.<PreparedRenderType.Texture>of();
        var prepared = new PreparedRenderType("custom", original, null, null, scissor, textures);
        var result = ScopeStencilRenderTypes.withLegacyStencil(prepared,
                ScopeStencilRenderTypes.ocularWrite(TEXTURE, 4).pipeline(), false);
        assertSame(scissor, result.scissorState());
        assertSame(textures, result.textures());
        assertSame(prepared.dynamicTransforms(), result.dynamicTransforms());
        assertEquals(original.getShaders(), result.pipeline().getShaders());
        assertEquals(original.getColorTargetStates().getFirst().blendFunction(),
                result.pipeline().getColorTargetStates().getFirst().blendFunction());
        assertEquals(ColorTargetState.WRITE_NONE, result.pipeline().getColorTargetStates().getFirst().writeMask());
        assertFalse(result.pipeline().getDepthStencilState().writeDepth());
    }

    private static RenderPipeline customPipeline(Optional<DepthStencilState> depth) {
        return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("test", "custom_scope"))
                .withVertexShader(Identifier.fromNamespaceAndPath("test", "custom_vertex"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("test", "custom_fragment"))
                .withShaderDefine("CUSTOM_DEFINE", 2)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(depth)
                .build();
    }

    private static Map<String, BindGroupLayout.UniformDescription> uniformDefinitions(RenderPipeline pipeline) {
        BindGroupLayout.ensureCompatible(pipeline.getBindGroupLayouts());
        // RenderPearl assigns GPU bindings by reflected uniform name, not the builder set's iteration order.
        return BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream()
                .collect(Collectors.toMap(BindGroupLayout.UniformDescription::name, Function.identity()));
    }
}
