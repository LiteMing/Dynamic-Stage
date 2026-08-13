package vibe.liteming.dynamicstage.client.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.lod.StageBackdropRuntime;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageSessionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageBoundaryAccess;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/** Client-only projection of the server-authoritative local stage membership. */
public final class ClientStageSession {

    private static final int MAX_ACTIVATION_ATTEMPTS = 200;
    @Nullable private static volatile Snapshot active;
    @Nullable private static UUID readyAfterActivation;
    private static int activationAttempts;

    private ClientStageSession() {
    }

    public static void accept(StageSessionPacket packet) {
        if (!packet.active()) {
            clearLocal();
            return;
        }
        Snapshot snapshot = new Snapshot(packet.instanceId(), packet.stageId(), packet.lodPackId(),
                packet.lodAnchor(), packet.stageOrigin(), packet.capacity(), packet.boundary(), packet.flightHash(),
                packet.flightBytes(), packet.flightDurationMillis());
        Snapshot previous = active;
        active = snapshot;
        StageBoundaryAccess.setClient(packet.instanceId(), packet.stageOrigin(), packet.boundary());
        if (previous != null && previous.instanceId().equals(snapshot.instanceId())
                && previous.lodPackId().equals(snapshot.lodPackId())
                && StageBackdropRuntime.isMounted(snapshot.instanceId())) {
            return;
        }
        StageFlightController.clear();
        activationAttempts = 0;
        StageBackdropRuntime.Result result = StageBackdropRuntime.mount(snapshot);
        if (!result.ready()) {
            active = null;
            StageBoundaryAccess.clearClient();
            readyAfterActivation = null;
            StageBackdropRuntime.unmount();
            DynamicStageNetwork.clientReady(snapshot.instanceId(), false, result.error());
            return;
        }
        if (StageWorlds.isStageLevel(Minecraft.getInstance().level)) {
            readyAfterActivation = snapshot.instanceId();
        } else {
            readyAfterActivation = null;
            DynamicStageNetwork.clientReady(snapshot.instanceId(), true, "");
        }
    }

    public static void clearLocal() {
        active = null;
        StageBoundaryAccess.clearClient();
        readyAfterActivation = null;
        activationAttempts = 0;
        StageFlightController.clear();
        StageBackdropRuntime.unmount();
    }

    @Nullable
    public static Snapshot active() {
        return active;
    }

    public static boolean activateLodIfNeeded() {
        Snapshot snapshot = active;
        if (snapshot == null || !StageBackdropRuntime.needsStageActivation(snapshot.instanceId())) {
            return snapshot != null;
        }
        StageBackdropRuntime.Result result = StageBackdropRuntime.activateStage(snapshot.instanceId());
        if (!result.ready() && ++activationAttempts < MAX_ACTIVATION_ATTEMPTS) {
            return false;
        }
        UUID deferredReady = readyAfterActivation;
        readyAfterActivation = null;
        if (!result.ready()) {
            active = null;
            StageBoundaryAccess.clearClient();
            StageFlightController.clear();
            StageBackdropRuntime.unmount();
            DynamicStageNetwork.clientReady(snapshot.instanceId(), false, result.error());
            return false;
        } else if (snapshot.instanceId().equals(deferredReady)) {
            DynamicStageNetwork.clientReady(snapshot.instanceId(), true, "");
        }
        activationAttempts = 0;
        return true;
    }

    public record Snapshot(UUID instanceId, String stageId, ResourceLocation lodPackId, BlockPos lodAnchor,
                           BlockPos stageOrigin, int capacity, StageBoundary boundary,
                           String flightHash, int flightBytes,
                           long flightDurationMillis) {

        public boolean hasFlight() {
            return !flightHash.isEmpty();
        }
    }
}
