package vibe.liteming.dynamicstage.client.stage;

import net.minecraft.core.BlockPos;
import vibe.liteming.dynamicstage.client.backdrop.BackdropClientDownloader;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
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
            StageFlightController.clear();
            return;
        }
        Snapshot snapshot = new Snapshot(packet.stageId(), packet.backdropHash(),
                packet.backdropBytes(), packet.stageOrigin(), packet.flightHash(),
                packet.flightBytes(), packet.flightDurationMillis());
        active = snapshot;
        StageFlightController.clear();
        BackdropClientDownloader.prepare(snapshot);
    }

    public static void clearLocal() {
        if (active != null) {
            active = null;
            BackdropClientDownloader.clear();
            StageFlightController.clear();
        }
    }

    @Nullable
    public static Snapshot active() {
        return active;
    }

    public record Snapshot(String stageId, String backdropHash, long backdropBytes, BlockPos stageOrigin,
                           String flightHash, int flightBytes, long flightDurationMillis) {

        public boolean hasFlight() {
            return !flightHash.isEmpty();
        }
    }
}
