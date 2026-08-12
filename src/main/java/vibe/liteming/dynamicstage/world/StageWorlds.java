package vibe.liteming.dynamicstage.world;

import vibe.liteming.dynamicstage.DynamicStage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Stage dimensions. {@link #STG_STAGE} is the gameplay arena space; multiple
 * players can use different slots simultaneously. The editor dimension is
 * deferred until the editor lands.
 */
public final class StageWorlds {

    public static final ResourceKey<Level> STG_STAGE = ResourceKey.create(Registries.DIMENSION,
            DynamicStage.id("stg_stage"));

    private StageWorlds() {
    }

    public static boolean isStageLevel(Level level) {
        return level.dimension().equals(STG_STAGE);
    }

    public static boolean isStageOrEditorLevel(Level level) {
        return isStageLevel(level);
    }
}
