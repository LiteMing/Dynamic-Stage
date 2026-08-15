package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.util.ContentHash;

/** One bounded server-to-client archive segment. */
public record LodDownloadChunkPacket(ResourceLocation lodPackId, String sha256, long offset,
                                     byte[] data, boolean complete) {
    public static final int MAX_CHUNK_BYTES = 24 * 1024;

    public LodDownloadChunkPacket {
        if (lodPackId == null || !ContentHash.isSha256(sha256) || offset < 0L
                || data == null || data.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid LOD download chunk");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public static void encode(LodDownloadChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.lodPackId());
        buf.writeUtf(packet.sha256(), 64);
        buf.writeVarLong(packet.offset());
        buf.writeByteArray(packet.data());
        buf.writeBoolean(packet.complete());
    }

    public static LodDownloadChunkPacket decode(FriendlyByteBuf buf) {
        return new LodDownloadChunkPacket(buf.readResourceLocation(), buf.readUtf(64), buf.readVarLong(),
                buf.readByteArray(MAX_CHUNK_BYTES), buf.readBoolean());
    }
}
