package vibe.liteming.dynamicstage.platform.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;

public final class StagePlatformImpl {
    private static final String PREFIX = "dynamicstage.instance.";

    private StagePlatformImpl() {
    }

    public static void setInstanceMarker(ServerPlayer player, UUID instanceId) {
        clearInstanceMarker(player);
        player.addTag(PREFIX + instanceId);
    }

    public static void clearInstanceMarker(ServerPlayer player) {
        player.getTags().stream().filter(tag -> tag.startsWith(PREFIX)).toList().forEach(player::removeTag);
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
