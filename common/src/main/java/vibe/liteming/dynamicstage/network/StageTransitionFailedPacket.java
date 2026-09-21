package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S report for a client-side Dynamic Stage transition that could not complete. */
public record StageTransitionFailedPacket(UUID transitionId, UUID instanceId, String reason) {
    public StageTransitionFailedPacket {
        if (transitionId == null) {
            throw new IllegalArgumentException("transitionId is required");
        }
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId is required");
        }
        reason = reason == null ? "unknown" : reason.length() > 128 ? reason.substring(0, 128) : reason;
    }

    public StageTransitionFailedPacket(UUID instanceId, String reason) {
        this(UUID.randomUUID(), instanceId, reason);
    }

    public static void encode(StageTransitionFailedPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transitionId());
        buf.writeUUID(packet.instanceId());
        buf.writeUtf(packet.reason(), 128);
    }

    public static StageTransitionFailedPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionFailedPacket(buf.readUUID(), buf.readUUID(), buf.readUtf(128));
    }
}
