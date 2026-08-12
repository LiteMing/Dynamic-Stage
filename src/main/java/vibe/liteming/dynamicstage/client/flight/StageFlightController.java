package vibe.liteming.dynamicstage.client.flight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.backdrop.CMDCamPoseBridge;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.network.StageFlightPacket;
import vibe.liteming.dynamicstage.util.ContentHash;
import vibe.liteming.dynamicstage.world.StageWorlds;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Owns server-authorized CMDCam playback for the active local stage session. */
public final class StageFlightController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageFlightController.class);
    private static final String CMDCAM_CLIENT = "team.creative.cmdcam.client.CMDCamClient";
    private static final String CAM_SCENE = "team.creative.cmdcam.common.scene.CamScene";

    @Nullable private static Pending pending;
    @Nullable private static Object ownedScene;
    private static boolean resolved;
    private static boolean available;
    @Nullable private static Constructor<?> sceneConstructor;
    @Nullable private static Method setServerSynced;
    @Nullable private static Method start;
    @Nullable private static Method stopServer;
    @Nullable private static Method getScene;
    @Nullable private static Method sceneGameTick;
    @Nullable private static Field sceneRun;
    @Nullable private static Field runTimer;
    @Nullable private static Field timerLastResumed;
    @Nullable private static Field timerTimePlayed;
    @Nullable private static Class<?> realTimeTimerClass;

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
            stopOwnedPlayback();
            pending = new Pending(packet.stageId(), packet.flightHash(), packet.startGameTime(),
                    packet.durationMillis(), scene.json());
        } catch (Exception e) {
            LOGGER.warn("Rejected invalid stage flight {}: {}", packet.flightHash(), e.getMessage());
        }
    }

    public static void tick() {
        Pending next = pending;
        Minecraft mc = Minecraft.getInstance();
        if (next == null || mc.level == null || mc.player == null || !StageWorlds.isStageLevel(mc.level)) {
            return;
        }
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot == null || !snapshot.stageId().equals(next.stageId)
                || !snapshot.flightHash().equals(next.flightHash)) {
            pending = null;
            return;
        }
        long elapsedTicks = mc.level.getGameTime() - next.startGameTime;
        if (elapsedTicks < 0L) {
            return;
        }
        long elapsedMillis;
        try {
            elapsedMillis = Math.multiplyExact(elapsedTicks, 50L);
        } catch (ArithmeticException e) {
            elapsedMillis = Long.MAX_VALUE;
        }
        pending = null;
        if (elapsedMillis >= next.durationMillis) {
            LOGGER.info("Stage flight {} already completed before this client became ready", next.flightHash);
            return;
        }
        if (!startScene(next.sceneJson, elapsedMillis)) {
            LOGGER.warn("CMDCam is unavailable; stage flight {} was not started", next.flightHash);
        }
    }

    public static void clear() {
        pending = null;
        stopOwnedPlayback();
        CMDCamPoseBridge.resetPlaybackOrigin();
    }

    private static boolean startScene(byte[] json, long elapsedMillis) {
        resolve();
        if (!available) {
            return false;
        }
        boolean startedScene = false;
        try {
            JsonObject object = JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            CompoundTag nbt = CMDCamJsonNbt.convert(object);
            Object scene = sceneConstructor.newInstance(nbt);
            setServerSynced.invoke(scene);
            stopServer.invoke(null);
            CMDCamPoseBridge.resetPlaybackOrigin();
            start.invoke(null, scene);
            startedScene = true;
            Minecraft mc = Minecraft.getInstance();
            sceneGameTick.invoke(scene, mc.level);
            CMDCamPoseBridge.Pose origin = CMDCamPoseBridge.calculatePlaybackOrigin();
            if (origin == null) {
                stopServer.invoke(null);
                throw new IllegalStateException("CMDCam did not produce a flight origin");
            }
            CMDCamPoseBridge.beginPlaybackOrigin(origin);
            Object run = sceneRun.get(scene);
            Object timer = runTimer.get(run);
            if (!realTimeTimerClass.isInstance(timer)) {
                throw new IllegalStateException("CMDCam is not using its real-time flight timer");
            }
            if (elapsedMillis > 0L) {
                timerTimePlayed.setLong(timer, 0L);
                timerLastResumed.setLong(timer, System.currentTimeMillis() - elapsedMillis);
            }
            ownedScene = scene;
            return true;
        } catch (Throwable e) {
            if (startedScene) {
                try {
                    stopServer.invoke(null);
                } catch (Throwable stopError) {
                    e.addSuppressed(stopError);
                }
            }
            CMDCamPoseBridge.resetPlaybackOrigin();
            LOGGER.warn("Failed to start CMDCam stage flight: {}", e.toString());
            ownedScene = null;
            return false;
        }
    }

    private static void stopOwnedPlayback() {
        Object scene = ownedScene;
        if (scene == null) {
            return;
        }
        resolve();
        try {
            if (available && Minecraft.getInstance().level != null && getScene.invoke(null) == scene) {
                stopServer.invoke(null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Failed to stop CMDCam stage flight: {}", e.toString());
        } finally {
            ownedScene = null;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> clientClass = Class.forName(CMDCAM_CLIENT);
            Class<?> sceneClass = Class.forName(CAM_SCENE);
            sceneConstructor = sceneClass.getConstructor(CompoundTag.class);
            setServerSynced = sceneClass.getMethod("setServerSynced");
            sceneGameTick = sceneClass.getMethod("gameTick", net.minecraft.world.level.Level.class);
            sceneRun = sceneClass.getField("run");
            start = clientClass.getMethod("start", sceneClass);
            stopServer = clientClass.getMethod("stopServer");
            getScene = clientClass.getMethod("getScene");

            Class<?> runClass = Class.forName("team.creative.cmdcam.common.scene.run.CamRun");
            runTimer = runClass.getDeclaredField("timer");
            runTimer.setAccessible(true);
            realTimeTimerClass = Class.forName("team.creative.cmdcam.common.scene.timer.RealTimeTimer");
            timerLastResumed = realTimeTimerClass.getDeclaredField("lastResumed");
            timerLastResumed.setAccessible(true);
            timerTimePlayed = realTimeTimerClass.getDeclaredField("timePlayed");
            timerTimePlayed.setAccessible(true);
            available = true;
        } catch (Throwable e) {
            LOGGER.info("CMDCam stage flights are unavailable: {}", e.getMessage());
            available = false;
        }
    }

    private record Pending(String stageId, String flightHash, long startGameTime,
                           long durationMillis, byte[] sceneJson) {
        private Pending {
            sceneJson = sceneJson.clone();
        }
    }
}
