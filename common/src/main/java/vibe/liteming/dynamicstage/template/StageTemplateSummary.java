package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

/** Small editable portion of a template; arena and flight assets never travel in editor packets. */
public record StageTemplateSummary(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                                   StageBoundary boundary, StageClientScene clientScene, int capacity,
                                   StageTemplate.InstanceMode instanceMode,
                                   StageTemplate.ResetPolicy resetPolicy) {
    public StageTemplateSummary {
        // Reuse the full template's validation without retaining asset data.
        new StageTemplate(id, lodPackId, lodAnchor, boundary, clientScene, capacity,
                instanceMode, resetPolicy, new byte[0], new CompoundTag());
    }

    public static StageTemplateSummary from(StageTemplate template) {
        return new StageTemplateSummary(template.id(), template.lodPackId(), template.lodAnchor(),
                template.boundary(), template.clientScene(), template.capacity(),
                template.instanceMode(), template.resetPolicy());
    }

    public StageTemplate applyTo(StageTemplate existing) {
        byte[] flight = existing == null ? new byte[0] : existing.flightJson();
        CompoundTag arena = existing == null ? new CompoundTag() : existing.arenaSnapshot();
        return new StageTemplate(id, lodPackId, lodAnchor, boundary, clientScene, capacity,
                instanceMode, resetPolicy, flight, arena);
    }
}
