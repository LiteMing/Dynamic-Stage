package vibe.liteming.dynamicstage.world;

import vibe.liteming.dynamicstage.DynamicStage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Stage dimensions. {@link #STG_STAGE} is the isolated gameplay space.
 */
public final class StageWorlds {

    public static final ResourceKey<Level> STG_STAGE = ResourceKey.create(Registries.DIMENSION,
            DynamicStage.id("stg_stage"));

    private StageWorlds() {
    }

    public static boolean isStageLevel(@Nullable Level level) {
        return level != null && level.dimension().equals(STG_STAGE);
    }
}
