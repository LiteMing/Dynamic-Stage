package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageLodCollisionPacketTest {
    @Test
    void preservesBoundedCollisionReport() {
        StageLodCollisionPacket packet = new StageLodCollisionPacket(
                UUID.randomUUID(), Direction.NORTH, 0.375F);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageLodCollisionPacket.encode(packet, buffer);

        assertEquals(packet, StageLodCollisionPacket.decode(buffer));
        buffer.release();
    }

    @Test
    void rejectsInvalidPenetration() {
        UUID instance = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                new StageLodCollisionPacket(instance, Direction.UP, Float.NaN));
        assertThrows(IllegalArgumentException.class, () ->
                new StageLodCollisionPacket(instance, Direction.UP, 0.0F));
        assertThrows(IllegalArgumentException.class, () ->
                new StageLodCollisionPacket(instance, Direction.UP,
                        StageLodCollisionPacket.MAX_PENETRATION + 0.01F));
    }
}
