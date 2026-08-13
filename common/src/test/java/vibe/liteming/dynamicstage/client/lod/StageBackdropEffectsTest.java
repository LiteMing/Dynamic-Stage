package vibe.liteming.dynamicstage.client.lod;

import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageBackdropEffectsTest {
    @Test
    void fadeUsesAClientGameTimeCurve() {
        StageClientScene scene = scene(true, 2.0F, StageClientScene.Transition.FADE, 40, 100L);

        assertEquals(0.0F, StageBackdropEffects.sample(scene, 100L, 0.0F).opacity(), 0.0001F);
        assertEquals(0.5F, StageBackdropEffects.sample(scene, 120L, 0.0F).opacity(), 0.0001F);
        assertEquals(1.0F, StageBackdropEffects.sample(scene, 140L, 0.0F).opacity(), 0.0001F);
        assertEquals(2.0F, StageBackdropEffects.sample(scene, 120L, 0.0F).blurRadius(), 0.0001F);
    }

    @Test
    void blurTransitionAddsBlurWhileFadingOut() {
        StageClientScene scene = scene(false, 3.0F, StageClientScene.Transition.BLUR, 40, 100L);
        StageBackdropEffects.State middle = StageBackdropEffects.sample(scene, 120L, 0.0F);

        assertEquals(0.5F, middle.opacity(), 0.0001F);
        assertEquals(11.0F, middle.blurRadius(), 0.0001F);
        assertTrue(StageBackdropEffects.sample(scene, 140L, 0.0F).hidden());
    }

    @Test
    void persistentBlurDoesNotChangeNativeAlphaBlending() {
        StageBackdropEffects.State state = StageBackdropEffects.sample(
                scene(true, 8.0F, StageClientScene.Transition.INSTANT, 0, 100L), 100L, 0.0F);

        assertEquals(1.0F, state.opacity(), 0.0001F);
        assertFalse(state.translucent());
    }

    private static StageClientScene scene(boolean visible, float blur, StageClientScene.Transition transition,
                                          int transitionTicks, long transitionStart) {
        return new StageClientScene(true, visible, blur, transition, transitionTicks, transitionStart,
                StageClientScene.TimeMode.FOLLOW, 0L, 0L, 0L);
    }
}
