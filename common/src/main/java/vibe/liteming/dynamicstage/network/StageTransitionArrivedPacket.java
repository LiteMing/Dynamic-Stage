package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** Sent after the respawn/position packets, including attachment without teleport. */
public record StageTransitionArrivedPacket(UUID transitionId, UUID instanceId) {
    public static void encode(StageTransitionArrivedPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transitionId());
        buf.writeUUID(packet.instanceId());
    }
    public static StageTransitionArrivedPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionArrivedPacket(buf.readUUID(), buf.readUUID());
    }
}
