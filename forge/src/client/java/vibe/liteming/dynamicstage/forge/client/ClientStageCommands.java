package vibe.liteming.dynamicstage.forge.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.client.StageSkySettings;

@Mod.EventBusSubscriber(modid = DynamicStage.MOD_ID, value = net.minecraftforge.api.distmarker.Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientStageCommands {
    private ClientStageCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("dstage")
                .then(Commands.literal("sky")
                        .then(Commands.literal("overworld").executes(context -> set(StageSkySettings.Mode.OVERWORLD)))
                        .then(Commands.literal("end").executes(context -> set(StageSkySettings.Mode.END)))
                        .then(Commands.literal("off").executes(context -> set(StageSkySettings.Mode.OFF)))));
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
