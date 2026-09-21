package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageTransitionPacketTest {
    @Test
    void preservesTransitionIdentityAndDirection() {
        StageTransitionPacket packet = new StageTransitionPacket(
                UUID.randomUUID(), UUID.randomUUID(), true, 40);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StageTransitionPacket.encode(packet, buffer);
            assertEquals(packet, StageTransitionPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
