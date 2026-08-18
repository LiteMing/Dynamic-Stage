package vibe.liteming.dynamicstage.stage;

import java.util.UUID;

/** Stable player-facing identity for an instance; UUID remains diagnostic metadata. */
public final class StageInstanceRef {
    private StageInstanceRef() {
    }

    public static String label(int slot, String stageId) {
        return (slot + 1) + "(" + stageId + ")";
    }

    public static String label(StageInstance instance) {
        return label(instance.slot(), instance.stageId());
    }

    public static boolean matches(StageInstance instance, String value) {
        if (instance == null || value == null) {
            return false;
        }
        String candidate = value.trim();
        if (label(instance).equalsIgnoreCase(candidate)) {
            return true;
        }
        try {
            return instance.instanceId().equals(UUID.fromString(candidate));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
