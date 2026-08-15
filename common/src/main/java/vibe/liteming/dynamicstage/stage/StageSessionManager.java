package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageFlightPacket;
import vibe.liteming.dynamicstage.network.StageBackdropSwitchPacket;
import vibe.liteming.dynamicstage.network.StageBackdropSwitchResultPacket;
import vibe.liteming.dynamicstage.platform.StagePlatform;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateStore;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server authority for stage instances, membership, and client backdrop readiness. */
public final class StageSessionManager {

    public static final String INSTANCE_NBT = "DynamicStageInstance";
    private static final int BACKDROP_SWITCH_TIMEOUT_MARGIN_TICKS = 20 * 15;
    private static final ConcurrentHashMap<UUID, PendingEntry> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> SENT_FLIGHTS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, BackdropSwitchState> BACKDROP_SWITCHES =
            new ConcurrentHashMap<>();

    private StageSessionManager() {
    }

    public static boolean createAndEnter(ServerPlayer player, String stageId, ResourceLocation lodPackId,
                                         BlockPos lodAnchor, int capacity) {
        MinecraftServer server = player.getServer();
        if (!canPrepare(player, server) || !validStageId(stageId)
                || capacity < 1 || capacity > StageSession.MAX_CAPACITY) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        int slot = allocateSlot(data);
        if (slot < 0) {
            player.sendSystemMessage(Component.literal("All Dynamic Stage instance regions are in use."));
            return false;
        }
        UUID instanceId = UUID.randomUUID();
        StageSession session = createMembership(player, instanceId, stageId, lodPackId, lodAnchor,
                slot, capacity, flightFor(server, stageId), -1L);
        return prepare(player, session);
    }

    public static boolean createAndEnterTemplate(ServerPlayer player, StageTemplate template) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        final StageFlightAssets.Asset flight;
        try {
            flight = StageTemplateStore.installFlight(server, template);
        } catch (java.io.IOException e) {
            player.sendSystemMessage(Component.literal("Could not install the template flight: " + e.getMessage()));
            return false;
        }
        if (template.instanceMode() == StageTemplate.InstanceMode.SHARED) {
            StageSession exemplar = StageSessionData.get(server).all().stream()
                    .filter(template::matches).findFirst().orElseGet(() -> PENDING.values().stream()
                            .map(PendingEntry::session).filter(template::matches).findFirst().orElse(null));
            if (exemplar != null) {
                return joinExisting(player, exemplar);
            }
        }
        if (!canPrepare(player, server)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        int slot = allocateSlot(data);
        if (slot < 0) {
            player.sendSystemMessage(Component.literal("All Dynamic Stage instance regions are in use."));
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            player.sendSystemMessage(Component.literal("The Dynamic Stage dimension is unavailable."));
            return false;
        }
        if (template.resetPolicy() == StageTemplate.ResetPolicy.ON_CREATE) {
            try {
                StageArenaSnapshot.restore(stageLevel, StagePlacement.originForSlot(slot),
                        template.boundary(), template.arenaSnapshot());
            } catch (java.io.IOException | RuntimeException e) {
                player.sendSystemMessage(Component.literal(
                        "Could not initialize the template arena: " + e.getMessage()));
                return false;
            }
        }
        long gameTime = player.serverLevel().getGameTime();
        StageClientScene scene = template.sceneForNewInstance(server.overworld().getDayTime(), gameTime);
        StageSession session = createMembership(player, UUID.randomUUID(), template.id(), template.lodPackId(),
                template.lodAnchor(), slot, template.capacity(), template.boundary(), scene, flight, -1L);
        return prepare(player, session);
    }

    public static boolean resetTemplateArena(ServerPlayer player, StageTemplate template) {
        MinecraftServer server = player.getServer();
        StageSession session = get(player).orElse(null);
        if (server == null || session == null || !session.stageId().equals(template.id())) {
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            return false;
        }
        try {
            StageArenaSnapshot.restore(stageLevel, session.stageOrigin(),
                    template.boundary(), template.arenaSnapshot());
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(Component.literal("Could not reset the template arena: " + e.getMessage()));
            return false;
        }
    }

    public static boolean join(ServerPlayer player, UUID instanceId) {
        MinecraftServer server = player.getServer();
        if (!canPrepare(player, server)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageSession exemplar = data.findInstance(instanceId).orElse(null);
        if (exemplar == null) {
            player.sendSystemMessage(Component.literal("Unknown or inactive Dynamic Stage instance."));
            return false;
        }
        return joinExisting(player, exemplar);
    }

    private static boolean joinExisting(ServerPlayer player, StageSession exemplar) {
        MinecraftServer server = player.getServer();
        if (!canPrepare(player, server)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        long members = data.members(exemplar.instanceId()).size()
                + PENDING.values().stream()
                .filter(entry -> entry.session.instanceId().equals(exemplar.instanceId())).count();
        if (members >= exemplar.capacity()) {
            player.sendSystemMessage(Component.literal("That Dynamic Stage instance is full."));
            return false;
        }
        if (exemplar.hasFlight() && !validFlight(server, exemplar)) {
            player.sendSystemMessage(Component.literal("That Dynamic Stage instance's flight is unavailable."));
            return false;
        }
        return prepare(player, createMembership(player, exemplar));
    }

    public static boolean setAnchor(ServerPlayer player, BlockPos anchor) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageSession session = data.get(player.getUUID()).orElse(null);
        if (session == null) {
            return false;
        }
        data.updateInstanceAnchor(session.instanceId(), anchor);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withLodAnchor(anchor))
                : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendSession(target, member.withLodAnchor(anchor));
            }
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(session.instanceId())) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        return true;
    }

    public static boolean switchLodPack(ServerPlayer player, ResourceLocation lodPackId,
                                        StageClientScene.Transition transition, int transitionTicks) {
        MinecraftServer server = player.getServer();
        if (server == null || lodPackId == null || transition == null) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageSession session = data.get(player.getUUID()).orElse(null);
        if (session == null) {
            return false;
        }
        if (session.lodPackId().equals(lodPackId)) {
            return true;
        }
        long deadline = server.overworld().getGameTime()
                + transitionTicks / 2L + BACKDROP_SWITCH_TIMEOUT_MARGIN_TICKS;
        BackdropSwitchState state = new BackdropSwitchState(
                session.lodPackId(), lodPackId, new HashSet<>(), deadline);
        if (BACKDROP_SWITCHES.putIfAbsent(session.instanceId(), state) != null) {
            player.sendSystemMessage(Component.literal("That stage instance is already switching LOD packages."));
            return false;
        }

        data.updateInstanceLodPack(session.instanceId(), lodPackId);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withLodPack(lodPackId)) : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target == null) {
                continue;
            }
            if (!StageWorlds.isStageLevel(target.level())) {
                DynamicStageNetwork.sendSession(target, member);
                continue;
            }
            state.awaiting.add(member.playerId());
            DynamicStageNetwork.sendBackdropSwitch(target, new StageBackdropSwitchPacket(
                    vibe.liteming.dynamicstage.network.StageSessionPacket.active(member),
                    transition, transitionTicks));
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(session.instanceId())) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        if (state.awaiting.isEmpty()) {
            BACKDROP_SWITCHES.remove(session.instanceId(), state);
        }
        return true;
    }

    public static void onBackdropSwitchResult(ServerPlayer player, StageBackdropSwitchResultPacket packet) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        BackdropSwitchState state = BACKDROP_SWITCHES.get(packet.instanceId());
        StageSession session = StageSessionData.get(server).get(player.getUUID()).orElse(null);
        if (state == null || session == null || !session.instanceId().equals(packet.instanceId())
                || !state.target.equals(packet.lodPackId()) || !state.awaiting.contains(player.getUUID())) {
            return;
        }
        if (packet.ready()) {
            state.awaiting.remove(player.getUUID());
            warnMissingLod(player, packet.error());
            if (state.awaiting.isEmpty()) {
                BACKDROP_SWITCHES.remove(packet.instanceId(), state);
            }
            return;
        }
        if (!BACKDROP_SWITCHES.remove(packet.instanceId(), state)) {
            return;
        }
        rollbackBackdropSwitch(server, packet.instanceId(), state, packet.error());
    }

    public static boolean setFlight(ServerPlayer player, StageFlightAssets.Asset flight,
                                    StageClientScene.Transition transition, int transitionTicks) {
        MinecraftServer server = player.getServer();
        StageSession session = get(player).orElse(null);
        if (server == null || session == null || flight == null
                || BACKDROP_SWITCHES.containsKey(session.instanceId())) {
            return false;
        }
        long startDelay = transition == StageClientScene.Transition.INSTANT
                ? 0L : Math.max(1, transitionTicks / 2);
        long start = player.serverLevel().getGameTime() + startDelay;
        StageSessionData data = StageSessionData.get(server);
        data.updateInstanceFlight(session.instanceId(), flight.hash(), flight.bytes(),
                flight.durationMillis(), start);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withFlight(flight.hash(), flight.bytes(),
                flight.durationMillis(), start)) : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendFlight(target,
                        StageFlightPacket.active(member, flight.sceneJson(), transition, transitionTicks));
                DynamicStageNetwork.sendSession(target, member);
                SENT_FLIGHTS.add(member.playerId());
            }
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(session.instanceId())) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        return true;
    }

    public static boolean clearFlight(ServerPlayer player, StageClientScene.Transition transition,
                                      int transitionTicks) {
        MinecraftServer server = player.getServer();
        StageSession session = get(player).orElse(null);
        if (server == null || session == null || BACKDROP_SWITCHES.containsKey(session.instanceId())) {
            return false;
        }
        if (!session.hasFlight()) {
            return true;
        }
        StageSessionData data = StageSessionData.get(server);
        data.updateInstanceFlight(session.instanceId(), "", 0, 0L, -1L);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withoutFlight()) : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendFlight(target,
                        StageFlightPacket.clear(member.stageId(), transition, transitionTicks));
                DynamicStageNetwork.sendSession(target, member);
                SENT_FLIGHTS.remove(member.playerId());
            }
        }
        return true;
    }

    public static boolean setBoundary(ServerPlayer player, StageBoundary boundary) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageSession session = data.get(player.getUUID()).orElse(null);
        if (session == null) {
            return false;
        }
        data.updateInstanceBoundary(session.instanceId(), boundary);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withBoundary(boundary))
                : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target == null) {
                continue;
            }
            StageSession updated = member.withBoundary(boundary);
            if (StageWorlds.isStageLevel(target.level())) {
                Vec3 clamped = boundary.clampPlayer(updated.stageOrigin(), target.position(),
                        target.getBbWidth(), target.getBbHeight());
                if (!clamped.equals(target.position())) {
                    target.teleportTo(target.serverLevel(), clamped.x, clamped.y, clamped.z,
                            target.getYRot(), target.getXRot());
                }
            }
            DynamicStageNetwork.sendSession(target, updated);
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(session.instanceId())) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        return true;
    }

    public static boolean setFollowPlayer(ServerPlayer player, boolean followPlayer) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withFollowPlayer(followPlayer));
    }

    public static boolean setLodMovementScale(ServerPlayer player, float scale) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withLodMovementScale(scale));
    }

    public static boolean setDhNearFadeScale(ServerPlayer player, float scale) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withDhNearFadeScale(scale));
    }

    public static boolean setVoxyNearCulling(ServerPlayer player, boolean enabled) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withVoxyNearCulling(enabled));
    }

    public static boolean setLodVisible(ServerPlayer player, boolean visible,
                                        StageClientScene.Transition transition, int transitionTicks) {
        StageSession session = get(player).orElse(null);
        if (session == null) {
            return false;
        }
        if (session.clientScene().lodVisible() == visible) {
            return true;
        }
        long gameTime = player.serverLevel().getGameTime();
        return updateClientScene(player, session.clientScene().withLodVisible(
                visible, transition, transitionTicks, gameTime));
    }

    public static boolean setLodBlur(ServerPlayer player, float blurRadius) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withLodBlurRadius(blurRadius));
    }

    public static boolean setClientTime(ServerPlayer player, StageClientScene.TimeMode mode,
                                        long baseDayTime, long cycleTicks) {
        MinecraftServer server = player.getServer();
        StageSession session = get(player).orElse(null);
        if (server == null || session == null) {
            return false;
        }
        long gameTime = player.serverLevel().getGameTime();
        StageClientScene scene = session.clientScene().withTime(mode, baseDayTime, gameTime,
                mode == StageClientScene.TimeMode.CYCLE ? cycleTicks : 0L);
        return updateClientScene(player, scene);
    }

    public static boolean setSkyMode(ServerPlayer player, StageClientScene.SkyMode mode) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player, session.clientScene().withSkyMode(mode));
    }

    public static boolean setClientScene(ServerPlayer player, StageClientScene scene) {
        return get(player).isPresent() && updateClientScene(player, scene);
    }

    /** Server-side safety net for the one-tick window around teleports and session updates. */
    public static void enforceBoundary(ServerPlayer player) {
        if (player == null || player.isSpectator() || !StageWorlds.isStageLevel(player.level())) {
            return;
        }
        StageSession session = get(player).orElse(null);
        if (session == null) {
            return;
        }
        Vec3 position = player.position();
        Vec3 clamped = session.boundary().clampPlayer(session.stageOrigin(), position,
                player.getBbWidth(), player.getBbHeight());
        Vec3 correction = clamped.subtract(position);
        double distance = correction.length();
        if (distance <= 1.0E-4D) {
            return;
        }
        // Small crossings receive an inward pull, which avoids a visible rubber
        // band while still preventing a player standing on an edge from falling.
        if (distance <= 1.5D) {
            Vec3 normal = correction.scale(1.0D / distance);
            Vec3 velocity = player.getDeltaMovement();
            double inward = velocity.dot(normal);
            if (inward < 0.0D) {
                velocity = velocity.subtract(normal.scale(inward));
                inward = 0.0D;
            }
            double pull = 0.08D + 0.42D * Math.min(1.0D, distance / 1.5D);
            if (inward < pull) {
                velocity = velocity.add(normal.scale(pull - inward));
            }
            player.setDeltaMovement(velocity);
            player.hurtMarked = true;
            player.hasImpulse = true;
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            return;
        }
        player.teleportTo(player.serverLevel(), clamped.x, clamped.y, clamped.z,
                player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.hasImpulse = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    private static boolean updateClientScene(ServerPlayer player, StageClientScene scene) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageSession session = data.get(player.getUUID()).orElse(null);
        if (session == null) {
            return false;
        }
        data.updateInstanceClientScene(session.instanceId(), scene);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? new PendingEntry(entry.session.withClientScene(scene)) : entry);
        for (StageSession member : data.members(session.instanceId())) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendSession(target, member);
            }
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(session.instanceId())) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        return true;
    }

    public static void onClientReady(ServerPlayer player, UUID instanceId, boolean ready, String error) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PendingEntry pending = PENDING.get(player.getUUID());
        if (pending != null && pending.session.instanceId().equals(instanceId)) {
            PENDING.remove(player.getUUID(), pending);
            if (!ready) {
                DynamicStageNetwork.clearSession(player);
                player.sendSystemMessage(Component.literal("LOD backdrop unavailable: " + boundedError(error)));
                return;
            }
            warnMissingLod(player, error);
            enterPrepared(player, pending.session);
            return;
        }

        StageSession restored = StageSessionData.get(server).get(player.getUUID()).orElse(null);
        if (restored != null && restored.instanceId().equals(instanceId)
                && StageWorlds.isStageLevel(player.level())) {
            if (!ready) {
                player.sendSystemMessage(Component.literal("LOD backdrop unavailable after reconnect: "
                        + boundedError(error)));
                exit(player);
            } else {
                warnMissingLod(player, error);
                sendOrStartFlight(player, restored);
            }
        }
    }

    public static boolean exit(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        boolean cancelled = PENDING.remove(player.getUUID()) != null;
        Optional<StageSession> removed = StageSessionData.get(server).remove(player.getUUID());
        SENT_FLIGHTS.remove(player.getUUID());
        removeBackdropSwitchWait(player.getUUID());
        clearPlayerMarker(player);
        if (removed.isEmpty()) {
            DynamicStageNetwork.clearSession(player);
            return cancelled;
        }
        StageSession session = removed.get();
        ServerLevel returnLevel = server.getLevel(session.returnDimension());
        if (returnLevel == null) {
            returnLevel = server.overworld();
        }
        player.stopRiding();
        player.fallDistance = 0.0F;
        // Release the client-side LOD package before the respawn packet makes
        // the new level renderer open Voxy's normal world storage.
        DynamicStageNetwork.clearSession(player);
        player.teleportTo(returnLevel, session.returnPosition().x, session.returnPosition().y,
                session.returnPosition().z, session.returnYRot(), session.returnXRot());
        return true;
    }

    public static void releaseWithoutTeleport(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            StageSessionData.get(server).remove(player.getUUID());
        }
        PENDING.remove(player.getUUID());
        SENT_FLIGHTS.remove(player.getUUID());
        removeBackdropSwitchWait(player.getUUID());
        clearPlayerMarker(player);
        DynamicStageNetwork.clearSession(player);
    }

    public static void onLogout(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        SENT_FLIGHTS.remove(player.getUUID());
        removeBackdropSwitchWait(player.getUUID());
    }

    public static void onServerStopped() {
        PENDING.clear();
        SENT_FLIGHTS.clear();
        BACKDROP_SWITCHES.clear();
    }

    public static void tick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        for (var entry : BACKDROP_SWITCHES.entrySet()) {
            BackdropSwitchState state = entry.getValue();
            if (gameTime >= state.deadlineGameTime
                    && BACKDROP_SWITCHES.remove(entry.getKey(), state)) {
                rollbackBackdropSwitch(server, entry.getKey(), state,
                        "client LOD activation timed out");
            }
        }
    }

    public static void restore(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        StageSession session = StageSessionData.get(server).get(player.getUUID()).orElse(null);
        if (session == null) {
            clearPlayerMarker(player);
            DynamicStageNetwork.clearSession(player);
            if (StageWorlds.isStageLevel(player.level())) {
                BlockPos spawn = server.overworld().getSharedSpawnPos();
                player.teleportTo(server.overworld(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                        player.getYRot(), player.getXRot());
            }
            return;
        }
        if (!StageWorlds.isStageLevel(player.level())) {
            StageSessionData.get(server).remove(player.getUUID());
            clearPlayerMarker(player);
            DynamicStageNetwork.clearSession(player);
            return;
        }
        if (session.hasFlight() && !validFlight(server, session)) {
            player.sendSystemMessage(Component.literal("The saved stage flight is unavailable; returning safely."));
            exit(player);
            return;
        }
        markPlayer(player, session.instanceId());
        DynamicStageNetwork.sendSession(player, session);
    }

    public static Optional<StageSession> get(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? Optional.empty() : StageSessionData.get(server).get(player.getUUID());
    }

    private static boolean prepare(ServerPlayer player, StageSession session) {
        PENDING.put(player.getUUID(), new PendingEntry(session));
        DynamicStageNetwork.sendSession(player, session);
        player.sendSystemMessage(Component.literal("Checking local LOD pack '" + session.lodPackId() + "'..."));
        return true;
    }

    private static void enterPrepared(ServerPlayer player, StageSession session) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            DynamicStageNetwork.clearSession(player);
            player.sendSystemMessage(Component.literal("The Dynamic Stage dimension is unavailable."));
            return;
        }
        StageSessionData data = StageSessionData.get(server);
        if (data.get(player.getUUID()).isPresent() || slotClaimedByOtherInstance(data, session)) {
            DynamicStageNetwork.clearSession(player);
            player.sendSystemMessage(Component.literal("The Dynamic Stage instance changed while preparing."));
            return;
        }
        data.put(session);
        markPlayer(player, session.instanceId());
        BlockPos origin = session.stageOrigin();
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.teleportTo(stageLevel, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal("Entered stage '" + session.stageId() + "' instance "
                + session.instanceId() + '.'));
        sendOrStartFlight(player, session);
    }

    private static void sendOrStartFlight(ServerPlayer player, StageSession session) {
        if (!session.hasFlight() || !SENT_FLIGHTS.add(player.getUUID())) {
            return;
        }
        MinecraftServer server = player.getServer();
        StageSessionData data = StageSessionData.get(server);
        if (session.flightStartGameTime() < 0L) {
            long start = player.serverLevel().getGameTime() + 20L;
            data.updateInstanceFlightStart(session.instanceId(), start);
            session = data.get(player.getUUID()).orElse(session.withFlightStart(start));
        }
        StageFlightAssets.Asset asset = StageFlightAssets.load(server, session.stageId(), session.flightHash());
        if (asset == null) {
            SENT_FLIGHTS.remove(player.getUUID());
            return;
        }
        DynamicStageNetwork.sendFlight(player, StageFlightPacket.active(session, asset.sceneJson()));
    }

    private static StageSession createMembership(ServerPlayer player, UUID instanceId, String stageId,
                                                  ResourceLocation lodPackId, BlockPos anchor, int slot, int capacity,
                                                  StageFlightAssets.Asset flight, long flightStart) {
        return createMembership(player, instanceId, stageId, lodPackId, anchor, slot, capacity,
                StageBoundary.defaults(), StageClientScene.defaults(
                        player.getServer().overworld().getDayTime(), player.getServer().overworld().getGameTime()),
                flight, flightStart);
    }

    private static StageSession createMembership(ServerPlayer player, UUID instanceId, String stageId,
                                                  ResourceLocation lodPackId, BlockPos anchor, int slot, int capacity,
                                                  StageBoundary boundary, StageClientScene clientScene,
                                                  StageFlightAssets.Asset flight, long flightStart) {
        return new StageSession(player.getUUID(), instanceId, stageId, lodPackId, anchor, slot, capacity,
                boundary, clientScene,
                player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot(),
                flight == null ? "" : flight.hash(), flight == null ? 0 : flight.bytes(),
                flight == null ? 0L : flight.durationMillis(), flight == null ? -1L : flightStart);
    }

    private static StageSession createMembership(ServerPlayer player, StageSession instance) {
        return new StageSession(player.getUUID(), instance.instanceId(), instance.stageId(), instance.lodPackId(),
                instance.lodAnchor(), instance.slot(), instance.capacity(), instance.boundary(), instance.clientScene(),
                player.serverLevel().dimension(),
                player.position(), player.getYRot(), player.getXRot(), instance.flightHash(), instance.flightBytes(),
                instance.flightDurationMillis(), instance.flightStartGameTime());
    }

    private static StageFlightAssets.Asset flightFor(MinecraftServer server, String stageId) {
        return StageFlightAssets.findConfigured(server.getWorldPath(LevelResource.ROOT), stageId);
    }

    private static boolean validFlight(MinecraftServer server, StageSession session) {
        StageFlightAssets.Asset flight = StageFlightAssets.load(server, session.stageId(), session.flightHash());
        return flight != null && flight.bytes() == session.flightBytes()
                && flight.durationMillis() == session.flightDurationMillis();
    }

    private static boolean canPrepare(ServerPlayer player, MinecraftServer server) {
        if (server == null || PENDING.containsKey(player.getUUID())) {
            return false;
        }
        if (StageSessionData.get(server).get(player.getUUID()).isPresent()) {
            player.sendSystemMessage(Component.literal("A Dynamic Stage session is already active."));
            return false;
        }
        return true;
    }

    private static int allocateSlot(StageSessionData data) {
        Set<Integer> occupied = new HashSet<>();
        data.all().forEach(session -> occupied.add(session.slot()));
        PENDING.values().forEach(entry -> occupied.add(entry.session.slot()));
        for (int slot = 0; slot < StagePlacement.MAX_SLOTS; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean slotClaimedByOtherInstance(StageSessionData data, StageSession candidate) {
        return data.all().stream().anyMatch(session -> session.slot() == candidate.slot()
                && !session.instanceId().equals(candidate.instanceId()));
    }

    private static boolean validStageId(String stageId) {
        return stageId != null && !stageId.isBlank() && stageId.length() <= 128;
    }

    private static String boundedError(String error) {
        if (error == null || error.isBlank()) {
            return "unknown client error";
        }
        return error.length() <= 256 ? error : error.substring(0, 256);
    }

    private static void warnMissingLod(ServerPlayer player, String warning) {
        if (warning != null && !warning.isBlank()) {
            player.sendSystemMessage(Component.literal(
                    "LOD backdrop missing; entering the stage without it: " + boundedError(warning)));
        }
    }

    private static void markPlayer(ServerPlayer player, UUID instanceId) {
        StagePlatform.setInstanceMarker(player, instanceId);
    }

    private static void clearPlayerMarker(ServerPlayer player) {
        StagePlatform.clearInstanceMarker(player);
    }

    private static void removeBackdropSwitchWait(UUID playerId) {
        BACKDROP_SWITCHES.entrySet().removeIf(entry -> {
            entry.getValue().awaiting.remove(playerId);
            return entry.getValue().awaiting.isEmpty();
        });
    }

    private static void rollbackBackdropSwitch(MinecraftServer server, UUID instanceId,
                                               BackdropSwitchState state, String error) {
        StageSessionData data = StageSessionData.get(server);
        data.updateInstanceLodPack(instanceId, state.previous);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(instanceId)
                ? new PendingEntry(entry.session.withLodPack(state.previous)) : entry);
        String message = boundedError(error);
        for (StageSession member : data.members(instanceId)) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendSession(target, member);
                target.sendSystemMessage(Component.literal("LOD switch rolled back: " + message));
            }
        }
    }

    private record PendingEntry(StageSession session) {
    }

    private record BackdropSwitchState(ResourceLocation previous, ResourceLocation target,
                                       Set<UUID> awaiting, long deadlineGameTime) {
    }
}
