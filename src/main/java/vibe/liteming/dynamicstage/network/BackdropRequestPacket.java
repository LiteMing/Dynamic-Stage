package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S request for one backdrop chunk. */
public record BackdropRequestPacket(String stageId, String backdropHash, int index) {

    public static void encode(BackdropRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.stageId, 128);
        buf.writeUtf(packet.backdropHash, 64);
        buf.writeVarInt(packet.index);
    }

    public static BackdropRequestPacket decode(FriendlyByteBuf buf) {
        return new BackdropRequestPacket(buf.readUtf(128), buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(BackdropRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    BackdropDistribution.sendChunk(player, packet.stageId, packet.backdropHash, packet.index);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
