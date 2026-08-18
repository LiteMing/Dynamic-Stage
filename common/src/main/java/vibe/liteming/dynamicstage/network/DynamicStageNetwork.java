package vibe.liteming.dynamicstage.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageInstance;
import vibe.liteming.dynamicstage.stage.StageSessionData;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateStore;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.lod.LodDistributionStore;
import vibe.liteming.dynamicstage.lod.LodServerTransferManager;
import vibe.liteming.dynamicstage.event.StageLodCollisionEvents;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stage control protocol plus bounded server-hosted LOD archive transfers. */
public final class DynamicStageNetwork {

    public static final net.minecraft.resources.ResourceLocation SESSION = DynamicStage.id("session");
    public static final net.minecraft.resources.ResourceLocation CLIENT_READY = DynamicStage.id("client_ready");
    public static final net.minecraft.resources.ResourceLocation FLIGHT = DynamicStage.id("flight");
    public static final net.minecraft.resources.ResourceLocation BACKDROP_SWITCH = DynamicStage.id("backdrop_switch");
    public static final net.minecraft.resources.ResourceLocation BACKDROP_SWITCH_RESULT = DynamicStage.id("backdrop_switch_result");
    public static final net.minecraft.resources.ResourceLocation SKY = DynamicStage.id("sky");
    public static final net.minecraft.resources.ResourceLocation TEMPLATE_REQUEST = DynamicStage.id("template_request");
    public static final net.minecraft.resources.ResourceLocation TEMPLATE_LIST = DynamicStage.id("template_list");
    public static final net.minecraft.resources.ResourceLocation TEMPLATE_EDIT = DynamicStage.id("template_edit");
    public static final net.minecraft.resources.ResourceLocation EDITOR_ADMIN_REQUEST = DynamicStage.id("editor_admin_request");
    public static final net.minecraft.resources.ResourceLocation EDITOR_ADMIN_STATE = DynamicStage.id("editor_admin_state");
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_REQUEST = DynamicStage.id("lod_download_request");
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_CHUNK = DynamicStage.id("lod_download_chunk");
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_RESULT = DynamicStage.id("lod_download_result");
    public static final net.minecraft.resources.ResourceLocation LOD_COLLISION_EVENT = DynamicStage.id("lod_collision_event");
    private static final long LOD_COLLISION_REPORT_INTERVAL_TICKS = 5L;
    private static final Map<UUID, Long> LAST_LOD_COLLISION_REPORT = new ConcurrentHashMap<>();
    private static boolean serverRegistered;

    private DynamicStageNetwork() {
    }

    public static synchronized void registerServer() {
        if (serverRegistered) {
            return;
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CLIENT_READY, (buf, context) -> {
            StageClientReadyPacket packet = StageClientReadyPacket.decode(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    vibe.liteming.dynamicstage.stage.StageSessionManager.onClientReady(player,
                            packet.instanceId(), packet.ready(), packet.error());
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BACKDROP_SWITCH_RESULT, (buf, context) -> {
            StageBackdropSwitchResultPacket packet = StageBackdropSwitchResultPacket.decode(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    StageSessionManager.onBackdropSwitchResult(player, packet);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TEMPLATE_REQUEST, (buf, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.hasPermissions(2)) {
                        sendTemplateList(player);
                    }
                }));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TEMPLATE_EDIT, (buf, context) -> {
            StageTemplatePackets.EditPacket packet = StageTemplatePackets.decodeEdit(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player && player.hasPermissions(2)) {
                    handleTemplateEdit(player, packet);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, EDITOR_ADMIN_REQUEST, (buf, context) -> {
            StageEditorAdminPacket.Request packet = StageEditorAdminPacket.decodeRequest(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    handleEditorAdminRequest(player, packet);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, LOD_DOWNLOAD_REQUEST, (buf, context) -> {
            LodDownloadRequestPacket packet = LodDownloadRequestPacket.decode(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    LodServerTransferManager.request(player, packet);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, LOD_COLLISION_EVENT, (buf, context) -> {
            StageLodCollisionPacket packet = StageLodCollisionPacket.decode(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    handleLodCollision(player, packet);
                }
            });
        });
        serverRegistered = true;
    }

    public static void sendSession(ServerPlayer player, StageSession session) {
        send(player, sessionPacket(player, session));
    }

    public static StageSessionPacket sessionPacket(ServerPlayer player, StageSession session) {
        return StageSessionPacket.active(session,
                LodDistributionStore.find(player.getServer(), session.lodPackId()));
    }

    public static void clearSession(ServerPlayer player) {
        send(player, StageSessionPacket.clear());
    }

    public static void clientReady(UUID instanceId, boolean ready, String error) {
        FriendlyByteBuf buf = buffer();
        StageClientReadyPacket.encode(new StageClientReadyPacket(instanceId, ready, error), buf);
        NetworkManager.sendToServer(CLIENT_READY, buf);
    }

    public static void sendFlight(ServerPlayer player, StageFlightPacket packet) {
        send(player, packet);
    }

    public static void sendBackdropSwitch(ServerPlayer player, StageBackdropSwitchPacket packet) {
        FriendlyByteBuf buf = buffer();
        StageBackdropSwitchPacket.encode(packet, buf);
        NetworkManager.sendToPlayer(player, BACKDROP_SWITCH, buf);
    }

    public static void backdropSwitchResult(java.util.UUID instanceId,
                                             net.minecraft.resources.ResourceLocation lodPackId,
                                             boolean ready, String error) {
        FriendlyByteBuf buf = buffer();
        StageBackdropSwitchResultPacket.encode(
                new StageBackdropSwitchResultPacket(instanceId, lodPackId, ready,
                        error == null ? "" : error.length() > 256 ? error.substring(0, 256) : error), buf);
        NetworkManager.sendToServer(BACKDROP_SWITCH_RESULT, buf);
    }

    public static void sendSky(ServerPlayer player, StageSkyPacket.Mode mode) {
        FriendlyByteBuf buf = buffer();
        StageSkyPacket.encode(new StageSkyPacket(mode), buf);
        NetworkManager.sendToPlayer(player, SKY, buf);
    }

    public static void requestTemplates() {
        NetworkManager.sendToServer(TEMPLATE_REQUEST, buffer());
    }

    public static void editTemplate(StageTemplatePackets.EditPacket packet) {
        FriendlyByteBuf buf = buffer();
        StageTemplatePackets.encodeEdit(packet, buf);
        NetworkManager.sendToServer(TEMPLATE_EDIT, buf);
    }

    public static void requestEditorAdmin(StageEditorAdminPacket.Request packet) {
        FriendlyByteBuf buf = buffer();
        StageEditorAdminPacket.encodeRequest(packet, buf);
        NetworkManager.sendToServer(EDITOR_ADMIN_REQUEST, buf);
    }

    public static void requestLodDownload(LodDownloadRequestPacket packet) {
        FriendlyByteBuf buf = buffer();
        LodDownloadRequestPacket.encode(packet, buf);
        NetworkManager.sendToServer(LOD_DOWNLOAD_REQUEST, buf);
    }

    public static void reportLodCollision(StageLodCollisionPacket packet) {
        FriendlyByteBuf buf = buffer();
        StageLodCollisionPacket.encode(packet, buf);
        NetworkManager.sendToServer(LOD_COLLISION_EVENT, buf);
    }

    public static void forgetLodCollisionReports(UUID playerId) {
        if (playerId != null) {
            LAST_LOD_COLLISION_REPORT.remove(playerId);
        }
    }

    public static void clearLodCollisionReports() {
        LAST_LOD_COLLISION_REPORT.clear();
    }

    public static void sendLodDownloadChunk(ServerPlayer player, LodDownloadChunkPacket packet) {
        FriendlyByteBuf buf = buffer();
        LodDownloadChunkPacket.encode(packet, buf);
        NetworkManager.sendToPlayer(player, LOD_DOWNLOAD_CHUNK, buf);
    }

    public static void sendLodDownloadResult(ServerPlayer player, LodDownloadResultPacket packet) {
        FriendlyByteBuf buf = buffer();
        LodDownloadResultPacket.encode(packet, buf);
        NetworkManager.sendToPlayer(player, LOD_DOWNLOAD_RESULT, buf);
    }

    public static void sendTemplateList(ServerPlayer player) {
        try {
            java.util.List<StageTemplateSummary> summaries = StageTemplateStore.listTemplates().stream()
                    .limit(StageTemplatePackets.MAX_TEMPLATES).map(StageTemplateSummary::from).toList();
            FriendlyByteBuf buf = buffer();
            StageTemplatePackets.encodeList(new StageTemplatePackets.ListPacket(summaries), buf);
            NetworkManager.sendToPlayer(player, TEMPLATE_LIST, buf);
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Could not list stage templates: " + e.getMessage()));
        }
    }

    public static void sendEditorAdminState(ServerPlayer player, String message, boolean error) {
        StageSessionData data = StageSessionData.get(player.getServer());
        java.util.List<StageEditorAdminPacket.Instance> instances = data.instances().stream()
                .sorted(java.util.Comparator.comparingInt(StageInstance::slot))
                .limit(StageEditorAdminPacket.MAX_INSTANCES)
                .map(instance -> new StageEditorAdminPacket.Instance(instance.instanceId(), instance.stageId(),
                        instance.slot(), data.members(instance.instanceId()).size(), instance.capacity(),
                        instance.persistent()))
                .toList();
        boolean permitted = player.hasPermissions(2);
        boolean editAvailable = permitted && player.isCreative()
                && StageWorlds.isStageLevel(player.level()) && StageSessionManager.get(player).isPresent();
        StageEditorAdminPacket.State state = new StageEditorAdminPacket.State(
                permitted ? instances : java.util.List.of(), StageSessionManager.isEditing(player),
                editAvailable, bounded(message), error);
        FriendlyByteBuf buf = buffer();
        StageEditorAdminPacket.encodeState(state, buf);
        NetworkManager.sendToPlayer(player, EDITOR_ADMIN_STATE, buf);
    }

    private static void handleEditorAdminRequest(ServerPlayer player, StageEditorAdminPacket.Request packet) {
        if (!player.hasPermissions(2)) {
            sendEditorAdminState(player, "Operator permission is required.", true);
            return;
        }
        boolean success = true;
        String message = "";
        switch (packet.action()) {
            case REFRESH -> {
            }
            case TOGGLE_EDITING -> {
                boolean enabled = !StageSessionManager.isEditing(player);
                success = StageSessionManager.setEditing(player, enabled);
                message = success ? "Stage editing " + (enabled ? "enabled." : "disabled.")
                        : "Enter a stage in creative mode before enabling editing.";
            }
            case JOIN -> {
                success = StageSessionManager.join(player, packet.instanceId());
                message = success ? "Preparing the selected stage instance..."
                        : "Could not join the selected stage instance.";
            }
            case TOGGLE_PERSISTENT -> {
                StageSessionData data = StageSessionData.get(player.getServer());
                StageInstance instance = data.findInstance(packet.instanceId()).orElse(null);
                success = instance != null && StageSessionManager.setInstancePersistent(
                        player, packet.instanceId(), !instance.persistent());
                message = success ? "Updated the selected instance lifecycle."
                        : "Could not update the selected stage instance.";
            }
            case RELEASE -> {
                success = StageSessionManager.releaseEmptyInstance(player, packet.instanceId());
                message = success ? "Released the selected stage instance."
                        : "Only an empty stage instance can be released.";
            }
        }
        sendEditorAdminState(player, message, !success);
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private static void handleTemplateEdit(ServerPlayer player, StageTemplatePackets.EditPacket packet) {
        try {
            StageTemplateSummary summary = packet.template();
            StageTemplate template;
            boolean arenaCleared = false;
            if (packet.action() == StageTemplatePackets.Action.RELOAD_ACTIVE) {
                template = StageTemplateStore.load(summary.id());
                if (template == null) {
                    throw new IllegalStateException("Unknown stage template '" + summary.id() + "'");
                }
                if (!StageSessionManager.reloadTemplate(player, template)) {
                    return;
                }
                sendTemplateList(player);
                return;
            } else if (packet.action() == StageTemplatePackets.Action.CAPTURE_ACTIVE) {
                StageSession session = StageSessionManager.get(player).orElseThrow(() ->
                        new IllegalStateException("No active stage instance to capture"));
                template = StageTemplateStore.capture(player.getServer(), session, summary);
            } else if (packet.action() == StageTemplatePackets.Action.USE_CONFIGURED_FLIGHT) {
                StageTemplate existing = StageTemplateStore.load(summary.id());
                arenaCleared = arenaWillBeCleared(existing, summary);
                if (summary.flightName().isEmpty()) {
                    throw new IllegalStateException("Select a Flight from the Dynamic Stage library");
                }
                StageFlightAssets.Asset asset = StageFlightAssets.importFromLibrary(
                        player.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                        summary.id(), summary.flightName());
                template = summary.applyTo(existing, asset.sceneJson(), summary.flightName());
            } else if (packet.action() == StageTemplatePackets.Action.CLEAR_FLIGHT) {
                StageTemplate existing = StageTemplateStore.load(summary.id());
                arenaCleared = arenaWillBeCleared(existing, summary);
                template = summary.applyTo(existing, new byte[0], "");
            } else {
                StageTemplate existing = StageTemplateStore.load(summary.id());
                arenaCleared = arenaWillBeCleared(existing, summary);
                template = summary.applyTo(existing);
            }
            StageTemplateStore.save(template);
            if (arenaCleared) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Boundary size changed; the old arena snapshot was cleared. Use Capture to save the new arena."));
            }
            if (packet.action() == StageTemplatePackets.Action.CAPTURE_ACTIVE) {
                StageSession active = StageSessionManager.get(player).orElseThrow();
                StageSessionManager.setBoundary(player, template.boundary());
                StageSessionManager.setAnchor(player, template.lodAnchor());
                StageSessionManager.setClientScene(player, template.clientScene());
                if (!active.lodPackId().equals(template.lodPackId()) || active.capacity() != template.capacity()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "LOD package and capacity changes apply when the next instance is created."));
                }
            }
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Saved stage template '" + template.id() + "'."));
            if (packet.action() == StageTemplatePackets.Action.SAVE_AND_START) {
                StageSessionManager.createAndEnterTemplate(player, template);
            }
            sendTemplateList(player);
        } catch (java.io.IOException | RuntimeException e) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Could not edit stage template: " + e.getMessage()));
        }
    }

    private static boolean arenaWillBeCleared(StageTemplate existing, StageTemplateSummary summary) {
        return existing != null && existing.hasArenaSnapshot()
                && !StageTemplateSummary.sameBoundarySize(existing.boundary(), summary.boundary());
    }

    private static void handleLodCollision(ServerPlayer player, StageLodCollisionPacket packet) {
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null || !StageWorlds.isStageLevel(player.level()) || player.isSpectator()
                || !player.isAlive() || !session.instanceId().equals(packet.instanceId())) {
            return;
        }
        long gameTime = player.level().getGameTime();
        Long previous = LAST_LOD_COLLISION_REPORT.put(player.getUUID(), gameTime);
        if (previous != null && gameTime >= previous
                && gameTime - previous < LOD_COLLISION_REPORT_INTERVAL_TICKS) {
            return;
        }
        StageLodCollisionEvents.post(player, session, packet.direction(), packet.penetration(), gameTime);
    }

    private static void send(ServerPlayer player, Object packet) {
        FriendlyByteBuf buf = buffer();
        if (packet instanceof StageSessionPacket session) {
            StageSessionPacket.encode(session, buf);
            NetworkManager.sendToPlayer(player, SESSION, buf);
        } else if (packet instanceof StageFlightPacket flight) {
            StageFlightPacket.encode(flight, buf);
            NetworkManager.sendToPlayer(player, FLIGHT, buf);
        } else if (packet instanceof StageBackdropSwitchPacket backdrop) {
            StageBackdropSwitchPacket.encode(backdrop, buf);
            NetworkManager.sendToPlayer(player, BACKDROP_SWITCH, buf);
        } else {
            throw new IllegalArgumentException("Unsupported Dynamic Stage packet " + packet.getClass().getName());
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
