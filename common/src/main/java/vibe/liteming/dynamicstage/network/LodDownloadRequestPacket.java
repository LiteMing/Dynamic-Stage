package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.util.ContentHash;

/** Client request for a server-hosted archive, optionally resuming a local partial file. */
public record LodDownloadRequestPacket(ResourceLocation lodPackId, String sha256, long offset) {
    public LodDownloadRequestPacket {
        if (lodPackId == null || !ContentHash.isSha256(sha256) || offset < 0L) {
            throw new IllegalArgumentException("Invalid LOD download request");
        }
    }

    public static void encode(LodDownloadRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.lodPackId());
        buf.writeUtf(packet.sha256(), 64);
        buf.writeVarLong(packet.offset());
    }

    public static LodDownloadRequestPacket decode(FriendlyByteBuf buf) {
        return new LodDownloadRequestPacket(buf.readResourceLocation(), buf.readUtf(64), buf.readVarLong());
    }
}
