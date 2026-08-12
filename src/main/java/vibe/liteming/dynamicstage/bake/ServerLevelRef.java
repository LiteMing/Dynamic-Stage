package vibe.liteming.dynamicstage.bake;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Lightweight reference to the source world for offline providers.
 * Providers read files from disk; they never receive a live {@code ServerLevel}
 * so the server never loads or ticks the baked region.
 */
public record ServerLevelRef(ResourceLocation dimension, String worldSavePath) {
}
