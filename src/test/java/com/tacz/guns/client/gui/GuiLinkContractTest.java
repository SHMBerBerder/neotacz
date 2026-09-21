package com.tacz.guns.client.gui;

import com.tacz.guns.client.gui.compat.ClothConfigScreen;
import net.minecraft.util.Util;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuiLinkContractTest {
    @Test
    void fixedDownloadLinkAndPackWebLinksUseTheNativeUriContract() throws URISyntaxException {
        assertEquals("https", URI.create(ClothConfigScreen.CLOTH_CONFIG_URL).getScheme());
        for (String url : new String[]{"https://example.com/pack", "http://example.com/pack", "HTTPS://example.com/pack"}) {
            assertEquals(new URI(url), Util.parseAndValidateUntrustedUri(url));
        }
    }

    @Test
    void untrustedPackLinksRejectMissingProtocolsOtherSchemesAndInvalidSyntax() {
        for (String url : new String[]{"example.com/pack", "file:///tmp/pack", "javascript:alert(1)", "https://bad host/pack"}) {
            assertThrows(URISyntaxException.class, () -> Util.parseAndValidateUntrustedUri(url));
        }
    }
}
