package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageCoordinateMapperTest {
    private static final BlockPos LOD_ANCHOR = new BlockPos(0, 100, 0);

    @Test
    void mapsEveryStageSlotOriginToTheLodAnchor() {
        assertEquals(new Vec3(0.5D, 100.0D, 0.5D),
                StageCoordinateMapper.map(LOD_ANCHOR, new BlockPos(0, 80, 0),
                        new Vec3(0.5D, 80.0D, 0.5D)));
        assertEquals(new Vec3(0.5D, 100.0D, 0.5D),
                StageCoordinateMapper.map(LOD_ANCHOR, new BlockPos(4096, 80, 0),
                        new Vec3(4096.5D, 80.0D, 0.5D)));
    }

    @Test
    void preservesLocalMovementAndCameraOffsets() {
        assertEquals(new Vec3(5.5D, 103.62D, -2.5D),
                StageCoordinateMapper.map(LOD_ANCHOR, new BlockPos(4096, 80, 0),
                        new Vec3(4101.5D, 83.62D, -2.5D)));
    }

    @Test
    void canFreezePlayerTranslationWhilePreservingCameraOffset() {
        BlockPos stageOrigin = new BlockPos(4096, 80, 0);
        Vec3 player = new Vec3(4108.5D, 81.0D, -4.5D);
        Vec3 thirdPersonCamera = new Vec3(4108.5D, 83.0D, -8.5D);

        assertEquals(new Vec3(12.5D, 103.0D, -8.5D),
                StageCoordinateMapper.mapCamera(LOD_ANCHOR, stageOrigin, player, thirdPersonCamera, true));
        assertEquals(new Vec3(0.5D, 102.0D, -3.5D),
                StageCoordinateMapper.mapCamera(LOD_ANCHOR, stageOrigin, player, thirdPersonCamera, false));
    }

    @Test
    void scalesPlayerTranslationAroundTheStageOrigin() {
        assertEquals(new Vec3(6.5D, 101.5D, -1.0D),
                StageCoordinateMapper.map(LOD_ANCHOR, new BlockPos(4096, 80, 0),
                        new Vec3(4108.5D, 83.0D, -2.5D), 0.5F));
        assertEquals(new Vec3(24.5D, 106.0D, -5.5D),
                StageCoordinateMapper.map(LOD_ANCHOR, new BlockPos(4096, 80, 0),
                        new Vec3(4108.5D, 83.0D, -2.5D), 2.0F));
    }
}
