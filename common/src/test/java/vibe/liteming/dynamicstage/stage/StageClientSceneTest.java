package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageClientSceneTest {
    @Test
    void persistsAllClientSceneSettings() {
        StageClientScene scene = new StageClientScene(false,
                StageClientScene.TimeMode.CYCLE, 18_000L, 200L, 1_200L);

        assertEquals(scene, StageClientScene.load(scene.save()));
    }

    @Test
    void legacySessionsUseCurrentBehaviorDefaults() {
        StageClientScene scene = StageClientScene.load(new CompoundTag());

        assertEquals(StageClientScene.defaults(0L, 0L), scene);
    }

    @Test
    void rejectsInvalidCycles() {
        assertThrows(IllegalArgumentException.class, () -> new StageClientScene(true,
                StageClientScene.TimeMode.CYCLE, 0L, 0L, 1L));
    }
}
