package com.tacz.guns.api.event.common;

import com.tacz.guns.api.item.runtime.GunHitContext;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.LogicalSide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GunHitContextTest {
    @Test
    void hurtEventCarriesNeutralHitContext() {
        GunHitContext context = new GunHitContext(42.4d, 2, 0.75d);

        EntityHurtByGunEvent.Post event = new EntityHurtByGunEvent.Post(
                null,
                null,
                null,
                Identifier.fromNamespaceAndPath("tacz", "frontier"),
                Identifier.fromNamespaceAndPath("tacz", "frontier_display"),
                95,
                null,
                true,
                1.5f,
                LogicalSide.SERVER,
                context
        );

        assertEquals(context, event.getHitContext());
    }

    @Test
    void unknownHitContextKeepsOptionalNumbersOutOfBand() {
        GunHitContext context = GunHitContext.unknown();

        assertEquals(-1.0d, context.distanceMeters());
        assertEquals(0, context.penetrationCount());
        assertEquals(-1.0d, context.penetrationDamageMultiplier());
    }
}
