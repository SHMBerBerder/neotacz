package com.tacz.guns.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczKeyMappingsTest {
    @Test
    void defaultBindingsRetainTheirSavedNamesAndModifiersWithNativeInputCodes() {
        assertDefault(AimKey.AIM_KEY, "aim", "key.mouse.right", KeyModifier.NONE);
        assertDefault(ShootKey.SHOOT_KEY, "shoot", "key.mouse.left", KeyModifier.NONE);
        assertDefault(CrawlKey.CRAWL_KEY, "crawl", "key.keyboard.c", KeyModifier.NONE);
        assertDefault(ConfigKey.OPEN_CONFIG_KEY, "open_config", "key.keyboard.t", KeyModifier.ALT);
        assertDefault(FireSelectKey.FIRE_SELECT_KEY, "fire_select", "key.keyboard.g", KeyModifier.NONE);
        assertDefault(InspectKey.INSPECT_KEY, "inspect", "key.keyboard.h", KeyModifier.NONE);
        assertDefault(InteractKey.INTERACT_KEY, "interact", "key.keyboard.o", KeyModifier.NONE);
        assertDefault(MeleeKey.MELEE_KEY, "melee", "key.keyboard.v", KeyModifier.NONE);
        assertDefault(RefitKey.REFIT_KEY, "refit", "key.keyboard.z", KeyModifier.NONE);
        assertDefault(ReloadKey.RELOAD_KEY, "reload", "key.keyboard.r", KeyModifier.NONE);
        assertDefault(ZoomKey.ZOOM_KEY, "zoom", "key.keyboard.v", KeyModifier.NONE);
    }

    @Test
    void keyboardMatchingUsesThePhysicalKeyAndNotTheVirtualKeycode() {
        InputEvent.Key nativeR = new InputEvent.Key(new KeyEvent(21, 114, 0), InputConstants.PRESS);
        InputEvent.Key virtualROnDifferentPhysicalKey = new InputEvent.Key(new KeyEvent(25, 114, 0), InputConstants.PRESS);
        assertTrue(TaczKeyMappings.matches(ReloadKey.RELOAD_KEY, nativeR));
        assertFalse(TaczKeyMappings.matches(ReloadKey.RELOAD_KEY, virtualROnDifferentPhysicalKey));
        assertFalse(TaczKeyMappings.matches(ShootKey.SHOOT_KEY, nativeR));
    }

    @Test
    void mouseMatchingUsesNativeLeftAndRightButtonNumbers() {
        InputEvent.MouseButton.Post left = new InputEvent.MouseButton.Post(new MouseButtonInfo(1, 0), InputConstants.PRESS);
        InputEvent.MouseButton.Post right = new InputEvent.MouseButton.Post(new MouseButtonInfo(3, 0), InputConstants.RELEASE);
        assertTrue(TaczKeyMappings.matchesMouse(ShootKey.SHOOT_KEY, left));
        assertTrue(TaczKeyMappings.matchesMouse(AimKey.AIM_KEY, right));
        assertFalse(TaczKeyMappings.matchesMouse(AimKey.AIM_KEY, left));
        assertFalse(TaczKeyMappings.matchesMouse(ReloadKey.RELOAD_KEY, right));
    }

    @Test
    void rebindingRetainsKeyboardMouseAndUnboundMatching() {
        KeyMapping mapping = new KeyMapping("key.tacz.test_native_input", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_R, TaczKeyMappings.CATEGORY);
        mapping.setKey(InputConstants.getKey("key.mouse.4"));
        assertEquals("key.mouse.4", mapping.saveString());
        assertTrue(TaczKeyMappings.matchesMouse(mapping,
                new InputEvent.MouseButton.Post(new MouseButtonInfo(4, 0), InputConstants.PRESS)));
        assertFalse(TaczKeyMappings.matches(mapping,
                new InputEvent.Key(new KeyEvent(InputConstants.KEY_R, 114, 0), InputConstants.PRESS)));
        mapping.setKey(InputConstants.getKey("key.keyboard.unknown"));
        assertTrue(mapping.isUnbound());
        assertFalse(TaczKeyMappings.matches(mapping,
                new InputEvent.Key(new KeyEvent(0, 0, 0), InputConstants.PRESS)));
    }

    private static void assertDefault(KeyMapping mapping, String action, String savedName, KeyModifier modifier) {
        InputConstants.Key expected = InputConstants.getKey(savedName);
        assertEquals("key.tacz." + action + ".desc", mapping.getName());
        assertEquals(expected, mapping.getDefaultKey());
        assertEquals(expected.getType(), mapping.getDefaultKey().getType());
        assertEquals(savedName, mapping.saveString());
        assertEquals(TaczKeyMappings.CATEGORY, mapping.getCategory());
        assertEquals(KeyConflictContext.IN_GAME, mapping.getKeyConflictContext());
        assertEquals(modifier, mapping.getDefaultKeyModifier());
    }
}
