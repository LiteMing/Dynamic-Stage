package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageTransitionPacketTest {
    @Test
    void transferMilestonesPreserveBothIdentities() {
        UUID transition = UUID.randomUUID(), instance = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (StageTransitionAckPacket.Signal signal : StageTransitionAckPacket.Signal.values()) {
                StageTransitionAckPacket packet = new StageTransitionAckPacket(transition, instance, signal);
                StageTransitionAckPacket.encode(packet, buffer);
                assertEquals(packet, StageTransitionAckPacket.decode(buffer));
            }
            StageTransitionCancelPacket cancel = new StageTransitionCancelPacket(transition, instance);
            StageTransitionCancelPacket.encode(cancel, buffer);
            assertEquals(cancel, StageTransitionCancelPacket.decode(buffer));
            StageTransitionArrivedPacket arrived = new StageTransitionArrivedPacket(transition, instance);
            StageTransitionArrivedPacket.encode(arrived, buffer);
            assertEquals(arrived, StageTransitionArrivedPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

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
