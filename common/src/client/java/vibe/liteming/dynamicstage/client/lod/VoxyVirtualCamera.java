package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

public final class VoxyVirtualCamera {
    private VoxyVirtualCamera() {
    }

    @Nullable
    public static Vec3 position() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)
                || !StageBackdropRuntime.isVoxyMounted()) {
            return null;
        }
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot == null || !StageBackdropRuntime.isMounted(snapshot.instanceId())) {
            return null;
        }
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        return camera == null ? null : camera.position();
    }
}
