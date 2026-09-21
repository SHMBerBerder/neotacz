package com.tacz.guns.client.tooltip;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.PackInfo;
import com.tacz.guns.inventory.tooltip.AttachmentItemTooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ClientAttachmentItemTooltip implements ClientTooltipComponent {
    private static final int MAX_COLUMNS = 16;
    private static final Cache<Identifier, List<ItemStack>> CACHE = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).build();
    private final Identifier attachmentId;
    private final List<Component> components = Lists.newArrayList();
    private final Component tips = Component.translatable("tooltip.tacz.attachment.yaw.shift");
    private final Component support = Component.translatable("tooltip.tacz.attachment.yaw.support");
    private final @Nullable Component packInfo;
    private List<ItemStack> showGuns = Lists.newArrayList();

    public ClientAttachmentItemTooltip(AttachmentItemTooltip tooltip) {
        this.attachmentId = tooltip.getAttachmentId();
        AttachmentTooltipTextBuilder.appendAttachmentDetails(
                tooltip.getAttachmentItem(), attachmentId, tooltip.getType(), components::add);
        this.getShowGuns();
        PackInfo pack = ClientAssetsManager.INSTANCE.getPackInfo(attachmentId);
        this.packInfo = pack == null ? null : Component.translatable(pack.getName())
                .withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC);
    }

    private static List<ItemStack> getAllAllowGuns(List<ItemStack> output, Identifier attachmentId) {
        ItemStack attachment = AttachmentItemBuilder.create().setId(attachmentId).build();
        TimelessAPI.getAllCommonGunIndex().forEach(entry -> {
            Identifier gunId = entry.getKey();
            ItemStack gun = GunItemBuilder.create().setId(gunId).build();
            if (!(gun.getItem() instanceof IGun iGun)) {
                return;
            }
            if (iGun.allowAttachment(gun, attachment)) {
                output.add(gun);
            }
        });
        return output;
    }

    @Override
    public int getHeight(Font font) {
        return contentHeight(components.size(), showGuns.size(), hasShiftDown());
    }

    @Override
    public int getWidth(Font font) {
        int width = packInfo == null ? 0 : font.width(packInfo) + 4;
        for (Component component : components) {
            width = Math.max(width, font.width(component));
        }
        if (!hasShiftDown()) {
            return Math.max(width, font.width(tips) + 4);
        }
        return Math.max(Math.max(width, font.width(support) + 4), gridWidth(showGuns.size()));
    }

    @Override
    public void extractText(GuiGraphicsExtractor graphics, Font font, int pX, int pY) {
        int yOffset = pY;
        for (Component component : components) {
            graphics.text(font, component, pX, yOffset, 0xFFFFAA00, false);
            yOffset += 10;
        }
        if (hasShiftDown()) {
            yOffset += (gridRows(showGuns.size()) - 1) * 18 + 32;
        } else {
            graphics.text(font, tips, pX, yOffset + 5, 0xFF9E9E9E, false);
            yOffset += 10;
        }
        if (packInfo != null) {
            graphics.text(font, packInfo, pX, yOffset + 8, 0xFFFFFFFF, false);
        }
    }

    @Override
    public void extractImage(Font font, int mouseX, int mouseY, int width, int height, GuiGraphicsExtractor gui) {
        if (!hasShiftDown()) {
            return;
        }
        int minY = components.size() * 10 + 3;
        gui.fill(mouseX, mouseY + minY, mouseX + getWidth(font), mouseY + minY + 11, 0x8F00B0FF);
        gui.text(font, support, mouseX + 2, mouseY + minY + 2, 0xFFE3F2FD, true);
        // Keep every compatible gun visible in the original sixteen-column layout.
        for (int i = 0; i < showGuns.size(); i++) {
            ItemStack stack = showGuns.get(i);
            int x = i % MAX_COLUMNS * 16 + 2;
            int y = i / MAX_COLUMNS * 18 + minY + 15;
            TooltipIconRenderer.drawSlotIcon(gui, stack, mouseX + x, mouseY + y);
        }
    }

    static int gridRows(int gunCount) {
        return Math.max(1, (gunCount + MAX_COLUMNS - 1) / MAX_COLUMNS);
    }

    static int gridWidth(int gunCount) {
        return Math.min(gunCount, MAX_COLUMNS) * 16 + 4;
    }

    static int contentHeight(int detailCount, int gunCount, boolean expanded) {
        return detailCount * 10 + (expanded ? gridRows(gunCount) * 18 + 32 : 28);
    }

    private static boolean hasShiftDown() {
        return Minecraft.getInstance().hasShiftDown();
    }

    private void getShowGuns() {
        try {
            this.showGuns = CACHE.get(attachmentId, () -> getAllAllowGuns(Lists.newArrayList(), attachmentId));
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

}
