package vibe.liteming.dynamicstage.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import vibe.liteming.dynamicstage.client.boundary.StageBoundaryRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import vibe.liteming.dynamicstage.client.StageClientEvents;
import vibe.liteming.dynamicstage.network.DynamicStageClientNetwork;

public final class DynamicStageFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DynamicStageClientNetwork.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> StageClientEvents.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> StageClientEvents.disconnect());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (context.matrixStack() != null) {
                StageBoundaryRenderer.render(context.matrixStack(), context.camera());
            }
        });
    }
}
