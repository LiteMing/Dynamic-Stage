package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import vibe.liteming.dynamicstage.client.backdrop.CMDCamPoseBridge;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import javax.annotation.Nullable;

/** Camera coordinates supplied to DH while it renders an external stage LOD package. */
public final class DhVirtualCamera {

    private DhVirtualCamera() {
    }

    @Nullable
    public static Vec3 position() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return null;
        }
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot == null || !DhBackdropRuntime.isMounted(snapshot.instanceId())) {
            return null;
        }
        BlockPos anchor = snapshot.lodAnchor();
        Vec3 position = new Vec3(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
        CMDCamPoseBridge.Pose delta = CMDCamPoseBridge.poseDelta();
        return delta == null ? position : position.add(delta.position());
    }
}
