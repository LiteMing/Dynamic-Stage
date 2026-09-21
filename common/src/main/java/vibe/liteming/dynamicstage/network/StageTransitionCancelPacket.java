package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** S2C: this transfer was cancelled; session cleanup/return packets precede it. */
public record StageTransitionCancelPacket(UUID transitionId, UUID instanceId) {
    public static void encode(StageTransitionCancelPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transitionId());
        buf.writeUUID(packet.instanceId());
    }

    public static StageTransitionCancelPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionCancelPacket(buf.readUUID(), buf.readUUID());
    }
}
