package com.tacz.guns.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public record GunSmithTablePreviewState(
        ItemStackRenderState itemRenderState,
        int x0, int y0, int x1, int y1,
        float centerX, float centerY, float scale,
        float pitchDegrees, float yawDegrees,
        Matrix3x2fc pose, @Nullable ScreenRectangle scissorArea
) implements PictureInPictureRenderState {
    public GunSmithTablePreviewState {
        pose = new Matrix3x2f(pose);
    }

    public static float rotationDegrees(long timeMillis) {
        return timeMillis % 8000L * (360.0F / 8000.0F);
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, pose, scissorArea);
    }
}
