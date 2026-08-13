package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
