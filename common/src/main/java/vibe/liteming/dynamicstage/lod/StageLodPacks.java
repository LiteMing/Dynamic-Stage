package vibe.liteming.dynamicstage.lod;

import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.DynamicStage;

/** Reserved LOD package identifiers understood by both server and client. */
public final class StageLodPacks {
    public static final ResourceLocation NONE = DynamicStage.id("none");

    private StageLodPacks() {
    }
}
