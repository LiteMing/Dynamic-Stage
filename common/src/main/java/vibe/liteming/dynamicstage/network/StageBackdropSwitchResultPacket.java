package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S result of a live native LOD package switch. */
public record StageBackdropSwitchResultPacket(UUID instanceId, ResourceLocation lodPackId,
                                              boolean ready, String error) {
    public StageBackdropSwitchResultPacket {
        if (instanceId == null || lodPackId == null || error == null || error.length() > 256) {
            throw new IllegalArgumentException("Invalid stage backdrop switch result");
        }
    }

    public static void encode(StageBackdropSwitchResultPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId);
        buf.writeResourceLocation(packet.lodPackId);
        buf.writeBoolean(packet.ready);
        buf.writeUtf(packet.error, 256);
    }

    public static StageBackdropSwitchResultPacket decode(FriendlyByteBuf buf) {
        return new StageBackdropSwitchResultPacket(buf.readUUID(), buf.readResourceLocation(),
                buf.readBoolean(), buf.readUtf(256));
    }
}
