package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageClientSceneTest {
    @Test
    void defaultsToOneThousandthDhNearFadeDistance() {
        assertEquals(0.001F, StageClientScene.DEFAULT_DH_NEAR_FADE_SCALE);
        assertEquals(0.001F, StageClientScene.defaults(0L, 0L).dhNearFadeScale());
    }

    @Test
    void persistsAllClientSceneSettings() {
        StageClientScene scene = new StageClientScene(false, 0.5F, 0.125F, 1.25F, true, false, 6.5F,
                StageClientScene.Transition.BLUR, 40, 250L,
                StageClientScene.TimeMode.CYCLE, 18_000L, 200L, 1_200L, StageClientScene.SkyMode.END);

        assertEquals(scene, StageClientScene.load(scene.save()));
    }

    @Test
    void legacySceneTagsDisableVoxyNearCulling() {
        CompoundTag tag = StageClientScene.defaults(6000L, 20L).save();
        tag.remove("VoxyNearCulling");
        tag.putFloat("VoxyNearClipScale", 0.01F);

        assertEquals(StageClientScene.DEFAULT_VOXY_NEAR_CULLING,
                StageClientScene.load(tag).voxyNearCulling());
    }

    @Test
    void legacySessionsUseCurrentBehaviorDefaults() {
        StageClientScene scene = StageClientScene.load(new CompoundTag());

        assertEquals(StageClientScene.defaults(0L, 0L), scene);
        assertEquals(StageClientScene.DEFAULT_DH_NEAR_FADE_SCALE, scene.dhNearFadeScale());
        assertEquals(StageClientScene.DEFAULT_VOXY_NEAR_PLANE, scene.voxyNearPlane());
    }

    @Test
    void legacySceneTagsUseDefaultDhNearFadeScale() {
        CompoundTag tag = StageClientScene.defaults(6000L, 20L).save();
        tag.remove("DhNearFadeScale");

        assertEquals(StageClientScene.DEFAULT_DH_NEAR_FADE_SCALE,
                StageClientScene.load(tag).dhNearFadeScale());
    }

    @Test
    void rejectsInvalidDhNearFadeScale() {
        assertThrows(IllegalArgumentException.class, () -> new StageClientScene(true, 1.0F, 0.00001F, true, 0.0F,
                StageClientScene.Transition.INSTANT, 0, 0L,
                StageClientScene.TimeMode.FOLLOW, 0L, 0L, 0L, StageClientScene.SkyMode.OVERWORLD));
    }

    @Test
    void rejectsInvalidVoxyNearPlane() {
        assertThrows(IllegalArgumentException.class, () -> new StageClientScene(true, 1.0F, 0.1F,
                0.0F, false, true, 0.0F, StageClientScene.Transition.INSTANT, 0, 0L,
                StageClientScene.TimeMode.FOLLOW, 0L, 0L, 0L, StageClientScene.SkyMode.OVERWORLD));
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
