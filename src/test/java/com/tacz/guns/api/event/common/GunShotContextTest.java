package com.tacz.guns.api.event.common;

import com.tacz.guns.api.item.runtime.GunRuntimeContext;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GunShotContextTest {
    @Test
    void defaultContextKeepsShotGunAmmoAndMainSlotIdentity() {
        Identifier gunId = Identifier.fromNamespaceAndPath("tacz", "frontier");
        Identifier ammoId = Identifier.fromNamespaceAndPath("tacz", "long_fmj");

        GunRuntimeContext context = new GunRuntimeContext(42L, gunId, ammoId, "", null);

        assertEquals(42L, context.shotId());
        assertEquals(gunId, context.gunId());
        assertEquals(ammoId, context.ammoId());
        assertEquals("main", context.ammoSlotId());
        assertEquals("", context.runtimeItemId());
    }

    @Test
    void shotContextEventCanReplaceContext() {
        GunRuntimeContext initial = new GunRuntimeContext(
                1L,
                Identifier.fromNamespaceAndPath("tacz", "frontier"),
                Identifier.fromNamespaceAndPath("tacz", "long_fmj"),
                "main",
                "runtime.one"
        );
        GunRuntimeContext replacement = new GunRuntimeContext(
                2L,
                Identifier.fromNamespaceAndPath("tacz", "frontier"),
                Identifier.fromNamespaceAndPath("tacz", "long_spitzer"),
                "special",
                "runtime.one"
        );

        GunShotContextEvent event = new GunShotContextEvent(null, null, initial);
        event.setContext(replacement);

        assertEquals(replacement, event.getContext());
    }
}
