package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageFlightPacketTest {
    @Test
    void preservesActiveFlightAndTransition() {
        byte[] scene = "{\"duration\":1000}".getBytes(StandardCharsets.UTF_8);
        StageFlightPacket packet = new StageFlightPacket("boss", "ab".repeat(32), 420L,
                1_000L, scene, StageClientScene.Transition.BLUR, 40);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageFlightPacket.encode(packet, buffer);
        StageFlightPacket decoded = StageFlightPacket.decode(buffer);

        assertTrue(decoded.active());
        assertEquals(packet.stageId(), decoded.stageId());
        assertEquals(packet.flightHash(), decoded.flightHash());
        assertEquals(packet.startGameTime(), decoded.startGameTime());
        assertEquals(packet.durationMillis(), decoded.durationMillis());
        assertEquals(packet.transition(), decoded.transition());
        assertEquals(packet.transitionTicks(), decoded.transitionTicks());
        org.junit.jupiter.api.Assertions.assertArrayEquals(packet.sceneJson(), decoded.sceneJson());
        buffer.release();
    }

    @Test
    void preservesClearFlightTransition() {
        StageFlightPacket packet = StageFlightPacket.clear("boss", StageClientScene.Transition.FADE, 20);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageFlightPacket.encode(packet, buffer);
        StageFlightPacket decoded = StageFlightPacket.decode(buffer);

        assertFalse(decoded.active());
        assertEquals(packet.stageId(), decoded.stageId());
        assertEquals(packet.transition(), decoded.transition());
        assertEquals(packet.transitionTicks(), decoded.transitionTicks());
        assertEquals(0, decoded.sceneJson().length);
        buffer.release();
    }
}
