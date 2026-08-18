package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageEditorAdminPacketTest {
    @Test
    void roundTripsRequestsAndInstanceState() {
        UUID instanceId = UUID.randomUUID();
        StageEditorAdminPacket.Request request = new StageEditorAdminPacket.Request(
                StageEditorAdminPacket.Action.TOGGLE_PERSISTENT, instanceId);
        FriendlyByteBuf requestBuffer = new FriendlyByteBuf(Unpooled.buffer());
        StageEditorAdminPacket.encodeRequest(request, requestBuffer);
        assertEquals(request, StageEditorAdminPacket.decodeRequest(requestBuffer));
        requestBuffer.release();

        StageEditorAdminPacket.State state = new StageEditorAdminPacket.State(List.of(
                new StageEditorAdminPacket.Instance(instanceId, "arena", 2, 1, 4, true)),
                true, true, "updated", false);
        FriendlyByteBuf stateBuffer = new FriendlyByteBuf(Unpooled.buffer());
        StageEditorAdminPacket.encodeState(state, stateBuffer);
        assertEquals(state, StageEditorAdminPacket.decodeState(stateBuffer));
        stateBuffer.release();
    }
}
