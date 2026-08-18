package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagePlacementTest {

    @Test
    void mapsSlotsToStableSeparatedRegions() {
        assertEquals(new BlockPos(0, StagePlacement.STAGE_Y, 0), StagePlacement.originForSlot(0));
        assertEquals(new BlockPos(StagePlacement.REGION_SPACING, StagePlacement.STAGE_Y, 0),
                StagePlacement.originForSlot(1));
        assertEquals(new BlockPos(0, StagePlacement.STAGE_Y, StagePlacement.REGION_SPACING),
                StagePlacement.originForSlot(StagePlacement.REGIONS_PER_ROW));
        assertThrows(IllegalArgumentException.class, () -> StagePlacement.originForSlot(StagePlacement.MAX_SLOTS));
    }

    @Test
    void assignsEntitiesToOneHalfOpenInstanceRegion() {
        BlockPos origin = StagePlacement.originForSlot(1);
        double halfSpacing = StagePlacement.REGION_SPACING * 0.5D;

        assertTrue(StagePlacement.containsRegion(origin,
                origin.getX() - halfSpacing, origin.getZ()));
        assertTrue(StagePlacement.containsRegion(origin,
                origin.getX() + halfSpacing - 0.001D, origin.getZ()));
        assertFalse(StagePlacement.containsRegion(origin,
                origin.getX() + halfSpacing, origin.getZ()));
        assertFalse(StagePlacement.containsRegion(origin,
                origin.getX(), origin.getZ() - halfSpacing - 0.001D));
    }

    @Test
    void fullRegionBoundsDoNotOverlapTheNextSlot() {
        var first = StagePlacement.regionBounds(StagePlacement.originForSlot(0), -64, 320);
        var next = StagePlacement.regionBounds(StagePlacement.originForSlot(1), -64, 320);

        assertFalse(first.intersects(next));
        assertTrue(first.contains(-1023.5D, 80.0D, 0.0D));
        assertFalse(first.contains(1024.0D, 80.0D, 0.0D));
    }
}
