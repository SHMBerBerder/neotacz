package com.tacz.guns.api.item.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GunRuntimeAuthorityTest {
    @Test
    void protectedFieldRequiresTrustedMutation() {
        GunRuntimeAuthority authority = new GunRuntimeAuthority("server_runtime", Set.of("GunId", "AmmoSlots"));

        assertFalse(authority.canMutate("GunId", ""));
        assertFalse(authority.canMutate("AmmoSlots", "other"));
        assertTrue(authority.canMutate("GunId", "server_runtime"));
        assertTrue(authority.canMutate("GunCurrentAmmoCount", ""));
    }
}
