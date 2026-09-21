package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** S2C notification that the RPG-style stage transition owns the client frame. */
public record StageTransitionPacket(UUID transitionId, UUID instanceId, boolean entering, int durationTicks) {
    public static final int MAX_DURATION_TICKS = 200;

    public StageTransitionPacket {
        if (transitionId == null || instanceId == null) {
            throw new IllegalArgumentException("transition identifiers are required");
        }
        if (durationTicks < 1 || durationTicks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("invalid transition duration");
        }
    }

    public StageTransitionPacket(UUID instanceId, boolean entering, int durationTicks) {
        this(UUID.randomUUID(), instanceId, entering, durationTicks);
    }

    public static void encode(StageTransitionPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.transitionId());
        buf.writeUUID(packet.instanceId());
        buf.writeBoolean(packet.entering());
        buf.writeVarInt(packet.durationTicks());
    }

    public static StageTransitionPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionPacket(buf.readUUID(), buf.readUUID(), buf.readBoolean(), buf.readVarInt());
    }
}
