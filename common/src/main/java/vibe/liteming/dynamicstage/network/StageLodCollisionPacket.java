package vibe.liteming.dynamicstage.network;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** A bounded client report that the local player intersects a rendered Voxy voxel. */
public record StageLodCollisionPacket(UUID instanceId, Direction direction, float penetration) {
    public static final float MAX_PENETRATION = 2.0F;

    public StageLodCollisionPacket {
        if (instanceId == null || direction == null || !Float.isFinite(penetration)
                || penetration <= 0.0F || penetration > MAX_PENETRATION) {
            throw new IllegalArgumentException("Invalid Voxy LOD collision report");
        }
    }

    public static void encode(StageLodCollisionPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId);
        buf.writeEnum(packet.direction);
        buf.writeFloat(packet.penetration);
    }

    public static StageLodCollisionPacket decode(FriendlyByteBuf buf) {
        return new StageLodCollisionPacket(buf.readUUID(), buf.readEnum(Direction.class), buf.readFloat());
    }
}
