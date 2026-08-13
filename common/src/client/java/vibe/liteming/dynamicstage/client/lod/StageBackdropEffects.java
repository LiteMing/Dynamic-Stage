package vibe.liteming.dynamicstage.client.lod;

import vibe.liteming.dynamicstage.stage.StageClientScene;

/** Pure client-side transition curve shared by every LOD compositor backend. */
public final class StageBackdropEffects {
    private static final float TRANSITION_BLUR_RADIUS = 16.0F;

    private StageBackdropEffects() {
    }

    public static State sample(StageClientScene scene, long gameTime, float partialTick) {
        if (scene.lodTransition() == StageClientScene.Transition.INSTANT
                || scene.lodTransitionTicks() == 0) {
            return new State(scene.lodVisible() ? 1.0F : 0.0F, scene.lodBlurRadius());
        }
        float elapsed = gameTime - scene.lodTransitionStartGameTime()
                + Math.max(0.0F, Math.min(1.0F, partialTick));
        float progress = smoothstep(Math.max(0.0F,
                Math.min(1.0F, elapsed / scene.lodTransitionTicks())));
        float opacity = scene.lodVisible() ? progress : 1.0F - progress;
        float transitionBlur = scene.lodTransition() == StageClientScene.Transition.BLUR
                ? TRANSITION_BLUR_RADIUS * (scene.lodVisible() ? 1.0F - progress : progress) : 0.0F;
        return new State(opacity, Math.min(StageClientScene.MAX_BLUR_RADIUS,
                scene.lodBlurRadius() + transitionBlur));
    }

    private static float smoothstep(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    public record State(float opacity, float blurRadius) {
        public boolean hidden() {
            return opacity <= 0.0001F;
        }

        public boolean passthrough() {
            return opacity >= 0.9999F && blurRadius <= 0.0001F;
        }

        public boolean translucent() {
            return !hidden() && opacity < 0.9999F;
        }
    }
}
