package com.tacz.guns.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeRenderExtrasTest {
    @Test
    void geometryKeepsItsPoseAndCollectorContextUntilTheScopeGroupBuilds() {
        int[] fallback = {0};
        SubmitNodeCollector collector = collector(fallback);
        ScopeRenderExtras extras = new ScopeRenderExtras(collector);
        PoseStack pose = new PoseStack();
        pose.translate(1, 2, 3);
        List<Float> rendered = new ArrayList<>();
        RenderHelper.withSubmitNodeCollector(collector, () -> extras.capture(() -> {
            RenderHelper.submitCustomGeometry(collector, pose, null, (saved, buffer) -> {
                assertSame(collector, RenderHelper.currentSubmitNodeCollector());
                assertTrue(RenderHelper.isInsideSubmitCustomGeometryCallback());
                rendered.add(saved.pose().m30());
            });
            pose.translate(4, 0, 0);
            // The actual non-optical glTF route has this static collector type.
            RenderHelper.submitCustomGeometry((OrderedSubmitNodeCollector) collector, pose, null,
                    (saved, buffer) -> rendered.add(saved.pose().m30()));
        }));
        pose.translate(50, 0, 0);
        assertEquals(0, fallback[0]);
        assertTrue(rendered.isEmpty());
        extras.emit(null, type -> RenderHelper.noopVertexConsumer());
        assertEquals(List.of(1F, 5F), rendered);
        assertNull(RenderHelper.currentSubmitNodeCollector());
        assertFalse(RenderHelper.isInsideSubmitCustomGeometryCallback());
    }

    @Test
    void nestedCapturesAndUncapturedGunIntervalsRestoreTheirExactOwnerEvenOnFailure() {
        int[] fallback = {0};
        SubmitNodeCollector collector = collector(fallback);
        SubmitNodeCollector other = collector(fallback);
        ScopeRenderExtras outer = new ScopeRenderExtras(collector);
        ScopeRenderExtras inner = new ScopeRenderExtras(collector);
        List<String> draws = new ArrayList<>();
        RuntimeException failure = new RuntimeException("delegate failure");
        RenderHelper.withSubmitNodeCollector(collector, () -> outer.capture(() -> {
            assertSame(failure, assertThrows(RuntimeException.class, () -> inner.capture(() -> {
                RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> draws.add("inner"));
                throw failure;
            })));
            ScopeRenderExtras.withoutCapture(() ->
                    RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> fail()));
            RenderHelper.submitCustomGeometry(other, new PoseStack(), null, (p, b) -> fail());
            RenderHelper.withSubmitNodeCollector(other, () ->
                    RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> fail()));
            RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> draws.add("outer"));
        }));
        assertEquals(3, fallback[0]);
        outer.emit(null, type -> RenderHelper.noopVertexConsumer());
        assertEquals(List.of("outer"), draws);
        assertThrows(IllegalStateException.class, () -> outer.capture(() -> fail()));
        RenderHelper.submitCustomGeometry(collector, new PoseStack(), null, (p, b) -> fail());
        assertEquals(4, fallback[0]);
        assertNull(RenderHelper.currentSubmitNodeCollector());
    }

    @Test
    void textUsesNativePreparedTextGlyphAndEffectFlowWithOriginalLayoutAndLight() {
        SubmitNodeCollector collector = collector(new int[1]);
        ScopeRenderExtras extras = new ScopeRenderExtras(collector);
        PoseStack pose = new PoseStack();
        pose.translate(3, 4, 5);
        FormattedCharSequence text = FormattedCharSequence.forward("scope", Style.EMPTY);
        RenderHelper.withSubmitNodeCollector(collector, () -> extras.capture(() ->
                assertTrue(ScopeRenderExtras.captureText(collector, pose, -7, -4.5F, text,
                        true, Font.DisplayMode.NORMAL, 0x123456, 0xFF345678))));
        pose.translate(20, 0, 0);
        int[] rendered = {0};
        int[] buffers = {0};
        Font font = new Font(null) {
            @Override
            public PreparedText prepareText(FormattedCharSequence actual, float x, float y, int color,
                                            boolean shadow, boolean empty, int background) {
                assertSame(text, actual);
                assertEquals(-7F, x);
                assertEquals(-4.5F, y);
                assertEquals(0xFF345678, color);
                assertTrue(shadow);
                assertFalse(empty);
                assertEquals(0, background);
                return new PreparedText() {
                    @Override
                    public void visit(GlyphVisitor visitor) {
                        visitor.acceptGlyph((TextRenderable.Styled) renderable(TextRenderable.Styled.class, rendered));
                        visitor.acceptEffect(renderable(TextRenderable.class, rendered));
                    }

                    @Override
                    public net.minecraft.client.gui.navigation.ScreenRectangle bounds() { return null; }
                };
            }
        };
        extras.emit(font, type -> {
            buffers[0]++;
            return RenderHelper.noopVertexConsumer();
        });
        assertEquals(2, rendered[0]);
        assertEquals(1, buffers[0]);
    }

    private static TextRenderable renderable(Class<?> type, int[] rendered) {
        return renderable(type, rendered, null, false);
    }

    @Test
    void alternatingFontPagesNeverReuseAFinishedNativeStagedBuilder() {
        SubmitNodeCollector collector = collector(new int[1]);
        ScopeRenderExtras extras = new ScopeRenderExtras(collector);
        PoseStack pose = new PoseStack();
        pose.translate(3, 4, 5);
        RenderHelper.withSubmitNodeCollector(collector, () -> extras.capture(() ->
                ScopeRenderExtras.captureText(collector, pose, 0, 0,
                        FormattedCharSequence.forward("ABA", Style.EMPTY), false,
                        Font.DisplayMode.NORMAL, 0x123456, -1)));
        RenderType first = RenderTypes.entityCutout(Identifier.parse("tacz:texture_page_a"));
        RenderType second = RenderTypes.entityCutout(Identifier.parse("tacz:texture_page_b"));
        int[] rendered = {0};
        Font font = new Font(null) {
            @Override
            public PreparedText prepareText(FormattedCharSequence text, float x, float y, int color,
                                            boolean shadow, boolean empty, int background) {
                return new PreparedText() {
                    @Override
                    public void visit(GlyphVisitor visitor) {
                        for (RenderType type : List.of(first, second, first)) {
                            visitor.acceptGlyph((TextRenderable.Styled) renderable(TextRenderable.Styled.class, rendered, type, true));
                        }
                    }

                    @Override
                    public net.minecraft.client.gui.navigation.ScreenRectangle bounds() { return null; }
                };
            }
        };
        try (StagedVertexBuffer staging = new StagedVertexBuffer(() -> "Scope text test", 4096)) {
            List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
            List<RenderType> types = new ArrayList<>();
            extras.emit(font, type -> {
                types.add(type);
                var draw = staging.appendDraw(DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES);
                draws.add(draw);
                return staging.getVertexBuilder(draw);
            });
            staging.getVertexBuilder(staging.appendDraw(DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES));
            assertEquals(List.of(first, second, first), types);
            assertEquals(3, rendered[0]);
            assertTrue(draws.stream().noneMatch(StagedVertexBuffer.Draw::isEmpty));
        }
    }

    private static TextRenderable renderable(Class<?> type, int[] rendered, RenderType renderType, boolean emitVertices) {
        return (TextRenderable) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if (method.getName().equals("renderType")) {
                assertEquals(Font.DisplayMode.NORMAL, args[0]);
                return renderType;
            }
            if (method.getName().equals("render")) {
                assertEquals(3F, ((Matrix4fc) args[0]).m30());
                assertEquals(0x123456, args[2]);
                assertEquals(false, args[3]);
                if (emitVertices) {
                    VertexConsumer buffer = (VertexConsumer) args[1];
                    buffer.addVertex(0, 0, 0);
                    buffer.addVertex(1, 0, 0);
                    buffer.addVertex(0, 1, 0);
                }
                rendered[0]++;
                return null;
            }
            throw new AssertionError(method.getName());
        });
    }

    private static SubmitNodeCollector collector(int[] fallback) {
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry")) {
                        fallback[0]++;
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
    }
}
