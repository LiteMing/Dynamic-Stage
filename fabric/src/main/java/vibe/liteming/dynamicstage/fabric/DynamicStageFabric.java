package vibe.liteming.dynamicstage.fabric;

import net.fabricmc.api.ModInitializer;
import vibe.liteming.dynamicstage.DynamicStage;

public final class DynamicStageFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DynamicStage.init();
        FabricStageEvents.register();
    }
}
