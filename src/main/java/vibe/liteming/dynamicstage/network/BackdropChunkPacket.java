package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.dynamicstage.client.backdrop.BackdropClientDownloader;

import java.util.function.Supplier;

/** S2C response containing one bounded backdrop chunk. */
public record BackdropChunkPacket(String stageId, String backdropHash, int index,
                                  byte[] data, boolean last) {

    public static void encode(BackdropChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.stageId, 128);
        buf.writeUtf(packet.backdropHash, 64);
        buf.writeVarInt(packet.index);
        buf.writeByteArray(packet.data);
        buf.writeBoolean(packet.last);
    }

    public static BackdropChunkPacket decode(FriendlyByteBuf buf) {
        return new BackdropChunkPacket(buf.readUtf(128), buf.readUtf(64), buf.readVarInt(),
                buf.readByteArray(BackdropDistribution.CHUNK_SIZE), buf.readBoolean());
    }

    public static void handle(BackdropChunkPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> BackdropClientDownloader.onChunk(packet)));
        }
        context.setPacketHandled(true);
    }
}
