package com.tacz.guns.client.model.gltf.quality;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.tacz.guns.config.client.VideoConfig;
import net.neoforged.fml.config.LoadedConfigFixture;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GltfRenderQualityTest {
    @Test
    void qualityBoundsRejectUnsupportedOrNonFiniteSettings() {
        assertEquals(2048, new GltfRenderQuality(2048, 1, .5f).textureMaxSize());
        for (int size : new int[]{0, 256, 1000, 16384}) {
            assertThrows(IllegalArgumentException.class, () -> new GltfRenderQuality(size, 0, 1));
        }
        for (float ratio : new float[]{0, .09f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new GltfRenderQuality(2048, 0, ratio));
        }
        assertThrows(IllegalArgumentException.class, () -> new GltfRenderQuality(2048, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GltfRenderQuality(2048, 5, 1));
    }

    @Test
    void presetValuesDisplayOrderAndDefaultStayStable() {
        assertArrayEquals(new VideoConfig.Preset[]{
                VideoConfig.Preset.ORIGINAL, VideoConfig.Preset.HIGH, VideoConfig.Preset.BALANCED,
                VideoConfig.Preset.LOW, VideoConfig.Preset.SMOOTH, VideoConfig.Preset.CUSTOM
        }, VideoConfig.Preset.values());
        assertEquals(List.of(VideoConfig.Preset.SMOOTH, VideoConfig.Preset.LOW, VideoConfig.Preset.BALANCED,
                VideoConfig.Preset.HIGH, VideoConfig.Preset.ORIGINAL, VideoConfig.Preset.CUSTOM),
                VideoConfig.Preset.displayOrder());

        assertPreset(VideoConfig.Preset.SMOOTH, 512, 3, 15);
        assertPreset(VideoConfig.Preset.LOW, 1024, 2, 30);
        assertPreset(VideoConfig.Preset.BALANCED, 2048, 1, 50);
        assertPreset(VideoConfig.Preset.HIGH, 4096, 0, 75);
        assertPreset(VideoConfig.Preset.ORIGINAL, 8192, 0, 100);
        assertPreset(VideoConfig.Preset.CUSTOM, 2048, 1, 50);
        assertEquals(new GltfRenderQuality(2048, 1, .5f), VideoConfig.DEFAULT.quality());
    }

    @Test
    void presetSelectionOnlyLetsCustomCarryManualValues() {
        var dirtyHigh = new VideoConfig.Settings(VideoConfig.Preset.HIGH, 512, 3, 15);

        assertSame(dirtyHigh, dirtyHigh.customize(1024, 2, 30));
        assertEquals(new GltfRenderQuality(4096, 0, .75f), dirtyHigh.quality());
        assertEquals(new VideoConfig.Settings(VideoConfig.Preset.CUSTOM, 4096, 0, 75),
                dirtyHigh.selectPreset(VideoConfig.Preset.CUSTOM));

        var custom = dirtyHigh.selectPreset(VideoConfig.Preset.CUSTOM).customize(1024, 2, 30);
        assertEquals(new VideoConfig.Settings(VideoConfig.Preset.CUSTOM, 1024, 2, 30), custom);
        assertEquals(VideoConfig.presetSettings(VideoConfig.Preset.LOW),
                custom.selectPreset(VideoConfig.Preset.LOW));
    }

    @Test
    void authoredSelectionAndTextureResolutionAreIndependent() {
        var custom = new VideoConfig.Settings(VideoConfig.Preset.CUSTOM, 2048, 0, 100);
        assertEquals(new GltfRenderQuality(2048, 0, 1), custom.quality());
        assertEquals(new GltfRenderQuality(8192, 0, 1),
                VideoConfig.presetSettings(VideoConfig.Preset.ORIGINAL).quality());
        assertEquals(new GltfRenderQuality(512, 3, .15f),
                VideoConfig.presetSettings(VideoConfig.Preset.SMOOTH).quality());
        assertEquals(VideoConfig.Preset.BALANCED, VideoConfig.DEFAULT.preset());
    }

    @Test
    void configRoundTripIsHotAndDoesNotFreezeFirstValue() {
        var builder = new ModConfigSpec.Builder();
        VideoConfig.init(builder);
        var spec = builder.build();
        LoadedConfigFixture.accept(spec, CommentedConfig.inMemory());
        assertEquals(VideoConfig.DEFAULT, VideoConfig.settings());
        var custom = new VideoConfig.Settings(VideoConfig.Preset.CUSTOM, 1024, 2, 33);
        VideoConfig.set(custom);
        assertEquals(custom, VideoConfig.settings());
        assertEquals(new GltfRenderQuality(1024, 2, .33f), VideoConfig.settings().quality());
        VideoConfig.set(VideoConfig.presetSettings(VideoConfig.Preset.ORIGINAL));
        assertEquals(8192, VideoConfig.settings().quality().textureMaxSize());
        assertEquals(ModConfigSpec.RestartType.NONE, VideoConfig.PRESET.getSpec().restartType());
    }

    private static void assertPreset(VideoConfig.Preset preset, int textureMaxSize, int lodLevel, int trianglePercent) {
        var settings = VideoConfig.presetSettings(preset);
        assertEquals(new VideoConfig.Settings(preset, textureMaxSize, lodLevel, trianglePercent), settings);
        assertEquals(new GltfRenderQuality(textureMaxSize, lodLevel, trianglePercent / 100f), settings.quality());
    }
}
