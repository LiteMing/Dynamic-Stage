package vibe.liteming.dynamicstage.network;

import dev.architectury.networking.NetworkManager;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.client.editor.StageTemplateEditorState;

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
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DynamicStageNetwork.BACKDROP_SWITCH,
                (buf, context) -> {
                    StageBackdropSwitchPacket packet = StageBackdropSwitchPacket.decode(buf);
                    context.queue(() -> ClientStageSession.switchBackdrop(packet));
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
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DynamicStageNetwork.TEMPLATE_LIST,
                (buf, context) -> {
                    StageTemplatePackets.ListPacket packet = StageTemplatePackets.decodeList(buf);
                    context.queue(() -> StageTemplateEditorState.accept(packet.templates()));
                });
        registered = true;
    }
}
