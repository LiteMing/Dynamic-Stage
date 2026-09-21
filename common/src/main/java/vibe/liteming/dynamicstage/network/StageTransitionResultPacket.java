package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** S2C acknowledgement that the server completed a transition rollback. */
public record StageTransitionResultPacket(UUID instanceId) {
    public static void encode(StageTransitionResultPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId());
    }

    public static StageTransitionResultPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionResultPacket(buf.readUUID());
    }
}
