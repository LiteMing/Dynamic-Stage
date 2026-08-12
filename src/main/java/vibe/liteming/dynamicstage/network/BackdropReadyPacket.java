package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.util.function.Supplier;

/** C2S signal that the current session backdrop has been validated and uploaded. */
public record BackdropReadyPacket(String stageId, String backdropHash, String flightHash) {

    public static void encode(BackdropReadyPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.stageId, 128);
        buf.writeUtf(packet.backdropHash, 64);
        buf.writeUtf(packet.flightHash, 64);
    }

    public static BackdropReadyPacket decode(FriendlyByteBuf buf) {
        return new BackdropReadyPacket(buf.readUtf(128), buf.readUtf(64), buf.readUtf(64));
    }

    public static void handle(BackdropReadyPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    StageSessionManager.onBackdropReady(
                            player, packet.stageId, packet.backdropHash, packet.flightHash);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
