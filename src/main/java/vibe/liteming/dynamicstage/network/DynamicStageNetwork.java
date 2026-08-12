package vibe.liteming.dynamicstage.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSession;

/** Minimal protocol for stage session state and content-addressed backdrop transfer. */
public final class DynamicStageNetwork {

    private static final String PROTOCOL = "2";
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
        CHANNEL.registerMessage(id++, BackdropRequestPacket.class,
                BackdropRequestPacket::encode, BackdropRequestPacket::decode, BackdropRequestPacket::handle);
        CHANNEL.registerMessage(id++, BackdropChunkPacket.class,
                BackdropChunkPacket::encode, BackdropChunkPacket::decode, BackdropChunkPacket::handle);
        CHANNEL.registerMessage(id++, BackdropReadyPacket.class,
                BackdropReadyPacket::encode, BackdropReadyPacket::decode, BackdropReadyPacket::handle);
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

    public static void requestBackdrop(String stageId, String hash, int index) {
        CHANNEL.sendToServer(new BackdropRequestPacket(stageId, hash, index));
    }

    public static void sendChunk(ServerPlayer player, BackdropChunkPacket packet) {
        send(player, packet);
    }

    public static void backdropReady(String stageId, String backdropHash, String flightHash) {
        CHANNEL.sendToServer(new BackdropReadyPacket(stageId, backdropHash, flightHash));
    }

    public static void sendFlight(ServerPlayer player, StageFlightPacket packet) {
        send(player, packet);
    }

    private static void send(ServerPlayer player, Object packet) {
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
