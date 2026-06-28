package com.tacz.guns.api.item.ammo;

import com.tacz.guns.api.item.runtime.GunRuntimeDataAccessor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GunAmmoSlotsTest {
    @Test
    void defaultSlotUsesMainAndGivenAmmo() {
        Identifier ammoId = Identifier.fromNamespaceAndPath("tacz", "standard");

        GunAmmoSlots slots = GunAmmoSlots.single(ammoId);

        assertEquals("main", slots.activeSlotId());
        assertEquals(ammoId, slots.activeSlot().ammoId());
        assertEquals(1, slots.slots().size());
    }

    @Test
    void activeSlotCanSwitchOnlyToKnownSlot() {
        Identifier standard = Identifier.fromNamespaceAndPath("tacz", "standard");
        Identifier incendiary = Identifier.fromNamespaceAndPath("tacz", "incendiary");
        GunAmmoSlots slots = new GunAmmoSlots(
                List.of(
                        new GunAmmoSlot("left", standard, "pool.long"),
                        new GunAmmoSlot("right", incendiary, "pool.special")
                ),
                "left"
        );

        GunAmmoSlots switched = slots.withActiveSlot("right");

        assertEquals("right", switched.activeSlotId());
        assertEquals(incendiary, switched.activeSlot().ammoId());
        assertThrows(IllegalArgumentException.class, () -> switched.withActiveSlot("missing"));
    }

    @Test
    void slotsRoundTripThroughNbt() {
        Identifier standard = Identifier.fromNamespaceAndPath("tacz", "standard");
        Identifier poison = Identifier.fromNamespaceAndPath("tacz", "poison");
        GunAmmoSlots slots = new GunAmmoSlots(
                List.of(
                        new GunAmmoSlot("main", standard, "pool.short"),
                        new GunAmmoSlot("alt", poison, "pool.special")
                ),
                "alt"
        );

        net.minecraft.nbt.CompoundTag tag = GunRuntimeDataAccessor.writeAmmoSlots(slots);
        GunAmmoSlots parsed = GunRuntimeDataAccessor.readAmmoSlots(tag, standard);

        assertEquals("alt", parsed.activeSlotId());
        assertEquals(poison, parsed.activeSlot().ammoId());
        assertEquals("pool.special", parsed.activeSlot().ammoPoolId());
    }
}
