package vibe.liteming.dynamicstage.platform.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.util.UUID;
import java.nio.file.Path;

public final class StagePlatformImpl {
    private StagePlatformImpl() {
    }

    public static void setInstanceMarker(ServerPlayer player, UUID instanceId) {
        player.getPersistentData().putUUID(StageSessionManager.INSTANCE_NBT, instanceId);
    }

    public static void clearInstanceMarker(ServerPlayer player) {
        player.getPersistentData().remove(StageSessionManager.INSTANCE_NBT);
    }

    public static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }
}
