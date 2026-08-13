package vibe.liteming.dynamicstage.client;

import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageClientTimeTest {
    @Test
    void followsTheSynchronizedOverworldEpoch() {
        StageClientScene scene = scene(StageClientScene.TimeMode.FOLLOW, 6_000L, 100L, 0L);

        assertEquals(6_250L, StageClientTime.dayTime(scene, 350L));
    }

    @Test
    void holdsFixedTime() {
        StageClientScene scene = scene(StageClientScene.TimeMode.FIXED, 18_000L, 100L, 0L);

        assertEquals(18_000L, StageClientTime.dayTime(scene, 50_000L));
    }

    @Test
    void cyclesAtTheConfiguredClientPeriod() {
        StageClientScene scene = scene(StageClientScene.TimeMode.CYCLE, 6_000L, 100L, 1_200L);

        assertEquals(12_000L, StageClientTime.dayTime(scene, 400L));
        assertEquals(30_000L, StageClientTime.dayTime(scene, 1_300L));
    }

    private static StageClientScene scene(StageClientScene.TimeMode mode, long dayTime,
                                          long gameTime, long cycleTicks) {
        return new StageClientScene(true, true, 0.0F, StageClientScene.Transition.INSTANT, 0, gameTime,
                mode, dayTime, gameTime, cycleTicks);
    }
}
