package com.tacz.guns.client.gui;

import com.tacz.guns.client.resource.ClientVideoSettings;
import com.tacz.guns.config.client.VideoConfig;
import com.tacz.guns.util.MinecraftGuiCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public final class NeoTaczVideoSettingsScreen extends Screen {
    private static final int TEXT = 0xFFF0F2F0;
    private static final int MUTED = 0xFFB5BDB8;
    private static final int ACCENT = 0xFF89D9B5;
    private static final int LINE = 0xFF454D48;
    private static final int ROW_HEIGHT = 36;
    private final Screen parent;
    private final VideoConfig.Settings initialSettings;
    private VideoConfig.Settings draft;
    private final List<PresetButton> presetButtons = new ArrayList<>();
    private NumericSlider textureSlider;
    private NumericSlider lodSlider;
    private NumericSlider triangleSlider;
    private int left;
    private int top;
    private int contentWidth;

    public NeoTaczVideoSettingsScreen(Screen parent) {
        super(Component.translatable("gui.tacz.video.title"));
        this.parent = parent;
        VideoConfig.Settings snapshot = VideoConfig.settings();
        this.initialSettings = snapshot.preset() == VideoConfig.Preset.CUSTOM
                ? snapshot : VideoConfig.presetSettings(snapshot.preset());
        this.draft = this.initialSettings;
    }

    @Override
    protected void init() {
        this.contentWidth = Math.min(620, this.width - 32);
        this.left = (this.width - this.contentWidth) / 2;
        this.top = Math.max(8, (this.height - 224) / 2);
        this.presetButtons.clear();
        List<VideoConfig.Preset> presets = VideoConfig.Preset.displayOrder();
        for (int i = 0; i < presets.size(); i++) {
            int x = this.left + i * this.contentWidth / presets.size();
            int nextX = this.left + (i + 1) * this.contentWidth / presets.size();
            PresetButton button = new PresetButton(x, this.top + 44, nextX - x - 3, presets.get(i));
            this.presetButtons.add(this.addRenderableWidget(button));
        }
        int sliderWidth = Math.min(180, this.contentWidth / 2 - 8);
        int sliderX = this.left + this.contentWidth - sliderWidth;
        this.textureSlider = this.addRenderableWidget(new NumericSlider(sliderX, rowY(0), sliderWidth,
                "texture_size", 0, 4, textureStep(this.draft.textureMaxSize()),
                value -> Component.literal(value == 0 ? "512 px" : (1 << (value - 1)) + "K"),
                value -> this.customize(512 << value, this.draft.lodLevel(), this.draft.trianglePercent())));
        this.lodSlider = this.addRenderableWidget(new NumericSlider(sliderX, rowY(1), sliderWidth,
                "lod_level", 0, 4, this.draft.lodLevel(),
                value -> Component.translatable("gui.tacz.video.level_value", value),
                value -> this.customize(this.draft.textureMaxSize(), value, this.draft.trianglePercent())));
        this.triangleSlider = this.addRenderableWidget(new NumericSlider(sliderX, rowY(2), sliderWidth,
                "triangles", 10, 100, this.draft.trianglePercent(),
                value -> Component.literal(value + "%"),
                value -> this.customize(this.draft.textureMaxSize(), this.draft.lodLevel(), value)));
        int footerY = this.top + 204;
        this.addRenderableWidget(new FlatButton(this.left, footerY, 92, 20,
                Component.translatable("gui.tacz.video.reset"), button -> {
                    this.draft = VideoConfig.DEFAULT;
                    this.refreshControls();
                }, false));
        this.addRenderableWidget(new FlatButton(this.left + this.contentWidth - 150, footerY, 70, 20,
                CommonComponents.GUI_CANCEL, button -> this.onClose(), false));
        this.addRenderableWidget(new FlatButton(this.left + this.contentWidth - 74, footerY, 74, 20,
                CommonComponents.GUI_DONE,
                button -> ClientVideoSettings.applyWithLoading(this.initialSettings, this.draft, this.parent), true));
        this.refreshControls();
    }

    private int rowY(int index) {
        return this.top + 84 + index * ROW_HEIGHT;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE8151816);
        graphics.text(this.font, this.title, this.left, this.top + 3, TEXT, false);
        graphics.fill(this.left, this.top + 21, this.left + this.contentWidth, this.top + 22, LINE);
        graphics.text(this.font, Component.translatable("gui.tacz.video.preset"),
                this.left, this.top + 30, MUTED, false);
        drawSetting(graphics, 0, "texture_size");
        drawSetting(graphics, 1, "lod_level");
        drawSetting(graphics, 2, "triangles");
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSetting(GuiGraphicsExtractor graphics, int index, String key) {
        int y = rowY(index);
        graphics.text(this.font, Component.translatable("gui.tacz.video." + key),
                this.left, y + 3, TEXT, false);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(this.left, y + 22).scale(0.85f);
            graphics.textWithWordWrap(this.font, Component.translatable("gui.tacz.video." + key + ".detail"),
                    0, 0, (int) (this.contentWidth / 0.85f), MUTED, false);
        } finally {
            graphics.pose().popMatrix();
        }
        if (index < 2) {
            graphics.fill(this.left, y + ROW_HEIGHT - 2, this.left + this.contentWidth,
                    y + ROW_HEIGHT - 1, 0x80454D48);
        }
    }

    @Override
    public void removed() {
        this.minecraft.options.save();
    }

    @Override
    public void onClose() {
        // Cancel and Escape discard the draft; only Done reaches the canonical loading path.
        MinecraftGuiCompat.setScreen(this.parent);
    }

    private void customize(int textureMaxSize, int lodLevel, int trianglePercent) {
        this.draft = this.draft.customize(textureMaxSize, lodLevel, trianglePercent);
        this.refreshControls();
    }

    private void refreshControls() {
        this.textureSlider.setSelectedValue(textureStep(this.draft.textureMaxSize()));
        this.lodSlider.setSelectedValue(this.draft.lodLevel());
        this.triangleSlider.setSelectedValue(this.draft.trianglePercent());
        boolean custom = this.draft.preset() == VideoConfig.Preset.CUSTOM;
        this.textureSlider.active = custom;
        this.lodSlider.active = custom;
        this.triangleSlider.active = custom;
        for (PresetButton button : this.presetButtons) {
            button.selected = button.preset == this.draft.preset();
        }
    }

    private static Component presetName(VideoConfig.Preset preset) {
        return Component.translatable("gui.tacz.video.preset." + preset.name().toLowerCase(Locale.ROOT));
    }

    private static int textureStep(int size) {
        return Integer.numberOfTrailingZeros(size) - 9;
    }

    private class FlatButton extends Button {
        protected boolean selected;

        private FlatButton(int x, int y, int width, int height, Component label, OnPress onPress, boolean selected) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            boolean highlighted = this.isHoveredOrFocused();
            graphics.fill(x, y, x + this.width, y + this.height,
                    this.selected ? 0xFF304B3E : highlighted ? 0xFF3A423D : 0xFF292F2B);
            if (this.selected) graphics.fill(x, y + this.height - 2, x + this.width, y + this.height, ACCENT);
            if (this.isFocused()) {
                graphics.outline(x, y, this.width, this.height, TEXT);
            }
            List<FormattedCharSequence> lines = font.split(this.getMessage(), this.width - 6);
            int lineY = y + (this.height - lines.size() * font.lineHeight) / 2;
            for (FormattedCharSequence line : lines) {
                graphics.text(font, line, x + (this.width - font.width(line)) / 2, lineY, TEXT, false);
                lineY += font.lineHeight;
            }
        }
    }

    private final class PresetButton extends FlatButton {
        private final VideoConfig.Preset preset;

        private PresetButton(int x, int y, int width, VideoConfig.Preset preset) {
            super(x, y, width, 28, presetName(preset), button -> {
                draft = draft.selectPreset(preset);
                refreshControls();
            }, false);
            this.preset = preset;
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return this.selected
                    ? Component.translatable("gui.tacz.video.preset.selected", this.getMessage())
                    : super.createNarrationMessage();
        }
    }

    private final class NumericSlider extends AbstractSliderButton {
        private final String labelKey;
        private final int minimum;
        private final int maximum;
        private final IntFunction<Component> valueLabel;
        private final IntConsumer onChange;
        private int selectedValue;

        private NumericSlider(int x, int y, int width, String labelKey, int minimum, int maximum, int initialValue,
                              IntFunction<Component> valueLabel, IntConsumer onChange) {
            super(x, y, width, 19, CommonComponents.EMPTY, (double) (initialValue - minimum) / (maximum - minimum));
            this.labelKey = labelKey;
            this.minimum = minimum;
            this.maximum = maximum;
            this.selectedValue = initialValue;
            this.valueLabel = valueLabel;
            this.onChange = onChange;
            this.updateMessage();
        }

        private void setSelectedValue(int value) {
            this.selectedValue = value;
            this.value = (double) (value - this.minimum) / (this.maximum - this.minimum);
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("gui.tacz.video." + this.labelKey)
                    .append(": ").append(this.valueLabel.apply(this.selectedValue)));
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            Component label = this.valueLabel.apply(this.selectedValue);
            graphics.text(font, label, x + this.width - font.width(label), y + 2, this.active ? ACCENT : TEXT, false);
            graphics.fill(x + 4, y + 15, x + this.width - 4, y + 17, LINE);
            int handleX = x + (int) Math.round(this.value * (this.width - 8));
            graphics.fill(x + 4, y + 15, handleX + 4, y + 17, this.active ? ACCENT : MUTED);
            if (this.active) {
                graphics.fill(handleX, y + 13, handleX + 8, y + 19, ACCENT);
                if (this.isFocused()) graphics.outline(x - 2, y, this.width + 4, this.height + 2, TEXT);
            }
            this.handleCursor(graphics);
        }

        @Override
        protected void applyValue() {
            if (!this.active) {
                this.setSelectedValue(this.selectedValue);
                return;
            }
            int selected = this.minimum + (int) Math.round(this.value * (this.maximum - this.minimum));
            boolean changed = selected != this.selectedValue;
            this.setSelectedValue(selected);
            if (changed) this.onChange.accept(selected);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            // Native sliders accept arrow events even when disabled, so preserve this input guard.
            if (!this.active) return false;
            if (this.canChangeValue && (event.isLeft() || event.isRight())) {
                int direction = event.isLeft() ? -1 : 1;
                this.setValue(this.value + (double) direction / (this.maximum - this.minimum));
                return true;
            }
            return super.keyPressed(event);
        }
    }
}
