package vibe.liteming.dynamicstage.client;

import net.minecraft.client.Minecraft;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.lod.LodPackDownloadManager;
import vibe.liteming.dynamicstage.client.lod.VoxyLodCollision;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.stage.StageBoundaryAccess;
import vibe.liteming.dynamicstage.client.editor.StageTemplateEditorState;
import vibe.liteming.dynamicstage.client.gui.StageBrowserState;

public final class StageClientEvents {
    private StageClientEvents() {
    }

    public static void disconnect() {
        LodPackDownloadManager.disconnect();
        VoxyLodCollision.reset();
        ClientStageSession.clearLocal();
        StageTemplateEditorState.clear();
        StageBrowserState.clear();
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        StageBoundaryAccess.bindClientPlayer(mc.player.getUUID());
        if (StageWorlds.isStageLevel(mc.level)) {
            ClientStageSession.tickBackdropSwitch();
            if (ClientStageSession.activateLodIfNeeded()) {
                StageFlightController.tick();
                VoxyLodCollision.tick();
            }
        } else if (ClientStageSession.active() == null) {
            StageFlightController.clear();
        }
    }
}
