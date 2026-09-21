package com.tacz.guns.network.message;

import com.tacz.guns.api.item.attachment.AttachmentType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMessageLaserColorTest {
    @Test
    void roundTripsAllAttachmentColorsUsingTheExistingWireLayout() {
        Map<AttachmentType, Integer> colors = new EnumMap<>(AttachmentType.class);
        for (AttachmentType type : AttachmentType.values()) {
            colors.put(type, 0x80010203 + type.ordinal());
        }
        assertWireRoundTrip(colors, true, 0xff123456, 40);
    }

    @Test
    void roundTripsAnEmptyColorMapAndUnsetGunSlot() {
        assertWireRoundTrip(Map.of(), false, 0, -1);
    }

    private static void assertWireRoundTrip(Map<AttachmentType, Integer> colors, boolean applyGunColor, int gunColor, int slot) {
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // The pre-26.3 format contains no extra key bytes in the map value codec.
            input.writeVarInt(colors.size());
            colors.forEach((type, color) -> {
                input.writeEnum(type);
                input.writeInt(color);
            });
            input.writeBoolean(applyGunColor);
            input.writeInt(gunColor);
            input.writeInt(slot);
            int expectedSize = input.readableBytes();

            ClientMessageLaserColor message = ClientMessageLaserColor.decode(input);
            assertEquals(0, input.readableBytes());
            ClientMessageLaserColor.encode(message, output);
            assertEquals(expectedSize, output.readableBytes());
            int count = output.readVarInt();
            assertEquals(colors.size(), count);
            Map<AttachmentType, Integer> actual = new EnumMap<>(AttachmentType.class);
            for (int index = 0; index < count; index++) {
                actual.put(output.readEnum(AttachmentType.class), output.readInt());
            }
            assertEquals(colors, actual);
            assertEquals(applyGunColor, output.readBoolean());
            assertEquals(gunColor, output.readInt());
            assertEquals(slot, output.readInt());
            assertEquals(0, output.readableBytes());
        } finally {
            input.release();
            output.release();
        }
    }
}
