package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** C2S milestones, scoped to one transfer (not just one reusable instance). */
public record StageTransitionAckPacket(UUID transitionId, UUID instanceId, Signal signal) {
    public enum Signal { FRAME_PRESENTED, COMPLETE, FAILED }

    public static void encode(StageTransitionAckPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transitionId());
        buf.writeUUID(packet.instanceId());
        buf.writeEnum(packet.signal());
    }

    public static StageTransitionAckPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionAckPacket(buf.readUUID(), buf.readUUID(), buf.readEnum(Signal.class));
    }
}
