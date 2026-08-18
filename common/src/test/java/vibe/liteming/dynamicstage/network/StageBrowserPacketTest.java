package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageBrowserPacketTest {
    @Test
    void roundTripsRequestsAndInstanceState() {
        UUID instanceId = UUID.randomUUID();
        StageBrowserPacket.Request request = new StageBrowserPacket.Request(
                StageBrowserPacket.Action.JOIN, instanceId);
        FriendlyByteBuf requestBuffer = new FriendlyByteBuf(Unpooled.buffer());
        StageBrowserPacket.encodeRequest(request, requestBuffer);
        assertEquals(request, StageBrowserPacket.decodeRequest(requestBuffer));
        requestBuffer.release();

        StageBrowserPacket.State state = new StageBrowserPacket.State(List.of(
                new StageBrowserPacket.Instance(instanceId, "gr1", 2, 1, 4, true)),
                "ready", false);
        FriendlyByteBuf stateBuffer = new FriendlyByteBuf(Unpooled.buffer());
        StageBrowserPacket.encodeState(state, stateBuffer);
        assertEquals(state, StageBrowserPacket.decodeState(stateBuffer));
        stateBuffer.release();
    }
}
