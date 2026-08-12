package vibe.liteming.dynamicstage;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;

@Mod(DynamicStage.MOD_ID)
public class DynamicStage {
    public static final String MOD_ID = "dynamicstage";

    public DynamicStage() {
        DynamicStageNetwork.register();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
