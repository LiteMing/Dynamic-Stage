package vibe.liteming.dynamicstage.client.flight;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.lod.StageBackdropEffects;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.network.StageFlightPacket;
import vibe.liteming.dynamicstage.util.ContentHash;
import vibe.liteming.dynamicstage.world.StageWorlds;

/** Samples a server-authorized CMDCam path for client-only stage backdrops. */
public final class StageFlightController {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageFlightController.class);

    @Nullable private static Active active;
    @Nullable private static String awaitingSessionHash;
    @Nullable private static Pending pending;

    private StageFlightController() {
    }

    public static void accept(StageFlightPacket packet) {
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot == null || !snapshot.stageId().equals(packet.stageId())) {
            LOGGER.warn("Rejected a stage flight for a different active stage");
            return;
        }
        if (!packet.active()) {
            awaitingSessionHash = "";
            queueTransition(packet, null);
            return;
        }
        byte[] packetJson = packet.sceneJson();
        if (packetJson.length <= 0 || packet.startGameTime() < 0L
                || !ContentHash.sha256Hex(packetJson).equals(packet.flightHash())) {
            LOGGER.warn("Rejected a stage flight that does not match the active session");
            return;
        }
        try {
            StageFlightCodec.Scene scene = StageFlightCodec.readSingle(packetJson);
            if (scene.durationMillis() != packet.durationMillis()) {
                throw new IllegalArgumentException("flight duration mismatch");
            }
            Active replacement = new Active(packet.stageId(), packet.flightHash(), packet.startGameTime(),
                    StageFlightPath.parse(scene.json()));
            awaitingSessionHash = packet.flightHash();
            queueTransition(packet, replacement);
        } catch (Exception e) {
            LOGGER.warn("Rejected invalid stage flight {}: {}", packet.flightHash(), e.getMessage());
        }
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level) || snapshot == null) {
            active = null;
            awaitingSessionHash = null;
            pending = null;
            return;
        }
        Pending queued = pending;
        if (queued != null) {
            if (!snapshot.stageId().equals(queued.stageId)) {
                active = null;
                awaitingSessionHash = null;
                pending = null;
                return;
            }
            if (minecraft.level.getGameTime() >= queued.switchGameTime) {
                active = queued.replacement;
                pending = null;
            } else {
                return;
            }
        }
        Active flight = active;
        if (flight != null && (!snapshot.stageId().equals(flight.stageId)
                || (!snapshot.flightHash().equals(flight.flightHash)
                && !flight.flightHash.equals(awaitingSessionHash)))) {
            active = null;
            awaitingSessionHash = null;
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
        awaitingSessionHash = null;
        pending = null;
    }

    public static void confirmSession(String flightHash) {
        if (flightHash != null && flightHash.equals(awaitingSessionHash)) {
            awaitingSessionHash = null;
        }
    }

    private static void queueTransition(StageFlightPacket packet, @Nullable Active replacement) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && packet.transitionTicks() > 0) {
            StageBackdropEffects.beginSwap(packet.transition(), packet.transitionTicks(),
                    minecraft.level.getGameTime());
            int outTicks = Math.max(1, packet.transitionTicks() / 2);
            pending = new Pending(packet.stageId(), replacement,
                    minecraft.level.getGameTime() + outTicks);
        } else {
            active = replacement;
            pending = null;
        }
    }

    private record Active(String stageId, String flightHash, long startGameTime, StageFlightPath path) {
    }

    private record Pending(String stageId, @Nullable Active replacement, long switchGameTime) {
    }

    private static final class MthClamp {
        private MthClamp() {
        }

        private static float partialTick(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }
}
