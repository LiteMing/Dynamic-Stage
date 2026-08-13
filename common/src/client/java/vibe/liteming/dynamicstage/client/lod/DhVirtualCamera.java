package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import org.jetbrains.annotations.Nullable;

/** Camera coordinates supplied to DH while it renders an external stage LOD package. */
public final class DhVirtualCamera {

    private DhVirtualCamera() {
    }

    @Nullable
    public static Vec3 position() {
        StageLodCamera.Snapshot snapshot = StageLodCamera.snapshot();
        return snapshot == null ? null : snapshot.position();
    }

    /** Position used by DH for LOD selection, mapped from the real stage player. */
    @Nullable
    public static Vec3 lodSelectionPosition() {
        ClientStageSession.Snapshot snapshot = activeSnapshot();
        if (snapshot == null) {
            return null;
        }
        Vec3 position = StageCoordinateMapper.playerPosition(snapshot);
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        if (position == null || camera == null || camera.flight() == null) {
            return position;
        }
        return position.add(camera.flight().positionOffset());
    }

    /** Forward vector used by DH when ordering and selecting LOD render sections. */
    @Nullable
    public static Vec3 lookVector() {
        if (activeSnapshot() == null) {
            return null;
        }
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        return camera == null ? null : StageLodCamera.lookVector(camera.flight());
    }

    @Nullable
    private static ClientStageSession.Snapshot activeSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return null;
        }
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot == null || !DhBackdropRuntime.isMounted(snapshot.instanceId())) {
            return null;
        }
        return snapshot;
    }
}
