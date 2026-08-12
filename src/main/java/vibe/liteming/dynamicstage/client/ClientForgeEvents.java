package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.client.backdrop.BackdropClientDownloader;
import vibe.liteming.dynamicstage.client.backdrop.BackdropRenderer;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = DynamicStage.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientForgeEvents {

    private static final AtomicBoolean LOADED_FOR_LEVEL = new AtomicBoolean(false);

    private ClientForgeEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        BackdropRenderer.render(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        StageFlightController.clear();
        ClientStageSession.clearLocal();
    }

    /** Maintains the downloaded backdrop lifecycle across stage transitions. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ClientStageSession.clearLocal();
            if (LOADED_FOR_LEVEL.getAndSet(false)) {
                BackdropRenderer.clearBackdrop();
            }
            return;
        }
        BackdropClientDownloader.tick();
        StageFlightController.tick();
        boolean inStage = StageWorlds.isStageLevel(mc.level);
        if (inStage && !LOADED_FOR_LEVEL.getAndSet(true)) {
            if (ClientStageSession.active() == null) {
                BackdropRenderer.clearBackdrop();
            }
        } else if (!inStage) {
            StageFlightController.clear();
            if (LOADED_FOR_LEVEL.getAndSet(false)) {
                BackdropRenderer.clearBackdrop();
            }
        }
    }
}
