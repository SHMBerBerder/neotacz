package com.tacz.guns.config.client;

import com.tacz.guns.client.model.gltf.quality.GltfRenderQuality;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Objects;
import java.util.List;

public final class VideoConfig {
    public enum Preset {
        ORIGINAL(8192, 0, 100), HIGH(4096, 0, 75), BALANCED(2048, 1, 50),
        LOW(1024, 2, 30), SMOOTH(512, 3, 15), CUSTOM(2048, 1, 50);

        private final int size;
        private final int level;
        private final int percent;

        Preset(int size, int level, int percent) {
            this.size = size;
            this.level = level;
            this.percent = percent;
        }

        public static List<Preset> displayOrder() {
            return List.of(SMOOTH, LOW, BALANCED, HIGH, ORIGINAL, CUSTOM);
        }
    }

    public record Settings(Preset preset, int textureMaxSize, int lodLevel, int trianglePercent) {
        public Settings {
            Objects.requireNonNull(preset, "preset");
            new GltfRenderQuality(textureMaxSize, lodLevel, trianglePercent / 100f);
        }

        public GltfRenderQuality quality() {
            Settings effective = preset == Preset.CUSTOM ? this : presetSettings(preset);
            return new GltfRenderQuality(effective.textureMaxSize, effective.lodLevel,
                    effective.trianglePercent / 100f);
        }

        public Settings selectPreset(Preset next) {
            Settings effective = preset == Preset.CUSTOM ? this : presetSettings(preset);
            return next == Preset.CUSTOM
                    ? new Settings(next, effective.textureMaxSize, effective.lodLevel, effective.trianglePercent)
                    : presetSettings(next);
        }

        public Settings customize(int textureMaxSize, int lodLevel, int trianglePercent) {
            // Presets are fixed; entering Custom is an explicit choice, not a slider side effect.
            return preset == Preset.CUSTOM ? new Settings(preset, textureMaxSize, lodLevel, trianglePercent) : this;
        }
    }

    public static final Settings DEFAULT = presetSettings(Preset.BALANCED);
    public static ModConfigSpec.EnumValue<Preset> PRESET;
    public static ModConfigSpec.ConfigValue<Integer> TEXTURE_MAX_SIZE;
    public static ModConfigSpec.IntValue LOD_LEVEL;
    public static ModConfigSpec.IntValue TRIANGLE_PERCENT;

    private VideoConfig() { }

    public static void init(ModConfigSpec.Builder builder) {
        builder.push("video");
        PRESET = builder.comment("Mesh render quality. CUSTOM uses the three values below. Applies without restart.")
                .defineEnum("Preset", DEFAULT.preset());
        TEXTURE_MAX_SIZE = builder.comment("Maximum resident texture dimension: 512, 1024, 2048, 4096 or 8192.")
                .define("TextureMaxSize", DEFAULT.textureMaxSize(), value -> value instanceof Integer size
                        && size >= 512 && size <= 8192 && Integer.bitCount(size) == 1);
        LOD_LEVEL = builder.comment("Preferred authored MSFT_lod level; clamped to available levels.")
                .defineInRange("LodLevel", DEFAULT.lodLevel(), 0, 4);
        TRIANGLE_PERCENT = builder.comment("Target percentage for rigid meshes without authored LOD.",
                        "Topology and a 0.1% normalized geometry/attribute error limit may retain more triangles. Skin/morph are preserved.")
                .defineInRange("TrianglePercent", DEFAULT.trianglePercent(), 10, 100);
        builder.pop();
    }

    public static Settings presetSettings(Preset preset) {
        Objects.requireNonNull(preset, "preset");
        return new Settings(preset, preset.size, preset.level, preset.percent);
    }

    public static synchronized Settings settings() {
        return new Settings(PRESET.get(), TEXTURE_MAX_SIZE.get(), LOD_LEVEL.get(), TRIANGLE_PERCENT.get());
    }

    public static synchronized void set(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        PRESET.set(settings.preset());
        TEXTURE_MAX_SIZE.set(settings.textureMaxSize());
        LOD_LEVEL.set(settings.lodLevel());
        TRIANGLE_PERCENT.set(settings.trianglePercent());
    }
}
