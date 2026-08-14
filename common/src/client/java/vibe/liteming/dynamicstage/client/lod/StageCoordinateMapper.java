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
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        return mapCamera(snapshot.lodAnchor(), snapshot.stageOrigin(), minecraft.player.position(),
                minecraft.gameRenderer.getMainCamera().getPosition(), snapshot.clientScene().followPlayer(),
                snapshot.clientScene().lodMovementScale());
    }

    @Nullable
    public static Vec3 playerPosition(ClientStageSession.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        return snapshot.clientScene().followPlayer()
                ? map(snapshot.lodAnchor(), snapshot.stageOrigin(), minecraft.player.position(),
                snapshot.clientScene().lodMovementScale()) : anchor(snapshot.lodAnchor());
    }

    static Vec3 map(BlockPos lodAnchor, BlockPos stageOriginBlock, Vec3 stagePosition) {
        return map(lodAnchor, stageOriginBlock, stagePosition, 1.0F);
    }

    static Vec3 map(BlockPos lodAnchor, BlockPos stageOriginBlock, Vec3 stagePosition, float movementScale) {
        Vec3 sourceOrigin = anchor(lodAnchor);
        Vec3 stageOrigin = blockCenter(stageOriginBlock);
        return sourceOrigin.add(stagePosition.subtract(stageOrigin).scale(movementScale));
    }

    static Vec3 mapCamera(BlockPos lodAnchor, BlockPos stageOriginBlock, Vec3 playerPosition,
                          Vec3 cameraPosition, boolean followPlayer) {
        return mapCamera(lodAnchor, stageOriginBlock, playerPosition, cameraPosition, followPlayer, 1.0F);
    }

    static Vec3 mapCamera(BlockPos lodAnchor, BlockPos stageOriginBlock, Vec3 playerPosition,
                          Vec3 cameraPosition, boolean followPlayer, float movementScale) {
        return followPlayer ? map(lodAnchor, stageOriginBlock, cameraPosition, movementScale)
                : anchor(lodAnchor).add(cameraPosition.subtract(playerPosition));
    }

    private static Vec3 blockCenter(BlockPos position) {
        return new Vec3(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
    }

    private static Vec3 anchor(BlockPos position) {
        return blockCenter(position);
    }
}
