package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Places a data-pack structure relative to a Dynamic Stage instance origin. */
public record StageStructurePlacement(ResourceLocation structureId, BlockPos offset) {
    public StageStructurePlacement {
        if (structureId == null || offset == null) {
            throw new IllegalArgumentException("Stage structure placement contains null state");
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Structure", structureId.toString());
        tag.putLong("Offset", offset.asLong());
        return tag;
    }

    public static StageStructurePlacement load(CompoundTag tag) {
        return new StageStructurePlacement(new ResourceLocation(tag.getString("Structure")),
                BlockPos.of(tag.getLong("Offset")));
    }
}
