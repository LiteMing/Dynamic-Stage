package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import vibe.liteming.dynamicstage.network.StageTransitionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.UUID;

/**
 * Owns a lightweight RPG-style frame transition. It never mounts or unmounts
 * a LOD backend; Voxy remains owned by the normal stage session lifecycle.
 */
public final class StageTransitionManager {
    private static final long MAX_WAIT_NANOS = 15_000_000_000L;
    private static volatile Transition active;

    private StageTransitionManager() {
    }

    public static void begin(StageTransitionPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean sourceStage = minecraft.level != null && StageWorlds.isStageLevel(minecraft.level);
        active = new Transition(packet.transitionId(), packet.instanceId(), packet.entering(),
                packet.durationTicks(), sourceStage, System.nanoTime());
        if (minecraft.screen instanceof ReceivingLevelScreen) {
            minecraft.setScreen(null);
        }
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PORTAL_TRIGGER, 0.55F));
    }

    public static void tick() {
        Transition transition = active;
        if (transition == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (minecraft.screen instanceof ReceivingLevelScreen) {
            minecraft.setScreen(null);
        }
        if (now - transition.startedNanos > MAX_WAIT_NANOS) {
            active = null;
            return;
        }
        if (!transition.entering && minecraft.level != null
                && transition.sourceStage != StageWorlds.isStageLevel(minecraft.level)) {
            transition.targetReadyNanos = transition.targetReadyNanos == 0L ? now : transition.targetReadyNanos;
        }
        if (transition.targetReadyNanos != 0L
                && now - transition.targetReadyNanos >= transition.fadeOutNanos()) {
            active = null;
        }
    }

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
        long now = System.nanoTime();
        float alpha = transition.alpha(now, partialTick);
        if (alpha <= 0.001F) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int color = ((int) (Math.min(0.94F, alpha) * 255.0F) << 24) | 0x07101C;
        graphics.fill(0, 0, width, height, color);

        // Moving bands provide a readable scene transition while the source
        // frame remains underneath. No screen or Voxy renderer is held.
        float motion = (float) ((now - transition.startedNanos) / 1_000_000_000.0D);
        int center = (int) ((motion * 180.0F) % Math.max(1, height + 160)) - 80;
        for (int index = -2; index <= 2; index++) {
            int y = center + index * 44;
            if (y < -8 || y > height) {
                continue;
            }
            int bandAlpha = (int) (Math.min(0.35F, alpha * 0.35F) * 255.0F);
            graphics.fill(0, y, width, Math.min(height, y + 3), (bandAlpha << 24) | 0x6DD6FF);
        }
        graphics.fill(width / 2 - 1, height / 2 - 24, width / 2 + 1, height / 2 + 24,
                ((int) (Math.min(0.24F, alpha * 0.24F) * 255.0F) << 24) | 0xFFFFFF);
    }

    private static final class Transition {
        private final UUID transitionId;
        private final UUID instanceId;
        private final boolean entering;
        private final int durationTicks;
        private final boolean sourceStage;
        private final long startedNanos;
        private long targetReadyNanos;

        private Transition(UUID transitionId, UUID instanceId, boolean entering, int durationTicks,
                           boolean sourceStage, long startedNanos) {
            this.transitionId = transitionId;
            this.instanceId = instanceId;
            this.entering = entering;
            this.durationTicks = durationTicks;
            this.sourceStage = sourceStage;
            this.startedNanos = startedNanos;
        }

        private long fadeOutNanos() {
            return Math.max(2L, durationTicks - Math.round(durationTicks * 0.25D)) * 50_000_000L;
        }

        private float alpha(long now, float partialTick) {
            double elapsed = (now - startedNanos) / 50_000_000.0D
                    + Math.max(0.0F, Math.min(1.0F, partialTick));
            double fadeIn = Math.max(2.0D, Math.round(durationTicks * 0.25D));
            if (elapsed < fadeIn) {
                return smoothstep((float) (elapsed / fadeIn)) * 0.92F;
            }
            if (targetReadyNanos == 0L) {
                return 0.92F;
            }
            double fadeElapsed = (now - targetReadyNanos) / 50_000_000.0D
                    + Math.max(0.0F, Math.min(1.0F, partialTick));
            double fadeOut = Math.max(2.0D, durationTicks - Math.round(durationTicks * 0.25D));
            return Math.max(0.0F, 0.92F * (1.0F - smoothstep((float) (fadeElapsed / fadeOut))));
        }

        private static float smoothstep(float value) {
            float clamped = Math.max(0.0F, Math.min(1.0F, value));
            return clamped * clamped * (3.0F - 2.0F * clamped);
        }
    }
}
