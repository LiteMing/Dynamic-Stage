package vibe.liteming.dynamicstage.client.gui;

import vibe.liteming.dynamicstage.network.StageBrowserPacket;

import java.util.List;

/** Latest server-authoritative instance list for the player-facing stage screen. */
public final class StageBrowserState {
    private static volatile StageBrowserPacket.State state = new StageBrowserPacket.State(List.of(), "", false);
    private static volatile long revision;

    private StageBrowserState() {
    }

    public static void accept(StageBrowserPacket.State value) {
        state = value;
        revision++;
    }

    public static StageBrowserPacket.State state() {
        return state;
    }

    public static long revision() {
        return revision;
    }

    public static void clear() {
        state = new StageBrowserPacket.State(List.of(), "", false);
        revision++;
    }
}
