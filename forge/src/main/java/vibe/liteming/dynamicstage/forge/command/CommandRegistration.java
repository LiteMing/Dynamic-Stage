package vibe.liteming.dynamicstage.forge.command;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.command.DynamicStageCommands;

@Mod.EventBusSubscriber(modid = DynamicStage.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommandRegistration {

    private CommandRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DynamicStageCommands.register(event.getDispatcher());
    }
}
