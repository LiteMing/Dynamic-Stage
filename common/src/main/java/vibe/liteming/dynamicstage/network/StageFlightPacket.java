package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.stage.StageSession;


/** S2C authorized CMDCam scene and server-world playback epoch. */
public record StageFlightPacket(String stageId, String flightHash, long startGameTime,
                                long durationMillis, byte[] sceneJson) {

    public StageFlightPacket {
        sceneJson = sceneJson.clone();
    }

    public static StageFlightPacket active(StageSession session, byte[] sceneJson) {
        return new StageFlightPacket(session.stageId(), session.flightHash(), session.flightStartGameTime(),
                session.flightDurationMillis(), sceneJson);
    }

    @Override
    public byte[] sceneJson() {
        return sceneJson.clone();
    }

    public static void encode(StageFlightPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.stageId, 128);
        buf.writeUtf(packet.flightHash, 64);
        buf.writeVarLong(packet.startGameTime);
        buf.writeVarLong(packet.durationMillis);
        buf.writeByteArray(packet.sceneJson);
    }

    public static StageFlightPacket decode(FriendlyByteBuf buf) {
        return new StageFlightPacket(buf.readUtf(128), buf.readUtf(64), buf.readVarLong(),
                buf.readVarLong(), buf.readByteArray(StageFlightCodec.MAX_BYTES));
    }

}
