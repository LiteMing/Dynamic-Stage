package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S report for a client-side Dynamic Stage transition that could not complete. */
public record StageTransitionFailedPacket(UUID instanceId, String reason) {
    public StageTransitionFailedPacket {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId is required");
        }
        reason = reason == null ? "unknown" : reason.length() > 128 ? reason.substring(0, 128) : reason;
    }

    public static void encode(StageTransitionFailedPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId());
        buf.writeUtf(packet.reason(), 128);
    }

    public static StageTransitionFailedPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionFailedPacket(buf.readUUID(), buf.readUtf(128));
    }
}
