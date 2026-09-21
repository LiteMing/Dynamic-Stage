package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** S2C notification that a Dynamic Stage dimension transition is taking over the frame. */
public record StageTransitionPacket(UUID instanceId, boolean entering, int durationTicks) {
    public static final int MAX_DURATION_TICKS = 200;

    public StageTransitionPacket {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId is required");
        }
        if (durationTicks < 1 || durationTicks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("Invalid transition duration");
        }
    }

    public static void encode(StageTransitionPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId());
        buf.writeBoolean(packet.entering());
        buf.writeVarInt(packet.durationTicks());
    }

    public static StageTransitionPacket decode(FriendlyByteBuf buf) {
        return new StageTransitionPacket(buf.readUUID(), buf.readBoolean(), buf.readVarInt());
    }
}
