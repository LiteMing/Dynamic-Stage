package vibe.liteming.dynamicstage.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import vibe.liteming.dynamicstage.DynamicStage;

@Mod(DynamicStage.MOD_ID)
public final class DynamicStageForge {
    public DynamicStageForge() {
        EventBuses.registerModEventBus(DynamicStage.MOD_ID,
                FMLJavaModLoadingContext.get().getModEventBus());
        DynamicStage.init();
    }
}
