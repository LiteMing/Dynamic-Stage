package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.util.ContentHash;

/** Terminal server result for a requested LOD archive stream. */
public record LodDownloadResultPacket(ResourceLocation lodPackId, String sha256, boolean success, String error) {
    public static final int MAX_ERROR_LENGTH = 256;

    public LodDownloadResultPacket {
        if (lodPackId == null || !ContentHash.isSha256(sha256)) {
            throw new IllegalArgumentException("Invalid LOD download result");
        }
        error = error == null ? "" : error;
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
    }

    public static void encode(LodDownloadResultPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.lodPackId());
        buf.writeUtf(packet.sha256(), 64);
        buf.writeBoolean(packet.success());
        buf.writeUtf(packet.error(), MAX_ERROR_LENGTH);
    }

    public static LodDownloadResultPacket decode(FriendlyByteBuf buf) {
        return new LodDownloadResultPacket(buf.readResourceLocation(), buf.readUtf(64), buf.readBoolean(),
                buf.readUtf(MAX_ERROR_LENGTH));
    }
}
