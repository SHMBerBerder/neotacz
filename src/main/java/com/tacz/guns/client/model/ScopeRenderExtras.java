package com.tacz.guns.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;

/** The gun's functional delegate interval, replayed before its scope stencil is cleared. */
public final class ScopeRenderExtras {
    private static final ThreadLocal<ScopeRenderExtras> CURRENT = new ThreadLocal<>();
    private final SubmitNodeCollector owner;
    private final List<Command> commands = new ArrayList<>();
    private boolean captured;
    private final Consumer<Runnable> frameState;

    ScopeRenderExtras(SubmitNodeCollector owner) {
        this(owner, Runnable::run);
    }

    ScopeRenderExtras(SubmitNodeCollector owner, Consumer<Runnable> frameState) {
        this.owner = owner;
        this.frameState = frameState;
    }

    void withFrameState(Runnable action) {
        frameState.accept(action);
    }

    void capture(Runnable action) {
        if (captured) throw new IllegalStateException("Scope extras already captured");
        captured = true;
        withCapture(this, action);
    }

    static void withoutCapture(Runnable action) {
        withCapture(null, action);
    }

    private static void withCapture(ScopeRenderExtras extras, Runnable action) {
        ScopeRenderExtras previous = CURRENT.get();
        if (extras == null) CURRENT.remove();
        else CURRENT.set(extras);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    private static ScopeRenderExtras current(OrderedSubmitNodeCollector collector) {
        ScopeRenderExtras extras = CURRENT.get();
        return extras != null && extras.owner == collector
                && RenderHelper.currentSubmitNodeCollector() == extras.owner ? extras : null;
    }

    public static boolean captureGeometry(OrderedSubmitNodeCollector collector, PoseStack pose,
                                          RenderType renderType,
                                          BiConsumer<PoseStack.Pose, VertexConsumer> renderer) {
        ScopeRenderExtras extras = current(collector);
        if (extras == null) return false;
        PoseStack copy = new PoseStack();
        copy.last().pose().set(pose.last().pose());
        copy.last().normal().set(pose.last().normal());
        extras.commands.add((font, buffers) -> renderer.accept(copy.last(), buffers.apply(renderType)));
        return true;
    }

    /** TextShow only: its background and outline colors are both zero. */
    public static boolean captureText(SubmitNodeCollector collector, PoseStack pose, float x, float y,
                                      FormattedCharSequence text, boolean shadow, Font.DisplayMode displayMode,
                                      int light, int color) {
        ScopeRenderExtras extras = current(collector);
        if (extras == null) return false;
        Matrix4f matrix = new Matrix4f(pose.last().pose());
        extras.commands.add((font, buffers) -> {
            font.prepareText(text, x, y, color, shadow, false, 0).visit(new Font.GlyphVisitor() {
                private RenderType currentType;
                private VertexConsumer currentBuffer;

                @Override
                public void acceptRenderable(TextRenderable renderable) {
                    RenderType type = renderable.renderType(displayMode);
                    // Switching staged draws finishes the previous BufferBuilder permanently.
                    if (currentBuffer == null || currentType != type) {
                        currentType = type;
                        currentBuffer = buffers.apply(type);
                    }
                    renderable.render(matrix, currentBuffer, light, false);
                }
            });
        });
        return true;
    }

    void emit(Font font, Function<RenderType, VertexConsumer> buffers) {
        // Emission is geometry-only and cannot capture commands for another gun or frame.
        withoutCapture(() -> commands.forEach(command -> command.emit(font, buffers)));
    }

    @FunctionalInterface
    private interface Command {
        void emit(Font font, Function<RenderType, VertexConsumer> buffers);
    }
}
