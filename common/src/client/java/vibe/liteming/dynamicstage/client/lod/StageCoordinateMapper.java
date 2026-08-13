package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;

/** Maps real stage camera/player coordinates directly into an external LOD world. */
public final class StageCoordinateMapper {
    private StageCoordinateMapper() {
    }

    @Nullable
    public static Vec3 cameraPosition(ClientStageSession.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return map(snapshot, minecraft.gameRenderer.getMainCamera().getPosition());
    }

    @Nullable
    public static Vec3 playerPosition(ClientStageSession.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        return map(snapshot, minecraft.player.position());
    }

    private static Vec3 map(ClientStageSession.Snapshot snapshot, Vec3 stagePosition) {
        return map(snapshot.lodAnchor(), snapshot.stageOrigin(), stagePosition);
    }

    static Vec3 map(BlockPos lodAnchor, BlockPos stageOriginBlock, Vec3 stagePosition) {
        Vec3 sourceOrigin = blockCenter(lodAnchor);
        Vec3 stageOrigin = blockCenter(stageOriginBlock);
        return sourceOrigin.add(stagePosition.subtract(stageOrigin));
    }

    private static Vec3 blockCenter(BlockPos position) {
        return new Vec3(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
    }
}
