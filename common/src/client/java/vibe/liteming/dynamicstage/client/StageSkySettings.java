package vibe.liteming.dynamicstage.client;

/** Client-only selection for the stage sky color source. */
public final class StageSkySettings {
    public enum Mode { OVERWORLD, END, OFF }

    private static volatile Mode mode = Mode.OVERWORLD;

    private StageSkySettings() {
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode value) {
        mode = value == null ? Mode.OVERWORLD : value;
    }
}
