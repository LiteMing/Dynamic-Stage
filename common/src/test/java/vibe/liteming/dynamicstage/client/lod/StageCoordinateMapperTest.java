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
}
