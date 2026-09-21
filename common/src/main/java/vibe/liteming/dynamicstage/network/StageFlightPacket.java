package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.util.UUID;


/** S2C authorized CMDCam scene and server-world playback epoch. */
public record StageFlightPacket(UUID instanceId, String stageId, String flightHash, long startGameTime,
                                long durationMillis, byte[] sceneJson,
                                StageClientScene.Transition transition, int transitionTicks) {

    public StageFlightPacket(String stageId, String flightHash, long startGameTime,
                             long durationMillis, byte[] sceneJson) {
        this(new UUID(0L, 0L), stageId, flightHash, startGameTime, durationMillis, sceneJson,
                StageClientScene.Transition.INSTANT, 0);
    }

    public StageFlightPacket(UUID instanceId, String stageId, String flightHash, long startGameTime,
                             long durationMillis, byte[] sceneJson) {
        this(instanceId, stageId, flightHash, startGameTime, durationMillis, sceneJson,
                StageClientScene.Transition.INSTANT, 0);
    }

    public StageFlightPacket(String stageId, String flightHash, long startGameTime,
                             long durationMillis, byte[] sceneJson,
                             StageClientScene.Transition transition, int transitionTicks) {
        this(new UUID(0L, 0L), stageId, flightHash, startGameTime, durationMillis, sceneJson,
                transition, transitionTicks);
    }

    public StageFlightPacket {
        sceneJson = sceneJson.clone();
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId is required");
        }
        if (transition == null || transitionTicks < 0 || transitionTicks > StageClientScene.MAX_TRANSITION_TICKS
                || (transition == StageClientScene.Transition.INSTANT && transitionTicks != 0)) {
            throw new IllegalArgumentException("Invalid flight transition");
        }
    }

    public static StageFlightPacket active(StageSession session, byte[] sceneJson) {
        return new StageFlightPacket(session.instanceId(), session.stageId(), session.flightHash(), session.flightStartGameTime(),
                session.flightDurationMillis(), sceneJson);
    }

    public static StageFlightPacket active(StageSession session, byte[] sceneJson,
                                           StageClientScene.Transition transition, int transitionTicks) {
        return new StageFlightPacket(session.instanceId(), session.stageId(), session.flightHash(), session.flightStartGameTime(),
                session.flightDurationMillis(), sceneJson, transition, transitionTicks);
    }

    public static StageFlightPacket clear(String stageId, StageClientScene.Transition transition, int transitionTicks) {
        return clear(new UUID(0L, 0L), stageId, transition, transitionTicks);
    }

    public static StageFlightPacket clear(UUID instanceId, String stageId,
                                          StageClientScene.Transition transition, int transitionTicks) {
        return new StageFlightPacket(instanceId, stageId, "", -1L, 0L, new byte[0], transition, transitionTicks);
    }

    public boolean active() {
        return !flightHash.isEmpty();
    }

    @Override
    public byte[] sceneJson() {
        return sceneJson.clone();
    }

    public static void encode(StageFlightPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.instanceId);
        buf.writeUtf(packet.stageId, 128);
        buf.writeBoolean(packet.active());
        if (packet.active()) {
            buf.writeUtf(packet.flightHash, 64);
            buf.writeVarLong(packet.startGameTime);
            buf.writeVarLong(packet.durationMillis);
            buf.writeByteArray(packet.sceneJson);
        }
        buf.writeEnum(packet.transition);
        buf.writeVarInt(packet.transitionTicks);
    }

    public static StageFlightPacket decode(FriendlyByteBuf buf) {
        UUID instanceId = buf.readUUID();
        String stageId = buf.readUtf(128);
        if (!buf.readBoolean()) {
            return clear(instanceId, stageId, buf.readEnum(StageClientScene.Transition.class), buf.readVarInt());
        }
        return new StageFlightPacket(instanceId, stageId, buf.readUtf(64), buf.readVarLong(),
                buf.readVarLong(), buf.readByteArray(StageFlightCodec.MAX_BYTES),
                buf.readEnum(StageClientScene.Transition.class), buf.readVarInt());
    }

}
