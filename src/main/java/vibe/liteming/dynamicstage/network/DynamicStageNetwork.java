package vibe.liteming.dynamicstage.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.util.UUID;

/** Small stage control protocol; LOD geometry and databases are never transferred by DS. */
public final class DynamicStageNetwork {

    private static final String PROTOCOL = "3";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            DynamicStage.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private DynamicStageNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        int id = 0;
        CHANNEL.registerMessage(id++, StageSessionPacket.class,
                StageSessionPacket::encode, StageSessionPacket::decode, StageSessionPacket::handle);
        CHANNEL.registerMessage(id++, StageClientReadyPacket.class,
                StageClientReadyPacket::encode, StageClientReadyPacket::decode, StageClientReadyPacket::handle);
        CHANNEL.registerMessage(id, StageFlightPacket.class,
                StageFlightPacket::encode, StageFlightPacket::decode, StageFlightPacket::handle);
        registered = true;
    }

    public static void sendSession(ServerPlayer player, StageSession session) {
        send(player, StageSessionPacket.active(session));
    }

    public static void clearSession(ServerPlayer player) {
        send(player, StageSessionPacket.clear());
    }

    public static void clientReady(UUID instanceId, boolean ready, String error) {
        CHANNEL.sendToServer(new StageClientReadyPacket(instanceId, ready, error));
    }

    public static void sendFlight(ServerPlayer player, StageFlightPacket packet) {
        send(player, packet);
    }

    private static void send(ServerPlayer player, Object packet) {
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
