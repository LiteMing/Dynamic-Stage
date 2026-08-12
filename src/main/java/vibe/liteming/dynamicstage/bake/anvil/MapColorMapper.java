package vibe.liteming.dynamicstage.bake.anvil;

import net.minecraft.world.level.block.state.BlockState;
import vibe.liteming.dynamicstage.bake.BlockColorMapper;

/**
 * Basic map-colour based mapper. Keeps the bake pipeline runnable and
 * deterministic without client texture sampling. A texture-average palette
 * sampler can replace it later.
 */
public class MapColorMapper implements BlockColorMapper {

    @Override
    public int colorFor(BlockState state) {
        int c = state.getBlock().defaultMapColor().col;
        return 0xFF000000 | (c & 0xFFFFFF);
    }
}
