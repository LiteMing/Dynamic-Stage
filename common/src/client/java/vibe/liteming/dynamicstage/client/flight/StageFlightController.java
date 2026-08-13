package vibe.liteming.dynamicstage.client.flight;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.network.StageFlightPacket;
import vibe.liteming.dynamicstage.util.ContentHash;
import vibe.liteming.dynamicstage.world.StageWorlds;

/** Samples a server-authorized CMDCam path without taking control of the player camera. */
public final class StageFlightController {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageFlightController.class);

    @Nullable private static Active active;

    private StageFlightController() {
    }

    public static void accept(StageFlightPacket packet) {
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        byte[] packetJson = packet.sceneJson();
        if (snapshot == null || !snapshot.hasFlight() || !snapshot.stageId().equals(packet.stageId())
                || !snapshot.flightHash().equals(packet.flightHash())
                || snapshot.flightBytes() != packetJson.length
                || snapshot.flightDurationMillis() != packet.durationMillis()
                || packet.startGameTime() < 0L
                || !ContentHash.sha256Hex(packetJson).equals(packet.flightHash())) {
            LOGGER.warn("Rejected a stage flight that does not match the active session");
            return;
        }
        try {
            StageFlightCodec.Scene scene = StageFlightCodec.readSingle(packetJson);
            if (scene.durationMillis() != packet.durationMillis()) {
                throw new IllegalArgumentException("flight duration mismatch");
            }
            active = new Active(packet.stageId(), packet.flightHash(), packet.startGameTime(),
                    StageFlightPath.parse(scene.json()));
        } catch (Exception e) {
            LOGGER.warn("Rejected invalid stage flight {}: {}", packet.flightHash(), e.getMessage());
        }
    }

    public static void tick() {
        Active flight = active;
        if (flight == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)
                || snapshot == null || !snapshot.stageId().equals(flight.stageId)
                || !snapshot.flightHash().equals(flight.flightHash)) {
            active = null;
        }
    }

    @Nullable
    public static StageFlightPose currentPose(float partialTick) {
        Active flight = active;
        Minecraft minecraft = Minecraft.getInstance();
        if (flight == null || minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return null;
        }
        double elapsedTicks = minecraft.level.getGameTime() - flight.startGameTime
                + MthClamp.partialTick(partialTick);
        return flight.path.sample(elapsedTicks * 50.0D);
    }

    public static void clear() {
        active = null;
    }

    private record Active(String stageId, String flightHash, long startGameTime, StageFlightPath path) {
    }

    private static final class MthClamp {
        private MthClamp() {
        }

        private static float partialTick(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }
}
