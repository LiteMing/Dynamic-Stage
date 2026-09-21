package vibe.liteming.dynamicstage.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;
import vibe.liteming.dynamicstage.network.*;
import vibe.liteming.dynamicstage.world.StageWorlds;
import java.util.UUID;

/** A captured frame owns the display before any LOD mount or dimension transfer is authorized. */
public final class StageTransitionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageTransitionManager.class);
    private static Transition active;
    private StageTransitionManager() { }

    public static void begin(StageTransitionPacket packet) {
        clear();
        Minecraft mc = Minecraft.getInstance();
        active = new Transition(packet, System.nanoTime());
        // Copy the previous completed frame on the render thread. No world renderer is retained.
        RenderTarget source = mc.getMainRenderTarget();
        try {
            active.frame = new TextureTarget(source.width, source.height, false, Minecraft.ON_OSX);
            copy(source, active.frame);
        } catch (RuntimeException error) {
            LOGGER.warn("[StageTransition {}] frame capture failed; using opaque fallback", packet.transitionId(), error);
            if (active.frame != null) active.frame.destroyBuffers();
            active.frame = null;
        } finally {
            source.bindWrite(true);
        }
        mc.setScreen(new StageTransitionScreen());
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PORTAL_TRIGGER, 0.55F));
        LOGGER.info("[StageTransition {}] begin instance={} entering={} source={} captured={}",
                packet.transitionId(), packet.instanceId(), packet.entering(),
                mc.level == null ? "null" : mc.level.dimension().location(), active.frame != null);
    }

    /** Both vanilla screens are replaced, including setLevel's forced ProgressScreen render. */
    public static Screen replaceLoadingScreen(Screen screen) {
        if (active != null && (screen instanceof ProgressScreen || screen instanceof ReceivingLevelScreen)) {
            LOGGER.info("[StageTransition {}] replace {}", active.packet.transitionId(), screen.getClass().getName());
            return new StageTransitionScreen();
        }
        return screen;
    }

    public static void arrived(StageTransitionArrivedPacket packet) {
        Transition t = active;
        if (t == null || !t.packet.transitionId().equals(packet.transitionId())
                || !t.packet.instanceId().equals(packet.instanceId())) return;
        t.positionReceived = true;
        t.timeline.arrived(System.nanoTime());
        LOGGER.info("[StageTransition {}] transfer packets applied; waiting for target renderer", packet.transitionId());
    }

    public static void tick() {
        Transition t = active;
        if (t == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mc.getConnection() == null) { clear(); return; }
        Screen replacement = replaceLoadingScreen(mc.screen);
        if (replacement != mc.screen) mc.setScreen(replacement);
        if (t.cancelledAt != -1L) {
            if (!VoxyBackdropRuntime.normalRestorePending() && terrainReady()) t.targetReady = true;
            if (t.timeline.finished(now)) { clear(); return; }
            if (now - t.cancelledAt >= 5_000_000_000L) {
                clear();
                if (!terrainReady()) mc.setScreen(new ReceivingLevelScreen());
            }
            return;
        }
        if (t.timeline.failed()) {
            if (t.timeline.fallbackDue(now)) {
                LOGGER.error("[StageTransition {}] rollback acknowledgement timed out; restoring vanilla UI", t.packet.transitionId());
                clear();
                if (!terrainReady()) mc.setScreen(new ReceivingLevelScreen());
            }
            return;
        }
        boolean downloading = t.packet.entering() && !t.positionReceived;
        if (t.timeline.timedOut(now, downloading)) {
            t.timeline.fail(now);
            send(t, StageTransitionAckPacket.Signal.FAILED);
            LOGGER.warn("[StageTransition {}] timed out, waiting for server rollback", t.packet.transitionId());
            return;
        }
        if (!t.packet.entering() && t.positionReceived && !StageWorlds.isStageLevel(mc.level)
                && !VoxyBackdropRuntime.normalRestorePending() && terrainReady()) t.targetReady = true;
        if (t.timeline.finished(now)) {
            send(t, StageTransitionAckPacket.Signal.COMPLETE);
            LOGGER.info("[StageTransition {}] complete", t.packet.transitionId());
            clear();
        }
    }

    private static boolean terrainReady() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && (mc.player.isSpectator()
                || mc.level.isOutsideBuildHeight(mc.player.blockPosition())
                || mc.levelRenderer.isChunkCompiled(mc.player.blockPosition()));
    }

    public static void markTargetReady(UUID instanceId) {
        Transition t = active;
        if (t != null && t.packet.entering() && t.packet.instanceId().equals(instanceId)
                && t.positionReceived && StageWorlds.isStageLevel(Minecraft.getInstance().level)
                && terrainReady()) t.targetReady = true;
    }

    public static void cancel(StageTransitionCancelPacket packet) {
        Transition t = active;
        if (t == null || !t.packet.transitionId().equals(packet.transitionId())
                || !t.packet.instanceId().equals(packet.instanceId())) return;
        LOGGER.warn("[StageTransition {}] server cancelled/returned", packet.transitionId());
        if (!t.timeline.hasPresented()) {
            clear();
        } else {
            t.cancelledAt = System.nanoTime();
            t.targetReady = false;
            t.timeline.cancelFailure();
        }
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(
                Component.translatable("message.dynamicstage.transition.cancelled"), false);
    }

    public static boolean active() { return active != null; }
    public static void clear() {
        Transition t = active;
        active = null;
        if (t != null && t.frame != null) t.frame.destroyBuffers();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StageTransitionScreen) mc.setScreen(null);
    }

    /** Called after the window buffer swap; this is the only source of FRAME_PRESENTED. */
    public static void framePresented() {
        Transition t = active;
        if (t != null && t.timeline.presented()) {
            LOGGER.info("[StageTransition {}] opaque frame presented; authorizing preparation", t.packet.transitionId());
            send(t, StageTransitionAckPacket.Signal.FRAME_PRESENTED);
        }
    }

    private static void send(Transition t, StageTransitionAckPacket.Signal signal) {
        DynamicStageNetwork.transitionAck(new StageTransitionAckPacket(t.packet.transitionId(), t.packet.instanceId(), signal));
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        Transition t = active;
        if (t == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (t.targetReady && !t.timeline.revealing() && !t.timeline.failed()) {
            t.timeline.targetReady(now); // At least one target frame has now rendered.
            LOGGER.info("[StageTransition {}] target frame ready; revealing", t.packet.transitionId());
        }
        if (!t.timeline.revealing() && t.frame != null) copy(t.frame, mc.getMainRenderTarget());
        mc.getMainRenderTarget().bindWrite(true);
        // GameRenderer has popped its GUI matrix by TAIL. Establish and restore our own.
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, graphics.guiWidth(),
                graphics.guiHeight(), 0, 1000, 3000), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0, 0, -2000);
        RenderSystem.applyModelViewMatrix();
        try {
            float alpha = t.timeline.alpha(now);
            int width = graphics.guiWidth(), height = graphics.guiHeight();
            graphics.fill(0, 0, width, height, ((int) (alpha * 255F) << 24) | 0x07101C);
            // Finish motion before synchronous renderer work; the opaque hold is intentionally static.
            if (!t.timeline.hasPresented()) {
                int y = (int) ((1F - alpha) * height);
                graphics.fill(0, y, width, Math.min(height, y + 3), 0xFF6DD6FF);
            }
            graphics.flush();
            t.timeline.drawn(now);
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void copy(RenderTarget source, RenderTarget target) {
        GlStateManager._glBindFramebuffer(36008, source.frameBufferId);
        GlStateManager._glBindFramebuffer(36009, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, 0,
                target.width, target.height, 16384, 9728);
        target.bindWrite(true);
    }

    private static final class Transition {
        final StageTransitionPacket packet;
        final StageTransitionTimeline timeline;
        RenderTarget frame;
        boolean positionReceived;
        boolean targetReady;
        long cancelledAt = -1L;
        Transition(StageTransitionPacket packet, long now) {
            this.packet = packet;
            timeline = new StageTransitionTimeline(packet.durationTicks(), now);
        }
    }
}
