package vibe.liteming.dynamicstage;

import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;

public final class DynamicStage {
    public static final String MOD_ID = "dynamicstage";

    private DynamicStage() {
    }

    public static void init() {
        DynamicStageNetwork.registerServer();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
