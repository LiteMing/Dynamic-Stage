package vibe.liteming.dynamicstage.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.util.function.Supplier;

/** S2C authority state for one local player's active stage. */
public record StageSessionPacket(boolean active, String stageId, String backdropHash,
                                 long backdropBytes, BlockPos stageOrigin) {

    public static StageSessionPacket active(StageSession session) {
        return new StageSessionPacket(true, session.stageId(), session.backdropHash(),
                session.backdropBytes(), session.stageOrigin());
    }

    public static StageSessionPacket clear() {
        return new StageSessionPacket(false, "", "", 0L, BlockPos.ZERO);
    }

    public static void encode(StageSessionPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        if (packet.active) {
            buf.writeUtf(packet.stageId, 128);
            buf.writeUtf(packet.backdropHash, 64);
            buf.writeVarLong(packet.backdropBytes);
            buf.writeBlockPos(packet.stageOrigin);
        }
    }

    public static StageSessionPacket decode(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return clear();
        }
        return new StageSessionPacket(true, buf.readUtf(128), buf.readUtf(64),
                buf.readVarLong(), buf.readBlockPos());
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
