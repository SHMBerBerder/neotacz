package com.tacz.guns.client.renderer.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.client.gui.GunSmithTablePreviewState;
import com.tacz.guns.util.RenderDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

public final class GunSmithTablePreviewRenderer extends PictureInPictureRenderer<GunSmithTablePreviewState> {
    @Override
    public Class<GunSmithTablePreviewState> getRenderStateClass() {
        return GunSmithTablePreviewState.class;
    }

    @Override
    protected void renderToTexture(GunSmithTablePreviewState state, PoseStack poseStack, SubmitNodeCollector collector) {
        applyPreviewTransform(state, poseStack);
        // Submission, not extraction, decides Bedrock LOD in the retained item-rendering path.
        RenderDistance.markGuiRenderTimestamp();
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT);
        state.itemRenderState().submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
    }

    static void applyPreviewTransform(GunSmithTablePreviewState state, PoseStack poseStack) {
        // PiP already flips Z. Keep the legacy workbench's net (X, -Y, Z) basis and off-center anchor.
        poseStack.scale(1.0F, -1.0F, -1.0F);
        float middleX = (state.x0() + state.x1()) / 2.0F;
        float middleY = (state.y0() + state.y1()) / 2.0F;
        poseStack.translate((state.centerX() - middleX) / state.scale(),
                (middleY - state.centerY()) / state.scale(), 0.0F);
        poseStack.rotateDegrees(Axis.XP, state.pitchDegrees());
        poseStack.rotateDegrees(Axis.YP, state.yawDegrees());
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected String getTextureLabel() {
        return "tacz workbench preview";
    }
}
