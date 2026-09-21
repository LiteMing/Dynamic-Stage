package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageTransitionPacketTest {
    @Test
    void preservesTransitionDirectionAndDuration() {
        StageTransitionPacket packet = new StageTransitionPacket(UUID.randomUUID(), true, 80);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageTransitionPacket.encode(packet, buffer);
        assertEquals(packet, StageTransitionPacket.decode(buffer));
        buffer.release();
    }

    @Test
    void preservesFailureReason() {
        StageTransitionFailedPacket packet = new StageTransitionFailedPacket(UUID.randomUUID(), "timeout");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageTransitionFailedPacket.encode(packet, buffer);
        assertEquals(packet, StageTransitionFailedPacket.decode(buffer));
        buffer.release();
    }

    @Test
    void preservesRollbackResult() {
        StageTransitionResultPacket packet = new StageTransitionResultPacket(UUID.randomUUID());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageTransitionResultPacket.encode(packet, buffer);
        assertEquals(packet, StageTransitionResultPacket.decode(buffer));
        buffer.release();
    }
}
