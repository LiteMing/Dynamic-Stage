package vibe.liteming.dynamicstage.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C stage membership and client-side native LOD binding. */
public record StageSessionPacket(boolean active, UUID instanceId, String stageId, ResourceLocation lodPackId,
                                 BlockPos lodAnchor, BlockPos stageOrigin, int capacity,
                                 String flightHash, int flightBytes, long flightDurationMillis) {

    public static StageSessionPacket active(StageSession session) {
        return new StageSessionPacket(true, session.instanceId(), session.stageId(), session.lodPackId(),
                session.lodAnchor(), session.stageOrigin(), session.capacity(), session.flightHash(),
                session.flightBytes(), session.flightDurationMillis());
    }

    public static StageSessionPacket clear() {
        return new StageSessionPacket(false, new UUID(0L, 0L), "",
                ResourceLocation.fromNamespaceAndPath("dynamicstage", "none"),
                BlockPos.ZERO, BlockPos.ZERO, 1, "", 0, 0L);
    }

    public static void encode(StageSessionPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        if (packet.active) {
            buf.writeUUID(packet.instanceId);
            buf.writeUtf(packet.stageId, 128);
            buf.writeResourceLocation(packet.lodPackId);
            buf.writeBlockPos(packet.lodAnchor);
            buf.writeBlockPos(packet.stageOrigin);
            buf.writeVarInt(packet.capacity);
            buf.writeUtf(packet.flightHash, 64);
            buf.writeVarInt(packet.flightBytes);
            buf.writeVarLong(packet.flightDurationMillis);
        }
    }

    public static StageSessionPacket decode(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return clear();
        }
        return new StageSessionPacket(true, buf.readUUID(), buf.readUtf(128), buf.readResourceLocation(),
                buf.readBlockPos(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(64),
                buf.readVarInt(), buf.readVarLong());
    }

    public static void handle(StageSessionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientStageSession.accept(packet)));
        }
        context.setPacketHandled(true);
    }
}
