package com.tacz.guns.client.renderer.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.gui.GunSmithTablePreviewState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.joml.Matrix3f;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GunSmithTablePreviewRendererTest {
    @Test
    void nativePipBaseAndPreviewTransformMatchLegacyWorkbenchAtEveryScaleAndQuarterTurn() {
        for (int guiScale : new int[]{1, 2, 3}) {
            for (int zoom : new int[]{10, 70, 200}) {
                for (int yaw : new int[]{0, 90, 180, 270}) {
                    GunSmithTablePreviewState state = new GunSmithTablePreviewState(
                            new ItemStackRenderState(), 103, 66, 231, 165, 168, 108,
                            zoom, 15, yaw, new Matrix3x2f(), null);
                    PoseStack pose = new PoseStack();
                    int width = (state.x1() - state.x0()) * guiScale;
                    int height = (state.y1() - state.y0()) * guiScale;
                    float pixelScale = guiScale * state.scale();
                    // Reproduce the native PictureInPictureRenderer.prepare prefix, not only our helper.
                    pose.translate(width / 2.0F, height / 2.0F, 0.0F);
                    pose.scale(pixelScale, pixelScale, -pixelScale);
                    GunSmithTablePreviewRenderer.applyPreviewTransform(state, pose);

                    float pitchRadians = (float) Math.toRadians(15);
                    float yawRadians = (float) Math.toRadians(yaw);
                    Matrix4f legacy = new Matrix4f()
                            .translation((168 - 103) * guiScale, (108 - 66) * guiScale, 0)
                            .scale(pixelScale, -pixelScale, pixelScale)
                            .rotateX(pitchRadians).rotateY(yawRadians);
                    Matrix3f legacyNormal = new Matrix3f().scaling(1, -1, 1)
                            .rotateX(pitchRadians).rotateY(yawRadians);
                    String context = "guiScale=" + guiScale + ", zoom=" + zoom + ", yaw=" + yaw;
                    assertTrue(legacy.equals(pose.last().pose(), 0.001F), context);
                    assertTrue(legacyNormal.equals(pose.last().normal(), 0.00001F), context);
                }
            }
        }
    }
}
