package vibe.liteming.dynamicstage.client.stage;

import net.minecraft.core.BlockPos;
import vibe.liteming.dynamicstage.client.backdrop.BackdropClientDownloader;
import vibe.liteming.dynamicstage.network.StageSessionPacket;

import javax.annotation.Nullable;

/** Client-only projection of the server-authoritative local stage session. */
public final class ClientStageSession {

    @Nullable
    private static volatile Snapshot active;

    private ClientStageSession() {
    }

    public static void accept(StageSessionPacket packet) {
        if (!packet.active()) {
            active = null;
            BackdropClientDownloader.clear();
            return;
        }
        Snapshot snapshot = new Snapshot(packet.stageId(), packet.backdropHash(),
                packet.backdropBytes(), packet.stageOrigin());
        active = snapshot;
        BackdropClientDownloader.prepare(snapshot);
    }

    public static void clearLocal() {
        if (active != null) {
            active = null;
            BackdropClientDownloader.clear();
        }
    }

    @Nullable
    public static Snapshot active() {
        return active;
    }

    public record Snapshot(String stageId, String backdropHash, long backdropBytes, BlockPos stageOrigin) {
    }
}
