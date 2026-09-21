package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.network.StageTransitionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.UUID;

/** Owns the client frame while a Dynamic Stage dimension change is in flight. */
public final class StageTransitionManager {
    private static final long MAX_WAIT_NANOS = 15_000_000_000L;
    private static final int FADE_IN_TICKS = 8;
    private static final int FADE_OUT_TICKS = 12;
    private static volatile Transition active;

    private StageTransitionManager() {
    }

    public static void begin(StageTransitionPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean sourceStage = minecraft.level != null && StageWorlds.isStageLevel(minecraft.level);
        active = new Transition(packet.instanceId(), packet.entering(), packet.durationTicks(),
                sourceStage, System.nanoTime());
    }

    public static void tick() {
        Transition transition = active;
        if (transition == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || System.nanoTime() - transition.startedNanos > MAX_WAIT_NANOS) {
            active = null;
            return;
        }
        boolean nowStage = StageWorlds.isStageLevel(minecraft.level);
        ClientStageSession.Snapshot session = ClientStageSession.active();
        boolean targetLoaded = !transition.entering && transition.sourceStage != nowStage
                && session == null;
        if (targetLoaded && transition.targetReadyNanos == 0L) {
            transition.targetReadyNanos = System.nanoTime();
        }
        if (transition.targetReadyNanos != 0L
                && System.nanoTime() - transition.targetReadyNanos
                >= transition.fadeOutNanos()) {
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

    public static boolean active() {
        return active != null;
    }

    public static void clear() {
        active = null;
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        Transition transition = active;
        if (transition == null) {
            return;
        }
        float alpha = transition.alpha(System.nanoTime(), partialTick);
        if (alpha <= 0.001F) {
            return;
        }
        int color = ((int) (Math.min(1.0F, alpha) * 255.0F) << 24) | 0x07101C;
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), color);
    }

    private static final class Transition {
        private final UUID instanceId;
        private final boolean entering;
        private final int durationTicks;
        private final boolean sourceStage;
        private final long startedNanos;
        private long targetReadyNanos;

        private Transition(UUID instanceId, boolean entering, int durationTicks,
                           boolean sourceStage, long startedNanos) {
            this.instanceId = instanceId;
            this.entering = entering;
            this.durationTicks = durationTicks;
            this.sourceStage = sourceStage;
            this.startedNanos = startedNanos;
        }

        private long fadeOutNanos() {
            return FADE_OUT_TICKS * 50_000_000L;
        }

        private float alpha(long now, float partialTick) {
            double elapsedTicks = (now - startedNanos) / 50_000_000.0D + Math.max(0.0F, Math.min(1.0F, partialTick));
            double fadeIn = FADE_IN_TICKS;
            if (elapsedTicks < fadeIn) {
                return smoothstep((float) (elapsedTicks / fadeIn)) * 0.88F;
            }
            if (targetReadyNanos == 0L) {
                return 0.88F;
            }
            double fadeElapsed = (now - targetReadyNanos) / 50_000_000.0D + Math.max(0.0F, Math.min(1.0F, partialTick));
            return Math.max(0.0F, 0.88F * (1.0F - smoothstep((float) (fadeElapsed / FADE_OUT_TICKS))));
        }

        private static float smoothstep(float value) {
            float clamped = Math.max(0.0F, Math.min(1.0F, value));
            return clamped * clamped * (3.0F - 2.0F * clamped);
        }
    }
}
