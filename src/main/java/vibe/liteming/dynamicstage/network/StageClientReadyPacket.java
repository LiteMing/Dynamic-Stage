package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S result of mounting the session's native client LOD package. */
public record StageClientReadyPacket(UUID instanceId, boolean ready, String error) {

    public StageClientReadyPacket {
        if (error == null) {
            error = "";
        } else if (error.length() > 256) {
            error = error.substring(0, 256);
        }
    }

    public static void encode(StageClientReadyPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId);
        buf.writeBoolean(packet.ready);
        buf.writeUtf(packet.error, 256);
    }

    public static StageClientReadyPacket decode(FriendlyByteBuf buf) {
        return new StageClientReadyPacket(buf.readUUID(), buf.readBoolean(), buf.readUtf(256));
    }

    public static void handle(StageClientReadyPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    StageSessionManager.onClientReady(player, packet.instanceId, packet.ready, packet.error);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
