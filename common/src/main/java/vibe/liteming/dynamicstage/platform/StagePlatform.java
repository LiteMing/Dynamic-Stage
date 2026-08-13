package vibe.liteming.dynamicstage.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class StagePlatform {
    private StagePlatform() {
    }

    @ExpectPlatform
    public static void setInstanceMarker(ServerPlayer player, UUID instanceId) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void clearInstanceMarker(ServerPlayer player) {
        throw new AssertionError();
    }
}
