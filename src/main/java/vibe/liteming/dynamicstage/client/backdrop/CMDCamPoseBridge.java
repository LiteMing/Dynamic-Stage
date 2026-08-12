package vibe.liteming.dynamicstage.client.backdrop;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Runtime CMDCam soft-dependency bridge (6-DoF camera pose).
 * <p>
 * CMDCam path points are RELATIVE internally ({@code makeRelative}); the
 * per-frame absolute pose (position + yaw/pitch/roll) comes from
 * {@code CamRunStage.calculatePoint(level, time, partial)}. Follow mode
 * ({@code posTarget}) offsets the path by the target entity — the returned
 * CamPoint already includes it, so both absolute and relative scenes work.
 * <p>
 * The renderer consumes {@link #poseDelta()} — the pose relative to the pose at
 * playback start — so a scene can be dropped onto any anchor with full XYZ
 * translation AND rotation.
 */
public final class CMDCamPoseBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(CMDCamPoseBridge.class);

    private static final String CMDCAM_CLIENT_CLASS = "team.creative.cmdcam.client.CMDCamClient";

    @Nullable private static Method isPlayingMethod;
    @Nullable private static Method getSceneMethod;
    @Nullable private static Method runPositionMethod;
    @Nullable private static Method calculatePointMethod;
    @Nullable private static Field stagesField;
    @Nullable private static Field currentStageField;

    @Nullable private static Class<?> camPointClass;
    @Nullable private static Method pointGetX;
    @Nullable private static Method pointGetY;
    @Nullable private static Method pointGetZ;
    @Nullable private static Field yawField;
    @Nullable private static Field pitchField;
    @Nullable private static Field rollField;

    private static boolean resolved;
    private static boolean available;

    // Playback-start snapshot for delta computation.
    private static boolean wasPlaying;
    private static boolean snapshotValid;
    @Nullable private static Pose startPose;

    private CMDCamPoseBridge() {
    }

    /** A full camera pose (absolute world position + euler angles in degrees). */
    public record Pose(Vec3 position, double yaw, double pitch, double roll) {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> client = Class.forName(CMDCAM_CLIENT_CLASS);
            isPlayingMethod = client.getMethod("isPlaying");
            getSceneMethod = client.getMethod("getScene");
            Class<?> scene = getSceneMethod.getReturnType();
            runPositionMethod = scene.getField("run").getType().getMethod("position", float.class);
            stagesField = scene.getField("run").getType().getDeclaredField("stages");
            stagesField.setAccessible(true);
            currentStageField = scene.getField("run").getType().getDeclaredField("currentStage");
            currentStageField.setAccessible(true);
            camPointClass = Class.forName("team.creative.cmdcam.common.math.point.CamPoint");
            pointGetX = camPointClass.getMethod("getX");
            pointGetY = camPointClass.getMethod("getY");
            pointGetZ = camPointClass.getMethod("getZ");
            yawField = camPointClass.getField("rotationYaw");
            pitchField = camPointClass.getField("rotationPitch");
            rollField = camPointClass.getField("roll");
            available = true;
        } catch (Throwable t) {
            LOGGER.debug("CMDCam pose bridge unavailable: {}", t.getMessage());
            available = false;
        }
    }

    /** True when CMDCam is installed AND playing a scene. */
    public static boolean isPlaying() {
        resolve();
        if (!available || isPlayingMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isPlayingMethod.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Current absolute camera pose from the running CMDCam scene, or null.
     */
    @Nullable
    public static Pose currentPose() {
        resolve();
        if (!available) {
            return null;
        }
        try {
            Object scene = getSceneMethod.invoke(null);
            if (scene == null) {
                return null;
            }
            Class<?> sceneClass = scene.getClass();
            Field runField = sceneClass.getField("run");
            Object run = runField.get(scene);
            if (run == null) {
                return null;
            }
            Level level = Minecraft.getInstance().level;
            if (level == null || runPositionMethod == null) {
                return null;
            }
            float partial = partialTick();
            long time = (Long) runPositionMethod.invoke(run, partial);
            List<?> stages = (List<?>) stagesField.get(run);
            if (stages == null || stages.isEmpty()) {
                return null;
            }
            int stageIndex = currentStageField.getInt(run);
            if (stageIndex < 0 || stageIndex >= stages.size()) {
                return null;
            }
            Object stage = stages.get(stageIndex);
            if (calculatePointMethod == null) {
                // resolve lazily per stage class
                calculatePointMethod = stage.getClass().getMethod("calculatePoint", Level.class, long.class, float.class);
            }
            Object point = calculatePointMethod.invoke(stage, level, time, partial);
            if (point == null) {
                return null;
            }
            return new Pose(
                    new Vec3((Double) pointGetX.invoke(point), (Double) pointGetY.invoke(point), (Double) pointGetZ.invoke(point)),
                    ((Number) yawField.get(point)).doubleValue(),
                    ((Number) pitchField.get(point)).doubleValue(),
                    ((Number) rollField.get(point)).doubleValue());
        } catch (Throwable t) {
            LOGGER.debug("CMDCam pose read failed: {}", t.toString());
            return null;
        }
    }

    /**
     * Pose delta since playback start (position delta + rotation delta).
     * Called each render tick; snapshots on the playing edge.
     */
    @Nullable
    public static Pose poseDelta() {
        boolean playing = isPlaying();
        if (playing && !wasPlaying) {
            snapshotValid = false; // will snapshot below on first successful read
        }
        wasPlaying = playing;
        if (!playing) {
            return null;
        }
        Pose current = currentPose();
        if (current == null) {
            return null;
        }
        if (!snapshotValid) {
            startPose = current;
            snapshotValid = true;
        }
        Pose start = startPose;
        if (start == null) {
            return null;
        }
        return new Pose(
                current.position().subtract(start.position()),
                current.yaw() - start.yaw(),
                current.pitch() - start.pitch(),
                current.roll() - start.roll());
    }

    /** Uses the mapped client API instead of reflecting private timer fields. */
    private static float partialTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return 0.0F;
        }
        return mc.getFrameTime();
    }
}
