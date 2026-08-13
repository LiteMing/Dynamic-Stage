package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.stage.StageBoundaryAccess;

public final class StageClientEvents {
    private StageClientEvents() {
    }

    public static void disconnect() {
        ClientStageSession.clearLocal();
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        StageBoundaryAccess.bindClientPlayer(mc.player.getUUID());
        if (StageWorlds.isStageLevel(mc.level)) {
            if (ClientStageSession.activateLodIfNeeded()) {
                StageFlightController.tick();
            }
        } else if (ClientStageSession.active() == null) {
            StageFlightController.clear();
        }
    }
}
