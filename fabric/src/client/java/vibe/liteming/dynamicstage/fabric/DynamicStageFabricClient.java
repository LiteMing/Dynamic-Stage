package vibe.liteming.dynamicstage.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import vibe.liteming.dynamicstage.client.boundary.StageBoundaryRenderer;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import vibe.liteming.dynamicstage.client.StageClientEvents;
import vibe.liteming.dynamicstage.network.DynamicStageClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("dstage").then(ClientCommandManager.literal("sky")
                        .then(ClientCommandManager.literal("overworld").executes(context -> set(StageSkySettings.Mode.OVERWORLD)))
                        .then(ClientCommandManager.literal("end").executes(context -> set(StageSkySettings.Mode.END)))
                        .then(ClientCommandManager.literal("off").executes(context -> set(StageSkySettings.Mode.OFF))))));
    }

    private static int set(StageSkySettings.Mode mode) {
        StageSkySettings.setMode(mode);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Dynamic Stage sky: " + mode.name().toLowerCase(java.util.Locale.ROOT)), false);
        }
        return 1;
    }
}
