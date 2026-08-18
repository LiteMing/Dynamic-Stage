package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Minimal spatial isolation for players sharing the otherwise empty stage dimension. */
public final class StagePlacement {

    public static final int REGION_SPACING = 2048;
    public static final int REGIONS_PER_ROW = 64;
    public static final int MAX_SLOTS = REGIONS_PER_ROW * REGIONS_PER_ROW;
    public static final int STAGE_Y = 80;

    private StagePlacement() {
    }

    public static BlockPos originForSlot(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("slot must be between 0 and " + (MAX_SLOTS - 1));
        }
        int gridX = slot % REGIONS_PER_ROW;
        int gridZ = slot / REGIONS_PER_ROW;
        return new BlockPos(gridX * REGION_SPACING, STAGE_Y, gridZ * REGION_SPACING);
    }

    /** Assigns a non-player entity to the isolated region surrounding an active slot. */
    public static boolean containsRegion(BlockPos origin, double x, double z) {
        double halfSpacing = REGION_SPACING * 0.5D;
        return x >= origin.getX() - halfSpacing && x < origin.getX() + halfSpacing
                && z >= origin.getZ() - halfSpacing && z < origin.getZ() + halfSpacing;
    }

    /** Full isolated slot bounds used to remove entities that escaped the configured arena. */
    public static AABB regionBounds(BlockPos origin, int minY, int maxY) {
        if (maxY <= minY) {
            throw new IllegalArgumentException("maxY must be greater than minY");
        }
        double halfSpacing = REGION_SPACING * 0.5D;
        return new AABB(origin.getX() - halfSpacing, minY, origin.getZ() - halfSpacing,
                origin.getX() + halfSpacing - 1.0E-4D, maxY,
                origin.getZ() + halfSpacing - 1.0E-4D);
    }
}
