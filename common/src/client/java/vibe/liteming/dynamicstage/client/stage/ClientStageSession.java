package vibe.liteming.dynamicstage.client.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.client.lod.StageBackdropRuntime;
import vibe.liteming.dynamicstage.client.lod.StageBackdropEffects;
import vibe.liteming.dynamicstage.client.lod.LodPackDownloadManager;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageBackdropSwitchPacket;
import vibe.liteming.dynamicstage.network.StageSessionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.stage.StageBoundaryAccess;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;
import vibe.liteming.dynamicstage.lod.StageLodPacks;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/** Client-only projection of the server-authoritative local stage membership. */
public final class ClientStageSession {

    private static final int MAX_ACTIVATION_ATTEMPTS = 200;
    @Nullable private static volatile Snapshot active;
    @Nullable private static UUID readyAfterActivation;
    private static int activationAttempts;
    private static long preparationSerial;
    @Nullable private static SwitchState backdropSwitch;

    private ClientStageSession() {
    }

    public static void accept(StageSessionPacket packet) {
        if (!packet.active()) {
            clearLocal();
            return;
        }
        Snapshot snapshot = snapshot(packet);
        SwitchState switching = backdropSwitch;
        if (switching != null && switching.target.instanceId().equals(snapshot.instanceId())
                && switching.target.lodPackId().equals(snapshot.lodPackId())) {
            switching.target = snapshot;
            if (switching.phase != SwitchPhase.PREPARE && switching.phase != SwitchPhase.OUT) {
                applySnapshotState(snapshot);
                active = snapshot;
            }
            StageFlightController.confirmSession(snapshot.flightHash());
            return;
        }
        Snapshot previous = active;
        backdropSwitch = null;
        active = snapshot;
        applySnapshotState(snapshot);
        if (previous != null && previous.instanceId().equals(snapshot.instanceId())
                && previous.lodPackId().equals(snapshot.lodPackId())
                && StageBackdropRuntime.isMounted(snapshot.instanceId())) {
            StageFlightController.confirmSession(snapshot.flightHash());
            return;
        }
        if (previous == null || !previous.instanceId().equals(snapshot.instanceId())
                || !previous.flightHash().equals(snapshot.flightHash())) {
            StageFlightController.clear();
        }
        activationAttempts = 0;
        prepareInitialMount(snapshot);
    }

    private static void prepareInitialMount(Snapshot snapshot) {
        long serial = ++preparationSerial;
        if (StageLodPacks.NONE.equals(snapshot.lodPackId())) {
            StageBackdropRuntime.unmount();
            readyAfterActivation = null;
            DynamicStageNetwork.clientReady(snapshot.instanceId(), true, "");
            return;
        }
        LodPackDownloadManager.prepare(snapshot.lodPackId(), snapshot.lodOffer())
                .whenComplete((download, error) -> Minecraft.getInstance().execute(() -> {
                    Snapshot current = active;
                    if (serial != preparationSerial || current == null
                            || !current.instanceId().equals(snapshot.instanceId())
                            || !current.lodPackId().equals(snapshot.lodPackId())) {
                        return;
                    }
                    if (error != null) {
                        finishInitialPreparation(snapshot, false, rootMessage(error));
                        return;
                    }
                    if (!download.proceed()) {
                        finishInitialPreparation(snapshot, false, download.error());
                        return;
                    }
                    finishInitialPreparation(snapshot, true, download.error());
                }));
    }

    private static void finishInitialPreparation(Snapshot snapshot, boolean proceed, String warning) {
        if (!proceed) {
            readyAfterActivation = null;
            StageBackdropRuntime.unmount();
            active = null;
            StageBoundaryAccess.clearClient();
            DynamicStageNetwork.clientReady(snapshot.instanceId(), false, warning);
            return;
        }
        StageBackdropRuntime.Result result = StageBackdropRuntime.mount(snapshot);
        if (!result.ready()) {
            readyAfterActivation = null;
            StageBackdropRuntime.unmount();
            if (result.unavailable()) {
                DynamicStageNetwork.clientReady(snapshot.instanceId(), true, joinWarnings(warning, result.error()));
                return;
            }
            active = null;
            StageBoundaryAccess.clearClient();
            DynamicStageNetwork.clientReady(snapshot.instanceId(), false, joinWarnings(warning, result.error()));
            return;
        }
        if (StageWorlds.isStageLevel(Minecraft.getInstance().level)) {
            readyAfterActivation = snapshot.instanceId();
        } else {
            readyAfterActivation = null;
            DynamicStageNetwork.clientReady(snapshot.instanceId(), true, warning == null ? "" : warning);
        }
    }

    public static void switchBackdrop(StageBackdropSwitchPacket packet) {
        Snapshot target = snapshot(packet.session());
        Snapshot previous = active;
        if (previous == null || !previous.instanceId().equals(target.instanceId())) {
            accept(packet.session());
            return;
        }
        if (previous.lodPackId().equals(target.lodPackId())) {
            accept(packet.session());
            DynamicStageNetwork.backdropSwitchResult(target.instanceId(), target.lodPackId(), true, "");
            return;
        }
        boolean animate = previous.clientScene().lodVisible()
                && packet.transition() != StageClientScene.Transition.INSTANT
                && packet.transitionTicks() > 0;
        SwitchState state = new SwitchState(previous, target, packet.transition(), packet.transitionTicks(),
                gameTime(), SwitchPhase.PREPARE);
        backdropSwitch = state;
        long serial = ++preparationSerial;
        if (StageLodPacks.NONE.equals(target.lodPackId())) {
            state.phase = animate ? SwitchPhase.OUT : SwitchPhase.MOUNT;
            state.phaseStart = gameTime();
            state.preparationWarning = "";
            return;
        }
        LodPackDownloadManager.prepare(target.lodPackId(), target.lodOffer())
                .whenComplete((download, error) -> Minecraft.getInstance().execute(() -> {
                    if (serial != preparationSerial || backdropSwitch != state) {
                        return;
                    }
                    if (error != null || !download.proceed()) {
                        backdropSwitch = null;
                        DynamicStageNetwork.backdropSwitchResult(target.instanceId(), target.lodPackId(), false,
                                error == null ? download.error() : rootMessage(error));
                        return;
                    }
                    state.phase = animate ? SwitchPhase.OUT : SwitchPhase.MOUNT;
                    state.phaseStart = gameTime();
                    state.preparationWarning = download.error();
                }));
    }

    public static void clearLocal() {
        preparationSerial++;
        active = null;
        StageSkySettings.setMode(StageSkySettings.Mode.OVERWORLD);
        StageBoundaryAccess.clearClient();
        readyAfterActivation = null;
        activationAttempts = 0;
        backdropSwitch = null;
        StageFlightController.clear();
        StageBackdropEffects.clearSwap();
        StageBackdropRuntime.unmount();
    }

    @Nullable
    public static Snapshot active() {
        return active;
    }

    public static boolean activateLodIfNeeded() {
        Snapshot snapshot = active;
        if (backdropSwitch != null) {
            return snapshot != null;
        }
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

    public static void tickBackdropSwitch() {
        SwitchState state = backdropSwitch;
        Minecraft minecraft = Minecraft.getInstance();
        if (state == null || minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return;
        }
        long now = minecraft.level.getGameTime();
        if (state.phase == SwitchPhase.PREPARE) {
            return;
        }
        if (state.phase == SwitchPhase.OUT && now - state.phaseStart >= state.outTicks()) {
            state.phase = SwitchPhase.MOUNT;
        }
        if (state.phase == SwitchPhase.MOUNT) {
            mountSwitchTarget(state, now);
            return;
        }
        if (state.phase == SwitchPhase.ACTIVATE) {
            StageBackdropRuntime.Result result = StageBackdropRuntime.activateStage(state.target.instanceId());
            if (result.ready()) {
                finishMount(state, now, state.preparationWarning);
            } else if (++state.activationAttempts >= MAX_ACTIVATION_ATTEMPTS) {
                rollbackSwitch(state, result.error());
            }
            return;
        }
        if (state.phase == SwitchPhase.IN && now - state.phaseStart >= state.inTicks()) {
            backdropSwitch = null;
        }
    }

    public static StageBackdropEffects.State backdropEffects(StageClientScene scene,
                                                              long gameTime, float partialTick) {
        SwitchState state = backdropSwitch;
        if (state == null) {
            return StageBackdropEffects.sample(scene, gameTime, partialTick);
        }
        if (state.phase == SwitchPhase.OUT) {
            return StageBackdropEffects.sampleSwitch(state.previous.clientScene(), state.transition, true,
                    progress(gameTime, partialTick, state.phaseStart, state.outTicks()));
        }
        if (state.phase == SwitchPhase.IN) {
            return StageBackdropEffects.sampleSwitch(state.target.clientScene(), state.transition, false,
                    progress(gameTime, partialTick, state.phaseStart, state.inTicks()));
        }
        if (state.phase == SwitchPhase.PREPARE) {
            return StageBackdropEffects.sample(state.previous.clientScene(), gameTime, partialTick);
        }
        return StageBackdropEffects.sampleSwitch(state.target.clientScene(), state.transition, false, 0.0F);
    }

    private static void mountSwitchTarget(SwitchState state, long now) {
        StageBackdropRuntime.unmount();
        active = state.target;
        applySnapshotState(state.target);
        if (StageLodPacks.NONE.equals(state.target.lodPackId())) {
            finishMount(state, now, state.preparationWarning);
            return;
        }
        StageBackdropRuntime.Result result = StageBackdropRuntime.mount(state.target);
        if (result.unavailable()) {
            finishMount(state, now, joinWarnings(state.preparationWarning, result.error()));
            return;
        }
        if (!result.ready()) {
            rollbackSwitch(state, result.error());
            return;
        }
        if (StageBackdropRuntime.needsStageActivation(state.target.instanceId())) {
            state.phase = SwitchPhase.ACTIVATE;
            state.phaseStart = now;
            state.activationAttempts = 0;
        } else {
            finishMount(state, now, state.preparationWarning);
        }
    }

    private static void finishMount(SwitchState state, long now, String warning) {
        DynamicStageNetwork.backdropSwitchResult(state.target.instanceId(), state.target.lodPackId(), true,
                warning == null ? "" : warning);
        if (state.target.clientScene().lodVisible()
                && state.transition != StageClientScene.Transition.INSTANT && state.inTicks() > 0) {
            state.phase = SwitchPhase.IN;
            state.phaseStart = now;
        } else {
            backdropSwitch = null;
        }
    }

    private static void rollbackSwitch(SwitchState state, String error) {
        StageBackdropRuntime.unmount();
        active = state.previous;
        applySnapshotState(state.previous);
        StageBackdropRuntime.Result rollback = StageBackdropRuntime.mount(state.previous);
        if (rollback.ready() && StageBackdropRuntime.needsStageActivation(state.previous.instanceId())) {
            StageBackdropRuntime.activateStage(state.previous.instanceId());
        }
        backdropSwitch = null;
        DynamicStageNetwork.backdropSwitchResult(state.target.instanceId(), state.target.lodPackId(), false,
                error == null || error.isBlank() ? "unknown client LOD error" : error);
    }

    private static float progress(long gameTime, float partialTick, long start, int ticks) {
        if (ticks <= 0) {
            return 1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F,
                (gameTime - start + Math.max(0.0F, Math.min(1.0F, partialTick))) / ticks));
    }

    private static long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    private static Snapshot snapshot(StageSessionPacket packet) {
        return new Snapshot(packet.instanceId(), packet.stageId(), packet.lodPackId(),
                packet.lodAnchor(), packet.stageOrigin(), packet.capacity(), packet.boundary(), packet.flightHash(),
                packet.flightBytes(), packet.flightDurationMillis(), packet.clientScene(), packet.lodOffer());
    }

    private static void applySnapshotState(Snapshot snapshot) {
        StageSkySettings.setMode(StageSkySettings.Mode.valueOf(snapshot.clientScene().skyMode().name()));
        StageBoundaryAccess.setClient(snapshot.instanceId(), snapshot.stageOrigin(), snapshot.boundary());
        StageFlightController.confirmSession(snapshot.flightHash());
    }

    public record Snapshot(UUID instanceId, String stageId, ResourceLocation lodPackId, BlockPos lodAnchor,
                           BlockPos stageOrigin, int capacity, StageBoundary boundary,
                           String flightHash, int flightBytes,
                           long flightDurationMillis, StageClientScene clientScene,
                           LodPackageOffer lodOffer) {

        public boolean hasFlight() {
            return !flightHash.isEmpty();
        }
    }

    private enum SwitchPhase { PREPARE, OUT, MOUNT, ACTIVATE, IN }

    private static final class SwitchState {
        private final Snapshot previous;
        private Snapshot target;
        private final StageClientScene.Transition transition;
        private final int totalTicks;
        private long phaseStart;
        private SwitchPhase phase;
        private int activationAttempts;
        private String preparationWarning = "";

        private SwitchState(Snapshot previous, Snapshot target, StageClientScene.Transition transition,
                            int totalTicks, long phaseStart, SwitchPhase phase) {
            this.previous = previous;
            this.target = target;
            this.transition = transition;
            this.totalTicks = totalTicks;
            this.phaseStart = phaseStart;
            this.phase = phase;
        }

        private int outTicks() {
            return Math.max(1, totalTicks / 2);
        }

        private int inTicks() {
            return Math.max(1, totalTicks - outTicks());
        }
    }

    private static String joinWarnings(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "; " + second;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
