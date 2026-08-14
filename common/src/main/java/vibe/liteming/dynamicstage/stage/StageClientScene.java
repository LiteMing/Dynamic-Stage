package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persisted instance settings evaluated entirely by each stage client. */
public record StageClientScene(
        boolean followPlayer,
        float lodMovementScale,
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

    /** Source-compatible constructor for integrations using the pre-scale layout. */
    public StageClientScene(boolean followPlayer, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks) {
        this(followPlayer, 1.0F, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks,
                SkyMode.OVERWORLD);
    }

    public StageClientScene(boolean followPlayer, float lodMovementScale, boolean lodVisible, float lodBlurRadius,
                            Transition lodTransition, int lodTransitionTicks, long lodTransitionStartGameTime,
                            TimeMode timeMode, long timeBaseDayTime, long timeBaseGameTime, long timeCycleTicks) {
        this(followPlayer, lodMovementScale, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks,
                SkyMode.OVERWORLD);
    }

    public static StageClientScene defaults(long dayTime, long gameTime) {
        return new StageClientScene(true, 1.0F, true, 0.0F, Transition.INSTANT, 0, gameTime,
                TimeMode.FOLLOW, dayTime, gameTime, 0L, SkyMode.OVERWORLD);
    }

    public StageClientScene withFollowPlayer(boolean follow) {
        return copy(follow, lodMovementScale, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withLodMovementScale(float scale) {
        return copy(followPlayer, scale, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withLodVisible(boolean visible, Transition transition,
                                           int transitionTicks, long startGameTime) {
        return copy(followPlayer, lodMovementScale, visible, lodBlurRadius, transition, transitionTicks, startGameTime,
                timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withLodBlurRadius(float blurRadius) {
        return copy(followPlayer, lodMovementScale, lodVisible, blurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, skyMode);
    }

    public StageClientScene withTime(TimeMode mode, long baseDayTime, long baseGameTime, long cycleTicks) {
        return copy(followPlayer, lodMovementScale, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, mode, baseDayTime, baseGameTime, cycleTicks, skyMode);
    }

    public StageClientScene withSkyMode(SkyMode mode) {
        return copy(followPlayer, lodMovementScale, lodVisible, lodBlurRadius, lodTransition, lodTransitionTicks,
                lodTransitionStartGameTime, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks, mode);
    }

    private StageClientScene copy(boolean follow, float movementScale, boolean visible, float blurRadius,
                                  Transition transition, int transitionTicks, long transitionStart,
                                  TimeMode newTimeMode,
                                  long baseDayTime, long baseGameTime, long cycleTicks, SkyMode newSkyMode) {
        return new StageClientScene(follow, movementScale, visible, blurRadius, transition, transitionTicks, transitionStart,
                newTimeMode, baseDayTime, baseGameTime, cycleTicks, newSkyMode);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("FollowPlayer", followPlayer);
        tag.putFloat("LodMovementScale", lodMovementScale);
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
