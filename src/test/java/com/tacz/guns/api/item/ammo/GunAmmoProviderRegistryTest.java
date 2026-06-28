package com.tacz.guns.api.item.ammo;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GunAmmoProviderRegistryTest {
    @AfterEach
    void clearProviders() {
        GunAmmoProviderRegistry.clearForTests();
    }

    @Test
    void firstHandlingProviderConsumesAmmo() {
        GunAmmoProviderRegistry.register(request -> GunAmmoTransaction.notHandled());
        GunAmmoProviderRegistry.register(request -> GunAmmoTransaction.consumed(2));

        GunAmmoRequest request = new GunAmmoRequest(
                null,
                ItemStack.EMPTY,
                "main",
                Identifier.fromNamespaceAndPath("tacz", "standard"),
                "pool.short",
                3,
                GunAmmoRequest.Kind.CONSUME
        );

        GunAmmoTransaction transaction = GunAmmoProviderRegistry.consume(request);

        assertEquals(2, transaction.amount());
    }
}
