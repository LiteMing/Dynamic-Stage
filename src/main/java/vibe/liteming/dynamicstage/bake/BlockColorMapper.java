package vibe.liteming.dynamicstage.bake;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps a {@link BlockState} to an ARGB color for backdrop baking.
 * The bake pipeline never samples textures at runtime; providers use this to
 * resolve block states to colors offline.
 */
public interface BlockColorMapper {

    /**
     * @return ARGB color for the given state, or 0 when unknown.
     */
    int colorFor(BlockState state);
}
