package vibe.liteming.dynamicstage;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(DynamicStage.MOD_ID)
public class DynamicStage {
    public static final String MOD_ID = "dynamicstage";
    public static final String MODID = MOD_ID;

    public DynamicStage() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void onServerStarting(ServerStartingEvent event) {
        // Stage template + bake runtime initialisation lands here.
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
