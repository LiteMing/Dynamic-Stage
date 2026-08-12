package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;

import java.util.BitSet;

/** Minimal spatial isolation for players sharing the otherwise empty stage dimension. */
public final class StagePlacement {

    public static final int REGION_SPACING = 2048;
    public static final int REGIONS_PER_ROW = 64;
    public static final int MAX_SLOTS = REGIONS_PER_ROW * REGIONS_PER_ROW;
    public static final int STAGE_Y = 80;

    private StagePlacement() {
    }

    public static int allocate(StageSessionData data) {
        BitSet occupied = new BitSet();
        data.all().forEach(session -> {
            if (session.slot() >= 0) {
                occupied.set(session.slot());
            }
        });
        int slot = occupied.nextClearBit(0);
        return slot < MAX_SLOTS ? slot : -1;
    }

    public static BlockPos originForSlot(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("slot must be between 0 and " + (MAX_SLOTS - 1));
        }
        int gridX = slot % REGIONS_PER_ROW;
        int gridZ = slot / REGIONS_PER_ROW;
        return new BlockPos(gridX * REGION_SPACING, STAGE_Y, gridZ * REGION_SPACING);
    }
}
