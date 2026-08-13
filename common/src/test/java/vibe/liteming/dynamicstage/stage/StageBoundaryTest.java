package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageBoundaryTest {
    @Test
    void createsInstanceRelativeBounds() {
        StageBoundary boundary = new StageBoundary(60, 40, 18, 0x12ABEF);
        AABB bounds = boundary.bounds(new BlockPos(100, 80, -50));

        assertEquals(new AABB(70, 80, -70, 130, 98, -30), bounds);
    }

    @Test
    void clampsPlayersInsideAllSixPlanes() {
        StageBoundary boundary = StageBoundary.defaults();
        Vec3 clamped = boundary.clampPlayer(new BlockPos(0, 80, 0),
                new Vec3(50, 120, -50), 0.6F, 1.8F);

        assertEquals(29.69D, clamped.x, 0.0001D);
        assertEquals(96.19D, clamped.y, 0.0001D);
        assertEquals(-29.69D, clamped.z, 0.0001D);
    }

    @Test
    void rejectsBoundsThatCanOverlapNeighboringInstances() {
        assertThrows(IllegalArgumentException.class,
                () -> new StageBoundary(StagePlacement.REGION_SPACING, 60, 18, 0));
        assertThrows(IllegalArgumentException.class, () -> new StageBoundary(60, 60, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new StageBoundary(60, 60, 18, 0x1000000));
    }
}
