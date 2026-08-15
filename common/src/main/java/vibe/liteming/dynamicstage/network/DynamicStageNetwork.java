package vibe.liteming.dynamicstage.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateStore;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.lod.LodDistributionStore;
import vibe.liteming.dynamicstage.lod.LodServerTransferManager;

import java.util.UUID;

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
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_REQUEST = DynamicStage.id("lod_download_request");
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_CHUNK = DynamicStage.id("lod_download_chunk");
    public static final net.minecraft.resources.ResourceLocation LOD_DOWNLOAD_RESULT = DynamicStage.id("lod_download_result");
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
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, LOD_DOWNLOAD_REQUEST, (buf, context) -> {
            LodDownloadRequestPacket packet = LodDownloadRequestPacket.decode(buf);
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    LodServerTransferManager.request(player, packet);
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

    public static void requestLodDownload(LodDownloadRequestPacket packet) {
        FriendlyByteBuf buf = buffer();
        LodDownloadRequestPacket.encode(packet, buf);
        NetworkManager.sendToServer(LOD_DOWNLOAD_REQUEST, buf);
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
