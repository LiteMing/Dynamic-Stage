package vibe.liteming.dynamicstage.network;

import dev.architectury.networking.NetworkManager;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;

public final class DynamicStageClientNetwork {
    private static boolean registered;

    private DynamicStageClientNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DynamicStageNetwork.SESSION,
                (buf, context) -> {
                    StageSessionPacket packet = StageSessionPacket.decode(buf);
                    context.queue(() -> ClientStageSession.accept(packet));
                });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DynamicStageNetwork.FLIGHT,
                (buf, context) -> {
                    StageFlightPacket packet = StageFlightPacket.decode(buf);
                    context.queue(() -> StageFlightController.accept(packet));
                });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DynamicStageNetwork.SKY,
                (buf, context) -> {
                    StageSkyPacket packet = StageSkyPacket.decode(buf);
                    context.queue(() -> StageSkySettings.setMode(
                            StageSkySettings.Mode.valueOf(packet.mode().name())));
                });
        registered = true;
    }
}
