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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.lod.LodDistributionStore;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;
import vibe.liteming.dynamicstage.lod.LodServerTransferManager;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageFlightPacket;
import vibe.liteming.dynamicstage.network.StageBackdropSwitchPacket;
import vibe.liteming.dynamicstage.network.StageBackdropSwitchResultPacket;
import vibe.liteming.dynamicstage.platform.StagePlatform;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;
import vibe.liteming.dynamicstage.template.StageTemplateStore;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server authority for stage instances, membership, and client backdrop readiness. */
public final class StageSessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageSessionManager.class);
    public static final String INSTANCE_NBT = "DynamicStageInstance";
    private static final int BACKDROP_SWITCH_TIMEOUT_MARGIN_TICKS = 20 * 15;
    private static final ConcurrentHashMap<UUID, PendingEntry> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> SENT_FLIGHTS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, BackdropSwitchState> BACKDROP_SWITCHES =
            new ConcurrentHashMap<>();
    private static final Set<UUID> EDITING_PLAYERS = ConcurrentHashMap.newKeySet();

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
        return prepare(player, session, false, BlockPos.ZERO, StageTemplate.InteractionPolicy.ADVENTURE);
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
            StageSessionData data = StageSessionData.get(server);
            StageInstance instance = data.instances().stream().filter(template::matches)
                    .filter(candidate -> !BACKDROP_SWITCHES.containsKey(candidate.instanceId()))
                    .filter(candidate -> memberCount(data, candidate.instanceId()) < candidate.capacity())
                    .filter(candidate -> !candidate.hasFlight() || validFlight(server, candidate))
                    .findFirst().orElse(null);
            if (instance != null) {
                if (memberCount(data, instance.instanceId()) == 0
                        && !resetEmptyTemplateArena(server, instance, template)) {
                    return false;
                }
                return joinExisting(player, instance);
            }
            PendingEntry pending = PENDING.values().stream()
                    .filter(entry -> template.matches(entry.session))
                    .filter(entry -> entry.persistent
                            == (template.lifecyclePolicy() == StageTemplate.LifecyclePolicy.RETAIN))
                    .filter(entry -> entry.interactionPolicy == template.interactionPolicy())
                    .filter(entry -> memberCount(data, entry.session.instanceId()) < entry.session.capacity())
                    .filter(entry -> !entry.session.hasFlight() || validFlight(server, entry.session))
                    .findFirst().orElse(null);
            if (pending != null) {
                return joinExisting(player, pending.session, pending.persistent, pending.entryOffset,
                        pending.interactionPolicy);
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
        try {
            StageArenaSnapshot.restore(stageLevel, StagePlacement.originForSlot(slot),
                    template.boundary(), template.arenaSnapshot(), template.structures());
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(Component.literal(
                    "Could not initialize the template arena: " + e.getMessage()));
            return false;
        }
        long gameTime = player.serverLevel().getGameTime();
        StageClientScene scene = template.sceneForNewInstance(server.overworld().getDayTime(), gameTime);
        StageSession session = createMembership(player, UUID.randomUUID(), template.id(), template.lodPackId(),
                template.lodAnchor(), slot, template.capacity(), template.boundary(), scene, flight, -1L);
        return prepare(player, session, template.lifecyclePolicy() == StageTemplate.LifecyclePolicy.RETAIN,
                template.entryOffset(), template.interactionPolicy());
    }

    public static boolean resetTemplateArena(ServerPlayer player, StageTemplate template) {
        MinecraftServer server = player.getServer();
        StageSession session = get(player).orElse(null);
        if (server == null || session == null || !session.stageId().equals(template.id())) {
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            player.sendSystemMessage(Component.literal("The Dynamic Stage dimension is unavailable."));
            return false;
        }
        try {
            resetArena(stageLevel, session.stageOrigin(), template);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(Component.literal("Could not reset the template arena: " + e.getMessage()));
            return false;
        }
    }

    /** Replaces the active instance with a saved template while keeping its slot and members. */
    public static boolean reloadTemplate(ServerPlayer player, StageTemplate template) {
        MinecraftServer server = player.getServer();
        StageSession current = get(player).orElse(null);
        if (server == null || current == null || template == null) {
            player.sendSystemMessage(Component.literal("No active stage instance can be reloaded."));
            return false;
        }
        if (BACKDROP_SWITCHES.containsKey(current.instanceId())) {
            player.sendSystemMessage(Component.literal("That stage instance is already switching LOD packages."));
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        java.util.List<StageSession> members = data.members(current.instanceId());
        long memberCount = members.size() + PENDING.values().stream()
                .filter(entry -> entry.session.instanceId().equals(current.instanceId())).count();
        if (memberCount > template.capacity()) {
            player.sendSystemMessage(Component.literal("The selected template capacity is below the active member count."));
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            player.sendSystemMessage(Component.literal("The Dynamic Stage dimension is unavailable."));
            return false;
        }
        final StageFlightAssets.Asset flight;
        try {
            flight = StageTemplateStore.installFlight(server, template);
            StageArenaSnapshot.validate(stageLevel, template.boundary(), template.arenaSnapshot(),
                    template.structures());
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(Component.literal("Could not reload stage template: " + e.getMessage()));
            return false;
        }
        ReloadPlan plan = new ReloadPlan(template, flight, player.getUUID());
        if (!current.lodPackId().equals(template.lodPackId())) {
            player.sendSystemMessage(Component.literal("Checking the selected template's LOD package for every member..."));
            return beginBackdropSwitch(player, current, template.lodPackId(),
                    template.clientScene().lodTransition(), template.clientScene().lodTransitionTicks(), plan);
        }
        return applyReloadTemplate(server, current.instanceId(), plan);
    }

    private static boolean applyReloadTemplate(MinecraftServer server, UUID instanceId, ReloadPlan plan) {
        StageSessionData data = StageSessionData.get(server);
        StageInstance current = data.findInstance(instanceId).orElse(null);
        StageTemplate template = plan.template;
        if (current == null) {
            sendReloadMessage(server, plan, "Could not reload stage template: the instance is no longer active.");
            return false;
        }
        long memberCount = data.members(instanceId).size() + PENDING.values().stream()
                .filter(entry -> entry.session.instanceId().equals(instanceId)).count();
        if (memberCount > template.capacity()) {
            sendReloadMessage(server, plan,
                    "Could not reload stage template: its capacity is below the active member count.");
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            sendReloadMessage(server, plan, "Could not reload stage template: the stage dimension is unavailable.");
            return false;
        }
        try {
            boolean compatibleOverlay = template.cleanupPolicy() == StageTemplate.CleanupPolicy.OVERLAY
                    && current.stageId().equals(template.id())
                    && StageTemplateSummary.sameBoundarySize(current.boundary(), template.boundary());
            if (compatibleOverlay) {
                StageArenaSnapshot.overlay(stageLevel, current.stageOrigin(), template.boundary(),
                        template.arenaSnapshot(), template.structures());
            } else {
                StageArenaSnapshot.replace(stageLevel, current.stageOrigin(), current.boundary(),
                        template.boundary(), template.arenaSnapshot(), template.structures());
            }
        } catch (java.io.IOException | RuntimeException e) {
            sendReloadMessage(server, plan, "Could not reload stage template: " + e.getMessage());
            return false;
        }
        long gameTime = server.overworld().getGameTime();
        StageClientScene scene = template.sceneForNewInstance(server.overworld().getDayTime(), gameTime);
        StageFlightAssets.Asset flight = plan.flight;
        long flightStart = flight == null ? -1L : gameTime + 20L;
        data.updateInstanceTemplate(instanceId, template.id(), template.lodPackId(),
                template.lodAnchor(), template.capacity(), template.boundary(), scene,
                flight == null ? "" : flight.hash(), flight == null ? 0 : flight.bytes(),
                flight == null ? 0L : flight.durationMillis(), flightStart,
                template.lifecyclePolicy() == StageTemplate.LifecyclePolicy.RETAIN,
                template.interactionPolicy());
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(instanceId)
                ? entry.withSession(entry.session.withTemplateSettings(template.id(), template.lodPackId(),
                template.lodAnchor(), template.capacity(), template.boundary(), scene)
                 .withFlight(flight == null ? "" : flight.hash(), flight == null ? 0 : flight.bytes(),
                         flight == null ? 0L : flight.durationMillis(), flightStart),
                 template.lifecyclePolicy() == StageTemplate.LifecyclePolicy.RETAIN)
                 .withInteractionPolicy(template.interactionPolicy())
                 .withEntryOffset(template.entryOffset()) : entry);
        for (StageSession member : data.members(instanceId)) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target == null) {
                continue;
            }
            if (StageWorlds.isStageLevel(target.level())) {
                Vec3 clamped = template.boundary().clampPlayer(member.stageOrigin(), target.position(),
                        target.getBbWidth(), target.getBbHeight());
                if (!clamped.equals(target.position())) {
                    target.teleportTo(target.serverLevel(), clamped.x, clamped.y, clamped.z,
                            target.getYRot(), target.getXRot());
                }
            }
            SENT_FLIGHTS.remove(member.playerId());
            DynamicStageNetwork.sendSession(target, member);
            if (flight == null) {
                DynamicStageNetwork.sendFlight(target,
                        StageFlightPacket.clear(member.stageId(), scene.lodTransition(), scene.lodTransitionTicks()));
            } else {
                DynamicStageNetwork.sendFlight(target,
                        StageFlightPacket.active(member, flight.sceneJson(), scene.lodTransition(),
                                scene.lodTransitionTicks()));
                SENT_FLIGHTS.add(member.playerId());
            }
        }
        for (PendingEntry entry : PENDING.values()) {
            if (entry.session.instanceId().equals(instanceId)) {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.session.playerId());
                if (target != null) {
                    DynamicStageNetwork.sendSession(target, entry.session);
                }
            }
        }
        sendReloadMessage(server, plan, "Reloaded stage template '" + template.id() + "'.");
        return true;
    }

    private static void sendReloadMessage(MinecraftServer server, ReloadPlan plan, String message) {
        ServerPlayer requester = server.getPlayerList().getPlayer(plan.requesterId);
        if (requester != null) {
            requester.sendSystemMessage(Component.literal(message));
        }
    }

    public static boolean join(ServerPlayer player, UUID instanceId) {
        MinecraftServer server = player.getServer();
        if (!canPrepare(player, server)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageInstance instance = data.findInstance(instanceId).orElse(null);
        if (instance != null) {
            return joinExisting(player, instance);
        }
        PendingEntry pending = PENDING.values().stream()
                .filter(entry -> entry.session.instanceId().equals(instanceId)).findFirst().orElse(null);
        if (pending == null) {
            player.sendSystemMessage(Component.literal("Unknown or inactive Dynamic Stage instance."));
            return false;
        }
        return joinExisting(player, pending.session, pending.persistent, pending.entryOffset,
                pending.interactionPolicy);
    }

    /** Lists active or preparing stage instances the given player can currently join. */
    public static List<UUID> joinableInstances(ServerPlayer player) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (!canPrepare(player, server, false)) {
            return List.of();
        }
        StageSessionData data = StageSessionData.get(server);
        Map<UUID, JoinableInstance> instances = new LinkedHashMap<>();
        data.instances().forEach(instance -> instances.put(instance.instanceId(),
                new JoinableInstance(instance.instanceId(), instance.capacity(), instance.stageId(),
                        instance.flightHash(), instance.flightBytes(), instance.flightDurationMillis())));
        PENDING.values().forEach(entry -> instances.putIfAbsent(entry.session.instanceId(),
                new JoinableInstance(entry.session.instanceId(), entry.session.capacity(), entry.session.stageId(),
                        entry.session.flightHash(), entry.session.flightBytes(),
                        entry.session.flightDurationMillis())));
        return instances.values().stream()
                .filter(instance -> !BACKDROP_SWITCHES.containsKey(instance.instanceId()))
                .filter(instance -> memberCount(data, instance.instanceId()) < instance.capacity())
                .filter(instance -> !instance.hasFlight() || validFlight(server, instance))
                .map(JoinableInstance::instanceId)
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
    }

    private static boolean joinExisting(ServerPlayer player, StageInstance instance) {
        return joinExisting(player, createMembership(player, instance), instance.persistent(),
                entryOffsetFor(player.getServer(), instance.stageId()), instance.interactionPolicy());
    }

    private static boolean joinExisting(ServerPlayer player, StageSession membership, boolean persistent) {
        return joinExisting(player, membership, persistent, entryOffsetFor(player.getServer(), membership.stageId()));
    }

    private static boolean joinExisting(ServerPlayer player, StageSession membership, boolean persistent,
                                        BlockPos entryOffset) {
        return joinExisting(player, membership, persistent, entryOffset,
                StageTemplate.InteractionPolicy.ADVENTURE);
    }

    private static boolean joinExisting(ServerPlayer player, StageSession membership, boolean persistent,
                                        BlockPos entryOffset, StageTemplate.InteractionPolicy interactionPolicy) {
        MinecraftServer server = player.getServer();
        if (!canPrepare(player, server)) {
            return false;
        }
        if (BACKDROP_SWITCHES.containsKey(membership.instanceId())) {
            player.sendSystemMessage(Component.literal("That Dynamic Stage instance is changing its backdrop."));
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        long members = memberCount(data, membership.instanceId());
        if (members >= membership.capacity()) {
            player.sendSystemMessage(Component.literal("That Dynamic Stage instance is full."));
            return false;
        }
        if (membership.hasFlight() && !validFlight(server, membership)) {
            player.sendSystemMessage(Component.literal("That Dynamic Stage instance's flight is unavailable."));
            return false;
        }
        return prepare(player, membership, persistent, entryOffset, interactionPolicy);
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
                ? entry.withSession(entry.session.withLodAnchor(anchor))
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
        return beginBackdropSwitch(player, session, lodPackId, transition, transitionTicks, null);
    }

    private static boolean beginBackdropSwitch(ServerPlayer player, StageSession session,
                                               ResourceLocation lodPackId,
                                               StageClientScene.Transition transition,
                                               int transitionTicks, ReloadPlan reloadPlan) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        long deadline = server.overworld().getGameTime()
                + transitionTicks / 2L + backdropSwitchTimeoutTicks(server, lodPackId);
        BackdropSwitchState state = new BackdropSwitchState(
                session.lodPackId(), lodPackId, new HashSet<>(), deadline, reloadPlan);
        if (BACKDROP_SWITCHES.putIfAbsent(session.instanceId(), state) != null) {
            player.sendSystemMessage(Component.literal("That stage instance is already switching LOD packages."));
            return false;
        }

        data.updateInstanceLodPack(session.instanceId(), lodPackId);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(session.instanceId())
                ? entry.withSession(entry.session.withLodPack(lodPackId)) : entry);
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
                    DynamicStageNetwork.sessionPacket(target, member),
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
            if (reloadPlan != null && !applyReloadTemplate(server, session.instanceId(), reloadPlan)) {
                rollbackBackdropSwitch(server, session.instanceId(), state, "stage template reload failed");
                return false;
            }
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
                if (BACKDROP_SWITCHES.remove(packet.instanceId(), state)
                        && state.reloadPlan != null
                        && !applyReloadTemplate(server, packet.instanceId(), state.reloadPlan)) {
                    rollbackBackdropSwitch(server, packet.instanceId(), state, "stage template reload failed");
                }
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
                ? entry.withSession(entry.session.withFlight(flight.hash(), flight.bytes(),
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
                ? entry.withSession(entry.session.withoutFlight()) : entry);
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
                ? entry.withSession(entry.session.withBoundary(boundary))
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

    public static boolean setVoxyNearPlane(ServerPlayer player, float nearPlane) {
        StageSession session = get(player).orElse(null);
        return session != null && updateClientScene(player,
                session.clientScene().withVoxyNearPlane(nearPlane));
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
                ? entry.withSession(entry.session.withClientScene(scene)) : entry);
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
                releasePendingArenaIfUnused(server, pending);
                DynamicStageNetwork.clearSession(player);
                player.sendSystemMessage(Component.literal("LOD backdrop unavailable: " + boundedError(error)));
                return;
            }
            warnMissingLod(player, error);
            enterPrepared(player, pending);
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
        PendingEntry cancelledPending = PENDING.remove(player.getUUID());
        boolean cancelled = cancelledPending != null;
        player.stopRiding();
        Optional<StageSession> removed = removeMembership(server, player.getUUID());
        if (removed.isEmpty() && cancelledPending != null) {
            releasePendingArenaIfUnused(server, cancelledPending);
        }
        SENT_FLIGHTS.remove(player.getUUID());
        EDITING_PLAYERS.remove(player.getUUID());
        DynamicStageNetwork.forgetLodCollisionReports(player.getUUID());
        removeBackdropSwitchWait(server, player.getUUID());
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
        PendingEntry cancelledPending = PENDING.remove(player.getUUID());
        if (server != null) {
            Optional<StageSession> removed = removeMembership(server, player.getUUID());
            if (removed.isEmpty() && cancelledPending != null) {
                releasePendingArenaIfUnused(server, cancelledPending);
            }
        }
        SENT_FLIGHTS.remove(player.getUUID());
        EDITING_PLAYERS.remove(player.getUUID());
        DynamicStageNetwork.forgetLodCollisionReports(player.getUUID());
        removeBackdropSwitchWait(server, player.getUUID());
        clearPlayerMarker(player);
        DynamicStageNetwork.clearSession(player);
    }

    public static void onLogout(ServerPlayer player) {
        PendingEntry cancelledPending = PENDING.remove(player.getUUID());
        if (cancelledPending != null && player.getServer() != null) {
            releasePendingArenaIfUnused(player.getServer(), cancelledPending);
        }
        SENT_FLIGHTS.remove(player.getUUID());
        EDITING_PLAYERS.remove(player.getUUID());
        DynamicStageNetwork.forgetLodCollisionReports(player.getUUID());
        removeBackdropSwitchWait(player.getServer(), player.getUUID());
        LodServerTransferManager.cancel(player.getUUID());
    }

    public static void onServerStopped() {
        PENDING.clear();
        SENT_FLIGHTS.clear();
        EDITING_PLAYERS.clear();
        BACKDROP_SWITCHES.clear();
        DynamicStageNetwork.clearLodCollisionReports();
        LodServerTransferManager.clear();
    }

    public static void tick(MinecraftServer server) {
        LodServerTransferManager.tick(server);
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
            removeMembership(server, player.getUUID());
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

    public static boolean setInstancePersistent(ServerPlayer administrator, UUID instanceId,
                                                boolean persistent) {
        MinecraftServer server = administrator.getServer();
        if (server == null || instanceId == null || !administrator.hasPermissions(2)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageInstance instance = data.findInstance(instanceId).orElse(null);
        if (instance == null || !data.updateInstancePersistent(instanceId, persistent)) {
            administrator.sendSystemMessage(Component.literal("Unknown Dynamic Stage instance."));
            return false;
        }
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(instanceId)
                ? entry.withPersistent(persistent) : entry);
        boolean hasPendingMembers = PENDING.values().stream()
                .anyMatch(entry -> entry.session.instanceId().equals(instanceId));
        if (!persistent && data.members(instanceId).isEmpty() && !hasPendingMembers) {
            if (!releaseArena(server, instance.withPersistent(false))) {
                data.updateInstancePersistent(instanceId, true);
                administrator.sendSystemMessage(Component.literal(
                        "Could not clear the empty stage instance; it remains retained."));
                return false;
            }
            data.removeEmptyInstance(instanceId);
            administrator.sendSystemMessage(Component.literal("Released empty stage instance " + instanceId + '.'));
            return true;
        }
        administrator.sendSystemMessage(Component.literal("Stage instance " + instanceId + " will "
                + (persistent ? "be retained when empty." : "be released when empty.")));
        return true;
    }

    public static boolean releaseEmptyInstance(ServerPlayer administrator, UUID instanceId) {
        MinecraftServer server = administrator.getServer();
        if (server == null || instanceId == null || !administrator.hasPermissions(2)) {
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        StageInstance instance = data.findInstance(instanceId).orElse(null);
        if (instance == null) {
            administrator.sendSystemMessage(Component.literal("Unknown Dynamic Stage instance."));
            return false;
        }
        boolean pending = PENDING.values().stream()
                .anyMatch(entry -> entry.session.instanceId().equals(instanceId));
        if (pending || !data.members(instanceId).isEmpty()) {
            administrator.sendSystemMessage(Component.literal(
                    "Only an empty stage instance can be released from the editor."));
            return false;
        }
        if (!releaseArena(server, instance)) {
            administrator.sendSystemMessage(Component.literal(
                    "Could not clear the selected stage instance; it remains retained."));
            return false;
        }
        if (!data.removeEmptyInstance(instanceId)) {
            administrator.sendSystemMessage(Component.literal("The stage instance changed before release."));
            return false;
        }
        administrator.sendSystemMessage(Component.literal("Released stage instance " + instanceId + '.'));
        return true;
    }

    /** Enables block editing for this player only while they are a creative member of a stage. */
    public static boolean setEditing(ServerPlayer player, boolean enabled) {
        if (!enabled) {
            EDITING_PLAYERS.remove(player.getUUID());
            return true;
        }
        if (!player.isCreative() || !StageWorlds.isStageLevel(player.level()) || get(player).isEmpty()) {
            return false;
        }
        EDITING_PLAYERS.add(player.getUUID());
        return true;
    }

    /** Returns whether this player may modify blocks in the current stage level. */
    public static boolean isEditing(ServerPlayer player) {
        return player != null && player.isCreative() && StageWorlds.isStageLevel(player.level())
                && EDITING_PLAYERS.contains(player.getUUID()) && get(player).isPresent();
    }

    /** Returns whether the requested block belongs to this editor's active stage instance. */
    public static boolean isEditing(ServerPlayer player, BlockPos position) {
        if (position == null || !isEditing(player)) {
            return false;
        }
        StageSession session = get(player).orElse(null);
        return session != null && session.boundary().bounds(session.stageOrigin())
                .contains(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D);
    }

    /** Platform callbacks may expose a generic player; only a server player can hold edit permission. */
    public static boolean isEditing(net.minecraft.world.entity.player.Player player) {
        return player instanceof ServerPlayer serverPlayer && isEditing(serverPlayer);
    }

    /** Generic platform callback variant of the instance-local edit check. */
    public static boolean isEditing(net.minecraft.world.entity.player.Player player, BlockPos position) {
        return player instanceof ServerPlayer serverPlayer && isEditing(serverPlayer, position);
    }

    /** Returns whether a stage member may run the target block's interaction. */
    public static boolean canUseBlock(ServerPlayer player, BlockPos position) {
        if (isEditing(player, position)) {
            return true;
        }
        if (player == null || position == null || !StageWorlds.isStageLevel(player.level())) {
            return false;
        }
        StageSession session = get(player).orElse(null);
        if (session == null || !session.boundary().bounds(session.stageOrigin())
                .contains(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D)) {
            return false;
        }
        MinecraftServer server = player.getServer();
        StageInstance instance = server == null ? null
                : StageSessionData.get(server).findInstance(session.instanceId()).orElse(null);
        return instance != null && instance.interactionPolicy() == StageTemplate.InteractionPolicy.ADVENTURE;
    }

    public static boolean canUseBlock(net.minecraft.world.entity.player.Player player, BlockPos position) {
        return player instanceof ServerPlayer serverPlayer && canUseBlock(serverPlayer, position);
    }

    public static boolean canRequestLodDownload(ServerPlayer player, ResourceLocation lodPackId) {
        if (player == null || lodPackId == null) {
            return false;
        }
        PendingEntry pending = PENDING.get(player.getUUID());
        if (pending != null && pending.session().lodPackId().equals(lodPackId)) {
            return true;
        }
        StageSession current = get(player).orElse(null);
        if (current == null) {
            return false;
        }
        if (current.lodPackId().equals(lodPackId)) {
            return true;
        }
        BackdropSwitchState switching = BACKDROP_SWITCHES.get(current.instanceId());
        return switching != null && switching.awaiting().contains(player.getUUID())
                && switching.target().equals(lodPackId);
    }

    private static boolean prepare(ServerPlayer player, StageSession session, boolean persistent,
                                   BlockPos entryOffset, StageTemplate.InteractionPolicy interactionPolicy) {
        PENDING.put(player.getUUID(), new PendingEntry(session, persistent, entryOffset, interactionPolicy));
        DynamicStageNetwork.sendSession(player, session);
        player.sendSystemMessage(Component.literal("Checking local LOD pack '" + session.lodPackId() + "'..."));
        return true;
    }

    private static void enterPrepared(ServerPlayer player, PendingEntry pending) {
        StageSession session = pending.session;
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
        data.put(session, pending.persistent, pending.interactionPolicy);
        markPlayer(player, session.instanceId());
        BlockPos origin = session.stageOrigin();
        BlockPos entry = origin.offset(pending.entryOffset);
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.teleportTo(stageLevel, entry.getX() + 0.5D, entry.getY(), entry.getZ() + 0.5D,
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

    private static StageSession createMembership(ServerPlayer player, StageInstance instance) {
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

    private static boolean validFlight(MinecraftServer server, StageInstance instance) {
        StageFlightAssets.Asset flight = StageFlightAssets.load(server, instance.stageId(), instance.flightHash());
        return flight != null && flight.bytes() == instance.flightBytes()
                && flight.durationMillis() == instance.flightDurationMillis();
    }

    private static boolean validFlight(MinecraftServer server, JoinableInstance instance) {
        StageFlightAssets.Asset flight = StageFlightAssets.load(server, instance.stageId(), instance.flightHash());
        return flight != null && flight.bytes() == instance.flightBytes()
                && flight.durationMillis() == instance.flightDurationMillis();
    }

    private static boolean canPrepare(ServerPlayer player, MinecraftServer server) {
        return canPrepare(player, server, true);
    }

    private static boolean canPrepare(ServerPlayer player, MinecraftServer server, boolean reportFailure) {
        if (player == null || server == null || PENDING.containsKey(player.getUUID())) {
            return false;
        }
        if (StageSessionData.get(server).get(player.getUUID()).isPresent()) {
            if (reportFailure) {
                player.sendSystemMessage(Component.literal("A Dynamic Stage session is already active."));
            }
            return false;
        }
        return true;
    }

    private static int allocateSlot(StageSessionData data) {
        Set<Integer> occupied = new HashSet<>();
        data.instances().forEach(instance -> occupied.add(instance.slot()));
        PENDING.values().forEach(entry -> occupied.add(entry.session.slot()));
        for (int slot = 0; slot < StagePlacement.MAX_SLOTS; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static long memberCount(StageSessionData data, UUID instanceId) {
        return data.members(instanceId).size() + PENDING.values().stream()
                .filter(entry -> entry.session.instanceId().equals(instanceId)).count();
    }

    private static boolean slotClaimedByOtherInstance(StageSessionData data, StageSession candidate) {
        return data.instances().stream().anyMatch(instance -> instance.slot() == candidate.slot()
                && !instance.instanceId().equals(candidate.instanceId()));
    }

    private static boolean validStageId(String stageId) {
        return stageId != null && !stageId.isBlank() && stageId.length() <= 128;
    }

    private static Optional<StageSession> removeMembership(MinecraftServer server, UUID playerId) {
        StageSessionData data = StageSessionData.get(server);
        StageSession membership = data.get(playerId).orElse(null);
        if (membership == null) {
            return Optional.empty();
        }
        StageInstance instance = data.findInstance(membership.instanceId())
                .orElseGet(() -> StageInstance.from(membership, false));
        boolean hasPendingMembers = PENDING.values().stream()
                .anyMatch(entry -> !entry.session.playerId().equals(playerId)
                        && entry.session.instanceId().equals(membership.instanceId()));
        // Keep the slot claimed until cleanup succeeds so a failed release cannot
        // expose stale arena contents to the next allocated instance.
        Optional<StageSession> removed = data.remove(playerId, true);
        if (!instance.persistent() && !hasPendingMembers
                && data.members(instance.instanceId()).isEmpty()) {
            if (releaseArena(server, instance)) {
                data.removeEmptyInstance(instance.instanceId());
            } else {
                data.updateInstancePersistent(instance.instanceId(), true);
            }
        }
        return removed;
    }

    private static boolean releaseArena(MinecraftServer server, StageInstance instance) {
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            LOGGER.warn("Could not release stage instance {} because the stage dimension is unavailable",
                    instance.instanceId());
            return false;
        }
        try {
            StageArenaSnapshot.release(stageLevel, instance.stageOrigin(), instance.boundary());
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("Could not release stage instance {} in slot {}", instance.instanceId(),
                    instance.slot(), e);
            return false;
        }
    }

    private static void releasePendingArenaIfUnused(MinecraftServer server, PendingEntry pending) {
        UUID instanceId = pending.session.instanceId();
        StageSessionData data = StageSessionData.get(server);
        if (data.findInstance(instanceId).isPresent() || PENDING.values().stream()
                .anyMatch(entry -> entry.session.instanceId().equals(instanceId))) {
            return;
        }
        // Retention begins only after the first member has successfully entered.
        releaseArena(server, StageInstance.from(pending.session, false, pending.interactionPolicy));
    }

    private static String boundedError(String error) {
        if (error == null || error.isBlank()) {
            return "unknown client error";
        }
        return error.length() <= 256 ? error : error.substring(0, 256);
    }

    private static void resetArena(ServerLevel level, BlockPos origin, StageTemplate template)
            throws java.io.IOException {
        if (template.cleanupPolicy() == StageTemplate.CleanupPolicy.OVERLAY) {
            StageArenaSnapshot.overlay(level, origin, template.boundary(),
                    template.arenaSnapshot(), template.structures());
        } else {
            StageArenaSnapshot.restore(level, origin, template.boundary(),
                    template.arenaSnapshot(), template.structures());
        }
    }

    private static boolean resetEmptyTemplateArena(MinecraftServer server, StageInstance instance,
                                                   StageTemplate template) {
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            return false;
        }
        try {
            resetArena(stageLevel, instance.stageOrigin(), template);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("Could not refresh retained stage instance {}", instance.instanceId(), e);
            return false;
        }
    }

    private static int backdropSwitchTimeoutTicks(MinecraftServer server, ResourceLocation lodPackId) {
        LodPackageOffer offer = LodDistributionStore.find(server, lodPackId);
        if (offer == null) {
            return BACKDROP_SWITCH_TIMEOUT_MARGIN_TICKS;
        }
        long mebibytes = Math.max(1L, (offer.bytes() + 1_048_575L) / 1_048_576L);
        long seconds = Math.min(600L, 60L + mebibytes * 3L);
        return (int) Math.max(BACKDROP_SWITCH_TIMEOUT_MARGIN_TICKS, seconds * 20L);
    }

    private static void warnMissingLod(ServerPlayer player, String warning) {
        if (warning != null && !warning.isBlank()) {
            player.sendSystemMessage(Component.literal(
                    "LOD backdrop missing; entering the stage without it: " + boundedError(warning)));
        }
    }

    private static BlockPos entryOffsetFor(MinecraftServer server, String stageId) {
        if (server == null) {
            return BlockPos.ZERO;
        }
        try {
            StageTemplate template = StageTemplateStore.load(server, stageId);
            return template == null ? BlockPos.ZERO : template.entryOffset();
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("Could not load entry offset for stage template {}", stageId, e);
            return BlockPos.ZERO;
        }
    }

    private static void markPlayer(ServerPlayer player, UUID instanceId) {
        StagePlatform.setInstanceMarker(player, instanceId);
    }

    private static void clearPlayerMarker(ServerPlayer player) {
        StagePlatform.clearInstanceMarker(player);
    }

    private static void removeBackdropSwitchWait(MinecraftServer server, UUID playerId) {
        if (server == null) {
            return;
        }
        for (var entry : BACKDROP_SWITCHES.entrySet()) {
            BackdropSwitchState state = entry.getValue();
            if (state.awaiting.remove(playerId) && state.awaiting.isEmpty()
                    && BACKDROP_SWITCHES.remove(entry.getKey(), state) && state.reloadPlan != null) {
                rollbackBackdropSwitch(server, entry.getKey(), state,
                        "stage template reload cancelled because a member left");
            }
        }
    }

    private static void rollbackBackdropSwitch(MinecraftServer server, UUID instanceId,
                                               BackdropSwitchState state, String error) {
        StageSessionData data = StageSessionData.get(server);
        data.updateInstanceLodPack(instanceId, state.previous);
        PENDING.replaceAll((playerId, entry) -> entry.session.instanceId().equals(instanceId)
                ? entry.withSession(entry.session.withLodPack(state.previous)) : entry);
        String message = boundedError(error);
        for (StageSession member : data.members(instanceId)) {
            ServerPlayer target = server.getPlayerList().getPlayer(member.playerId());
            if (target != null) {
                DynamicStageNetwork.sendSession(target, member);
                target.sendSystemMessage(Component.literal("LOD switch rolled back: " + message));
            }
        }
    }

    private record PendingEntry(StageSession session, boolean persistent, BlockPos entryOffset,
                                StageTemplate.InteractionPolicy interactionPolicy) {
        private PendingEntry withSession(StageSession updated) {
            return new PendingEntry(updated, persistent, entryOffset, interactionPolicy);
        }

        private PendingEntry withSession(StageSession updated, boolean newPersistent) {
            return new PendingEntry(updated, newPersistent, entryOffset, interactionPolicy);
        }

        private PendingEntry withEntryOffset(BlockPos updated) {
            return new PendingEntry(session, persistent, updated, interactionPolicy);
        }

        private PendingEntry withInteractionPolicy(StageTemplate.InteractionPolicy updated) {
            return new PendingEntry(session, persistent, entryOffset, updated);
        }

        private PendingEntry withPersistent(boolean updated) {
            return new PendingEntry(session, updated, entryOffset, interactionPolicy);
        }
    }

    private record JoinableInstance(UUID instanceId, int capacity, String stageId,
                                    String flightHash, int flightBytes, long flightDurationMillis) {
        private boolean hasFlight() {
            return !flightHash.isEmpty();
        }
    }

    private record ReloadPlan(StageTemplate template, StageFlightAssets.Asset flight, UUID requesterId) {
    }

    private record BackdropSwitchState(ResourceLocation previous, ResourceLocation target,
                                       Set<UUID> awaiting, long deadlineGameTime, ReloadPlan reloadPlan) {
    }
}
