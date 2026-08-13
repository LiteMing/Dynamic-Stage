package vibe.liteming.dynamicstage.platform.forge;

import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.util.UUID;

public final class StagePlatformImpl {
    private StagePlatformImpl() {
    }

    public static void setInstanceMarker(ServerPlayer player, UUID instanceId) {
        player.getPersistentData().putUUID(StageSessionManager.INSTANCE_NBT, instanceId);
    }

    public static void clearInstanceMarker(ServerPlayer player) {
        player.getPersistentData().remove(StageSessionManager.INSTANCE_NBT);
    }
}
