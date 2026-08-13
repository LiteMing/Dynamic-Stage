package vibe.liteming.dynamicstage.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.util.UUID;

/** Small stage control protocol; LOD geometry and databases are never transferred by DS. */
public final class DynamicStageNetwork {

    public static final net.minecraft.resources.ResourceLocation SESSION = DynamicStage.id("session");
    public static final net.minecraft.resources.ResourceLocation CLIENT_READY = DynamicStage.id("client_ready");
    public static final net.minecraft.resources.ResourceLocation FLIGHT = DynamicStage.id("flight");
    public static final net.minecraft.resources.ResourceLocation SKY = DynamicStage.id("sky");
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
        serverRegistered = true;
    }

    public static void sendSession(ServerPlayer player, StageSession session) {
        send(player, StageSessionPacket.active(session));
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

    public static void sendSky(ServerPlayer player, StageSkyPacket.Mode mode) {
        FriendlyByteBuf buf = buffer();
        StageSkyPacket.encode(new StageSkyPacket(mode), buf);
        NetworkManager.sendToPlayer(player, SKY, buf);
    }

    private static void send(ServerPlayer player, Object packet) {
        FriendlyByteBuf buf = buffer();
        if (packet instanceof StageSessionPacket session) {
            StageSessionPacket.encode(session, buf);
            NetworkManager.sendToPlayer(player, SESSION, buf);
        } else if (packet instanceof StageFlightPacket flight) {
            StageFlightPacket.encode(flight, buf);
            NetworkManager.sendToPlayer(player, FLIGHT, buf);
        } else {
            throw new IllegalArgumentException("Unsupported Dynamic Stage packet " + packet.getClass().getName());
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
