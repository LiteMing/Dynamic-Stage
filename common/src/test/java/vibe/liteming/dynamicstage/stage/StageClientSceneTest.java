package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageClientSceneTest {
    @Test
    void persistsAllClientSceneSettings() {
        StageClientScene scene = new StageClientScene(false, 0.5F, false, 6.5F,
                StageClientScene.Transition.BLUR, 40, 250L,
                StageClientScene.TimeMode.CYCLE, 18_000L, 200L, 1_200L, StageClientScene.SkyMode.END);

        assertEquals(scene, StageClientScene.load(scene.save()));
    }

    @Test
    void legacySessionsUseCurrentBehaviorDefaults() {
        StageClientScene scene = StageClientScene.load(new CompoundTag());

        assertEquals(StageClientScene.defaults(0L, 0L), scene);
    }

    @Test
    void rejectsInvalidCycles() {
        assertThrows(IllegalArgumentException.class, () -> new StageClientScene(true, 1.0F, true, 0.0F,
                StageClientScene.Transition.INSTANT, 0, 0L,
                StageClientScene.TimeMode.CYCLE, 0L, 0L, 1L));
    }

    @Test
    void rejectsInvalidMovementScale() {
        assertThrows(IllegalArgumentException.class, () -> new StageClientScene(true, -0.1F, true, 0.0F,
                StageClientScene.Transition.INSTANT, 0, 0L,
                StageClientScene.TimeMode.FOLLOW, 0L, 0L, 0L));
    }
}
