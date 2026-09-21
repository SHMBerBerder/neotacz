package com.tacz.guns.client.model.functional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextShowRenderTest {
    @Test
    void textSubmissionMakesConfiguredRgbColorsOpaqueWithoutChangingRgb() {
        assertEquals(0xFFFFFFFF, TextShowRender.opaqueTextColor(0xFFFFFF));
        assertEquals(0xFF000000, TextShowRender.opaqueTextColor(0x000000));
        assertEquals(0xFF2A7FC1, TextShowRender.opaqueTextColor(0x2A7FC1));
    }
}
