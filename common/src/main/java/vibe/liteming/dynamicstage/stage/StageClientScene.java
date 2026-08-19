package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persisted instance settings evaluated entirely by each stage client. */
public record StageClientScene(
        boolean followPlayer,
        float lodMovementScale,
        float dhNearFadeScale,
        float voxyNearPlane,
        boolean voxyNearCulling,
        boolean lodVisible,
        float lodBlurRadius,
        Transition lodTransition,
        int lodTransitionTicks,
        long lodTransitionStartGameTime,
        TimeMode timeMode,
        long timeBaseDayTime,
        long timeBaseGameTime,
        long timeCycleTicks,
        SkyMode skyMode
) {
    public static final float MIN_LOD_MOVEMENT_SCALE = 0.0F;
    public static final float MAX_LOD_MOVEMENT_SCALE = 8.0F;
    /** Multiplier applied to DH's near fade/clip distance while this stage is active. */
    public static final float DEFAULT_DH_NEAR_FADE_SCALE = 0.001F;
    public static final float MIN_DH_NEAR_FADE_SCALE = 0.0001F;
    public static final float MAX_DH_NEAR_FADE_SCALE = 1.0F;
    public static final float DEFAULT_VOXY_NEAR_PLANE = 0.5F;
    public static final float MIN_VOXY_NEAR_PLANE = 0.01F;
    public static final float MAX_VOXY_NEAR_PLANE = 16.0F;
    /** Voxy's camera-containing section is preserved by default in a stage. */
    public static final boolean DEFAULT_VOXY_NEAR_CULLING = false;
    public static final float MAX_BLUR_RADIUS = 32.0F;
    public static final int MAX_TRANSITION_TICKS = 20 * 60;
    public static final long MIN_TIME_CYCLE_TICKS = 20L;
    public static final long MAX_TIME_CYCLE_TICKS = 1_728_000L;

    public StageClientScene {
        if (lodTransition == null || timeMode == null || skyMode == null) {
            throw new IllegalArgumentException("Stage client scene contains a null mode");
        }
        if (!Float.isFinite(lodBlurRadius) || lodBlurRadius < 0.0F || lodBlurRadius > MAX_BLUR_RADIUS) {
            throw new IllegalArgumentException("Invalid LOD blur radius: " + lodBlurRadius);
        }
        if (!Float.isFinite(lodMovementScale) || lodMovementScale < MIN_LOD_MOVEMENT_SCALE
                || lodMovementScale > MAX_LOD_MOVEMENT_SCALE) {
            throw new IllegalArgumentException("Invalid LOD movement scale: " + lodMovementScale);
        }
        if (!Float.isFinite(dhNearFadeScale) || dhNearFadeScale < MIN_DH_NEAR_FADE_SCALE
                || dhNearFadeScale > MAX_DH_NEAR_FADE_SCALE) {
            throw new IllegalArgumentException("Invalid DH near fade scale: " + dhNearFadeScale);
        }
        if (!Float.isFinite(voxyNearPlane) || voxyNearPlane < MIN_VOXY_NEAR_PLANE
                || voxyNearPlane > MAX_VOXY_NEAR_PLANE) {
            throw new IllegalArgumentException("Invalid Voxy near plane: " + voxyNearPlane);
        }
        if (lodTransitionTicks < 0 || lodTransitionTicks > MAX_TRANSITION_TICKS) {
            throw new IllegalArgumentException("Invalid LOD transition duration: " + lodTransitionTicks);
        }
        if (lodTransition == Transition.INSTANT && lodTransitionTicks != 0) {
            throw new IllegalArgumentException("Instant LOD transition contains a duration");
        }
        if (lodTransitionStartGameTime < 0L) {
            throw new IllegalArgumentException("Invalid LOD transition epoch: " + lodTransitionStartGameTime);
        }
        if (timeBaseGameTime < 0L) {
            throw new IllegalArgumentException("Invalid client time epoch: " + timeBaseGameTime);
        }
        if (timeMode == TimeMode.CYCLE
                && (timeCycleTicks < MIN_TIME_CYCLE_TICKS || timeCycleTicks > MAX_TIME_CYCLE_TICKS)) {
            throw new IllegalArgumentException("Invalid client time cycle: " + timeCycleTicks);
        }
        if (timeMode != TimeMode.CYCLE && timeCycleTicks != 0L) {
            throw new IllegalArgumentException("Non-cycling client time contains a cycle duration");
        }
    }

    /** Source-compatible constructor for the layout before configurable Voxy projection. */
    public StageClientScene(boolean followPlayer, float lodMovementScale, float dhNearFadeScale,
                            boolean voxyNearCulling, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime,
                            long timeCycleTicks, SkyMode skyMode) {
        this(followPlayer, lodMovementScale, dhNearFadeScale, DEFAULT_VOXY_NEAR_PLANE,
                voxyNearCulling, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime,
                timeCycleTicks, skyMode);
    }

    /** Source-compatible constructor for integrations using the pre-scale layout. */
    public StageClientScene(boolean followPlayer, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks) {
        this(followPlayer, 1.0F, DEFAULT_DH_NEAR_FADE_SCALE, DEFAULT_VOXY_NEAR_CULLING, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks,
                SkyMode.OVERWORLD);
    }

    public StageClientScene(boolean followPlayer, float lodMovementScale, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks) {
        this(followPlayer, lodMovementScale, DEFAULT_DH_NEAR_FADE_SCALE, DEFAULT_VOXY_NEAR_CULLING, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks,
                SkyMode.OVERWORLD);
    }

    /** Source-compatible constructor for the pre-DH-fade layout with an explicit sky mode. */
    public StageClientScene(boolean followPlayer, float lodMovementScale, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks,
                            SkyMode skyMode) {
        this(followPlayer, lodMovementScale, DEFAULT_DH_NEAR_FADE_SCALE, DEFAULT_VOXY_NEAR_CULLING, lodVisible, lodBlurRadius,
                lodTransition, lodTransitionTicks, lodTransitionStartGameTime, timeMode, timeBaseDayTime,
                timeBaseGameTime, timeCycleTicks, skyMode);
    }

    /** Compatibility constructor used by existing templates and integrations. */
    public StageClientScene(boolean followPlayer, float lodMovementScale, float dhNearFadeScale,
                             boolean lodVisible, float lodBlurRadius, Transition lodTransition,
                            int lodTransitionTicks, long lodTransitionStartGameTime, TimeMode timeMode,
                            long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks, SkyMode skyMode) {
        this(followPlayer, lodMovementScale, dhNearFadeScale, DEFAULT_VOXY_NEAR_CULLING, lodVisible,
                lodBlurRadius, lodTransition, lodTransitionTicks, lodTransitionStartGameTime, timeMode,
                timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    /** Source compatibility for the removed Voxy near-plane scale setting. */
    public StageClientScene(boolean followPlayer, float lodMovementScale, float dhNearFadeScale,
                            float legacyVoxyNearClipScale, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime,
                            long timeCycleTicks, SkyMode skyMode) {
        this(followPlayer, lodMovementScale, dhNearFadeScale, legacyVoxyNearClipScale >= 1.0F,
                lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks, lodTransitionStartGameTime,
                timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public static StageClientScene defaults(long dayTime, long gameTime) {
        return new StageClientScene(true, 1.0F, DEFAULT_DH_NEAR_FADE_SCALE, DEFAULT_VOXY_NEAR_CULLING, true, 0.0F, Transition.INSTANT, 0, gameTime,
                TimeMode.FOLLOW, dayTime, gameTime, 0L, SkyMode.OVERWORLD);
    }

    public StageClientScene withFollowPlayer(boolean follow) {
        return copy(follow, lodMovementScale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withLodMovementScale(float scale) {
        return copy(followPlayer, scale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withDhNearFadeScale(float scale) {
        return copy(followPlayer, lodMovementScale, scale, voxyNearPlane, voxyNearCulling, lodVisible, lodBlurRadius, lodTransition,
                lodTransitionTicks, lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime,
                timeCycleTicks, skyMode);
    }

    public StageClientScene withVoxyNearPlane(float nearPlane) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, nearPlane, voxyNearCulling,
                lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks, lodTransitionStartGameTime,
                timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withVoxyNearCulling(boolean enabled) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, voxyNearPlane, enabled, lodVisible, lodBlurRadius,
                lodTransition, lodTransitionTicks, lodTransitionStartGameTime, timeMode, timeBaseDayTime,
                timeBaseGameTime, timeCycleTicks, skyMode);
    }

    /** Legacy source API; the value no longer modifies Voxy's projection matrix. */
    public float voxyNearClipScale() {
        return voxyNearCulling ? 1.0F : 0.01F;
    }

    public StageClientScene withVoxyNearClipScale(float legacyScale) {
        return withVoxyNearCulling(legacyScale >= 1.0F);
    }

    public StageClientScene withLodVisible(boolean visible, Transition transition,
                                           int transitionTicks, long startGameTime) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, visible, lodBlurRadius, transition, transitionTicks, startGameTime,
                timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withLodBlurRadius(float blurRadius) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, lodVisible, blurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withTime(TimeMode mode, long baseDayTime, long baseGameTime, long cycleTicks) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, mode, baseDayTime, baseGameTime, cycleTicks, skyMode);
    }

    public StageClientScene withSkyMode(SkyMode mode) {
        return copy(followPlayer, lodMovementScale, dhNearFadeScale, voxyNearPlane, voxyNearCulling, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, mode);
    }

    private StageClientScene copy(boolean follow, float movementScale, float dhFadeScale, float nearPlane,
                                  boolean voxyCulling, boolean visible, float blurRadius,
                                  Transition transition, int transitionTicks, long transitionStart,
                                  TimeMode newTimeMode,
                                  long baseDayTime, long baseGameTime, long cycleTicks, SkyMode newSkyMode) {
        return new StageClientScene(follow, movementScale, dhFadeScale, nearPlane, voxyCulling, visible, blurRadius, transition, transitionTicks, transitionStart,
                newTimeMode, baseDayTime, baseGameTime, cycleTicks, newSkyMode);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("FollowPlayer", followPlayer);
        tag.putFloat("LodMovementScale", lodMovementScale);
        tag.putFloat("DhNearFadeScale", dhNearFadeScale);
        tag.putFloat("VoxyNearPlane", voxyNearPlane);
        tag.putBoolean("VoxyNearCulling", voxyNearCulling);
        tag.putBoolean("LodVisible", lodVisible);
        tag.putFloat("LodBlurRadius", lodBlurRadius);
        tag.putString("LodTransition", lodTransition.name());
        tag.putInt("LodTransitionTicks", lodTransitionTicks);
        tag.putLong("LodTransitionStartGameTime", lodTransitionStartGameTime);
        tag.putString("TimeMode", timeMode.name());
        tag.putLong("TimeBaseDayTime", timeBaseDayTime);
        tag.putLong("TimeBaseGameTime", timeBaseGameTime);
        tag.putLong("TimeCycleTicks", timeCycleTicks);
        tag.putString("SkyMode", skyMode.name());
        return tag;
    }

    public static StageClientScene load(CompoundTag tag) {
        if (!tag.contains("TimeMode", Tag.TAG_STRING)) {
            return defaults(0L, 0L);
        }
        return new StageClientScene(
                tag.getBoolean("FollowPlayer"),
                tag.contains("LodMovementScale", Tag.TAG_FLOAT) ? tag.getFloat("LodMovementScale") : 1.0F,
                tag.contains("DhNearFadeScale", Tag.TAG_FLOAT)
                        ? tag.getFloat("DhNearFadeScale") : DEFAULT_DH_NEAR_FADE_SCALE,
                tag.contains("VoxyNearPlane", Tag.TAG_FLOAT)
                        ? tag.getFloat("VoxyNearPlane") : DEFAULT_VOXY_NEAR_PLANE,
                tag.contains("VoxyNearCulling", Tag.TAG_BYTE)
                        ? tag.getBoolean("VoxyNearCulling")
                        : DEFAULT_VOXY_NEAR_CULLING,
                !tag.contains("LodVisible") || tag.getBoolean("LodVisible"),
                tag.getFloat("LodBlurRadius"),
                tag.contains("LodTransition", Tag.TAG_STRING)
                        ? Transition.valueOf(tag.getString("LodTransition")) : Transition.INSTANT,
                tag.getInt("LodTransitionTicks"),
                tag.contains("LodTransitionStartGameTime")
                        ? tag.getLong("LodTransitionStartGameTime") : tag.getLong("TimeBaseGameTime"),
                TimeMode.valueOf(tag.getString("TimeMode")),
                tag.getLong("TimeBaseDayTime"),
                tag.getLong("TimeBaseGameTime"),
                tag.getLong("TimeCycleTicks"),
                tag.contains("SkyMode", Tag.TAG_STRING)
                        ? SkyMode.valueOf(tag.getString("SkyMode")) : SkyMode.OVERWORLD
        );
    }

    public enum TimeMode { FOLLOW, FIXED, CYCLE }

    public enum SkyMode { OVERWORLD, END, OFF }

    public enum Transition { INSTANT, FADE, BLUR }
}
