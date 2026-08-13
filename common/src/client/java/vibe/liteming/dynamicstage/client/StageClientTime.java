package vibe.liteming.dynamicstage.client;

import vibe.liteming.dynamicstage.stage.StageClientScene;

/** Resolves synchronized instance time without continuously updating the server. */
public final class StageClientTime {
    private StageClientTime() {
    }

    public static long dayTime(StageClientScene scene, long currentGameTime) {
        long elapsed = Math.max(0L, currentGameTime - scene.timeBaseGameTime());
        return switch (scene.timeMode()) {
            case FIXED -> scene.timeBaseDayTime();
            case FOLLOW -> scene.timeBaseDayTime() + elapsed;
            case CYCLE -> scene.timeBaseDayTime()
                    + (elapsed / scene.timeCycleTicks()) * 24_000L
                    + (elapsed % scene.timeCycleTicks()) * 24_000L / scene.timeCycleTicks();
        };
    }
}
