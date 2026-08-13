package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

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

}
