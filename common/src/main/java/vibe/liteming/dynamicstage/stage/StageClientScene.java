package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persisted instance settings evaluated entirely by each stage client. */
public record StageClientScene(
        boolean followPlayer,
        TimeMode timeMode,
        long timeBaseDayTime,
        long timeBaseGameTime,
        long timeCycleTicks
) {
    public static final long MIN_TIME_CYCLE_TICKS = 20L;
    public static final long MAX_TIME_CYCLE_TICKS = 1_728_000L;

    public StageClientScene {
        if (timeMode == null) {
            throw new IllegalArgumentException("Stage client scene contains a null time mode");
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

    public static StageClientScene defaults(long dayTime, long gameTime) {
        return new StageClientScene(true, TimeMode.FOLLOW, dayTime, gameTime, 0L);
    }

    public StageClientScene withFollowPlayer(boolean follow) {
        return copy(follow, timeMode, timeBaseDayTime, timeBaseGameTime, timeCycleTicks);
    }

    public StageClientScene withTime(TimeMode mode, long baseDayTime, long baseGameTime, long cycleTicks) {
        return copy(followPlayer, mode, baseDayTime, baseGameTime, cycleTicks);
    }

    private StageClientScene copy(boolean follow, TimeMode newTimeMode,
                                  long baseDayTime, long baseGameTime, long cycleTicks) {
        return new StageClientScene(follow, newTimeMode, baseDayTime, baseGameTime, cycleTicks);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("FollowPlayer", followPlayer);
        tag.putString("TimeMode", timeMode.name());
        tag.putLong("TimeBaseDayTime", timeBaseDayTime);
        tag.putLong("TimeBaseGameTime", timeBaseGameTime);
        tag.putLong("TimeCycleTicks", timeCycleTicks);
        return tag;
    }

    public static StageClientScene load(CompoundTag tag) {
        if (!tag.contains("TimeMode", Tag.TAG_STRING)) {
            return defaults(0L, 0L);
        }
        return new StageClientScene(
                tag.getBoolean("FollowPlayer"),
                TimeMode.valueOf(tag.getString("TimeMode")),
                tag.getLong("TimeBaseDayTime"),
                tag.getLong("TimeBaseGameTime"),
                tag.getLong("TimeCycleTicks")
        );
    }

    public enum TimeMode { FOLLOW, FIXED, CYCLE }
}
