package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.lod.StageBackdropEffects;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageTransitionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.UUID;

/** Owns the client frame while a Dynamic Stage dimension change is in flight. */
public final class StageTransitionManager {
    private static final long MAX_WAIT_NANOS = 15_000_000_000L;
    private static final long FAILURE_FALLBACK_NANOS = 3_000_000_000L;
    private static volatile Transition active;

    private StageTransitionManager() {
    }

    public static void begin(StageTransitionPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean sourceStage = minecraft.level != null && StageWorlds.isStageLevel(minecraft.level);
        Transition transition = new Transition(packet.transitionId(), packet.instanceId(), packet.entering(), packet.durationTicks(),
                sourceStage, System.nanoTime());
        if (!packet.entering() && !sourceStage) {
            transition.targetReadyNanos = System.nanoTime();
        }
        active = transition;
        if (minecraft.screen instanceof ReceivingLevelScreen) {
            minecraft.setScreen(null);
        }
    }

    public static void tick() {
        Transition transition = active;
        if (transition == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (minecraft.screen instanceof ReceivingLevelScreen) {
            // ReceivingLevelScreen can be installed from the respawn handler
            // after the transition packet, so remove it again on the client
            // tick. The dedicated screen mixin also suppresses its render.
            minecraft.setScreen(null);
        }
        if (transition.failed) {
            if (now - transition.failedAtNanos >= FAILURE_FALLBACK_NANOS) {
                active = null;
            }
            return;
        }
        if (now - transition.startedNanos > MAX_WAIT_NANOS) {
            fail(transition, "timeout");
            return;
        }
        if (minecraft.level == null) {
            return;
        }
        boolean nowStage = StageWorlds.isStageLevel(minecraft.level);
        ClientStageSession.Snapshot session = ClientStageSession.active();
        boolean targetLoaded = !transition.entering && transition.sourceStage != nowStage
                && session == null;
        if (targetLoaded && transition.targetReadyNanos == 0L) {
            transition.targetReadyNanos = System.nanoTime();
        }
        if (transition.targetReadyNanos != 0L && transition.finished(now)) {
            active = null;
        }
    }

    /** Marks the stage renderer ready after its native LOD backend has activated. */
    public static void markTargetReady(UUID instanceId) {
        Transition transition = active;
        Minecraft minecraft = Minecraft.getInstance();
        if (transition == null || !transition.entering || !transition.instanceId.equals(instanceId)
                || minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return;
        }
        if (transition.targetReadyNanos == 0L) {
            transition.targetReadyNanos = System.nanoTime();
        }
    }

    private static void fail(Transition transition, String reason) {
        if (active != transition) {
            return;
        }
        transition.failed = true;
        transition.failedAtNanos = System.nanoTime();
        if (transition.failureSent) {
            return;
        }
        transition.failureSent = true;
        DynamicStageNetwork.transitionFailed(transition.transitionId, transition.instanceId, reason);
    }

    public static boolean active() {
        return active != null;
    }

    public static void clear() {
        active = null;
    }

    public static void complete(UUID transitionId, UUID instanceId) {
        Transition transition = active;
        if (transition != null && transition.transitionId.equals(transitionId)
                && transition.instanceId.equals(instanceId)) {
            active = null;
        }
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        Transition transition = active;
        if (transition == null) {
            return;
        }
        float alpha = transition.failed ? 0.94F : transition.alpha(System.nanoTime(), partialTick);
        Minecraft minecraft = Minecraft.getInstance();
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot != null && snapshot.instanceId().equals(transition.instanceId)
                && minecraft.level != null && StageWorlds.isStageLevel(minecraft.level)) {
            StageBackdropEffects.State effects = ClientStageSession.backdropEffects(
                    snapshot.clientScene(), minecraft.level.getGameTime(), minecraft.getFrameTime());
            // Let the native LOD compositor and Flight show through as their
            // own scene transition becomes visible.
            alpha *= 0.55F + 0.45F * (1.0F - effects.opacity());
            alpha = Math.min(0.94F, alpha + Math.min(0.08F, effects.blurRadius() / 256.0F));
        }
        if (alpha <= 0.001F) {
            return;
        }
        int color = ((int) (Math.min(1.0F, alpha) * 255.0F) << 24) | 0x07101C;
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), color);
    }

    private static final class Transition {
        private final UUID transitionId;
        private final UUID instanceId;
        private final boolean entering;
        private final int durationTicks;
        private final boolean sourceStage;
        private final long startedNanos;
        private long targetReadyNanos;
        private boolean failed;
        private boolean failureSent;
        private long failedAtNanos;

        private Transition(UUID transitionId, UUID instanceId, boolean entering, int durationTicks,
                           boolean sourceStage, long startedNanos) {
            this.transitionId = transitionId;
            this.instanceId = instanceId;
            this.entering = entering;
            this.durationTicks = durationTicks;
            this.sourceStage = sourceStage;
            this.startedNanos = startedNanos;
        }

        private int fadeInTicks() {
            return Math.max(2, Math.round(durationTicks * 0.25F));
        }

        private int fadeOutTicks() {
            return Math.max(2, durationTicks - fadeInTicks());
        }

        private long fadeOutStartNanos() {
            long scheduled = startedNanos + fadeInTicks() * 50_000_000L;
            return targetReadyNanos == 0L ? scheduled : Math.max(scheduled, targetReadyNanos);
        }

        private boolean finished(long now) {
            return now - fadeOutStartNanos() >= fadeOutTicks() * 50_000_000L;
        }

        private float alpha(long now, float partialTick) {
            double elapsedTicks = (now - startedNanos) / 50_000_000.0D + Math.max(0.0F, Math.min(1.0F, partialTick));
            double fadeIn = fadeInTicks();
            if (elapsedTicks < fadeIn) {
                return smoothstep((float) (elapsedTicks / fadeIn)) * 0.88F;
            }
            if (targetReadyNanos == 0L) {
                return 0.88F;
            }
            double fadeElapsed = (now - fadeOutStartNanos()) / 50_000_000.0D
                    + Math.max(0.0F, Math.min(1.0F, partialTick));
            return Math.max(0.0F, 0.88F * (1.0F - smoothstep((float) (fadeElapsed / fadeOutTicks()))));
        }

        private static float smoothstep(float value) {
            float clamped = Math.max(0.0F, Math.min(1.0F, value));
            return clamped * clamped * (3.0F - 2.0F * clamped);
        }
    }
}
