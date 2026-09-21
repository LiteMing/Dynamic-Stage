package vibe.liteming.dynamicstage.client.lod;

import vibe.liteming.dynamicstage.stage.StageClientScene;

/** Pure client-side transition curve shared by every LOD compositor backend. */
public final class StageBackdropEffects {
    private static final float TRANSITION_BLUR_RADIUS = 16.0F;
    private static volatile SwapOverride swapOverride;

    private StageBackdropEffects() {
    }

    public static State sample(StageClientScene scene, long gameTime, float partialTick) {
        State base;
        if (scene.lodTransition() == StageClientScene.Transition.INSTANT
                || scene.lodTransitionTicks() == 0) {
            base = new State(scene.lodVisible() ? 1.0F : 0.0F, scene.lodBlurRadius());
        } else {
            float elapsed = gameTime - scene.lodTransitionStartGameTime()
                    + Math.max(0.0F, Math.min(1.0F, partialTick));
            float progress = smoothstep(Math.max(0.0F,
                    Math.min(1.0F, elapsed / scene.lodTransitionTicks())));
            float opacity = scene.lodVisible() ? progress : 1.0F - progress;
            float transitionBlur = scene.lodTransition() == StageClientScene.Transition.BLUR
                    ? TRANSITION_BLUR_RADIUS * (scene.lodVisible() ? 1.0F - progress : progress) : 0.0F;
            base = new State(opacity, Math.min(StageClientScene.MAX_BLUR_RADIUS,
                    scene.lodBlurRadius() + transitionBlur));
        }
        SwapOverride override = swapOverride;
        return override == null ? base : override.apply(base, gameTime, partialTick);
    }

    /** Samples the two halves of a live LOD source replacement. */
    public static State sampleSwitch(StageClientScene scene, StageClientScene.Transition transition,
                                     boolean outgoing, float progress) {
        float clamped = smoothstep(Math.max(0.0F, Math.min(1.0F, progress)));
        float opacity = scene.lodVisible() ? (outgoing ? 1.0F - clamped : clamped) : 0.0F;
        float blur = scene.lodBlurRadius();
        if (transition == StageClientScene.Transition.BLUR) {
            blur = Math.min(StageClientScene.MAX_BLUR_RADIUS,
                    blur + TRANSITION_BLUR_RADIUS * (outgoing ? clamped : 1.0F - clamped));
        }
        return new State(opacity, blur);
    }

    /** Starts a black/blur swap used when only the camera flight changes. */
    public static void beginSwap(StageClientScene.Transition transition, int ticks, long gameTime) {
        if (transition == StageClientScene.Transition.INSTANT || ticks <= 0) {
            swapOverride = null;
        } else {
            swapOverride = new SwapOverride(transition, ticks, gameTime);
        }
    }

    public static void clearSwap() {
        swapOverride = null;
    }

    private static float smoothstep(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    private record SwapOverride(StageClientScene.Transition transition, int ticks, long startGameTime) {
        private State apply(State base, long gameTime, float partialTick) {
            float elapsed = gameTime - startGameTime
                    + Math.max(0.0F, Math.min(1.0F, partialTick));
            int outTicks = Math.max(1, ticks / 2);
            int inTicks = Math.max(1, ticks - outTicks);
            if (elapsed >= outTicks + inTicks) {
                swapOverride = null;
                return base;
            }
            if (elapsed < outTicks) {
                float progress = smoothstep(Math.max(0.0F, Math.min(1.0F, elapsed / outTicks)));
                float blur = transition == StageClientScene.Transition.BLUR
                        ? Math.min(StageClientScene.MAX_BLUR_RADIUS,
                        base.blurRadius() + TRANSITION_BLUR_RADIUS * progress)
                        : base.blurRadius();
                return new State(base.opacity() * (1.0F - progress), blur);
            }
            float progress = smoothstep(Math.max(0.0F,
                    Math.min(1.0F, (elapsed - outTicks) / inTicks)));
            float blur = transition == StageClientScene.Transition.BLUR
                    ? Math.min(StageClientScene.MAX_BLUR_RADIUS,
                    base.blurRadius() + TRANSITION_BLUR_RADIUS * (1.0F - progress))
                    : base.blurRadius();
            return new State(base.opacity() * progress, blur);
        }
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
