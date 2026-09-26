package com.tacz.guns.client.renderer.item;

import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.item.AmmoBoxItem;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import com.tacz.guns.util.ItemStackData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmmoBoxItemTintSourceTest {
    private final AmmoBoxItemTintSource tint = new AmmoBoxItemTintSource();

    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void allNineModelStatesHaveAnOpaqueBodyWithoutChangingStoredColor() {
        for (int state = 0; state <= 8; state++) {
            ItemStack stack = box(state);
            CompoundTag before = ItemStackData.copyCustomData(stack);
            assertEquals(state, AmmoBoxItem.getStatue(stack, null, null, 0));
            assertEquals(0xff727d6b, tint.calculate(stack, null, null), "model state " + state);
            assertEquals(0x727d6b, AmmoBoxItem.getColor(stack, 0));
            assertEquals(-1, AmmoBoxItem.getColor(stack, 1));
            assertEquals(before, ItemStackData.copyCustomData(stack));
        }
    }

    @Test
    void customRgbAndLegacyHighBitsStayOpaqueWithoutMutatingTheTag() {
        for (int color : new int[]{0, 0xffffff, 0x123456, 0x80123456, 0xff123456}) {
            ItemStack stack = box(0);
            ItemStackData.updateCustomData(stack, tag -> {
                CompoundTag display = new CompoundTag();
                display.putInt("color", color);
                tag.put("display", display);
            });
            CompoundTag before = ItemStackData.copyCustomData(stack);
            assertEquals(0xff000000 | color, tint.calculate(stack, null, null));
            assertEquals(color, AmmoBoxItem.getColor(stack, 0));
            assertEquals(before, ItemStackData.copyCustomData(stack));
        }
    }

    @Test
    void missingColorInExistingDisplayTagUsesOpaqueDefault() {
        ItemStack stack = box(0);
        ItemStackData.updateCustomData(stack, tag -> tag.put("display", new CompoundTag()));
        assertEquals(0xff727d6b, tint.calculate(stack, null, null));
    }

    private static ItemStack box(int state) {
        ItemStack stack = ModItems.AMMO_BOX.get().getDefaultInstance();
        IAmmoBox item = (IAmmoBox) stack.getItem();
        if (state >= 6) {
            item.setCreative(stack, state == 8);
        } else {
            item.setAmmoLevel(stack, state / 2);
        }
        if ((state & 1) != 0) {
            item.setAmmoId(stack, Identifier.fromNamespaceAndPath("tacz", "762x39"));
            item.setAmmoCount(stack, 30);
        }
        return stack;
    }
}
