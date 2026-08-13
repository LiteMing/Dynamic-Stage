package vibe.liteming.dynamicstage.forge.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.client.StageClientEvents;
import vibe.liteming.dynamicstage.network.DynamicStageClientNetwork;

@Mod.EventBusSubscriber(modid = DynamicStage.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientForgeEvents {
    static {
        DynamicStageClientNetwork.register();
    }

    private ClientForgeEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        StageClientEvents.disconnect();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            StageClientEvents.tick();
        }
    }
}
