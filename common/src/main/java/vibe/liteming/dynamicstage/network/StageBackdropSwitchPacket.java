package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import vibe.liteming.dynamicstage.stage.StageClientScene;

/** S2C request to replace the native LOD package after a client-side transition. */
public record StageBackdropSwitchPacket(StageSessionPacket session,
                                        StageClientScene.Transition transition,
                                        int transitionTicks) {
    public StageBackdropSwitchPacket {
        if (session == null || !session.active() || transition == null
                || transitionTicks < 0 || transitionTicks > StageClientScene.MAX_TRANSITION_TICKS
                || (transition == StageClientScene.Transition.INSTANT && transitionTicks != 0)) {
            throw new IllegalArgumentException("Invalid stage backdrop switch packet");
        }
    }

    public static void encode(StageBackdropSwitchPacket packet, FriendlyByteBuf buf) {
        StageSessionPacket.encode(packet.session, buf);
        buf.writeEnum(packet.transition);
        buf.writeVarInt(packet.transitionTicks);
    }

    public static StageBackdropSwitchPacket decode(FriendlyByteBuf buf) {
        return new StageBackdropSwitchPacket(StageSessionPacket.decode(buf), buf.readEnum(StageClientScene.Transition.class),
                buf.readVarInt());
    }
}
