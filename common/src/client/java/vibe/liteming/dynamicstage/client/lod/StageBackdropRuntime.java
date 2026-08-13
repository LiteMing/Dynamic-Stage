package vibe.liteming.dynamicstage.client.lod;

import vibe.liteming.dynamicstage.client.stage.ClientStageSession;

import java.util.UUID;

public final class StageBackdropRuntime {
    private static Backend backend = Backend.NONE;

    private StageBackdropRuntime() {
    }

    public static Result mount(ClientStageSession.Snapshot snapshot) {
        try {
            LodPackRegistry.Pack pack = LodPackRegistry.load(snapshot.lodPackId());
            Backend selected = pack instanceof LodPackRegistry.DhPack ? Backend.DH : Backend.VOXY;
            if (backend != Backend.NONE && backend != selected) {
                unmount();
            }
            Result result = selected == Backend.DH
                    ? fromDh(DhBackdropRuntime.mount(snapshot))
                    : VoxyBackdropRuntime.mount(snapshot, (LodPackRegistry.VoxyPack) pack);
            if (result.ready()) {
                backend = selected;
            }
            return result;
        } catch (Throwable e) {
            return Result.failure(rootMessage(e));
        }
    }

    public static void unmount() {
        Backend previous = backend;
        backend = Backend.NONE;
        if (previous == Backend.DH) {
            DhBackdropRuntime.unmount();
        } else if (previous == Backend.VOXY) {
            VoxyBackdropRuntime.unmount();
        }
    }

    public static boolean isMounted(UUID instanceId) {
        return switch (backend) {
            case DH -> DhBackdropRuntime.isMounted(instanceId);
            case VOXY -> VoxyBackdropRuntime.isMounted(instanceId);
            case NONE -> false;
        };
    }

    public static boolean needsStageActivation(UUID instanceId) {
        return switch (backend) {
            case DH -> DhBackdropRuntime.needsStageActivation(instanceId);
            case VOXY -> VoxyBackdropRuntime.needsStageActivation(instanceId);
            case NONE -> false;
        };
    }

    public static Result activateStage(UUID instanceId) {
        return switch (backend) {
            case DH -> fromDh(DhBackdropRuntime.activateStage(instanceId));
            case VOXY -> VoxyBackdropRuntime.activateStage(instanceId);
            case NONE -> Result.failure("No native LOD package is mounted");
        };
    }

    public static boolean isVoxyMounted() {
        return backend == Backend.VOXY;
    }

    public static boolean isDhMounted() {
        return backend == Backend.DH;
    }

    private static Result fromDh(DhBackdropRuntime.Result result) {
        return result.ready() ? Result.success() : Result.failure(result.error());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private enum Backend { NONE, DH, VOXY }

    public record Result(boolean ready, String error) {
        public static Result success() {
            return new Result(true, "");
        }

        public static Result failure(String error) {
            return new Result(false, error);
        }
    }
}
