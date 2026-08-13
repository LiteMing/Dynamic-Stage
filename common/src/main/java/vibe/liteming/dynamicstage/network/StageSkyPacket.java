package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

/** S2C selection for the receiving player's stage sky renderer. */
public record StageSkyPacket(Mode mode) {
    public enum Mode { OVERWORLD, END, OFF }

    public static void encode(StageSkyPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.mode);
    }

    public static StageSkyPacket decode(FriendlyByteBuf buf) {
        return new StageSkyPacket(buf.readEnum(Mode.class));
    }
}
