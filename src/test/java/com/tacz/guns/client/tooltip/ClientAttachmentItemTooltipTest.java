package com.tacz.guns.client.tooltip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientAttachmentItemTooltipTest {
    @Test
    void expandedGridKeepsSixteenColumnsAndDoesNotTruncateAfterFiftyFourGuns() {
        assertEquals(1, ClientAttachmentItemTooltip.gridRows(1));
        assertEquals(1, ClientAttachmentItemTooltip.gridRows(16));
        assertEquals(2, ClientAttachmentItemTooltip.gridRows(17));
        assertEquals(4, ClientAttachmentItemTooltip.gridRows(55));
        assertEquals(5, ClientAttachmentItemTooltip.gridRows(65));
        assertEquals(20, ClientAttachmentItemTooltip.gridWidth(1));
        assertEquals(260, ClientAttachmentItemTooltip.gridWidth(16));
        assertEquals(260, ClientAttachmentItemTooltip.gridWidth(55));
    }

    @Test
    void zeroCompatibleGunsRetainsTheLegacyEmptyRowAndTextSpacing() {
        assertEquals(1, ClientAttachmentItemTooltip.gridRows(0));
        assertEquals(4, ClientAttachmentItemTooltip.gridWidth(0));
        assertEquals(50, ClientAttachmentItemTooltip.contentHeight(0, 0, true));
        assertEquals(80, ClientAttachmentItemTooltip.contentHeight(3, 0, true));
        assertEquals(58, ClientAttachmentItemTooltip.contentHeight(3, 55, false));
        assertEquals(134, ClientAttachmentItemTooltip.contentHeight(3, 55, true));
    }
}
