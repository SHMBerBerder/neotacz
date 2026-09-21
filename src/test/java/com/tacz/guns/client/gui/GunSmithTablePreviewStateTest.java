package com.tacz.guns.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GunSmithTablePreviewStateTest {
    @Test
    void rotationKeepsTheLegacyEightSecondPeriod() {
        assertEquals(0.0F, GunSmithTablePreviewState.rotationDegrees(0));
        assertEquals(90.0F, GunSmithTablePreviewState.rotationDegrees(2000));
        assertEquals(180.0F, GunSmithTablePreviewState.rotationDegrees(4000));
        assertEquals(270.0F, GunSmithTablePreviewState.rotationDegrees(6000));
        assertEquals(0.0F, GunSmithTablePreviewState.rotationDegrees(8000));
        assertEquals(90.0F, GunSmithTablePreviewState.rotationDegrees(10000));
    }

    @Test
    void frameOwnsItsPoseAndBoundsRespectTheOuterClip() {
        Matrix3x2f pose = new Matrix3x2f().translation(10, 20);
        GunSmithTablePreviewState state = state(pose, new ScreenRectangle(20, 40, 50, 60));
        pose.identity();

        assertEquals(new Matrix3x2f().translation(10, 20), state.pose());
        assertEquals(new ScreenRectangle(20, 40, 50, 60), state.bounds());
        assertEquals(new ScreenRectangle(13, 36, 128, 99), state(new Matrix3x2f().translation(10, 20), null).bounds());
        assertNull(state(new Matrix3x2f(), new ScreenRectangle(500, 500, 10, 10)).bounds());
    }

    private static GunSmithTablePreviewState state(Matrix3x2f pose, ScreenRectangle clip) {
        return new GunSmithTablePreviewState(new ItemStackRenderState(),
                3, 16, 131, 115, 68, 58, 70, 15, 0, pose, clip);
    }
}
